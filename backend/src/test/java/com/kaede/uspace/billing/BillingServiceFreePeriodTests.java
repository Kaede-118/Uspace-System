package com.kaede.uspace.billing;

import com.kaede.uspace.billing.dto.BillingResult;
import com.kaede.uspace.billing.dto.SegmentBill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 免费活动（免费时段）的计费测试。
 *
 * <p><b>纯单测，不启动 Spring。</b>被测对象直接 {@code new} 出来，
 * 规则参数取 {@link BillingProperties} 的默认值（即线上配置的那一套：
 * 日 4 / 夜 3.5 元每半小时、宽限 5 分钟、封顶 40/35、门槛 200 元、
 * 优惠价 3.5/3 元且封顶 35/30）。
 *
 * <p><b>免费活动走的是月卡那条路（段级置零），不是包场那条（剪区间）</b>：
 * 段照常产生、档数单价封顶照常算，只把实收置 0，并记下「这场活动省了多少」。
 * 办活动就是要让人看见省了多少，剪掉区间的话账单上什么都看不出来。
 *
 * <p>三处「写错不报错、只会静默算错」的地方，各有用例守着：
 * <ol>
 *   <li><b>算「本单省了多少」时那次按原价重算漏传 freeRanges</b> ——
 *       被活动免掉的段会算出一份并不存在的原价，差额全部落进
 *       {@code discountAmount}，统计报表把这笔钱当成「月度优惠的效果」。
 *       与月卡那条同源，见 {@link BillingServiceCardCoverageTests}</li>
 *   <li><b>活动与月卡不互斥</b> —— 同一段被两者都记一次的话，
 *       复盘一场活动「送出去多少钱」会虚高：月卡用户本来就免费，
 *       活动并没有为他省下什么。判定里那句 {@code !freeByCard} 就是为它写的</li>
 *   <li><b>活动边界无条件切段</b> —— 边界不落在订单区间内也切的话，
 *       每段各享一次宽限，金额被悄悄算少（与跨日夜场那条已知副作用同源）。
 *       无活动、活动覆盖整单、活动完全在区间外，三种情形都一段都不该多切</li>
 * </ol>
 */
class BillingServiceFreePeriodTests {

    /** 未达门槛的当月累计额，用于隔离出「只有活动生效」的场景 */
    private static final BigDecimal BELOW_THRESHOLD = new BigDecimal("199");

    /** 已达门槛的当月累计额，用于让月度优惠与活动同时生效 */
    private static final BigDecimal ABOVE_THRESHOLD = new BigDecimal("500");

    /** 被测服务。用默认配置，与线上 application.properties 的取值一致 */
    private final BillingService billingService = new BillingService(new BillingProperties());

    /** 测试基准日期（月中，避开月初月末的干扰） */
    private static final LocalDate DATE = LocalDate.of(2026, 9, 16);

