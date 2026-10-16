package com.kaede.uspace.qqbot;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 指令回复的发送队列（模块 11）—— <b>给群消息限速，防 QQ 风控</b>。
 *
 * <h3>它解决什么</h3>
 *
 * <p>指令处理得快（都是一次索引查询、毫秒级），回复也就发得快 ——
 * 而连着刷出去的消息正是 QQ 风控盯的特征。于是回复不再「算完就发」，
 * 而是进一条队列、<b>按节奏一条一条发</b>：每条发出前等一段随机时长，
 * 均匀落在 {@code uspace.qqbot.reply-min-interval} ~
 * {@code uspace.qqbot.reply-max-interval} 之间（默认 <b>3~10 秒</b>）——
 * 固定间隔本身也是一种可识别的特征，随机化之后更像人在打字。
 *
 * <p><b>连第一条也要等</b>（2026-10-10 由用户定）：秒回同样是特征，
 * 所以「队列空时立即发第一条」那种常见写法在这里是错的 ——
 * 每条消息发出前都要等一个随机间隔。
 *
 * <h3>两类消息不排队，直接从发送点走</h3>
 *
 * <ul>
 *   <li><b>{@code fw开门} 与 {@code fw结账} 的回复</b>：人站在门口等密码、
 *       等结账结果，压进队列等十秒是不能接受的。这两条链路直接走
 *       {@code OneBotClient}（见 {@code QqWriteCommandService}），
 *       本类不拦它们 —— 拦在别处的做法（比如队列里再插队）挡不住「队列已满」这类边界</li>
 *   <li><b>播报</b>（到店 / 离店 / 包场生效……）：不是指令回复，且频率天然低
 *       （见 {@code QqBroadcastListener}），不经过本类</li>
 * </ul>
 *
 * <h3>回复一律 @ 发指令的人</h3>
 *
 * <p>队列的发送动作统一走 {@code sendGroupMessageAt}（<b>段数组</b>形式，
 * 不是关掉转义的那种拼串）—— 群消息窗口里一条条 @ 才认得出哪条回的是谁。
 * 这正是本类收 {@code qq} 参数的原因：谁发的指令，@ 谁。
 *
 * <h3>几条实现约束</h3>
 *
 * <ul>
 *   <li><b>单线程 worker、FIFO</b>：同一时刻只发一条，顺序即入队顺序 ——
 *       多条并发发送本身就是风控特征，也容易让回复与上下文错位</li>
 *   <li><b>每条发出前先等</b>（含第一条）：取到消息 → 等 3~10 秒随机 → 发。
 *       两条之间的实际间隔因此就是 3~10 秒；队列空时 worker 阻塞在
 *       {@code take()}，不空转</li>
 *   <li><b>队列有上限</b>（{@link #MAX_PENDING}）：真被刷屏时丢弃并记 error，
 *       不无限堆积 —— 十万条待发消息比丢几条更糟</li>
 *   <li><b>只在内存里</b>：与等待表（{@code QqPaymentProofService}）同一性质 ——
 *       重启丢队列，重发一次指令即可，不值得为它建表</li>
 * </ul>
 *
 * <p>⚠️ <b>它不改变「执行必有回响」的纪律</b>：消息只是晚几秒发，
 * 不是被吞掉。真正的丢弃只有两处，都记日志：队列满、以及
 * {@code text} 为空的防御性检查。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "uspace.qqbot.enabled", havingValue = "true")
public class QqReplyQueue {

    /**
     * 队列上限。按默认节奏（10 秒一条）算，压满 100 条约要 17 分钟 ——
     * 正常运营到不了；真到了说明有人在刷，这时丢新消息 + error 日志
     * 比无限堆积（内存 + 越排越久的延迟）更合理。
     */
    private static final int MAX_PENDING = 100;

    private final OneBotClient client;

    private final QqbotProperties properties;

    /** 待发消息。单线程 worker 消费，生产者是各指令的处理线程 */
    private final BlockingQueue<Item> queue = new LinkedBlockingQueue<>(MAX_PENDING);

    /** 发送线程。daemon —— 不让它挡住 JVM 退出 */
    private final Thread worker;

