package com.kaede.uspace.billing;

import com.kaede.uspace.billing.dto.BillingResult;
import com.kaede.uspace.billing.dto.NextChange;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 半场累计封顶的计费测试（2026-10-10 的规则）。
 *
 * <p><b>规则一句话</b>：同一个半场（日场 10:00-22:00、夜场 22:00-次日 10:00）
 * 里，实收合计不超过一个封顶价 —— 被包场剪开的几截、被活动或月卡切开的几段、
 * 乃至<b>同一半场内的其它订单</b>（用户玩一段结算再开新单），都共享同一份额度。
 * 跨半场不累计：日场收满 40 之后，接下来夜场重新从 0 起算。
 *
 * <p><b>它修掉的具体毛病</b>：在此之前每段各自套封顶 ——
 * 10:00-22:00 无活动时整段封顶 40 元，被活动切出一段后反而能收 56 元，
 * 「办了活动，顾客付得更多」。本类的第一条用例就是钉这个场景。
 *
 * <p><b>三处写错不会报错、只会静默算错的地方</b>，各有用例守着：
 * <ol>
 *   <li><b>「按原价重算」与原价线共用额度</b> —— 重算会读到优惠价已经用掉的
 *       额度，封顶差额落进 {@code discountAmount}，被记成「月度优惠省了多少」</li>
 *   <li><b>免费段消耗额度</b> —— 月卡 / 活动免掉的段若照样占额度，
 *       等于替用户少免了后面的钱（他自己的免费时长压住了自己的额度）</li>
 *   <li><b>跨零点夜场被拆成两个半场</b> —— 用日期而不是「半场起点」做键的话，
 *       10/8 深夜与 10/9 凌晨各有一份额度，封顶能被收两遍</li>
 * </ol>
 *
 * <p>纯单测，不启动 Spring；配置取 {@link BillingProperties} 的默认值
 * （与线上一致：日 4 / 夜 3.5 元每半小时、宽限 5 分钟、封顶 40/35）。
 */
class BillingServiceHalfPeriodTests {

    /** 被测服务。用默认配置，与线上 application.properties 的取值一致 */
    private final BillingService billingService = new BillingService(new BillingProperties());

    /** 未达门槛的当月累计额，用于隔离出「只有半场封顶生效」的场景 */
    private static final BigDecimal BELOW_THRESHOLD = new BigDecimal("199");

    /** 已达门槛的当月累计额，用于让月度优惠与半场封顶同时生效 */
    private static final BigDecimal ABOVE_THRESHOLD = new BigDecimal("500");

    /** 测试基准日期（月中，避开月初月末的干扰） */
    private static final LocalDate DATE = LocalDate.of(2026, 9, 16);

