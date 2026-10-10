package com.kaede.uspace.qqbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaede.uspace.qqbot.protocol.OneBotAction;
import com.kaede.uspace.qqbot.protocol.OneBotMessageSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 出站客户端：往 QQ 群发消息（模块 11）。
 *
 * <p>它同时是<b>连接状态的持有者</b> —— 反向 WebSocket 只有一条连接，
 * 谁连着、还开没开，本类是唯一知道的地方。NapCat 连上来时
 * {@link #attach}，断开时 {@link #detach}，发消息时从这里取。
 *
 * <h3>为什么持有一条而不是多条</h3>
 *
 * <p>一个后端通常只接一个 NapCat（一个 QQ 号 = 一个协议端）。用列表支持多条，
 * 就得回答「发消息时发给哪一条」「两条都发会不会重复」这两个问题，
 * 而当前场景下答案都是「不需要」。{@link AtomicReference} 把「只有一条」
 * 这个假设写进了类型里，将来真要接多个机器人时改起来也一目了然。
 *
 * <h3>绝不抛异常</h3>
 *
 * <p>本类的调用方包括订单的 {@code @TransactionalEventListener} 回调。
 * 让一个「群消息没发出去」把用户的操作搅黄，是荒唐的 ——
 * 所以所有失败路径都是记日志 + 返回 false，把处置权留给调用方（而调用方一般也 ignore）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "uspace.qqbot.enabled", havingValue = "true")
public class OneBotClient {

    /**
     * 单条消息的发送时限（毫秒）。超过就关闭该会话。
     *
     * <p>⚠️ <b>这条时限是「NapCat 卡住不读」的唯一防线</b>。播报的触发点
     * （{@code @TransactionalEventListener(AFTER_COMMIT)}）跑在<b>用户的请求线程</b>上 ——
     * 发送一直阻塞的话，用户点「结束使用」的请求就跟着一直不返回，
     * 而且此时数据库连接也还没归还（AFTER_COMMIT 监听器跑在连接归还之前）。
     *
     * <p>拿线程池兜是兜不住的：队列满了照样阻塞调用方，无界队列则会 OOM。
     * 超时关连接才是对的处置 —— 会话一关，NapCat 便按它自己配的
     * {@code reconnect_interval} 重连，而反向连接的重连本来就是协议端的责任。
     *
     * <p>2 秒是宽松的：本机或局域网写一条几百字节的消息是微秒级的，
     * 真卡到 2 秒说明这条连接已经没救了。
     */
    private static final int SEND_TIME_LIMIT_MILLIS = 2_000;

    /**
     * 单次会话的发送缓冲上限（字节）。
     *
     * <p>512KB 对群消息来说远超实际需要（一条播报也就一两百字节）。
     * 设这个值是为了给「对端只连不发」这种情况一个上限 ——
     * 没有上限的话，一个僵死的连接会让待发消息在内存里无限堆积。
     */
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    /** 当前连接。null 表示 NapCat 没连上 */
    private final AtomicReference<WebSocketSession> session = new AtomicReference<>();

    /** 出站消息的序号，拼进 {@code echo} 供日志追踪 */
    private final AtomicLong echoSequence = new AtomicLong();

    private final ObjectMapper objectMapper;

    /**
     * 构造器注入。
     *
     * @param objectMapper Jackson 序列化器，用容器里那个（与 Web 层同一份配置）
     */
    public OneBotClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 登记一条新连接。
     *
     * <p>⚠️ <b>存进去的是包装过的会话，不是原始会话</b>：
     * {@code WebSocketSession} 的 {@code sendMessage} <b>不是线程安全的</b>，
     * 而播报来自线程池、指令回复来自 WebSocket 的处理线程 —— 两者可能同时发送。
     * 并发写同一个会话会抛 {@code IllegalStateException}（有时是消息交错），
     * 这类故障在测试里几乎撞不到，只在真实并发下偶发。
     * {@link ConcurrentWebSocketSessionDecorator} 用一把锁把它们串起来，
     * 这正是它存在的意义。
     *
     * <p><b>新连接顶掉旧连接</b>：NapCat 重连时，旧连接的 {@code afterConnectionClosed}
     * 可能还没触发，此时有两份引用。留下旧的那份会让消息发进一个已经没人读的会话里 ——
     * 于是「机器人明明连着却不说话」。所以直接顶替，并把旧的关掉。
     *
     * @param rawSession 刚建立的原始会话
     */
    public void attach(WebSocketSession rawSession) {
        WebSocketSession wrapped = new ConcurrentWebSocketSessionDecorator(
                rawSession, SEND_TIME_LIMIT_MILLIS, BUFFER_SIZE_LIMIT_BYTES);
        WebSocketSession previous = session.getAndSet(wrapped);
        if (previous != null && previous.isOpen()) {
            log.info("[QQ机器人] 新连接顶替了尚未关闭的旧连接 oldSessionId={}", previous.getId());
            closeQuietly(previous);
        }
        log.info("[QQ机器人] OneBot 连接已建立 sessionId={}", rawSession.getId());
    }

