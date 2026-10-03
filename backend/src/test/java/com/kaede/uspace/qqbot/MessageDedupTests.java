package com.kaede.uspace.qqbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MessageDedup} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring。</b>时钟用一个<b>可以拨</b>的假实现 ——
 * 清理逻辑按「保留期」判断，真等 10 分钟没法测。
 */
class MessageDedupTests {

    /** 可手动推进的时钟 */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private final MutableClock clock = new MutableClock();

    private final MessageDedup dedup = new MessageDedup(clock);

    @Test
    @DisplayName("同一条消息第二次出现时判为重复")
    void isDuplicate_detectsRepeat() {
        assertFalse(dedup.isDuplicate(1001L), "第一次见到，不是重复");
        assertTrue(dedup.isDuplicate(1001L), "同一条再来一次就是重复");
    }

    @Test
    @DisplayName("不同的消息互不影响")
    void isDuplicate_keepsDifferentIdsIndependent() {
        assertFalse(dedup.isDuplicate(1001L));
        assertFalse(dedup.isDuplicate(1002L));
        assertTrue(dedup.isDuplicate(1001L));
        assertTrue(dedup.isDuplicate(1002L));
    }

    @Test
    @DisplayName("拿不到 messageId 时不去重：宁可多回一条，也别不理用户")
    void isDuplicate_treatsMissingIdAsFresh() {
        assertFalse(dedup.isDuplicate(null));
        assertFalse(dedup.isDuplicate(null),
                "没有 ID 就没有判重的依据 —— 返回 true 会让那条消息被静默丢掉");
        assertEquals(0, dedup.size(), "null 不该被记进表里");
    }

    @Test
    @DisplayName("记录表有上界：老记录会被清掉，不会无限增长")
    void isDuplicate_evictsOldEntries() {
        // 先塞满一批，把表推过清理阈值
        for (long id = 1; id <= 600; id++) {
            dedup.isDuplicate(id);
        }
        assertTrue(dedup.size() > 0, "前置条件：表里应当有记录");

        // 时间推过保留期（10 分钟）之后再来一条新的，这次写入应当触发一次清理
        clock.advance(Duration.ofMinutes(11));
        dedup.isDuplicate(9999L);

        assertTrue(dedup.size() < 600,
                "超过保留期的记录应当被清掉 —— 不清理的话，常驻进程的记录表会一直涨到重启");
    }

    @Test
    @DisplayName("保留期内的记录不会被清掉")
    void isDuplicate_keepsFreshEntries() {
        for (long id = 1; id <= 600; id++) {
            dedup.isDuplicate(id);
        }
        // 只推进 1 分钟，远没到保留期
        clock.advance(Duration.ofMinutes(1));
        dedup.isDuplicate(9999L);

        assertTrue(dedup.isDuplicate(1L),
                "刚记下不久的消息仍是重复的 —— 清理不能把还在保留期内的记录带走");
    }
}
