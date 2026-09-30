package com.kaede.uspace.billing;

import com.kaede.uspace.billing.dto.BillingResult;
import com.kaede.uspace.billing.dto.SegmentBill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 月卡段级免费的计费测试。
 *
 * <p><b>纯单测，不启动 Spring。</b>被测对象直接 {@code new} 出来，
 * 规则参数取 {@link BillingProperties} 的默认值（即线上配置的那一套：
 * 日 4 / 夜 3.5 元每半小时、宽限 5 分钟、封顶 40/35、门槛 200 元、
 * 优惠价 3.5/3 元且封顶 35/30）。
 *
 * <p>这一层有两处「写错不报错、只会静默算错」的地方，各有一条用例专门守着：
 * <ol>
 *   <li><b>算「本单省了多少」时那次按原价重算漏传覆盖范围</b> ——
 *       被月卡免掉的段会算出一份并不存在的原价，差额全部落进
 *       {@code discountAmount}。于是一笔「月卡免了 18.5 元」的单
 *       被记成「月度优惠省了 18.5 元」，不报任何错，
 *       统计报表还会把它当成优惠活动的效果</li>
 *   <li><b>免单额若用「原价总额 − 实收」反推</b> —— 包场时段已被订单层
 *       从计费区间里剪掉、根本不产生分段，反推会把包场免掉的钱一并算进月卡账上。
 *       本类不做这件事（段级累加），用例钉住的是它的结果形态</li>
 * </ol>
 *
 * <p>其余用例钉住两种优惠的边界：<b>月度优惠是整单粒度、月卡是段粒度</b>，
 * 两者并行存在、互不重叠；未覆盖的段照常计费且照常享月度优惠价；
 * 免费段仍保留档数与封顶信息（否则账单页说不出「这段为什么 0 元」）。
 */
class BillingServiceCardCoverageTests {

    /** 未达门槛的当月累计额，用于隔离出「只有月卡生效」的场景 */
    private static final BigDecimal BELOW_THRESHOLD = new BigDecimal("199");

    /** 已达门槛的当月累计额，用于让月度优惠与月卡同时生效 */
    private static final BigDecimal ABOVE_THRESHOLD = new BigDecimal("500");

    /** 被测服务。用默认配置，与线上 application.properties 的取值一致 */
    private final BillingService billingService = new BillingService(new BillingProperties());

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

    /** 测试基准日期，与 {@link #at} 用的是同一天 */
    private static final LocalDate DATE = LocalDate.of(2026, 9, 16);

    /**
     * 一张覆盖整个测试区间的全天卡（9/14 ~ 9/18）。
     *
     * <p>有效期取得比测试区间宽 —— 这些用例验的是<b>时段维度</b>，
     * 日期维度另有一组用例专门守着。范围刚好卡在边界上会让它们
     * 被有效期切分线意外截断，变成一条看不出所以然的断言失败。
     *
     * @return 全天卡覆盖范围
     */
    private static CardCoverage allDayCard() {
        return CardCoverage.of(CardScope.ALL, DATE.minusDays(2), DATE.plusDays(2));
    }

    /**
     * 一张覆盖整个测试区间的夜间卡（9/14 ~ 9/18）。
     *
     * @return 夜间卡覆盖范围
     */
    private static CardCoverage nightCard() {
        return CardCoverage.of(CardScope.NIGHT, DATE.minusDays(2), DATE.plusDays(2));
    }

