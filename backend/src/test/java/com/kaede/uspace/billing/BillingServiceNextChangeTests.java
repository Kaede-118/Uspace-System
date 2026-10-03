package com.kaede.uspace.billing;

import com.kaede.uspace.billing.dto.NextChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 跳档预告（{@link BillingService#nextChange}）的测试。
 *
 * <p><b>这个功能错了不会报任何错</b> —— 它只是在首页与结账页上写一句
 * 「还有多久进入下一档」。算错的后果是用户按错误的时间预期决定何时停表，
 * 而系统一切正常。所以边界逐条钉死。
 *
 * <p>四类最容易写错的地方：
 * <ol>
 *   <li><b>宽限期内</b>：{@code units = 0} 时下一次是第 <b>6</b> 分钟而不是第 36 分钟 ——
 *       差一个「+1」就会让刚进门的用户看到「还有 36 分钟才收费」</li>
 *   <li><b>档位边界恰好命中</b>：玩到 35 分钟时仍算第 1 档，下一次变化在 36 分钟，
 *       即 1 分钟后 —— 而不是「已经跳档」</li>
 *   <li><b>下一个档位落在时段之外</b>：必须报 {@code PERIOD} 而不是 {@code TIER}。
 *       只做 TIER 的话，21:40 进场的用户会看到「还有 36 分钟进入下一档」，
 *       而他实际在 22:00 就跨进夜场重新起了一段（夜场更便宜）</li>
 *   <li><b>已达封顶</b>：金额不再增长，秒数必须为 null —— 给一个「还有 6 分钟涨价」
 *       是彻头彻尾的假消息</li>
 * </ol>
 *
 * <p>参数取自 {@code application.properties}（宽限 5 分钟、每档 30 分钟、
 * 日场 10:00–22:00），所以本类用 {@code @SpringBootTest} 而不是手工 new ——
 * 改配置时这些用例会跟着变，而不是继续按写死的旧参数通过。
 */
@SpringBootTest
class BillingServiceNextChangeTests {

    /** 未达门槛的当月累计额 */
    private static final BigDecimal BELOW_THRESHOLD = new BigDecimal("199");

    /** 恰好达标的当月累计额（优惠价生效） */
    private static final BigDecimal AT_THRESHOLD = new BigDecimal("200");

    /** 测试基准日期，与 {@link #at} 用的是同一天 */
    private static final LocalDate DATE = LocalDate.of(2026, 9, 16);

    /**
     * 覆盖整个测试区间的全天卡。
     *
     * <p>有效期取宽一些（前后各两天）—— 这些用例要验的是<b>时段与档位</b>，
     * 日期维度另有用例专门守着。范围给窄了会让它们被日期边界意外截断，
     * 变成一条看不出所以然的断言失败。
     */
    private static final CardCoverage ALL_DAY_CARD =
            CardCoverage.of(CardScope.ALL, DATE.minusDays(2), DATE.plusDays(2));

    /** 覆盖整个测试区间的夜间卡 */
    private static final CardCoverage NIGHT_CARD =
            CardCoverage.of(CardScope.NIGHT, DATE.minusDays(2), DATE.plusDays(2));

    @Autowired
    private BillingService billingService;

    /**
     * 构造一个固定日期的时刻，避免测试结果随运行时刻变化。
     *
     * @param hour   小时
     * @param minute 分钟
     * @return 2026-09-16 当天的该时刻（月中，避开月初月末的干扰）
     */
    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 16, hour, minute);
    }

    /**
     * 断言预告的剩余秒数等于「当前时刻到目标时刻」的真实差值。
     *
     * <p>用 {@code Duration} 现算而不是写死秒数：写死的数字在改配置后不会失败，
     * 只会静默地不再验证任何东西。
     *
     * @param expected 期望的变化时刻
     * @param now      当前时刻
     * @param change   实际预告
     */
    private static void assertAt(LocalDateTime expected, LocalDateTime now, NextChange change) {
        assertNotNull(change, "应当有预告");
        assertNotNull(change.getInSeconds(), "这一档应当给出倒计时秒数");
        assertEquals(Duration.between(now, expected).getSeconds(), change.getInSeconds().longValue(),
                "距离 " + expected + " 还有 " + Duration.between(now, expected).getSeconds()
                        + " 秒，实际预告 " + change.getInSeconds() + " 秒");
    }

    // ------------------------------------------------------------------
    // 段内跳档（TIER）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("宽限期内：下一次变化是第 6 分钟，不是第 36 分钟")
    void withinGrace_pointsAtSixthMinute() {
        NextChange change = billingService.nextChange(at(10, 0), at(10, 2), null, null, null);

        assertAt(at(10, 6), at(10, 2), change);
        assertEquals("进入下一档 ¥4.00", change.getText());
    }

    @Test
    @DisplayName("档位边界恰好命中：35 分钟时仍算第 1 档，1 分钟后才跳档")
    void exactlyAtBoundary_stillPointsAtNextTier() {
        // 35 分钟时「可计费分钟」恰为 30，仍收 4 元；跳到 8 元要到第 36 分钟。
        // 少写那个「+1」的话，这里会算出「已经跳档」或「还有 30 分钟」
        NextChange change = billingService.nextChange(at(10, 0), at(10, 35), null, null, null);

        assertAt(at(10, 36), at(10, 35), change);
        assertEquals("进入下一档 ¥8.00", change.getText());
    }

    @Test
    @DisplayName("第 2 档中：下一次跳档在第 66 分钟")
    void inSecondUnit_pointsAtSixtySixthMinute() {
        NextChange change = billingService.nextChange(at(10, 0), at(10, 50), null, null, null);

        assertAt(at(11, 6), at(10, 50), change);
        assertEquals("进入下一档 ¥12.00", change.getText());
    }

    @Test
    @DisplayName("已享月度优惠：下一档金额按优惠价算")
    void discounted_usesDiscountPrice() {
        NextChange change = billingService.nextChange(at(10, 0), at(10, 2), AT_THRESHOLD, null, null);

        assertAt(at(10, 6), at(10, 2), change);
        assertEquals("进入下一档 ¥3.50", change.getText(),
                "门槛已达，单价降到 3.5 元 —— 拿原价 4 元去算会让用户多估一档的价钱");
    }

    @Test
    @DisplayName("差 1 元未达标：仍按原价")
    void justBelowThreshold_usesOriginalPrice() {
        NextChange change = billingService.nextChange(at(10, 0), at(10, 2), BELOW_THRESHOLD, null, null);

        assertEquals("进入下一档 ¥4.00", change.getText());
    }

    @Test
    @DisplayName("跨时段后：当前段是夜场，按夜场价预告")
    void afterCrossing_usesNightPrices() {
        // 21:00 进场、现在 23:00 —— 当前段是 22:00 起的那一段，段内已 60 分钟
        NextChange change = billingService.nextChange(at(21, 0), at(23, 0), null, null, null);

        assertAt(at(23, 6), at(23, 0), change);
        assertEquals("进入下一档 ¥10.50", change.getText(),
                "段起点要是错用了订单起点（21:00），这里会算出一个属于日场的答案");
    }

    @Test
    @DisplayName("恰好落在时段边界上：按刚起头的新段预告")
    void exactlyAtPeriodBoundary_treatsAsFreshSegment() {
        // 22:00:00 整 —— 上一段在边界处结束，此刻属于夜场的第 0 分钟
        NextChange change = billingService.nextChange(at(21, 0), at(22, 0), null, null, null);

        assertAt(at(22, 6), at(22, 0), change);
        assertEquals("进入下一档 ¥3.50", change.getText());
    }

    // ------------------------------------------------------------------
    // 跨时段（PERIOD）—— 本功能最容易漏掉的一档
    // ------------------------------------------------------------------

    @Test
    @DisplayName("日场里档位边界落在 22:00 之后：报跨时段，而不是报一个到不了的档位")
    void daySegmentWithTierBeyondBoundary_reportsPeriod() {
        // 21:40 进场、现在 21:50：段内 10 分钟已是第 1 档，下一个档位在 22:16 ——
        // 但 22:00 就跨进夜场了，那才是先发生的事
        NextChange change = billingService.nextChange(at(21, 40), at(21, 50), null, null, null);

        assertAt(at(22, 0), at(21, 50), change);
        assertEquals("跨入夜场，按夜场重新计价", change.getText(),
                "报 TIER 会写「还有 26 分钟进入下一档」，而用户实际在 10 分钟后就跨段了 —— "
                        + "展示一个错误的时间预期比不展示更糟");
    }

    @Test
    @DisplayName("夜场里档位边界落在 10:00 之后：报跨入日场")
    void nightSegmentWithTierBeyondBoundary_reportsPeriod() {
        // 08:00 进场（夜场）、现在 09:55：段内 115 分钟已是第 4 档，下一个档位在 10:06，
        // 而 10:00 就跨进日场了
        NextChange change = billingService.nextChange(at(8, 0), at(9, 55), null, null, null);

        assertAt(at(10, 0), at(9, 55), change);
        assertEquals("跨入日场，按日场重新计价", change.getText());
    }

    @Test
    @DisplayName("档位边界恰好与时段边界重合：按跨时段报")
    void tierExactlyAtPeriodBoundary_reportsPeriod() {
        // 17:24 进场、现在 21:30 —— 段内 246 分钟是第 9 档，下一个档位在 22:00，
        // 恰好与日场结束同一时刻。此时时段已经变了，按跨时段说更准确。
        //
        // 档位刻意选在第 9 档而不是更高：第 10 档起金额已达封顶，
        // 那时连「下一档」都不存在了，走的是「当前已到封顶价」那条分支
        NextChange change = billingService.nextChange(at(17, 24), at(21, 30), null, null, null);

        assertAt(at(22, 0), at(21, 30), change);
        assertEquals("跨入夜场，按夜场重新计价", change.getText());
    }

    // ------------------------------------------------------------------
    // 段内不再变化
    // ------------------------------------------------------------------

    @Test
    @DisplayName("已达封顶：只给说明、不给倒计时")
    void capped_reportsNoFurtherChange() {
        // 10:00 进场、现在 15:00 = 300 分钟，第 10 档，金额恰好等于日场封顶 40 元。
        // 注意此时段上的 capped 标志仍是 false（那要到第 11 档才为 true），
        // 靠它判断会漏报，必须用「原始金额 ≥ 封顶」
        NextChange change = billingService.nextChange(at(10, 0), at(15, 0), null, null, null);

        assertNotNull(change, "应当有一条说明");
        assertNull(change.getInSeconds(),
                "已到封顶价就不能再给倒计时 —— 「还有 6 分钟涨价」是彻头彻尾的假消息");
        assertEquals("当前已到封顶价", change.getText());
    }

    @Test
    @DisplayName("已达封顶：优惠价下同样识别得出")
    void capped_withDiscountPrice() {
        // 优惠价封顶 35 元 = 10 档 × 3.5 元，第 10 档同样在 4 小时 36 分起
        NextChange change = billingService.nextChange(at(10, 0), at(15, 0), AT_THRESHOLD, null, null);

        assertNull(change.getInSeconds(), "优惠价下封顶值不同，判定也必须跟着走");
        assertEquals("当前已到封顶价", change.getText());
    }

    @Test
    @DisplayName("全天卡覆盖的段：说明月卡免费，不给倒计时")
    void coveredByAllDayCard_reportsFree() {
        NextChange change = billingService.nextChange(at(10, 0), at(10, 2), null, ALL_DAY_CARD, null);

        assertNull(change.getInSeconds(), "被月卡覆盖的段实收恒为 0，段内不会有金额变化");
        assertEquals("当前时段月卡免费", change.getText());
    }

    @Test
    @DisplayName("夜间卡在夜场段免费、在日场段照常预告")
    void nightCard_coversNightButNotDay() {
        NextChange night = billingService.nextChange(at(21, 0), at(23, 0), null, NIGHT_CARD, null);
        assertNull(night.getInSeconds(), "夜场段被夜间卡覆盖");

        NextChange day = billingService.nextChange(at(10, 0), at(10, 2), null, NIGHT_CARD, null);
        assertAt(at(10, 6), at(10, 2), day);
        assertEquals("进入下一档 ¥4.00", day.getText(),
                "日场段不在夜间卡的覆盖范围里 —— 照抄「有卡就免费」会让账单少收钱");
    }

    @Test
    @DisplayName("月卡在本段结束前到期：报「月卡即将到期」而不是「免费」")
    void cardExpiresWithinSegment_reportsExpiry() {
        // 23:00 起步、卡在零点失效。这一段的时长为 2 小时（次日 01:00 才跨时段），
        // 所以先发生的不是时段边界、也不是档位边界，而是卡失效
        CardCoverage card = CardCoverage.of(CardScope.ALL, DATE.minusDays(29), DATE);
        NextChange change = billingService.nextChange(at(23, 0), at(23, 50), null, card, null);

        assertEquals(Duration.between(at(23, 50), DATE.plusDays(1).atStartOfDay()).getSeconds(),
                change.getInSeconds(), "倒计时的终点是卡失效的那一刻");
        assertEquals("月卡即将到期，之后按时长计费", change.getText());
    }

    @Test
    @DisplayName("月卡在下一时段边界之后才到期：仍报「月卡免费」")
    void cardOutlivesSegment_reportsFree() {
        // 22:30 起步，段在次日 10:00 才结束，而卡还有两天 —— 本段内不会到期
        CardCoverage card = CardCoverage.of(CardScope.ALL, DATE.minusDays(5), DATE.plusDays(5));
        NextChange change = billingService.nextChange(at(22, 30), at(23, 0), null, card, null);

        assertNull(change.getInSeconds(), "本段内卡不会失效，就没有可倒计时的东西");
        assertEquals("当前时段月卡免费", change.getText());
    }

    // ------------------------------------------------------------------
    // 参数边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("起点与当前时刻相同：按刚起头的那一段预告")
    void sameStartAndNow_treatsAsFreshSegment() {
        NextChange change = billingService.nextChange(at(10, 0), at(10, 0), null, null, null);

        assertAt(at(10, 6), at(10, 0), change);
    }

    @Test
    @DisplayName("当前时刻早于起点：返回 null 而不是抛异常")
    void nowBeforeStart_returnsNull() {
        // 时钟回拨或调用方传错参数时会走到这里。预告是锦上添花的信息，
        // 为它抛异常会把整个结账预览拖下水
        assertNull(billingService.nextChange(at(10, 0), at(9, 0), null, null, null));
    }

    @Test
    @DisplayName("时间参数为空：抛异常，把编程错误挡在早期")
    void nullTime_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> billingService.nextChange(null, at(10, 0), null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> billingService.nextChange(at(10, 0), null, null, null, null));
    }
}
