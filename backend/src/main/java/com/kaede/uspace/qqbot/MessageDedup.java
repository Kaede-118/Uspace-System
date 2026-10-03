package com.kaede.uspace.qqbot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群消息去重（模块 11）。
 *
 * <p>以 OneBot 的 {@code message_id} 为键，判断一条消息是否已经处理过。
 *
 * <h3>为什么反向 WebSocket 下还要去重</h3>
 *
 * <p>设计文档原来给的理由是「OneBot 上报有超时，等不到响应会重推」——
 * 那条描述的是 <b>HTTP 上报</b>模式的行为，反向 WebSocket 下并没有 ack 机制，
 * 也就不存在因超时而重推。所以这条去重<b>不是正确性防线</b>：
 * 就算漏了，最坏后果也只是重复回复一条消息（机器人只读，不写任何数据）。
 *
 * <p>但去重的成本是零，而它防住的是<b>群被刷屏</b>这件很难看的事 ——
 * 群里出现两条一模一样的「当前 3 人在店」，用户不知道是系统坏了还是真发了两次。
 *
 * <h3>有界</h3>
 *
 * <p>记录表不能无限增长：机器人是常驻进程，群里一天几百条消息，
 * 不清理的话这张表会一直涨到进程重启。
 * 清理<b>只在写入路径上、且表超过阈值时</b>做一次全扫
 * （模块 1 的 {@code QqVerifyService} 是同一个手法，只是那边没有阈值判断）——
 * 每条消息都全扫一遍是没必要的开销，而低频时表本来就小、不清也无所谓。
 *
 * <p>⚠️ <b>清理会带来一个理论上的漏网</b>：一条超过 {@link #RETENTION} 的旧记录被清掉之后，
 * 若同一个 {@code message_id} 再来一次，会被当成新消息。这<b>不会真的发生</b> ——
 * {@code message_id} 由 NapCat 单调递增地分配，不会重用。
 * 换句话说，清理只影响内存占用，不影响判断的正确性。
 */
@Slf4j
@Component
public class MessageDedup {

    /**
     * 记录保留时长。
     *
     * <p>取 10 分钟：真要发生重推，间隔是秒级的；留这么长是因为长一点几乎没有代价
     * （内存里多几条记录），而短了会让「延迟很久才重推」这种极端情况漏过去。
     */
    private static final Duration RETENTION = Duration.ofMinutes(10);

    /**
     * 触发清理的记录数阈值。
     *
     * <p>低于它就不扫 —— 一个只有几十条记录的表扫不扫都占不了多少内存，
     * 而每条消息都全扫一遍是白白烧 CPU。
     */
    private static final int CLEAN_THRESHOLD = 512;

    /** 已处理过的消息 ID → 首次见到的时刻 */
    private final Map<Long, LocalDateTime> seen = new ConcurrentHashMap<>();

    private final Clock clock;

    /**
     * 构造器注入。
     *
     * @param clock 系统时钟。注入而非直接取，便于测试拨钟验证清理逻辑
     *              （复用模块 1 的 {@code QqVerifyConfig} 里那个 bean）
     */
    public MessageDedup(Clock clock) {
        this.clock = clock;
    }

    /**
     * 判断并登记一条消息。
     *
     * <p><b>「判断」与「登记」是同一次操作</b>，不是先查后写 —— 后者在
     * NapCat 快速重发（两次间隔几毫秒）时会两个线程都查到 null、都判定为新消息，
     * 于是重复回复，而去重表看起来完全正常。{@code putIfAbsent} 是原子的，
     * 只有一个线程能拿到 null。
     *
     * @param messageId 消息 ID，可为 null（拿不到 ID 时不去重，按「没处理过」处理）
     * @return 这条消息此前已经处理过时返回 true，调用方应当直接丢弃
     */
    public boolean isDuplicate(Long messageId) {
        if (messageId == null) {
            // 没有 ID 就没法去重。宁可多回一条，也不要因为拿不到 ID 就不理用户
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime previous = seen.putIfAbsent(messageId, now);
        if (previous != null) {
            log.debug("[QQ机器人] 重复消息已丢弃 messageId={}", messageId);
            return true;
        }
        evictExpired(now);
        return false;
    }

    /**
     * 当前记录条数。仅供测试断言与排查用。
     *
     * @return 表里现有的记录数
     */
    public int size() {
        return seen.size();
    }

    /**
     * 清掉超过保留期的记录，但只在表超过阈值时做。
     *
     * @param now 本次操作的统一「此刻」
     */
    private void evictExpired(LocalDateTime now) {
        if (seen.size() <= CLEAN_THRESHOLD) {
            return;
        }
        LocalDateTime deadline = now.minus(RETENTION);
        seen.values().removeIf(seenAt -> seenAt.isBefore(deadline));
    }
}