    public QqReplyQueue(OneBotClient client, QqbotProperties properties) {
        this.client = client;
        this.properties = properties;
        this.worker = new Thread(this::loop, "qq-reply-queue");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 排队发一条回复（并 @ 发指令的人）。
     *
     * <p>间隔配成 0（{@code uspace.qqbot.reply-interval=0}）时限流关闭，
     * 直接发送 —— 留给测试与排障，生产别这么配。
     *
     * @param groupId 目标群
     * @param qq      发指令者的 QQ 号（@ 他）。为 null 时不 @
     * @param text    回复正文
     */
    public void submit(Long groupId, Long qq, String text) {
        if (text == null || text.isEmpty()) {
            // 防御：调用方 bug 不该在群里变成一条空消息
            log.warn("[QQ机器人] 回复正文为空，已丢弃（调用方不该走到这里）");
            return;
        }
        if (throttleDisabled() || !worker.isAlive()) {
            // 限流关掉时直发；worker 已停（进程收尾或异常死掉）时也不能让消息烂在队列里
            send(groupId, qq, text);
            return;
        }
        if (!queue.offer(new Item(groupId, qq, text))) {
            log.error("[QQ机器人] 回复队列已满（{} 条），本条未发送：{}",
                    MAX_PENDING, truncate(text));
        }
    }

    /**
     * 发送线程的主循环：取一条、等一个随机间隔、发出去，再取。
     *
     * <p>⚠️ <b>先等再发（含第一条）</b>，顺序与直觉相反但正是需求：
     * 「不要秒回」—— 秒回同样是被盯的特征（见类注释）。
     * 队列空时阻塞在 {@code take()}，不空转；发送失败只记日志
     * （{@code OneBotClient} 自己也不抛），一条发不出去不拖垮后面所有消息。
     */
    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Item item = queue.take();
                long delay = nextDelayMs();
                if (delay > 0) {
                    Thread.sleep(delay);
                }
                send(item.groupId(), item.qq(), item.text());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                // 兜底：未知异常不能让 worker 死掉，否则后续所有回复都发不出去
                log.error("[QQ机器人] 回复队列发送异常（循环继续）", e);
            }
        }
    }

    /**
     * 实际发送：带 QQ 号时走 @ 版（段数组，防昵称注入 —— 见
     * {@code OneBotAction#sendGroupMessageWithAt} 的注释）。
     *
     * @param groupId 目标群
     * @param qq      被 @ 的人，可为 null
     * @param text    正文
     */
    private void send(Long groupId, Long qq, String text) {
        if (qq == null) {
            client.sendGroupMessage(groupId, text);
        } else {
            client.sendGroupMessageAt(groupId, String.valueOf(qq), text);
        }
    }

    /**
     * 这条消息发出前该等多久：在 [下限, 上限] 之间均匀随机。
     *
     * <p>边界处理：下限夹到非负；上限配得比下限还小时按下限执行
     * （配置写错不该让队列倒过来、也不该让它全速发送）。
     *
     * @return 毫秒数
     */
    private long nextDelayMs() {
        long min = Math.max(0, properties.getReplyMinInterval().toMillis());
        long max = properties.getReplyMaxInterval().toMillis();
        if (max <= min) {
            return min;
        }
        return min + ThreadLocalRandom.current().nextLong(max - min + 1);
    }

    /**
     * 限流是否被关掉 —— <b>上限配成 0（或负）即直发</b>。
     *
     * <p>用「上限」而不是「下限」当开关：上限为 0 意味着「最多等 0 秒」，
     * 与直发是同一件事，语义上不矛盾；留给测试与排障（生产配置下限 3 / 上限 10）。
     */
    private boolean throttleDisabled() {
        Duration max = properties.getReplyMaxInterval();
        return max == null || max.isZero() || max.isNegative();
    }

    /**
     * 日志里的正文截断。
     *
     * @param text 正文
     * @return 最多 60 字
     */
    private static String truncate(String text) {
        String flat = text.replace('\n', ' ');
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }

    /** 关闭时叫停 worker（Spring 容器销毁 / 应用退出）。 */
    @PreDestroy
    void stop() {
        worker.interrupt();
    }

    /**
     * 一条待发消息。
     *
     * @param groupId 目标群
     * @param qq      发指令者的 QQ 号（@ 他），可为 null
     * @param text    正文
     */
    private record Item(Long groupId, Long qq, String text) {
    }
}
