package com.kaede.uspace.billing;

import com.kaede.uspace.billing.dto.BillingResult;
import com.kaede.uspace.billing.dto.SegmentBill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计费服务的测试。
 *
 * <p>计费规则里有多处「边界容易差一份」的地方（5 分钟免费线、35/36 分钟的进位线、
 * 整点是否恰好为单价 × 2、四个封顶点、跨时段的切分、月度优惠的门槛边界），
 * 这里逐条钉死。这些用例同时也是计费准确性的验证材料。
 *
 * <p>2026-09-26 定价改版、2026-09-28 调整宽限后的关键参数：
 * <ul>
 *   <li>日场 4 元/30 分、封顶 40；月度优惠价 3.5 元/30 分、封顶 35</li>
 *   <li>夜场 3.5 元/30 分、封顶 35；月度优惠价 3 元/30 分、封顶 30</li>
 *   <li>免费宽限 5 分钟（档位边界落在 5 / 35 / 65 …）；月度优惠门槛 200 元</li>
 * </ul>
 * 注意四组封顶都等于「10 档 × 对应单价」，即 5 小时价格。
 * 宽限只影响档位边界，不改动单价与封顶金额，因此整点金额、封顶金额、
 * 月度优惠相关的用例不受 09-28 调整影响。
 */
@SpringBootTest
class BillingServiceTests {

    /** 金额比较时的标度无关比较用 compareTo，这里统一用 0 判断 */
    private static final int ZERO = 0;

    /** 未达门槛的当月累计额（差 1 元） */
    private static final BigDecimal BELOW_THRESHOLD = new BigDecimal("199");

    /** 恰好等于门槛，用于验证判定方向是 {@code >=} 而非 {@code >} */
    private static final BigDecimal AT_THRESHOLD = new BigDecimal("200");

    /** 已明显达标的当月累计额 */
    private static final BigDecimal ABOVE_THRESHOLD = new BigDecimal("500");

    @Autowired
    private BillingService billingService;

