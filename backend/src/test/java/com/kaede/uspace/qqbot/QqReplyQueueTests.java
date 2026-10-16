package com.kaede.uspace.qqbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link QqReplyQueue} 的单元测试。
 *
 * <p><b>它守的是「群消息的发送节奏」</b>：每条回复发出前要等一段随机时长
 * （默认 3~10 秒），<b>连第一条也要等</b> —— 秒回与连发都是 QQ 风控盯的特征
 * （2026-10-10 由用户要求）。写错了不报错：队列退化成「同步直发」时
 * 功能一切正常，只有风控来的时候才知道。
 *
 * <p>用例用<b>毫秒级间隔</b>跑（50ms），验证的是节拍本身而不是具体秒数 ——
 * 具体的 3 秒 / 10 秒是配置，由 {@code QqbotProperties} 管。
 * 时间断言都留了余量（调度抖动），不会随机翻红。
 */
class QqReplyQueueTests {

    private static final Long GROUP_ID = 10001L;

    private static final Long QQ = 2198047522L;

    /** 测试用的间隔：50 毫秒，够短（用例快）又够大（能断言出先后） */
    private static final long GAP_MS = 50;

    /** 连间隔的一半都不到 —— 「还没发」的断言在这个时点做，躲开调度抖动 */
    private static final long NOT_YET_MS = 15;

    /** 被测队列（每个用例自己建，用完关掉） */
    private QqReplyQueue queue;

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.stop();
        }
    }

    @Test
    @DisplayName("⚠️ 守门：连第一条也要等 —— 秒回同样是被盯的特征")
    void submit_waitsBeforeTheFirstMessage() throws InterruptedException {
        RecordingClient client = new RecordingClient();
        queue = new QqReplyQueue(client, props(GAP_MS));

        queue.submit(GROUP_ID, QQ, "第一条");
        Thread.sleep(NOT_YET_MS);

        assertEquals(0, client.sent.size(),
                "发出前必须等：队列空时「立即发第一条」那种常见写法在这里是错的");

        waitUntil(() -> client.sent.size() >= 1);
        assertEquals("第一条", client.sent.get(0).text());
    }

    @Test
    @DisplayName("按入队顺序一条条发，相邻两条的间隔不小于下限")
    void submit_drainsInOrderWithGap() throws InterruptedException {
        RecordingClient client = new RecordingClient();
        queue = new QqReplyQueue(client, props(GAP_MS));

        queue.submit(GROUP_ID, QQ, "一");
        queue.submit(GROUP_ID, QQ, "二");
        queue.submit(GROUP_ID, QQ, "三");
        waitUntil(() -> client.sent.size() >= 3);

        assertEquals(List.of("一", "二", "三"),
                client.sent.stream().map(Sent::text).toList(), "顺序即入队顺序");

        // 间隔断言留 40% 余量（调度抖动、nanoTime 与 sleep 的精度差）
        long firstGap = client.sent.get(1).at() - client.sent.get(0).at();
        long secondGap = client.sent.get(2).at() - client.sent.get(1).at();
        assertTrue(firstGap >= GAP_MS * 600_000L,
                "相邻两条之间要隔一个完整间隔（纳秒计）：实际 " + firstGap / 1_000_000 + "ms");
        assertTrue(secondGap >= GAP_MS * 600_000L,
                "连发多条同样逐条限速：实际 " + secondGap / 1_000_000 + "ms");
    }

    @Test
    @DisplayName("带 @ 发送：走段数组那条（atQq 原样透传，正文里不含 @ 拼串）")
    void submit_sendsWithAt() throws InterruptedException {
        RecordingClient client = new RecordingClient();
        queue = new QqReplyQueue(client, props(GAP_MS));

        queue.submit(GROUP_ID, QQ, "正文");
        waitUntil(() -> client.sent.size() >= 1);

        assertEquals(String.valueOf(QQ), client.sent.get(0).atQq(),
                "回复一律 @ 发指令的人 —— 群里一条条 @ 才认得出哪条回的是谁");
        assertEquals("正文", client.sent.get(0).text(),
                "@ 走独立的段（sendGroupMessageAt），不是拼进正文的字符串");
    }

    @Test
    @DisplayName("QQ 为 null 时退化成纯文本，不 @")
    void submit_withoutQq() throws InterruptedException {
        RecordingClient client = new RecordingClient();
        queue = new QqReplyQueue(client, props(GAP_MS));

        queue.submit(GROUP_ID, null, "无主消息");
        waitUntil(() -> client.sent.size() >= 1);

        assertNull(client.sent.get(0).atQq());
    }

    @Test
    @DisplayName("上限配 0 时限流关闭：同步直发，不等 worker")
    void submit_directWhenThrottleDisabled() {
        RecordingClient client = new RecordingClient();
        queue = new QqReplyQueue(client, props(0));

        queue.submit(GROUP_ID, QQ, "直发");

        assertEquals(1, client.sent.size(),
                "上限为 0 = 「最多等 0 秒」＝ 直发（留给测试与排障，生产别这么配）");
    }

    @Test
    @DisplayName("空正文被丢弃，不会在群里变成一条空消息")
    void submit_dropsEmptyText() {
        RecordingClient client = new RecordingClient();
        queue = new QqReplyQueue(client, props(0));

        queue.submit(GROUP_ID, QQ, "");

        assertEquals(0, client.sent.size());
    }

    // ==================================================================
    // 造数据的小工具
    // ==================================================================

    /**
     * 造一份间隔固定的配置（下限＝上限，把随机量收成 0，断言才好写）。
     *
     * @param gapMs 间隔毫秒数；0 表示关闭限流
     * @return 配置
     */
    private static QqbotProperties props(long gapMs) {
        QqbotProperties properties = new QqbotProperties();
        properties.setReplyMinInterval(Duration.ofMillis(gapMs));
        properties.setReplyMaxInterval(Duration.ofMillis(gapMs));
        return properties;
    }

    /**
     * 等到条件成立，超时则失败。
     *
     * <p>轮询而不是固定 sleep：worker 的调度时间不保证，固定等待会让用例
     * 在慢机器上随机翻红。
     *
     * @param condition 条件
     */
    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        fail("等待超时：消息没有按预期发出来");
    }

    /**
     * 一条被记录下来的发送。
     *
     * @param atQq @ 的 QQ 号；纯文本发送时为 null
     * @param text 正文
     * @param at   发送时刻（纳秒）
     */
    private record Sent(String atQq, String text, long at) {
    }

    /**
     * 只记下发出的群消息、不碰连接的出站桩。
     */
    private static class RecordingClient extends OneBotClient {

        private final List<Sent> sent = new CopyOnWriteArrayList<>();

        RecordingClient() {
            // 这个 ObjectMapper 只在真正发送时才用得到，本桩不会走到那里
            super(new ObjectMapper());
        }

        @Override
        public boolean sendGroupMessage(Long groupId, String text) {
            sent.add(new Sent(null, text, System.nanoTime()));
            return true;
        }

        @Override
        public boolean sendGroupMessageAt(Long groupId, String atQq, String text) {
            sent.add(new Sent(atQq, text, System.nanoTime()));
            return true;
        }
    }
}
