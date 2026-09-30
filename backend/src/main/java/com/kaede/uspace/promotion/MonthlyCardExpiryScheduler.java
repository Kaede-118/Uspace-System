package com.kaede.uspace.promotion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 月卡到期调度器（模块 9）。
 *
 * <p><b>做什么</b>：按固定间隔扫一遍，把 {@code end_date} 已过的生效中卡
 * 翻转为已过期。所有判断都在 {@link MonthlyCardService#expireOutdated} 的
 * 一条 UPDATE 里，本类只负责「什么时候叫它」。
 *
 * <p><b>它不影响谁能免单</b> —— 这点值得说清楚，否则容易以为它是一个关键任务。
 * 免单判定同时校验状态与有效期：
 * <pre>
 *   status = 'ACTIVE' AND start_date &lt;= 某日 AND end_date &gt;= 某日
 * </pre>
 * 日期条件保证了「到点就停」，与状态是否已翻转无关。所以本调度器
 * 即便被关掉、或因服务停机漏跑，也不会出现「过期卡还在免单」——
 * 它只是让列表上的状态显示得准一点（运营后台筛选、模块 10 统计口径）。
 *
 * <p><b>为什么用定时扫描而不是「到点触发」</b>：与包场清场同理 ——
 * 卡是提前买好的，到期日分布在未来任意一天，应用可能在此期间重启过。
 * 扫描只需要「今天的日期 + 一条 UPDATE」就能得到正确结论，
 * 不依赖任何内存中的计划表，重启不丢。
 *
 * <p><b>重复执行是安全的</b>：翻转过的卡不再是生效中，下一轮查不出来，
 * 因此不必记录「哪些处理过了」，也不存在「记录与实际不一致」的可能。
 *
 * <p><b>开销</b>：每小时一条走 {@code idx_end_date} 的 UPDATE。
 * 没有到期卡时受影响行数为 0，不产生任何写放大。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "uspace.promotion.expiry-scheduler.enabled",
        havingValue = "true", matchIfMissing = true)
public class MonthlyCardExpiryScheduler {

    /** 月卡服务：到期翻转的实际执行者 */
    private final MonthlyCardService cardService;

    public MonthlyCardExpiryScheduler(MonthlyCardService cardService) {
        this.cardService = cardService;
    }

    /**
     * 扫描一轮：把已过有效期的卡置为已过期。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：前者是「上一轮结束后再等 N 秒」，
     * 后者是「每 N 秒起一轮」。这个任务的执行时间可以忽略，但统一用 fixedDelay
     * 更稳妥 —— 将来若在这个任务里加了别的动作，不会因为单轮变慢而堆积。
     *
     * <p><b>异常不会中断调度</b>：方法内自行 catch 并记日志，把「哪一轮失败」
     * 写清楚，排查时不必去翻框架的日志格式。失败不影响正确性 ——
     * 状态没翻转而已，免单判定照旧按日期走。
     */
    @Scheduled(fixedDelayString = "${uspace.promotion.expiry-scheduler.interval-millis:3600000}")
    public void expireOutdatedCards() {
        try {
            int expired = cardService.expireOutdated(LocalDate.now());
            if (expired > 0) {
                log.info("[优惠] 已将 {} 张到期月卡置为已过期", expired);
            }
        } catch (RuntimeException e) {
            log.error("[优惠] 月卡到期翻转失败，下一轮将重试（不影响免单判定，该停的卡仍会停）", e);
        }
    }
}