    /**
     * 注销一条连接。
     *
     * <p>⚠️ <b>要比对一下再清空</b>：连接断开是异步的，可能出现
     * 「旧连接断开的事件在新连接建立之后才到」。不比对的话，那个迟到的
     * {@code detach} 会把刚建立的新连接清掉，之后所有消息都发不出去，
     * 而日志里只有一句正常的「连接已断开」。
     *
     * <p>比对用 {@code sessionId} 而不是对象引用 —— {@link #attach} 存进去的是装饰器，
     * 与这里收到的原始会话不是同一个对象，用 {@code ==} 永远不相等。
     *
     * @param rawSession 刚断开的原始会话
     */
    public void detach(WebSocketSession rawSession) {
        WebSocketSession current = session.get();
        if (current != null && current.getId().equals(rawSession.getId())) {
            session.compareAndSet(current, null);
            log.info("[QQ机器人] OneBot 连接已断开 sessionId={}", rawSession.getId());
        }
    }

    /**
     * 往群里发一条纯文本消息。
     *
     * <p>没有连接、连接已关闭、序列化失败、发送抛异常 —— 四种情况一律
     * 记日志 + 返回 false，<b>绝不往外抛</b>（理由见类注释）。
     *
     * @param groupId 群号
     * @param text    消息文本，按纯文本发送（CQ 码会被转义，见 {@link OneBotAction}）
     * @return 确实发出去了返回 true
     */
    public boolean sendGroupMessage(Long groupId, String text) {
        if (groupId == null || text == null || text.isEmpty()) {
            return false;
        }
        String echo = "uspace-" + echoSequence.incrementAndGet();
        return dispatch(OneBotAction.sendGroupMessage(groupId, text, echo), echo,
                "群消息 groupId=" + groupId);
    }

    /**
     * 往群里发一条「@ 某人 + 文本」的消息。
     *
     * <p>失败处置与 {@link #sendGroupMessage} 完全一致（绝不抛异常），
     * 区别只在于消息以<b>段数组</b>发送，因此可以带 @ ——
     * 用途是付款凭证被驳回时提醒本人重新提交，那是唯一一条
     * <b>针对特定某个人</b>的播报，纯文本 @ 不出来。
     *
     * @param groupId 群号
     * @param atQq    要 @ 的 QQ 号；为 null 或空串时退化成普通群消息
     * @param text    @ 之后的文本
     * @return 帧确实发出去了返回 true
     */
    public boolean sendGroupMessageAt(Long groupId, String atQq, String text) {
        if (groupId == null || text == null || text.isEmpty()) {
            return false;
        }
        String echo = "uspace-" + echoSequence.incrementAndGet();
        return dispatch(OneBotAction.sendGroupMessageWithAt(groupId, atQq, text, echo), echo,
                "群消息(带@) groupId=" + groupId + " at=" + atQq);
    }

    /**
     * 往群里发一张图片（可附一句文本）。
     *
     * <p>图片按 <b>base64 内联</b>（{@code base64://}）—— 名册图是「看一眼就过期」
     * 的即时快照：传 URL 要图床、写文件要维护清理，而它只有几十 KB，
     * 一次帧就发完了。理由详见 {@code OneBotMessageSegment#image}。
     *
     * <p>失败处置与 {@link #sendGroupMessage} 完全一致（绝不抛异常）——
     * 调用方据此回落纯文本版（名册那条指令的降级路径）。
     *
     * @param groupId 群号
     * @param image   PNG 字节
     * @param text    图片之前的文本；为 null 或空串时只发图片
     * @return 帧确实发出去了返回 true
     */
    public boolean sendGroupMessageImage(Long groupId, byte[] image, String text) {
        return image == null
                ? false
                : sendGroupMessageImages(groupId, List.of(image), text);
    }

    /**
     * 往群里发一组图片 —— <b>全部塞在同一条消息里</b>（2026-10-10 由用户定）。
     *
     * <p>名册每 4 人一张图，逐张各发一条会把群刷屏；段数组支持一条消息多图，
     * 客户端渲染成图集。失败处置与 {@link #sendGroupMessage} 完全一致
     * （绝不抛异常），调用方据此回落纯文本名册。
     *
     * @param groupId 群号
     * @param images  PNG 字节列表，至少一张
     * @param text    图片之前的文本；为 null 或空串时只发图
     * @return 帧确实发出去了返回 true
     */
    public boolean sendGroupMessageImages(Long groupId, List<byte[]> images, String text) {
        if (groupId == null || images == null || images.isEmpty()) {
            return false;
        }
        List<String> files = images.stream()
                .map(image -> "base64://" + Base64.getEncoder().encodeToString(image))
                .toList();
        String echo = "uspace-" + echoSequence.incrementAndGet();
        int bytes = images.stream().mapToInt(image -> image.length).sum();
        return dispatch(OneBotAction.sendGroupMessageWithImages(groupId, files, text, echo), echo,
                "群图片x" + images.size() + " groupId=" + groupId + " bytes=" + bytes);
    }

