package com.kaede.uspace.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaede.uspace.qqbot.protocol.OneBotActionResponse;
import com.kaede.uspace.qqbot.protocol.OneBotEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 反向 WebSocket 端点（模块 11）。
 *
 * <p>连接建立后，<b>收与发走的是同一条连接</b>：NapCat 往上推事件，
 * 我们往下发 Action。这个类只管前者 —— 后者在 {@link OneBotClient}。
 *
 * <h3>分流：三种帧</h3>
 *
 * <p>同一条连接上回来的帧有三类，判据的顺序<b>不能换</b>：
 *
 * <table>
 *   <tr><th>顺序</th><th>判据</th><th>是什么</th></tr>
 *   <tr><td>1</td><td>有 {@code post_type}</td><td>事件（消息、元事件、通知）</td></tr>
 *   <tr><td>2</td><td>有 {@code retcode}</td><td>Action 的响应</td></tr>
 *   <tr><td>3</td><td>都没有</td><td>认不出 —— 记 WARN 与截断的原文</td></tr>
 * </table>
 *
 * <p>⚠️ <b>判据是 {@code retcode}，不是 {@code status}，这一条最容易写错</b>：
 * 心跳帧（{@code meta_event}）<b>也带 {@code status}</b>，而且它是个<b>对象</b>
 * （{@code {"online":true,"good":true}}）—— 拿它当判据会把这个对象往
 * {@code String status} 上绑，<b>每 30 秒抛一次反序列化异常</b>。
 * 症状是「日志里稳定地刷着一条错误，但功能一切正常」，很容易被当成无害的噪音忽略过去。
 *
 * <h3>方法体为什么整个包在 try 里</h3>
 *
 * <p>{@code TextWebSocketHandler} 抛出的异常会往上冒到容器的传输层，
 * 在那里可能被处理成<b>直接关闭连接</b>。一帧格式不对（NapCat 换版本加了字段、
 * 中间有代理改写了内容）不该弄死这条连接 —— 那会让机器人静默失联，
 * 而唯一的线索是一行「连接已断开」。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "uspace.qqbot.enabled", havingValue = "true")
public class OneBotWebSocketHandler extends TextWebSocketHandler {

    /** 认不出的帧，日志里最多打这么多字符 */
    private static final int UNKNOWN_FRAME_LOG_LIMIT = 300;

    /** 「已连接」提示的防抖间隔：网络抖动会在一分钟内反复重连，每次都说会刷屏 */
    private static final Duration CONNECT_NOTICE_COOLDOWN = Duration.ofSeconds(60);

    private final OneBotClient client;

    private final QqCommandService commandService;

    private final ObjectMapper objectMapper;

    private final QqbotProperties properties;

    /** 时钟。与写指令冷却同一个 bean（{@code QqVerifyConfig}），测试可拨钟 */
    private final Clock clock;

    /** 上一次发「已连接」群消息的时刻；null 表示本次进程内还没发过 */
    private final AtomicReference<LocalDateTime> lastConnectNoticeAt = new AtomicReference<>();