    /**
     * 构造一张在 {@link #DATE} 当天失效的全天卡。
     *
     * @return 卡的有效期是 DATE 前 29 天到 DATE 当天，共 30 天（含首尾）
     */
    private static CardCoverage allDayCardEndingToday() {
        return CardCoverage.of(CardScope.ALL, DATE.minusDays(29), DATE);
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
     * 断言月卡免单额（标度无关比较）。
     *
     * @param expected 期望金额（元）
     * @param actual   计费结果
     * @param message  断言说明
     */
    private static void assertCardFree(String expected, BillingResult actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.getCardFreeAmount()),
                message + "：期望免 " + expected + " 元，实际免 " + actual.getCardFreeAmount() + " 元");
    }

    /**
     * 断言月度优惠金额（标度无关比较）。
     *
     * @param expected 期望金额（元）
     * @param actual   计费结果
     * @param message  断言说明
     */
    private static void assertDiscount(String expected, BillingResult actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.getDiscountAmount()),
                message + "：期望省 " + expected + " 元，实际省 " + actual.getDiscountAmount() + " 元");
    }

    // ==================================================================
    // 全天卡
    // ==================================================================

    @Test
    @DisplayName("全天卡：日夜两段都免费，合计 0 元")
    void allDayCard_freesEverySegment() {
        BillingResult bill = billingService.calculate(
                at(21, 0), at(23, 30), BELOW_THRESHOLD, allDayCard());

        assertEquals(2, bill.getSegments().size(), "21:00–23:30 应被切成日场与夜场两段");
        assertAmount("0", bill, "全天卡覆盖所有段");
        assertCardFree("18.5", bill, "免掉的是不持卡时应付的金额：日场 8 + 夜场 10.5");
    }

    @Test
    @DisplayName("全天卡：免费段的档数、单价与封顶前金额照常保留")
    void allDayCard_keepsSegmentDetail() {
        BillingResult bill = billingService.calculate(
                at(21, 0), at(23, 30), BELOW_THRESHOLD, allDayCard());

        SegmentBill day = bill.getSegments().get(0);
        assertEquals(BillingPeriod.DAY, day.getPeriod());
        assertTrue(day.isFreeByCard(), "日场段应标记为月卡免费");
        assertEquals(0, BigDecimal.ZERO.compareTo(day.getAmount()), "免费段的实收为 0");

        // 这几个中间结果正是账单页解释「这段为什么免费」的依据，
        // 一起归零的话，界面上就只剩一个没有来由的 0 元
        assertEquals(2, day.getUnits(), "日场 1 小时 = 2 档");
        assertEquals(0, new BigDecimal("4").compareTo(day.getUnitPrice()), "单价仍是日场原价");
        assertEquals(0, new BigDecimal("8").compareTo(day.getRawAmount()), "封顶前金额仍算得出来");
        assertEquals(0, new BigDecimal("40").compareTo(day.getCapAmount()), "封顶值仍记下来");
        assertEquals(0, new BigDecimal("8").compareTo(day.getCardFreeAmount()), "本段免掉 8 元");

        SegmentBill night = bill.getSegments().get(1);
        assertEquals(BillingPeriod.NIGHT, night.getPeriod());
        assertTrue(night.isFreeByCard(), "夜场段同样免费");
        assertEquals(3, night.getUnits(), "夜场 1.5 小时 = 3 档");
        assertEquals(0, new BigDecimal("10.5").compareTo(night.getCardFreeAmount()), "本段免掉 10.5 元");
    }

    @Test
    @DisplayName("全天卡 + 已享月度优惠：免单额按优惠价计，且不污染 discountAmount")
    void allDayCard_withMonthlyDiscount_doesNotPolluteDiscountAmount() {
        BillingResult bill = billingService.calculate(
                at(21, 0), at(23, 30), ABOVE_THRESHOLD, allDayCard());

        assertTrue(bill.isDiscounted(), "当月累计已过门槛，本单走优惠价");
        assertAmount("0", bill, "全天卡覆盖所有段");
        assertCardFree("16", bill, "免的是优惠价口径的钱：日场 7 + 夜场 9，而非原价的 18.5");

        // ⚠️ 守门用例：算 discountAmount 时按原价重算那一步若漏传 CardCoverage，
        // 免费段会算出一份并不存在的原价，这里就会变成 18.5 —— 而金额、状态、
        // 页面全都正常，只有统计报表里的「优惠活动效果」悄悄虚高
        assertDiscount("0", bill, "月度优惠与月卡互不重叠，被月卡免掉的段不算月度优惠");
    }

    @Test
    @DisplayName("全天卡：宽限内出场时两段金额本就是 0，免单额也为 0")
    void allDayCard_withinGrace_freeAmountIsZero() {
        BillingResult bill = billingService.calculate(
                at(12, 0), at(12, 3), BELOW_THRESHOLD, allDayCard());

        assertAmount("0", bill, "3 分钟落在免费宽限内");
        assertCardFree("0", bill, "本来就不用付钱，月卡没免掉任何东西");
        assertTrue(bill.getSegments().get(0).isFreeByCard(),
                "但仍标记为月卡覆盖 —— 前端据此显示「月卡已覆盖」，与「免宽限」是两句话");
    }

    // ==================================================================
    // 夜间卡
    // ==================================================================

    @Test
    @DisplayName("夜间卡：草案原例 —— 21:00 进场 23:30 离场，日场段照收 8 元、夜场段免费")
    void nightCard_matchesDesignDocExample() {
        BillingResult bill = billingService.calculate(
                at(21, 0), at(23, 30), BELOW_THRESHOLD, nightCard());

        assertAmount("8", bill, "与 docs/月卡设计草案.md 第四节的例子逐字一致");
        assertCardFree("10.5", bill, "只有夜场段被覆盖");

        assertFalse(bill.getSegments().get(0).isFreeByCard(), "日场段不在夜间卡的覆盖范围内");
        assertTrue(bill.getSegments().get(1).isFreeByCard(), "夜场段被覆盖");
    }

    @Test
    @DisplayName("夜间卡：整单都在日场时，一个段也不免")
    void nightCard_pureDayOrder_freesNothing() {
        BillingResult bill = billingService.calculate(
                at(14, 0), at(16, 0), BELOW_THRESHOLD, nightCard());

        assertAmount("16", bill, "14:00–16:00 共 2 小时，4 档 × 4 元");
        assertCardFree("0", bill, "夜间卡对日场没有任何减免");
        assertFalse(bill.getSegments().get(0).isFreeByCard());
    }

    @Test
    @DisplayName("夜间卡 + 已享月度优惠：日场段照常走优惠价，覆盖段按优惠价免")
    void nightCard_withMonthlyDiscount_daySegmentKeepsDiscount() {
        BillingResult bill = billingService.calculate(
                at(21, 0), at(23, 30), ABOVE_THRESHOLD, nightCard());

        // 段级叠加（2026-09-29 拍板）：未覆盖的段是正常付费消费，照常享月度优惠价。
        // 若改成「有段被免就整单不打折」，会出现「多玩半小时反而更贵」的价格悬崖 ——
        // 20:00–21:30 全在日场收 7 元，21:00–23:30 进了夜场却收 8 元，运营解释不通
        assertAmount("7", bill, "日场段按优惠价 3.5 元 × 2 档 = 7 元，夜场段免费");
        assertCardFree("9", bill, "夜场段免掉的是优惠价口径的 3 档 × 3 元");
        assertDiscount("1", bill, "只有日场段享了月度优惠：原价 8 元 − 优惠价 7 元");
    }

    // ==================================================================
    // 边界
    // ==================================================================

    @Test
    @DisplayName("免费仍可能触发封顶：capped 按封顶前金额判定")
    void freeSegment_cappedFlagStillReflectsRawAmount() {
        // 日场 5 小时 6 分 → 可计费 301 分钟 → 11 档 × 4 元 = 44 元 > 封顶 40 元
        BillingResult bill = billingService.calculate(
                at(10, 0), at(15, 6), BELOW_THRESHOLD, allDayCard());

        SegmentBill day = bill.getSegments().get(0);
        assertTrue(day.isCapped(), "原始金额确实超过了封顶，与是否免费无关");
        assertEquals(0, new BigDecimal("44").compareTo(day.getRawAmount()), "封顶前 44 元");
        assertEquals(0, new BigDecimal("40").compareTo(day.getCardFreeAmount()),
                "免掉的是封顶后的 40 元，不是封顶前的 44 元");
        assertAmount("0", bill, "无卡时本该收 40 元，被全天卡免掉");
    }

    @Test
    @DisplayName("不传覆盖范围与三参重载完全等价")
    void nullCoverage_equalsThreeArgOverload() {
        BillingResult withNull = billingService.calculate(
                at(21, 0), at(23, 30), ABOVE_THRESHOLD, null);
        BillingResult threeArg = billingService.calculate(at(21, 0), at(23, 30), ABOVE_THRESHOLD);

        assertEquals(threeArg.getTotalAmount(), withNull.getTotalAmount(), "合计一致");
        assertEquals(threeArg.getDiscountAmount(), withNull.getDiscountAmount(), "优惠额一致");
        assertEquals(threeArg.getTotalMinutes(), withNull.getTotalMinutes(), "时长一致");
        assertEquals(threeArg.isDiscounted(), withNull.isDiscounted(), "优惠判定一致");
        assertEquals(threeArg.getSegments().size(), withNull.getSegments().size(), "分段一致");
        assertCardFree("0", withNull, "无卡时免单额为 0");
        assertFalse(withNull.getSegments().get(0).isFreeByCard(), "无卡时没有任何段被覆盖");
    }

    @Test
    @DisplayName("夜间卡的覆盖只认夜场时段，与订单长度无关")
    void nightCard_coversOnlyNightPeriod() {
        // 14:00 → 次日 02:00：日场 8 小时 + 夜场 4 小时，跨度够长
        BillingResult bill = billingService.calculate(
                at(14, 0), LocalDateTime.of(2026, 9, 17, 2, 0), BELOW_THRESHOLD,
                nightCard());

        for (SegmentBill segment : bill.getSegments()) {
            boolean isNight = segment.getPeriod() == BillingPeriod.NIGHT;
            assertEquals(isNight, segment.isFreeByCard(),
                    segment.getPeriod().getLabel() + "段的免费标记应当与时段一致");
        }
    }

    // ==================================================================
    // 日期维度（2026-09-30 加）
    // ==================================================================

    @Test
    @DisplayName("卡最后一天跨零点：零点前免费、零点后照常计费")
    void cardExpiringAtMidnight_onlyFreeBeforeMidnight() {
        // 改之前这一单是「按订单开始日期整单判定」，DATE 那天还剩 1 小时，
        // 于是整单都免 —— 等于多送一个多小时。现在按卡的有效区间逐段覆盖
        BillingResult bill = billingService.calculate(
                at(23, 0), LocalDateTime.of(2026, 9, 17, 1, 0), BELOW_THRESHOLD,
                allDayCardEndingToday());

        assertEquals(2, bill.getSegments().size(), "卡的失效时刻是一条切分线，这一单必须切成两段");

        SegmentBill before = bill.getSegments().get(0);
        assertEquals(LocalDateTime.of(2026, 9, 17, 0, 0), before.getEndTime(),
                "前一段恰好收到零点");
        assertTrue(before.isFreeByCard(), "零点之前仍在卡的有效期内");
        assertEquals(0, BigDecimal.ZERO.compareTo(before.getAmount()), "这一段实收 0");

        SegmentBill after = bill.getSegments().get(1);
        assertFalse(after.isFreeByCard(), "零点起卡已失效，照常计费");
        assertEquals(0, new BigDecimal("7").compareTo(after.getAmount()),
                "夜场 1 小时 = 2 档 × 3.5 元");

        assertAmount("7", bill, "整单只收卡失效之后的那 7 元");
        assertCardFree("7", bill, "零点前那 1 小时本该收 7 元，被月卡免掉");
    }

    @Test
    @DisplayName("卡生效日凌晨进店：生效前那段收费、生效后那段免费")
    void cardStartingToday_chargesBeforeEffectiveTime() {
        // 另一头同样要切。只处理失效那一头的话，生效前那段会白玩 ——
        // 而运营看到的只是「这个用户少收了钱」，没有任何报错
        CardCoverage card = CardCoverage.of(CardScope.ALL, DATE, DATE.plusDays(29));
        BillingResult bill = billingService.calculate(
                LocalDateTime.of(2026, 9, 15, 23, 0),
                LocalDateTime.of(2026, 9, 16, 1, 0), BELOW_THRESHOLD, card);

        assertEquals(2, bill.getSegments().size(), "卡的生效时刻同样是一条切分线");
        assertFalse(bill.getSegments().get(0).isFreeByCard(), "卡生效之前那段照常收费");
        assertTrue(bill.getSegments().get(1).isFreeByCard(), "生效之后那段免费");
        assertAmount("7", bill, "只收生效前那 1 小时：夜场 2 档 × 3.5 元");
        assertCardFree("7", bill, "免掉的是生效之后那 1 小时");
    }

    @Test
    @DisplayName("无卡时跨零点的订单不被零点切开")
    void withoutCard_midnightDoesNotSplit() {
        // 「按日期切段」只在有卡时才做。无卡时切了没有任何意义，
        // 却会让每段各享一次宽限，把金额悄悄算少 ——
        // 这正是本次改动最容易带出来的副作用
        BillingResult bill = billingService.calculate(
                at(23, 0), LocalDateTime.of(2026, 9, 17, 1, 0), BELOW_THRESHOLD, null);

        assertEquals(1, bill.getSegments().size(), "整单都在夜场，应当只有一段");
        assertAmount("14", bill, "2 小时 = 4 档 × 3.5 元");
    }

    @Test
    @DisplayName("卡覆盖整单时不多切段：段数与无卡时一致")
    void cardCoveringWholeOrder_doesNotAddSegments() {
        BillingResult withCard = billingService.calculate(
                at(21, 0), at(23, 30), BELOW_THRESHOLD, allDayCard());
        BillingResult withoutCard = billingService.calculate(
                at(21, 0), at(23, 30), BELOW_THRESHOLD, null);

        assertEquals(withoutCard.getSegments().size(), withCard.getSegments().size(),
                "卡的有效期边界不在订单区间内，就一段都不该多切");
    }

}