    /**
     * 给某个人发一条私聊消息。
     *
     * <p>目前唯一的用途是群指令 {@code fw开门}：把<b>固定的限时密码</b>单独发给本人 ——
     * 群消息所有人可见，密码不能出现在那里。
     *
     * <p>⚠️ <b>返回 true 只代表「帧发出去了」，不代表对方收到了</b>：
     * 本类不等 Action 响应（见类注释），而 NapCat 对「不是好友」的私聊是
     * <b>异步</b>返回失败的 —— 于是「发出去了」与「送达了」在这里无法区分。
     * 所以调用方<b>永远要准备一条降级路径</b>（在群里补一句「到网页端看」），
     * 不能把「私聊成功」当成前提。
     *
     * @param userId 目标 QQ 号
     * @param text   消息文本，按纯文本发送（CQ 码会被转义，见 {@link OneBotAction}）
     * @return 帧确实发出去了返回 true
     */
    public boolean sendPrivateMessage(Long userId, String text) {
        if (userId == null || text == null || text.isEmpty()) {
            return false;
        }
        String echo = "uspace-" + echoSequence.incrementAndGet();
        return dispatch(OneBotAction.sendPrivateMessage(userId, text, echo), echo,
                "私聊 userId=" + userId);
    }

    /**
     * 把一个构造好的动作发出去 —— 群消息与私聊共用这一段。
     *
     * <p>四种情况（没连接、连接已关闭、序列化失败、发送抛异常）一律记日志 + 返回 false，
     * <b>绝不往外抛</b>：调用方里有订单的 {@code @TransactionalEventListener} 回调，
     * 异常会沿着 {@code publishEvent} 冒回业务方法，把一笔已经成功的订单
     * 变成「用户看到开门失败」。
     *
     * @param action      要发送的动作
     * @param echo        回显串，仅用于日志
     * @param description 目标描述，仅用于日志，如「群消息 groupId=1001」
     * @return 确实发出去了返回 true
     */
    private boolean dispatch(OneBotAction action, String echo, String description) {
        WebSocketSession current = session.get();
        if (current == null || !current.isOpen()) {
            // 这是「正常但值得知道」的状态：NapCat 没跑、或刚断线正在重连。
            // 用 WARN 而不是 ERROR —— 错误日志应当留给真正需要人介入的事
            log.warn("[QQ机器人] 当前没有可用的 OneBot 连接，消息未发出 {}", description);
            return false;
        }

        try {
            current.sendMessage(new TextMessage(objectMapper.writeValueAsString(action)));
            log.info("[QQ机器人] 已发送 {} echo={} 内容={}",
                    description, echo, summarize(action.getParams().get("message")));
            return true;
        } catch (Exception e) {
            log.error("[QQ机器人] 发送失败 {} echo={}", description, echo, e);
            return false;
        }
    }

    /**
     * 把出站消息压成一行日志内容。
     *
     * <p><b>段数组只报「段类型」不报内容</b>：图片段里是一整串 base64
     *（几十 KB），照原样打出来会把日志淹掉，而那张图本来就能在群里看到。
     * 纯文本消息照旧原样打印 —— 它是排查指令问题时唯一的第一手材料。
     *
     * @param message 动作里的 {@code message} 字段（字符串或段数组）
     * @return 可直接打日志的对象：字符串原样返回、段数组给出类型列表
     */
    private static Object summarize(Object message) {
        if (!(message instanceof List<?> segments)) {
            return message;
        }
        return segments.stream()
                .map(segment -> segment instanceof OneBotMessageSegment seg
                        ? seg.getType() : "?")
                .toList();
    }

    /**
     * 当前是否连着。
     *
     * <p>供排查与测试用。业务代码不该拿它做判断 —— 判断完到发送之间连接可能就断了，
     * 直接调 {@link #sendGroupMessage} 并看返回值才是准的。
     *
     * @return 有连接且连接处于打开状态时返回 true
     */
    public boolean isConnected() {
        WebSocketSession current = session.get();
        return current != null && current.isOpen();
    }

    /**
     * 关掉一个会话并吞掉异常。
     *
     * <p>关闭失败没什么可做的（连接已经没用了），但异常不能冒到调用方 ——
     * 那会把「顶替旧连接」这个正常动作变成一个错误。
     *
     * @param session 要关闭的会话
     */
    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (Exception e) {
            log.debug("[QQ机器人] 关闭旧连接时出错（可忽略）sessionId={}", session.getId(), e);
        }
    }
}