    public OneBotWebSocketHandler(OneBotClient client,
                                  QqCommandService commandService,
                                  ObjectMapper objectMapper,
                                  QqbotProperties properties,
                                  Clock clock) {
        this.client = client;
        this.commandService = commandService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 连接建立：登记到 {@link OneBotClient}。
     *
     * <p>包装成线程安全会话这件事也发生在登记时，见
     * {@link OneBotClient#attach(WebSocketSession)}。
     *
     * @param session 刚建立的原始会话
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        client.attach(session);
        // 断开有日志（见 afterConnectionClosed）、连上反而一直什么都没有 ——
        // 排查「什么时候连上的」只能靠猜，这条补的就是那半边（2026-10-10 由用户提出）
        log.info("[QQ机器人] NapCat 已连接 sessionId={}", session.getId());
        noticeConnected();
    }

    /**
     * 连接建立后在群里说一声「机器人已连接」。
     *
     * <p><b>为什么值得发一条群消息</b>：断线在 NapCat 界面上看得见，
     * 而<b>重连成功此前两边都悄无声息</b> —— 运营者只能发一条 {@code fwping}
     * 去猜。这句话让群里直接看到「它回来了」，比翻日志直观。
     *
     * <p>三条纪律：
     * <ol>
     *   <li><b>受播报开关管</b>（与写指令同一条）：在一条从不说话的群里
     *       突然冒一句「已连接」，比不说更让人困惑</li>
     *   <li><b>只发一个群</b>：店主群优先（运营者盯着那里），没配则第一个交互群 ——
     *       往所有群广播「我上线了」是刷屏</li>
     *   <li><b>60 秒防抖</b>：NapCat 网络抖动会一分钟重连好几次，
     *       每次都说等于刷屏，而它们说的是同一件事</li>
     * </ol>
     */
    private void noticeConnected() {
        if (!properties.getBroadcast().isEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime last = lastConnectNoticeAt.get();
        if (last != null && Duration.between(last, now).compareTo(CONNECT_NOTICE_COOLDOWN) < 0) {
            return;
        }
        lastConnectNoticeAt.set(now);

        Long groupId = noticeGroupId();
        if (groupId != null) {
            client.sendGroupMessage(groupId, QqReplyText.botConnected());
        }
    }

    /**
     * 「已连接」提示发到哪个群：店主群优先，其次第一个交互群。
     *
     * @return 群号；一个群都没配时返回 null（此时只留后端日志）
     */
    private Long noticeGroupId() {
        if (!properties.getAdminGroups().isEmpty()) {
            return properties.getAdminGroups().get(0);
        }
        return properties.effectiveGroups().stream().findFirst().orElse(null);
    }

    /**
     * 收到一帧文本：分流后交给对应的处理。
     *
     * @param session 会话（本方法用不到它 —— 出站一律走 {@link OneBotClient} 里那个
     *                包装过的会话，不能拿这里传进来的裸会话发送，见
     *                {@link OneBotClient#attach(WebSocketSession)}）
     * @param message 帧内容
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode frame = objectMapper.readTree(message.getPayload());
            if (frame.hasNonNull("post_type")) {
                handleEvent(frame);
            } else if (frame.has("retcode")) {
                handleActionResponse(frame);
            } else {
                log.warn("[QQ机器人] 认不出的帧，已忽略：{}", truncate(message.getPayload()));
            }
        } catch (Exception e) {
            // 绝不外抛，理由见类注释
            log.error("[QQ机器人] 处理入站帧失败，已忽略该帧", e);
        }
    }

    /**
     * 连接关闭：注销。
     *
     * @param session 会话
     * @param status  关闭状态码，仅记日志
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        client.detach(session);
        log.info("[QQ机器人] 连接关闭 code={} reason={}", status.getCode(), status.getReason());
    }

    /**
     * 传输层出错。
     *
     * <p>只记录不处理：连接该断就断，NapCat 会按它自己配的间隔重连 ——
     * 反向连接的重连本来就是协议端的责任。
     *
     * @param session   出错的会话
     * @param exception 异常
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[QQ机器人] 连接出错 sessionId={}", session.getId(), exception);
    }

    /**
     * 处理一个事件帧。
     *
     * <p><b>心跳单独摘出来静默掉</b>：NapCat 默认每 30 秒发一条，
     * 走 INFO 会把日志刷得看不出别的；走 WARN 则更糟 —— 真正的告警会被淹没。
     * DEBUG 在这里是恰当的级别：平时不显示，需要排查连接是否活着时打开就有。
     *
     * <p>其余判断（是不是群消息、群号在不在白名单、要不要去重）全部在
     * {@link QqCommandService#handle} 里 —— 这个类只负责「把帧变成对象」。
     *
     * @param frame 已解析的 JSON
     * @throws Exception 反序列化失败时
     */
    private void handleEvent(JsonNode frame) throws Exception {
        OneBotEvent event = objectMapper.treeToValue(frame, OneBotEvent.class);
        if (event.isMetaEvent()) {
            log.debug("[QQ机器人] 元事件 metaEventType={}", event.getMetaEventType());
            return;
        }
        // ⚠️ 收到的每一条消息都记一笔，看着吵，但它是「机器人为什么不说话」
        // 唯一答得了的地方。少了它，「消息没推上来」与「推上来了但被某个判据
        // 静默掉了（不是群消息 / 自己发的且无前缀 / 判为闲聊）」在日志里
        // 长得一模一样 —— 都是什么都没有，而这两种情况的排查方向完全相反。
        //
        // 群消息的量不大（对比之下 SQL 日志吵得多），所以留在 INFO 是划算的。
        log.info("[QQ机器人] 收到事件 postType={} messageType={} groupId={} userId={} selfId={} 内容={}",
                event.getPostType(), event.getMessageType(), event.getGroupId(),
                event.getUserId(), event.getSelfId(), event.getRawMessage());
        if (event.getPostType() == null) {
            // 一个字段都没映射上时把原始帧打出来。**这条是吃过亏才加的**：
            // 协议用 snake_case（post_type / group_id / raw_message）而 Java 字段是
            // camelCase，Jackson 默认对不上 —— 反序列化不报错，只是每个字段都成了 null，
            // 而光看一串 null 完全猜不出原因。当时的现象是「机器人对 fwping 毫无反应，
            // 日志里只有一行全是 null 的事件」，从这里才能一眼看出协议字段的真面目。
            log.warn("[QQ机器人] 事件反序列化后 postType 为空（字段名对不上？），原始帧：{}", frame);
        }
        commandService.handle(event);
    }

    /**
     * 处理一个 Action 响应帧。
     *
     * <p>骨架阶段不做请求-响应配对，这里只记日志 —— 成功走 DEBUG，
     * 失败走 WARN 并带上 {@code echo}（它能把日志里的这一条与
     * {@link OneBotClient} 发出去的那一条对上）。见 {@code OneBotActionResponse} 的类注释。
     *
     * @param frame 已解析的 JSON
     * @throws Exception 反序列化失败时
     */
    private void handleActionResponse(JsonNode frame) throws Exception {
        OneBotActionResponse response = objectMapper.treeToValue(frame, OneBotActionResponse.class);
        if (response.isOk()) {
            log.debug("[QQ机器人] 动作成功 echo={}", response.getEcho());
        } else {
            log.warn("[QQ机器人] 动作失败 echo={} status={} retcode={}",
                    response.getEcho(), response.getStatus(), response.getRetcode());
        }
    }

    /**
     * 截断过长的文本，避免一条畸形帧把日志撑爆。
     *
     * @param text 原文
     * @return 超过上限时截断并标记
     */
    private static String truncate(String text) {
        if (text == null || text.length() <= UNKNOWN_FRAME_LOG_LIMIT) {
            return text;
        }
        return text.substring(0, UNKNOWN_FRAME_LOG_LIMIT) + "…（已截断）";
    }
}