    /**
     * 构造一个固定日期的时间点，避免测试结果随运行时刻变化。
     *
     * @param hour   小时
     * @param minute 分钟
     * @return 2026-09-16 当天的该时刻（月中，避开月初月末的干扰）
     */
    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 16, hour, minute);
    }

    /**
     * 断言计费金额。
     *
     * @param expected 期望金额（元）
     * @param actual   实际计费结果
     */
    private static void assertAmount(String expected, BillingResult actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.getTotalAmount()),
                "期望 " + expected + " 元，实际 " + actual.getTotalAmount() + " 元");
    }

    /**
     * 断言优惠金额。
     *
     * @param expected 期望金额（元）
     * @param actual   实际计费结果
     */
    private static void assertDiscount(String expected, BillingResult actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.getDiscountAmount()),
                "期望优惠 " + expected + " 元，实际 " + actual.getDiscountAmount() + " 元");
    }

    /**
     * 断言金额相等（标度无关）。
     *
     * @param expected 期望值
     * @param actual   实际值
     * @param message  断言失败时的提示
     */
    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message);
    }

    // ------------------------------------------------------------------
    // 免费线与档位边界（宽限 5 分钟，档位边界落在 5 / 35 / 65 …）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5 分钟以内：免费出场，不收费用")
    void withinGrace_isFree() {
        assertAmount("0", billingService.calculate(at(10, 0), at(10, 5)));
        assertAmount("0", billingService.calculate(at(10, 0), at(10, 3)));
    }

    @Test
    @DisplayName("6 分钟：超出宽限 1 分钟，进入第一档收 4 元")
    void oneMinuteOverGrace_chargesFirstUnit() {
        assertAmount("4", billingService.calculate(at(10, 0), at(10, 6)));
    }

    @Test
    @DisplayName("35 分钟：可计费分钟恰为 30，仍收 4 元（余数舍弃）")
    void atFirstBoundary_chargesFirstUnit() {
        assertAmount("4", billingService.calculate(at(10, 0), at(10, 35)));
    }

    @Test
    @DisplayName("36 分钟：可计费分钟超过 30，进位收 8 元")
    void oneMinuteOverBoundary_chargesTwoUnits() {
        assertAmount("8", billingService.calculate(at(10, 0), at(10, 36)));
    }

    // ------------------------------------------------------------------
    // 整点金额 —— 验证宽限确实让整点严格为「单价 × 2」
    // ------------------------------------------------------------------

    @Test
    @DisplayName("日场恰好 1 小时：2 档 × 4 元 = 8 元")
    void dayExactlyOneHour_isEightYuan() {
        assertAmount("8", billingService.calculate(at(10, 0), at(11, 0)));
    }

    @Test
    @DisplayName("日场恰好 2 小时：16 元")
    void dayExactlyTwoHours_isSixteenYuan() {
        assertAmount("16", billingService.calculate(at(10, 0), at(12, 0)));
    }

    @Test
    @DisplayName("日场恰好 3 小时：24 元")
    void dayExactlyThreeHours_isTwentyFourYuan() {
        assertAmount("24", billingService.calculate(at(10, 0), at(13, 0)));
    }

    @Test
    @DisplayName("夜场恰好 1 小时：2 档 × 3.5 元 = 7 元（夜间更便宜）")
    void nightExactlyOneHour_isSevenYuan() {
        assertAmount("7", billingService.calculate(at(22, 0), at(23, 0)));
    }

    // ------------------------------------------------------------------
    // 封顶
    // ------------------------------------------------------------------

    @Test
    @DisplayName("日场恰好 5 小时：金额正好等于 40 元封顶，但不算「超出封顶」")
    void dayExactlyFiveHours_equalsCapButNotMarkedCapped() {
        // 5 小时 = 10 档 × 4 元 = 40 元，刚好等于封顶值
        BillingResult result = billingService.calculate(at(10, 0), at(15, 0));

        assertAmount("40", result);
        SegmentBill segment = result.getSegments().get(0);
        assertEquals(10, segment.getUnits());
        assertFalse(segment.isCapped(), "恰好等于封顶值不算超出");
    }

    @Test
    @DisplayName("日场超长：原始金额 44 元被封到 40 元，标记 capped")
    void daySession_hitsCap() {
        // 10:00 起 5 小时 20 分，可计费 320 − 5 = 315 分钟 → 11 档 × 4 = 44 元
        BillingResult result = billingService.calculate(at(10, 0), at(15, 20));

        assertAmount("40", result);
        assertEquals(1, result.getSegments().size());
        SegmentBill segment = result.getSegments().get(0);
        assertEquals(BillingPeriod.DAY, segment.getPeriod());
        assertTrue(segment.isCapped(), "应标记为已封顶");
        assertMoney("40", segment.getAmount(), "段金额应被封顶");
        assertMoney("44", segment.getRawAmount(), "原始金额应如实保留，便于追溯");
        assertMoney("4", segment.getUnitPrice(), "日场段单价应为 4 元");
        assertMoney("40", segment.getCapAmount(), "封顶值应记为日场原价封顶，账单才解释得了 44 为何变 40");
    }

    @Test
    @DisplayName("夜场超长：原始金额 38.5 元被封到 35 元，标记 capped")
    void nightSession_hitsCap() {
        // 22:00 起 5 小时 20 分（跨到次日 03:20），可计费 320 − 5 = 315 分钟 → 11 档 × 3.5 = 38.5 元
        BillingResult result = billingService.calculate(at(22, 0), at(3, 20).plusDays(1));

        assertAmount("35", result);
        assertEquals(1, result.getSegments().size());
        SegmentBill segment = result.getSegments().get(0);
        assertEquals(BillingPeriod.NIGHT, segment.getPeriod());
        assertTrue(segment.isCapped());
        assertMoney("38.5", segment.getRawAmount(), "原始金额应如实保留");
        assertMoney("3.5", segment.getUnitPrice(), "夜场段单价应为 3.5 元");
    }

    @Test
    @DisplayName("未触顶时不应标记 capped")
    void underCap_isNotMarkedCapped() {
        BillingResult result = billingService.calculate(at(10, 0), at(11, 0));
        assertFalse(result.getSegments().get(0).isCapped());
    }

    // ------------------------------------------------------------------
    // 跨时段
    // ------------------------------------------------------------------

    @Test
    @DisplayName("跨时段：21:30–23:30 切成日场 30 分 4 元 + 夜场 90 分 10.5 元 = 14.5 元")
    void crossPeriod_splitsIntoSegments() {
        BillingResult result = billingService.calculate(at(21, 30), at(23, 30));

        List<SegmentBill> segments = result.getSegments();
        assertEquals(2, segments.size(), "应切成日场与夜场两段");

        SegmentBill day = segments.get(0);
        assertEquals(BillingPeriod.DAY, day.getPeriod());
        assertEquals(30, day.getMinutes());
        assertEquals(1, day.getUnits());
        assertMoney("4", day.getAmount(), "日场段 30 分钟收 1 档 4 元");

        SegmentBill night = segments.get(1);
        assertEquals(BillingPeriod.NIGHT, night.getPeriod());
        assertEquals(90, night.getMinutes());
        assertEquals(3, night.getUnits());
        assertMoney("10.5", night.getAmount(), "夜场段 90 分钟收 3 档 × 3.5 元");

        assertAmount("14.5", result);
    }

    @Test
    @DisplayName("跨时段短单：两段各自落在免费档，合计 0 元（已知规则副作用）")
    void crossPeriod_shortSession_isFree() {
        // 21:55–22:05 共 10 分钟，切成 5 分 + 5 分，两段都恰好等于 5 分钟宽限。
        // 宽限每缩短一次，能落进这个免费档的跨时段订单就更短一分：
        // 同样是 10 分钟，全在日场要收 4 元，跨时段反而免费。
        // 这个窗口现在只有 10 分钟宽（两段各 ≤5 分钟），刻意套利的空间已被压得很小
        BillingResult result = billingService.calculate(at(21, 55), at(22, 5));

        assertEquals(2, result.getSegments().size());
        assertAmount("0", result);

        // 对照组：同样 10 分钟全落在日场，进 1 档收 4 元
        assertAmount("4", billingService.calculate(at(20, 0), at(20, 10)));
    }

    @Test
    @DisplayName("恰好从时段边界开始：不产生 0 分钟的空段")
    void startingExactlyAtBoundary_producesNoEmptySegment() {
        BillingResult result = billingService.calculate(at(22, 0), at(23, 0));

        assertEquals(1, result.getSegments().size());
        assertEquals(BillingPeriod.NIGHT, result.getSegments().get(0).getPeriod());
    }

    @Test
    @DisplayName("跨零点：深夜到次日上午，夜场段封顶 35 元 + 日场段 4 元 = 39 元")
    void acrossMidnight_splitsCorrectly() {
        // 23:00 -> 次日 10:30：夜场 23:00–10:00，日场 10:00–10:30
        BillingResult result = billingService.calculate(at(23, 0), at(10, 30).plusDays(1));

        List<SegmentBill> segments = result.getSegments();
        assertEquals(2, segments.size());
        assertEquals(BillingPeriod.NIGHT, segments.get(0).getPeriod());
        assertEquals(660, segments.get(0).getMinutes(), "23:00 到次日 10:00 共 11 小时");
        assertEquals(BillingPeriod.DAY, segments.get(1).getPeriod());
        assertEquals(30, segments.get(1).getMinutes());

        // 夜场段 660 分钟原始 22 档 = 77 元，封顶 35 元；日场段 30 分钟 = 1 档 4 元
        assertAmount("39", result);
        assertTrue(segments.get(0).isCapped(), "夜场段应触顶");
    }

    @Test
    @DisplayName("夜场在前的跨时段：09:00–11:00 切成夜场 60 分 7 元 + 日场 60 分 8 元 = 15 元")
    void nightThenDay_splitsCorrectly() {
        // 上午 9 点仍属夜场（夜场一直算到次日 10:00），10 点起才转日场。
        // 这个用例专门验证单价是「按时段取」而不是「按段的先后顺序取」——
        // 若把第一段误当日场，会得出 8 + 7 = 15 的巧合结果，所以这里逐段断言单价
        BillingResult result = billingService.calculate(at(9, 0), at(11, 0));

        List<SegmentBill> segments = result.getSegments();
        assertEquals(2, segments.size(), "应先夜场、后日场");

        SegmentBill night = segments.get(0);
        assertEquals(BillingPeriod.NIGHT, night.getPeriod());
        assertEquals(60, night.getMinutes());
        assertEquals(2, night.getUnits());
        assertMoney("3.5", night.getUnitPrice(), "前一段是夜场，单价应为 3.5 元");

        SegmentBill day = segments.get(1);
        assertEquals(BillingPeriod.DAY, day.getPeriod());
        assertEquals(60, day.getMinutes());
        assertEquals(2, day.getUnits());
        assertMoney("4", day.getUnitPrice(), "后一段是日场，单价应为 4 元");

        assertAmount("15", result);
    }

    // ------------------------------------------------------------------
    // 月度累计消费优惠
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未达门槛：当月累计 199 元，整单仍按原价")
    void belowThreshold_noDiscount() {
        BillingResult result = billingService.calculate(at(10, 0), at(11, 0), BELOW_THRESHOLD);

        assertAmount("8", result);
        assertFalse(result.isDiscounted(), "未达门槛不应走优惠价");
        assertDiscount("0", result);
        assertMoney("199", result.getMonthSpentBefore(), "结算前累计额应原样留痕");
    }

    @Test
    @DisplayName("恰好 200 元：达到门槛即享优惠（判定方向是 >=）")
    void exactlyAtThreshold_discounts() {
        BillingResult result = billingService.calculate(at(10, 0), at(11, 0), AT_THRESHOLD);

        // 2 档 × 3.5 = 7 元，比原价 8 元省 1 元
        assertAmount("7", result);
        assertTrue(result.isDiscounted());
        assertDiscount("1", result);
    }

    @Test
    @DisplayName("门槛比较与小数标度无关：传入 200.00 同样触发优惠")
    void thresholdComparison_isScaleInsensitive() {
        // 数据库里 payable_amount 是 DECIMAL(10,2)，求和结果形如 200.00（标度 2），
        // 而配置里写的是 new BigDecimal("200")（标度 0）。
        // 若拿 equals 比较，标度不同会判为不相等 —— 恰好消费满 200 元的老用户
        // 就会莫名其妙拿不到优惠，且肉眼极难发现。这里把 compareTo 的语义钉死
        BillingResult result = billingService.calculate(
                at(10, 0), at(11, 0), new BigDecimal("200.00"));

        assertAmount("7", result);
        assertTrue(result.isDiscounted(), "200.00 与 200 必须视为同一门槛");
    }

    @Test
    @DisplayName("超过门槛：整单按优惠价，段单价记为 3.5 元")
    void aboveThreshold_usesDiscountedUnitPrice() {
        BillingResult result = billingService.calculate(at(10, 0), at(11, 0), ABOVE_THRESHOLD);

        assertAmount("7", result);
        assertTrue(result.isDiscounted());
        SegmentBill segment = result.getSegments().get(0);
        assertMoney("3.5", segment.getUnitPrice(), "优惠单的日场段单价应为 3.5 元");
        assertEquals(2, segment.getUnits(), "档数不因优惠而改变");
    }

    @Test
    @DisplayName("当月累计为 null：按原价处理，不抛异常")
    void nullMonthSpent_noDiscount() {
        BillingResult result = billingService.calculate(at(10, 0), at(11, 0), null);

        assertAmount("8", result);
        assertFalse(result.isDiscounted());
        assertDiscount("0", result);
        assertMoney("0", result.getMonthSpentBefore(), "null 应回填为 0，便于日志与展示");
    }

    @Test
    @DisplayName("不传累计额的两参数重载：等价于未达标，按原价")
    void twoArgOverload_treatedAsNoDiscount() {
        BillingResult result = billingService.calculate(at(10, 0), at(11, 0));

        assertAmount("8", result);
        assertFalse(result.isDiscounted());
    }

    @Test
    @DisplayName("优惠 + 跨时段：两段都按优惠价，各自套优惠封顶，合计 12.5 元")
    void discount_appliesToAllSegments() {
        BillingResult result = billingService.calculate(at(21, 30), at(23, 30), AT_THRESHOLD);

        List<SegmentBill> segments = result.getSegments();
        assertEquals(2, segments.size());

        // 日场 30 分 → 1 档 × 3.5 = 3.5；夜场 90 分 → 3 档 × 3 = 9
        assertMoney("3.5", segments.get(0).getAmount(), "优惠日场段");
        assertMoney("9", segments.get(1).getAmount(), "优惠夜场段");
        assertAmount("12.5", result);
        // 原价 4 + 10.5 = 14.5，优惠后 12.5，省 2 元
        assertDiscount("2", result);
    }

    @Test
    @DisplayName("优惠 + 长单触顶：日场 6 小时按优惠封顶收 35 元，省 5 元")
    void discount_hitsDiscountedDayCap() {
        // 10:00–16:00 共 6 小时，可计费 360 − 5 = 355 分钟 → 12 档 × 3.5 = 42 元 → 封顶 35 元
        BillingResult result = billingService.calculate(at(10, 0), at(16, 0), AT_THRESHOLD);

        assertAmount("35", result);
        SegmentBill segment = result.getSegments().get(0);
        assertTrue(segment.isCapped());
        assertMoney("42", segment.getRawAmount(), "优惠单的原始金额按优惠单价算");
        assertMoney("35", segment.getAmount(), "优惠后封顶为 35 元");
        assertMoney("35", segment.getCapAmount(), "封顶值应随优惠下降到 35 元");
        // 原价封顶 40，优惠后 35，省 5 元
        assertDiscount("5", result);
    }

    @Test
    @DisplayName("优惠 + 夜场长单触顶：夜场 5 小时 20 分按优惠封顶收 30 元，省 5 元")
    void discount_hitsDiscountedNightCap() {
        BillingResult result = billingService.calculate(
                at(22, 0), at(3, 20).plusDays(1), AT_THRESHOLD);

        assertAmount("30", result);
        SegmentBill segment = result.getSegments().get(0);
        assertTrue(segment.isCapped());
        assertMoney("3", segment.getUnitPrice(), "优惠单的夜场段单价应为 3 元");
        assertMoney("33", segment.getRawAmount(), "11 档 × 3 元 = 33 元");
        // 原价封顶 35，优惠后 30，省 5 元
        assertDiscount("5", result);
    }

    @Test
    @DisplayName("优惠 + 免费单：金额为 0 时优惠额也是 0，不会算出负优惠")
    void discount_onFreeOrder_discountIsZero() {
        // 只玩 5 分钟，落在宽限内
        BillingResult result = billingService.calculate(at(10, 0), at(10, 5), ABOVE_THRESHOLD);

        assertAmount("0", result);
        assertTrue(result.isDiscounted(), "已达标，本单标记为优惠单");
        assertDiscount("0", result);
    }

    @Test
    @DisplayName("优惠只降单价与封顶，不改变档数")
    void discount_doesNotChangeUnits() {
        BillingResult original = billingService.calculate(at(10, 0), at(12, 0), BELOW_THRESHOLD);
        BillingResult discounted = billingService.calculate(at(10, 0), at(12, 0), AT_THRESHOLD);

        assertEquals(original.getSegments().get(0).getUnits(),
                discounted.getSegments().get(0).getUnits());
        // 原价 4 档 × 4 = 16，优惠 4 档 × 3.5 = 14
        assertAmount("16", original);
        assertAmount("14", discounted);
        assertDiscount("2", discounted);
    }

    // ------------------------------------------------------------------
    // 汇总字段与异常
    // ------------------------------------------------------------------

    @Test
    @DisplayName("总时长与起止时间正确回填")
    void resultCarriesDurationAndRange() {
        BillingResult result = billingService.calculate(at(10, 0), at(11, 30));

        assertEquals(90, result.getTotalMinutes());
        assertEquals(at(10, 0), result.getStartTime());
        assertEquals(at(11, 30), result.getEndTime());
    }

    @Test
    @DisplayName("零时长：结果为 0 元且无分段")
    void zeroDuration_yieldsZero() {
        BillingResult result = billingService.calculate(at(10, 0), at(10, 0));

        assertEquals(0, result.getTotalMinutes());
        assertTrue(result.getSegments().isEmpty());
        assertEquals(ZERO, BigDecimal.ZERO.compareTo(result.getTotalAmount()));
    }

    @Test
    @DisplayName("结束时间早于开始时间：抛出异常")
    void endBeforeStart_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> billingService.calculate(at(11, 0), at(10, 0)));
    }

    @Test
    @DisplayName("时间为空：抛出异常")
    void nullTime_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> billingService.calculate(null, at(10, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> billingService.calculate(at(10, 0), null));
    }
}
