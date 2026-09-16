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
 * <p>计费规则里有多处「边界容易差一份」的地方（10 分钟免费线、40/41 分钟的进位线、
 * 整点是否恰好 8 元/小时、两个封顶点、跨时段的切分），这里逐条钉死。
 * 这些用例同时也是计费准确性的验证材料。
 */
@SpringBootTest
class BillingServiceTests {

    /** 金额比较时的标度无关比较用 compareTo，这里统一用 0 判断 */
    private static final int ZERO = 0;

    @Autowired
    private BillingService billingService;

    /**
     * 构造一个固定日期的时间点，避免测试结果随运行时刻变化。
     *
     * @param hour   小时
     * @param minute 分钟
     * @return 2026-09-16 当天的该时刻
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

    // ------------------------------------------------------------------
    // 免费线与档位边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("10 分钟以内：免费出场，不收费用")
    void withinTenMinutes_isFree() {
        assertAmount("0", billingService.calculate(at(10, 0), at(10, 10)));
        assertAmount("0", billingService.calculate(at(10, 0), at(10, 5)));
    }

    @Test
    @DisplayName("11 分钟：进入第一档，收 4 元")
    void elevenMinutes_chargesFirstUnit() {
        assertAmount("4", billingService.calculate(at(10, 0), at(10, 11)));
    }

    @Test
    @DisplayName("40 分钟：余数恰为 10 分钟，按舍弃处理，仍收 4 元")
    void fortyMinutes_remainderDiscarded() {
        assertAmount("4", billingService.calculate(at(10, 0), at(10, 40)));
    }

    @Test
    @DisplayName("41 分钟：超过 10 分钟进位，收 8 元")
    void fortyOneMinutes_chargesTwoUnits() {
        assertAmount("8", billingService.calculate(at(10, 0), at(10, 41)));
    }

    // ------------------------------------------------------------------
    // 整点金额 —— 验证宽限确实让整点严格为 8 元/小时
    // ------------------------------------------------------------------

    @Test
    @DisplayName("恰好 1 小时：8 元")
    void exactlyOneHour_isEightYuan() {
        assertAmount("8", billingService.calculate(at(10, 0), at(11, 0)));
    }

    @Test
    @DisplayName("恰好 2 小时：16 元")
    void exactlyTwoHours_isSixteenYuan() {
        assertAmount("16", billingService.calculate(at(10, 0), at(12, 0)));
    }

    @Test
    @DisplayName("恰好 3 小时：24 元")
    void exactlyThreeHours_isTwentyFourYuan() {
        assertAmount("24", billingService.calculate(at(10, 0), at(13, 0)));
    }

    // ------------------------------------------------------------------
    // 封顶
    // ------------------------------------------------------------------

    @Test
    @DisplayName("日场超长：触及 40 元封顶，标记 capped")
    void daySession_hitsCap() {
        // 10:00 起 5 小时 20 分，原始金额 11 档 × 4 = 44 元
        BillingResult result = billingService.calculate(at(10, 0), at(15, 20));

        assertAmount("40", result);
        assertEquals(1, result.getSegments().size());
        SegmentBill segment = result.getSegments().get(0);
        assertEquals(BillingPeriod.DAY, segment.getPeriod());
        assertTrue(segment.isCapped(), "应标记为已封顶");
        assertEquals(0, new BigDecimal("40").compareTo(segment.getAmount()));
        assertEquals(0, new BigDecimal("44").compareTo(segment.getRawAmount()),
                "原始金额应如实保留，便于追溯");
    }

    @Test
    @DisplayName("夜场超长：触及 30 元封顶，标记 capped")
    void nightSession_hitsCap() {
        // 22:00 起 4 小时，跨到次日 02:00，原始金额 8 档 × 4 = 32 元
        BillingResult result = billingService.calculate(at(22, 0), at(2, 0).plusDays(1));

        assertAmount("30", result);
        assertEquals(1, result.getSegments().size());
        SegmentBill segment = result.getSegments().get(0);
        assertEquals(BillingPeriod.NIGHT, segment.getPeriod());
        assertTrue(segment.isCapped());
        assertEquals(0, new BigDecimal("32").compareTo(segment.getRawAmount()));
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
    @DisplayName("跨时段：21:30–23:30 切成日场 4 元 + 夜场 12 元 = 16 元")
    void crossPeriod_splitsIntoSegments() {
        BillingResult result = billingService.calculate(at(21, 30), at(23, 30));

        List<SegmentBill> segments = result.getSegments();
        assertEquals(2, segments.size(), "应切成日场与夜场两段");

        SegmentBill day = segments.get(0);
        assertEquals(BillingPeriod.DAY, day.getPeriod());
        assertEquals(30, day.getMinutes());
        assertEquals(1, day.getUnits());
        assertEquals(0, new BigDecimal("4").compareTo(day.getAmount()));

        SegmentBill night = segments.get(1);
        assertEquals(BillingPeriod.NIGHT, night.getPeriod());
        assertEquals(90, night.getMinutes());
        assertEquals(3, night.getUnits());
        assertEquals(0, new BigDecimal("12").compareTo(night.getAmount()));

        assertAmount("16", result);
    }

    @Test
    @DisplayName("跨时段短单：两段各自落在免费档，合计 0 元（已知规则副作用）")
    void crossPeriod_shortSession_isFree() {
        // 21:50–22:10 共 20 分钟，切成 10 分 + 10 分，两段都在宽限之内
        BillingResult result = billingService.calculate(at(21, 50), at(22, 10));

        assertEquals(2, result.getSegments().size());
        assertAmount("0", result);
    }

    @Test
    @DisplayName("恰好从时段边界开始：不产生 0 分钟的空段")
    void startingExactlyAtBoundary_producesNoEmptySegment() {
        BillingResult result = billingService.calculate(at(22, 0), at(23, 0));

        assertEquals(1, result.getSegments().size());
        assertEquals(BillingPeriod.NIGHT, result.getSegments().get(0).getPeriod());
    }

    @Test
    @DisplayName("跨零点：深夜到次日上午，正确切成两段")
    void acrossMidnight_splitsCorrectly() {
        // 23:00 -> 次日 10:30：夜场 23:00–10:00，日场 10:00–10:30
        BillingResult result = billingService.calculate(at(23, 0), at(10, 30).plusDays(1));

        List<SegmentBill> segments = result.getSegments();
        assertEquals(2, segments.size());
        assertEquals(BillingPeriod.NIGHT, segments.get(0).getPeriod());
        assertEquals(660, segments.get(0).getMinutes(), "23:00 到次日 10:00 共 11 小时");
        assertEquals(BillingPeriod.DAY, segments.get(1).getPeriod());
        assertEquals(30, segments.get(1).getMinutes());

        // 夜场段 660 分钟原始 22 档 = 88 元，封顶 30 元；日场段 30 分钟 = 1 档 4 元
        assertAmount("34", result);
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