    /**
     * 构造 {@link #DATE} 当天的某个时刻。
     *
     * @param hour   小时
     * @param minute 分钟
     * @return 时刻
     */
    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(DATE, LocalTime.of(hour, minute));
    }

    /**
     * 构造 {@link #DATE} 次日的某个时刻（夜场跨零点用）。
     *
     * @param hour   小时
     * @param minute 分钟
     * @return 次日时刻
     */
    private static LocalDateTime nextDayAt(int hour, int minute) {
        return LocalDateTime.of(DATE.plusDays(1), LocalTime.of(hour, minute));
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
     * 标度无关地断言一笔金额。
     *
     * @param expected 期望金额（元）
     * @param actual   实际金额
     * @param message  断言说明
     */
    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                message + "：期望 " + expected + " 元，实际 " + actual + " 元");
    }

    // ==================================================================
    // 「活动切段反而更贵」—— 本次规则变化修的正是它
    // ==================================================================

    @Test
    @DisplayName("⚠️ 核心：日场整段封顶 40，被活动切出一段后仍收 40（修掉「有活动反而更贵」）")
    void activitySplit_stillCappedAtHalfPeriodCap() {
        // 无活动：12 小时整段 → 24 档 × 4 = 96 → 封顶 40
        BillingResult plain = billingService.calculate(at(10, 0), at(22, 0), BELOW_THRESHOLD);
        assertMoney("40", plain.getTotalAmount(), "无活动时整段封顶");

        // 有活动（12:00-13:00）：切出三段，段级各自封顶的话会收 16 + 40 = 56
        BillingResult split = billingService.calculate(at(10, 0), at(22, 0), BELOW_THRESHOLD,
                null, List.of(range(12, 0, 13, 0)));
        assertMoney("40", split.getTotalAmount(),
                "⚠️ 同半场累计封顶：有活动不该比没活动更贵 —— 这是本次规则变化的起因");

        // 第三段（13:00-22:00）被半场额度压低：原始 72 → 段级封顶 40 → 剩余额度 24
        SegmentBill third = split.getSegments().get(2);
        assertMoney("16", third.getHalfPeriodUsedBefore(), "前一段已收 16 元占掉额度");
        assertMoney("16", third.getHalfPeriodCutAmount(),
                "该段段级封顶后本可收 40，因半场只剩 24 元额度，再少收 16 元");
    }

    @Test
    @DisplayName("免费段不消耗额度：活动免掉的时长不占额度，后面照常收到封顶为止")
    void freeSegmentDoesNotConsumeQuota() {
        // 10:00-22:00，活动 12:00-14:00（2 小时免费）
        BillingResult bill = billingService.calculate(at(10, 0), at(22, 0), BELOW_THRESHOLD,
                null, List.of(range(12, 0, 14, 0)));

        // 段1 10-12：4 档 × 4 = 16（占额度）
        // 段2 12-14：活动免 0（不占额度——若占了，段3 只剩 8 元额度）
        // 段3 14-22：16 档 × 4 = 64 → 剩余额度 40-16 = 24 → 24
        assertMoney("40", bill.getTotalAmount(), "合计仍到封顶价");
        SegmentBill third = bill.getSegments().get(2);
        assertMoney("24", third.getAmount(),
                "⚠️ 免费段若消耗额度，这里会只剩 8 —— 等于他自己的免费时长压住了自己的额度");
        assertMoney("0", bill.getSegments().get(1).getAmount(), "活动段实收为 0");
    }

    // ==================================================================
    // 跨半场不累计
    // ==================================================================

    @Test
    @DisplayName("跨半场不累计：日场收满 40 后，夜场重新从 0 起算")
    void halfPeriodsDoNotShareQuotaAcrossPeriods() {
        HalfPeriodUsage usage = new HalfPeriodUsage();

        // 日场 10:00-22:00 整段：收满 40
        BillingResult day = billingService.calculate(at(10, 0), at(22, 0), BELOW_THRESHOLD,
                null, null, usage);
        assertMoney("40", day.getTotalAmount(), "日场收满封顶");

        // 接着夜场 22:00-次日 00:00：2 小时 → 4 档 × 3.5 = 14，不该被日场的额度压住
        BillingResult night = billingService.calculate(at(22, 0), nextDayAt(0, 0),
                BELOW_THRESHOLD, null, null, usage);
        assertMoney("14", night.getTotalAmount(),
                "夜场是另一个半场，额度重新起算 —— 跨半场一起累计的话这里会收 0");
    }

    // ==================================================================
    // 跨区间（包场剪开）共享额度
    // ==================================================================

    @Test
    @DisplayName("包场剪开的两截共享额度：先到的先占，后一截只收剩余")
    void splitRanges_shareQuota() {
        HalfPeriodUsage usage = new HalfPeriodUsage();

        // 区间1 10:00-14:00（4 小时 → 8 档 × 4 = 32）
        BillingResult first = billingService.calculate(at(10, 0), at(14, 0), BELOW_THRESHOLD,
                null, null, usage);
        assertMoney("32", first.getTotalAmount(), "第一截按其原价收");

        // 区间2 18:00-22:00（4 小时，原始 32，但只剩 8 元额度）
        BillingResult second = billingService.calculate(at(18, 0), at(22, 0), BELOW_THRESHOLD,
                null, null, usage);
        assertMoney("8", second.getTotalAmount(),
                "⚠️ 逐截各自新建累计器的话，这里会收 32 —— 同一个半场的封顶被收了两遍");

        SegmentBill segment = second.getSegments().get(0);
        assertMoney("32", segment.getHalfPeriodUsedBefore(), "账单要能解释这 8 元是怎么来的");
        assertMoney("24", segment.getHalfPeriodCutAmount(), "因半场累计少收 24 元");
    }

    @Test
    @DisplayName("一个累计器里的不同半场互不干扰：日场用掉的额度不影响夜场")
    void usageKeepsHalfPeriodsSeparate() {
        HalfPeriodUsage usage = new HalfPeriodUsage();

        // 日场收 32（用掉日场额度）
        billingService.calculate(at(10, 0), at(14, 0), BELOW_THRESHOLD, null, null, usage);

        // 夜场 22:00-次日 02:00（4 小时 → 8 档 × 3.5 = 28），不受日场额度影响
        BillingResult night = billingService.calculate(at(22, 0), nextDayAt(2, 0),
                BELOW_THRESHOLD, null, null, usage);
        assertMoney("28", night.getTotalAmount(), "夜场额度是独立的一份");
    }

    // ==================================================================
    // 「按原价重算」的两条线互不污染
    // ==================================================================

    @Test
    @DisplayName("⚠️ 优惠用户：按原价重算走另一条额度线，封顶差额不会被记成月度优惠")
    void discountedRecompute_usesSeparateQuotaLine() {
        // 日场 10:00-22:00 + 活动 12:00-13:00 切段，优惠用户（已达门槛）
        BillingResult bill = billingService.calculate(at(10, 0), at(22, 0), ABOVE_THRESHOLD,
                null, List.of(range(12, 0, 13, 0)));

        // 优惠价线：段1 4 档 × 3.5 = 14；段3 18 档 × 3.5 = 63 → 剩余额度 35-14 = 21
        //   合计 = 14 + 21 = 35（优惠封顶）
        assertMoney("35", bill.getTotalAmount(), "优惠封顶按半场累计");

        // 原价线（独立累计）：段1 16；段3 72 → 剩余 40-16 = 24 → 24
        //   合计 40；discountAmount = 40 - 35 = 5
        assertMoney("5", bill.getDiscountAmount(),
                "⚠️ 两条线共用额度的话，原价重算会读到优惠价用掉的额度，"
                        + "封顶差额被记成「月度优惠」，本单优惠金额会虚高");

        // 两条线各自算出的半场已收也要对：优惠线首段之前为 0
        assertMoney("0", bill.getSegments().get(0).getHalfPeriodUsedBefore(),
                "首段之前没有已收");
    }

    // ==================================================================
    // 跳档预告看剩余额度
    // ==================================================================

    @Test
    @DisplayName("预告：剩余额度不足时，「下一档」只报到剩余额度为止")
    void nextChange_capsAtRemainingQuota() {
        // 本段之前同半场已收 36 → 只剩 4 元额度
        BigDecimal usedBefore = new BigDecimal("36");

        // 第 2 分钟（宽限内，本段还是 0 元）：下一档收 1 档 × 4 = 4，恰好等于剩余额度
        NextChange change = billingService.nextChange(at(10, 0), at(10, 2),
                null, null, null, usedBefore);
        assertNotNull(change);
        assertEquals("进入下一档 ¥4.00", change.getText());

        // 第 6 分钟（已吃满剩余额度）：不再有「下一档」，直接报已到封顶
        NextChange capped = billingService.nextChange(at(10, 0), at(10, 6),
                null, null, null, usedBefore);
        assertNotNull(capped);
        assertEquals("当前已到封顶价", capped.getText());
        assertNull(capped.getInSeconds(), "到封顶后不给倒计时");
    }

    @Test
    @DisplayName("预告：半场额度已被历史收满时，立刻报「已到封顶价」")
    void nextChange_fullyUsedQuota() {
        NextChange change = billingService.nextChange(at(10, 0), at(10, 2),
                null, null, null, new BigDecimal("40"));

        assertNotNull(change);
        assertEquals("当前已到封顶价", change.getText(),
                "额度为 0 时，连宽限期过后那 4 元都收不到 —— 预告不能还报「进入下一档」");
    }

    @Test
    @DisplayName("预告：不传额度（null）视同 0，与旧行为一致")
    void nextChange_nullQuotaMeansZero() {
        NextChange change = billingService.nextChange(at(10, 0), at(10, 2), null, null, null);

        assertNotNull(change);
        assertEquals("进入下一档 ¥4.00", change.getText());
    }

    // ==================================================================
    // 已到封顶的判定（按半场分组求和）
    // ==================================================================

    @Test
    @DisplayName("封顶判定：同半场两段合计到封顶即为已封顶（旧口径会漏报）")
    void allHalfPeriodsCapped_trueWhenSumReachesCap() {
        assertTrue(billingService.allHalfPeriodsCapped(List.of(
                        segment(BillingPeriod.DAY, at(10, 0), "32", "40"),
                        segment(BillingPeriod.DAY, at(18, 0), "8", "40"))),
                "⚠️ 单看某一段（32 < 40）会漏报 —— 这个半场其实已经收满");
    }

    @Test
    @DisplayName("封顶判定：跨半场时每个半场都要到顶")
    void allHalfPeriodsCapped_requiresEveryHalfPeriod() {
        assertFalse(billingService.allHalfPeriodsCapped(List.of(
                        segment(BillingPeriod.DAY, at(10, 0), "40", "40"),
                        segment(BillingPeriod.NIGHT, at(22, 0), "21", "35"))),
                "夜场还没到顶，整张账单就还没到顶");
    }

    @Test
    @DisplayName("封顶判定：没有计费段时为 false（整段被包场覆盖）")
    void allHalfPeriodsCapped_falseWhenEmpty() {
        assertFalse(billingService.allHalfPeriodsCapped(List.of()),
                "零账单不是「已封顶」—— 包场结束后照样会重新计费");
    }

    @Test
    @DisplayName("封顶判定：实收恰好等于封顶算已封顶（与「超顶」不是一回事）")
    void allHalfPeriodsCapped_trueAtExactCap() {
        assertTrue(billingService.allHalfPeriodsCapped(List.of(
                        segment(BillingPeriod.DAY, at(10, 0), "40", "40"))),
                "第 10 档金额恰好等于封顶、段上的 capped 是 false，但那之后不会再涨");
    }

    // ==================================================================
    // 历史预置（跨订单累计的入口）与半场边界的求法
    // ==================================================================

    @Test
    @DisplayName("预置：历史段按半场归位，跨零点的夜场段落在同一个键上")
    void seedUsage_groupsByHalfPeriod() {
        HalfPeriodUsage usage = new HalfPeriodUsage();
        billingService.seedUsage(usage, List.of(
                segment(BillingPeriod.DAY, at(10, 0), "32", "40"),
                segment(BillingPeriod.NIGHT, at(22, 30), "10.5", "35"),
                // 次日凌晨 1 点 —— 仍属于 10/8 那一场夜场
                segment(BillingPeriod.NIGHT, nextDayAt(1, 0), "7", "35"),
                // 金额为 0 的段（被免费活动免掉的）不占额度
                segment(BillingPeriod.DAY, at(15, 0), "0", "40")));

        assertMoney("32", usage.usedIn(at(10, 0), true), "日场半场的已收");
        assertMoney("17.5", usage.usedIn(at(22, 0), true),
                "⚠️ 深夜与凌晨是同一场夜场，用日期做键会拆成两份额度、封顶被收两遍");
        assertMoney("0", usage.usedIn(at(10, 0).plusDays(1), true),
                "次日日场是新的半场，还没有已收");
        assertMoney("32", usage.usedIn(at(10, 0), false), "原价线与实收线同时预置");
    }

    @Test
    @DisplayName("半场边界：日场 [10:00, 22:00)，夜场 [22:00, 次日 10:00)")
    void halfPeriodBounds() {
        assertEquals(at(10, 0), billingService.halfPeriodStartAt(at(10, 0)));
        assertEquals(at(22, 0), billingService.halfPeriodEndAt(at(10, 0)));
        assertEquals(at(10, 0), billingService.halfPeriodStartAt(at(21, 59)));

        // 22:00 整：属于夜场（半开区间，边界时刻归后一段）
        assertEquals(at(22, 0), billingService.halfPeriodStartAt(at(22, 0)));
        assertEquals(nextDayAt(10, 0), billingService.halfPeriodEndAt(at(22, 0)));

        // 凌晨：仍属于前一天的夜场
        assertEquals(at(22, 0), billingService.halfPeriodStartAt(nextDayAt(1, 0)));
        assertEquals(nextDayAt(10, 0), billingService.halfPeriodEndAt(nextDayAt(9, 59)));

        // 次日 10:00：新一天的日场
        assertEquals(nextDayAt(10, 0), billingService.halfPeriodStartAt(nextDayAt(10, 0)));
    }

    /**
     * 造一个只填了半场归属与金额的计费段，供预置与封顶判定用。
     *
     * @param period 所属时段
     * @param start  段起点
     * @param amount 实收金额（元）
     * @param cap    该段封顶金额（元）
     * @return 计费段
     */
    private static SegmentBill segment(BillingPeriod period, LocalDateTime start,
                                       String amount, String cap) {
        SegmentBill segment = new SegmentBill();
        segment.setPeriod(period);
        segment.setStartTime(start);
        segment.setAmount(new BigDecimal(amount));
        segment.setCapAmount(new BigDecimal(cap));
        return segment;
    }
}