    /**
     * 构造一个固定日期的时间点，避免测试结果随运行时刻变化。
     *
     * @param hour   小时
     * @param minute 分钟
     * @return {@link #DATE} 当天的该时刻
     */
    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(DATE, LocalTime.of(hour, minute));
    }

    /**
     * 构造 {@link #DATE} 当天的活动区间。
     *
     * @param fromHour   开始小时
     * @param fromMinute 开始分钟
     * @param toHour     结束小时
     * @param toMinute   结束分钟
     * @return 免费区间
     */
    private static FreeRange range(int fromHour, int fromMinute, int toHour, int toMinute) {
        return new FreeRange(at(fromHour, fromMinute), at(toHour, toMinute));
    }

    /**
     * 一张覆盖整个测试区间的全天卡（9/14 ~ 9/18），用于验证活动与月卡的互斥。
     *
     * @return 全天卡覆盖范围
     */
    private static CardCoverage allDayCard() {
        return CardCoverage.of(CardScope.ALL, DATE.minusDays(2), DATE.plusDays(2));
    }

    /**
     * 断言实收总额（标度无关比较）。
     *
     * @param expected 期望金额（元）
     * @param actual   计费结果
     * @param message  断言说明
     */
    private static void assertAmount(String expected, BillingResult actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.getTotalAmount()),
                message + "：期望 " + expected + " 元，实际 " + actual.getTotalAmount() + " 元");
    }

    /**
     * 断言活动免单额（标度无关比较）。
     *
     * @param expected 期望金额（元）
     * @param actual   计费结果
     * @param message  断言说明
     */
    private static void assertActivityFree(String expected, BillingResult actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.getActivityFreeAmount()),
                message + "：期望免 " + expected + " 元，实际免 " + actual.getActivityFreeAmount() + " 元");
    }

    // ==================================================================
    // 覆盖整单 / 部分覆盖
    // ==================================================================

    @Test
    @DisplayName("活动覆盖整单：金额置 0，免单额是原本要收的钱")
    void activityCoveringWholeOrder_zeroesAmount() {
        BillingResult bill = billingService.calculate(at(14, 0), at(16, 0), BELOW_THRESHOLD, null,
                List.of(range(13, 0, 17, 0)));

        assertEquals(1, bill.getSegments().size(), "活动边界不在订单区间内，一段都不该多切");
        assertAmount("0", bill, "14:00–16:00 全在活动内");
        assertActivityFree("16", bill, "120 分钟 = 4 档 × 4 元，全部由活动免掉");

        SegmentBill day = bill.getSegments().get(0);
        assertTrue(day.isFreeByActivity(), "日场段应标记为活动免费");
    }

    @Test
    @DisplayName("活动免费段：档数、单价与封顶前金额照常保留")
    void activityFreeSegment_keepsSegmentDetail() {
        BillingResult bill = billingService.calculate(at(14, 0), at(16, 0), BELOW_THRESHOLD, null,
                List.of(range(13, 0, 17, 0)));

        SegmentBill day = bill.getSegments().get(0);
        assertEquals(BillingPeriod.DAY, day.getPeriod());
        assertEquals(0, BigDecimal.ZERO.compareTo(day.getAmount()), "活动覆盖的段实收为 0");

        // 这几个中间结果正是账单页解释「这段为什么免费」的依据。
        // 一起归零的话，界面上就只剩一个没有来由的 0 元
        assertEquals(4, day.getUnits(), "120 分钟 = 4 档");
        assertEquals(0, new BigDecimal("4").compareTo(day.getUnitPrice()), "单价仍是日场原价");
        assertEquals(0, new BigDecimal("16").compareTo(day.getRawAmount()), "封顶前金额仍算得出来");
        assertEquals(0, new BigDecimal("40").compareTo(day.getCapAmount()), "封顶值仍记下来");
    }

    @Test
    @DisplayName("活动只覆盖前半段：切分线把两段分开，一段免费一段照收")
    void activityCoversFirstHalf_splitsAtBoundary() {
        // 活动的结束时刻是一条切分线 —— 不切的话整单要么全免、要么全收
        BillingResult bill = billingService.calculate(at(14, 0), at(16, 0), BELOW_THRESHOLD, null,
                List.of(range(14, 0, 15, 0)));

        assertEquals(2, bill.getSegments().size(), "15:00 是活动边界，应把订单切成两段");
        assertTrue(bill.getSegments().get(0).isFreeByActivity(), "15:00 之前那段在活动内");
        assertFalse(bill.getSegments().get(1).isFreeByActivity(), "15:00 之后那段照常计费");

        assertAmount("8", bill, "只收活动结束后的那 1 小时");
        assertActivityFree("8", bill, "活动免掉的是活动期间的那 1 小时");
    }

    @Test
    @DisplayName("活动在订单中间：两头照收、中间免费，共切三段")
    void activityInTheMiddle_splitsIntoThreeSegments() {
        BillingResult bill = billingService.calculate(at(13, 0), at(17, 0), BELOW_THRESHOLD, null,
                List.of(range(14, 0, 15, 0)));

        assertEquals(3, bill.getSegments().size(), "活动的起止各是一条切分线");
        assertFalse(bill.getSegments().get(0).isFreeByActivity());
        assertTrue(bill.getSegments().get(1).isFreeByActivity());
        assertFalse(bill.getSegments().get(2).isFreeByActivity());

        // 13:00–14:00 收 8 元 + 15:00–17:00 收 16 元。
        // 注意它与「不切段」算出来的 32 元不同 —— 那是「分段各享一次宽限」的
        // 固有结果（与跨日夜场那条已知副作用同源），不是本次改动引入的
        assertAmount("24", bill, "8 + 0 + 16");
        assertActivityFree("8", bill, "只有中间那 1 小时被免");
    }

    @Test
    @DisplayName("活动与订单毫无交集：一段都不切、一分都不免")
    void activityOutsideOrder_freezesNothing() {
        // 真实链路里 FreePeriodService 已按交集筛过，这条守的是「万一传进来」——
        // BillingService 是纯计算，不该假设调用方一定筛干净
        BillingResult bill = billingService.calculate(at(22, 0), at(23, 0), BELOW_THRESHOLD, null,
                List.of(range(20, 0, 21, 0)));

        assertEquals(1, bill.getSegments().size(), "活动边界不在订单区间内，不该多切");
        assertAmount("7", bill, "夜场 1 小时 = 2 档 × 3.5 元");
        assertActivityFree("0", bill, "不在活动区间内，活动没免掉任何东西");
        assertFalse(bill.getSegments().get(0).isFreeByActivity());
    }

    // ==================================================================
    // 跨日夜场、跨零点（点名的场景：跨年活动 20:00 – 次日 02:00）
    // ==================================================================

    @Test
    @DisplayName("跨零点活动：日场夜场两段都免费，合计 0 元")
    void activityAcrossMidnight_freesBothSegments() {
        LocalDateTime start = at(20, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 17, 2, 0);

        BillingResult bill = billingService.calculate(start, end, BELOW_THRESHOLD, null,
                List.of(new FreeRange(start, end)));

        assertEquals(2, bill.getSegments().size(), "20:00–次日 02:00 应被切成日场与夜场两段");
        assertTrue(bill.getSegments().get(0).isFreeByActivity(), "日场段（20:00–22:00）在活动内");
        assertTrue(bill.getSegments().get(1).isFreeByActivity(), "夜场段（22:00–次日 02:00）同样在活动内");

        assertAmount("0", bill, "整场活动内都不计费");
        assertActivityFree("44", bill, "日场 16 元 + 夜场 28 元，全部由活动免掉");
    }

    @Test
    @DisplayName("订单完全落在活动内：跨日夜场两段都免费")
    void orderEntirelyInsideActivity_allCovered() {
        // 订单比活动短的情形：活动 20:00–次日 02:00，只玩 21:00–23:00。
        // 两头都够不到活动边界，因此除了日夜场边界外不该多切
        BillingResult bill = billingService.calculate(at(21, 0), at(23, 0), BELOW_THRESHOLD, null,
                List.of(new FreeRange(at(20, 0), LocalDateTime.of(2026, 9, 17, 2, 0))));

        assertEquals(2, bill.getSegments().size(), "只有日夜场边界切了一刀，活动边界都在订单之外");
        assertAmount("0", bill, "两段都在活动内");
        assertActivityFree("15", bill, "日场 1 小时 8 元 + 夜场 1 小时 7 元");
    }

    // ==================================================================
    // 与月卡、月度优惠的关系
    // ==================================================================

    @Test
    @DisplayName("活动与月卡重叠：只记月卡，活动免单额为 0")
    void activityOverlappingCard_onlyCardCounts() {
        BillingResult bill = billingService.calculate(at(21, 0), at(23, 30), BELOW_THRESHOLD,
                allDayCard(), List.of(range(20, 0, 23, 59)));

        assertAmount("0", bill, "两种优惠都覆盖了整单");
        assertEquals(0, new BigDecimal("18.5").compareTo(bill.getCardFreeAmount()),
                "免掉的钱记在月卡账上 —— 日场 8 + 夜场 10.5");
        assertActivityFree("0", bill,
                "月卡用户本来就免费，活动并没有为他省下什么；两个都记会让活动复盘虚高");

        for (SegmentBill segment : bill.getSegments()) {
            assertTrue(segment.isFreeByCard(), segment.getPeriod().getLabel() + "段被月卡覆盖");
            assertFalse(segment.isFreeByActivity(),
                    "被月卡覆盖的段不该同时标成活动免费 —— 两个标记只该有一个亮着");
        }
    }

    @Test
    @DisplayName("活动 + 已享月度优惠：免单额按优惠价计，且不污染 discountAmount")
    void activityWithMonthlyDiscount_doesNotPolluteDiscountAmount() {
        BillingResult bill = billingService.calculate(at(14, 0), at(16, 0), ABOVE_THRESHOLD, null,
                List.of(range(13, 0, 17, 0)));

        assertTrue(bill.isDiscounted(), "当月累计已过门槛，本单走优惠价");
        assertAmount("0", bill, "活动覆盖整单");
        assertActivityFree("14", bill, "免的是优惠价口径的钱：4 档 × 3.5 元，而非原价的 16");

        // ⚠️ 守门用例：算 discountAmount 时按原价重算那一步若漏传 freeRanges，
        // 被活动免掉的段会算出一份并不存在的原价，这里就会变成 14 ——
        // 而金额、状态、页面全都正常，只有统计报表里的「优惠活动效果」悄悄虚高
        assertEquals(0, BigDecimal.ZERO.compareTo(bill.getDiscountAmount()),
                "活动免掉的钱不是月度优惠省下的钱，两个口径不能混");
    }

    // ==================================================================
    // 边界
    // ==================================================================

    @Test
    @DisplayName("宽限内出场：本来就不用付钱，活动免单额为 0")
    void activityWithinGrace_freeAmountIsZero() {
        BillingResult bill = billingService.calculate(at(12, 0), at(12, 3), BELOW_THRESHOLD, null,
                List.of(range(11, 0, 13, 0)));

        assertAmount("0", bill, "3 分钟落在免费宽限内");
        assertActivityFree("0", bill, "本来就不用付钱，活动没免掉任何东西");
        assertTrue(bill.getSegments().get(0).isFreeByActivity(),
                "但仍标记为活动覆盖 —— 前端据此显示「活动免费」，与「免宽限」是两句话");
    }

    @Test
    @DisplayName("活动免费仍可能触发封顶：capped 按封顶前金额判定")
    void activityFreeSegment_cappedFlagStillReflectsRawAmount() {
        // 日场 5 小时 6 分 → 可计费 301 分钟 → 11 档 × 4 元 = 44 元 > 封顶 40 元
        BillingResult bill = billingService.calculate(at(10, 0), at(15, 6), BELOW_THRESHOLD, null,
                List.of(range(10, 0, 16, 0)));

        SegmentBill day = bill.getSegments().get(0);
        assertTrue(day.isCapped(), "原始金额确实超过了封顶，与是否免费无关");
        assertEquals(0, new BigDecimal("44").compareTo(day.getRawAmount()), "封顶前 44 元");
        assertEquals(0, new BigDecimal("40").compareTo(day.getActivityFreeAmount()),
                "免掉的是封顶后的 40 元，不是封顶前的 44 元");
        assertAmount("0", bill, "没有活动时本该收 40 元");
    }

    @Test
    @DisplayName("不传活动区间与传空列表完全等价")
    void nullFreeRanges_equalsEmptyList() {
        BillingResult withNull = billingService.calculate(
                at(21, 0), at(23, 30), ABOVE_THRESHOLD, null, null);
        BillingResult emptyList = billingService.calculate(
                at(21, 0), at(23, 30), ABOVE_THRESHOLD, null, List.of());

        assertEquals(emptyList.getTotalAmount(), withNull.getTotalAmount(), "合计一致");
        assertEquals(emptyList.getDiscountAmount(), withNull.getDiscountAmount(), "优惠额一致");
        assertEquals(emptyList.getActivityFreeAmount(), withNull.getActivityFreeAmount(), "活动免单额一致");
        assertEquals(emptyList.getSegments().size(), withNull.getSegments().size(), "分段一致");
        assertActivityFree("0", withNull, "无活动时免单额为 0");
        assertFalse(withNull.getSegments().get(0).isFreeByActivity());
    }
}
