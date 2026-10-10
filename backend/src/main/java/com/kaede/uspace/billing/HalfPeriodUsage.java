package com.kaede.uspace.billing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 半场累计额度的记账器（模块 7）。
 *
 * <p><b>它解决什么问题</b>：封顶价自 2026-10-10 起是<b>按半场</b>算的 ——
 * 同一个半场（日场 10:00-22:00、夜场 22:00-次日 10:00）里，
 * 无论因为包场被剪成几截、被活动或月卡切成几段，
 * 实收合计不超过<b>一个</b>封顶价。而计费是按段算的，
 * 「这段之前已经收了多少」需要一个跨段、跨调用累计的地方 —— 就是本类。
 *
 * <p><b>为什么由调用方持有、跨多次 calculate 传递</b>：一个订单的计费区间
 * 可能被包场剪成多截（{@code OrderService#billableRanges}），
 * 每截调一次 {@code BillingService#calculate}。它们属于同一半场时
 * 共享额度，所以累计器必须活在这些调用的<b>外面</b>。
 *
 * <p><b>两条线各自记账</b>：{@code discounted} 为 true / false 各一份累计。
 * 这不是可选设计 —— 月度优惠用户算「本单省了多少」时要按原价把整单重算一遍，
 * 两条线共用一份累计会让原价重算读到优惠价用掉的额度，
 * 把封顶差额记进「月度优惠」的口袋
 *（{@code BillingResult#getDiscountAmount()}）。单测里有一条专门盯住这个场景。
 *
 * <p>可变、非线程安全：一次计费在单线程里跑，用完即弃。
 */
public final class HalfPeriodUsage {

    /**
     * 累计表：{@code 是否优惠价 → 半场起点 → 已收金额}。
     *
     * <p>半场起点 = 该半场开始的时刻（日场为当天 {@code dayStart}，
     * 夜场为当天 {@code dayEnd}）—— 用它做键而不是「日期」，是因为夜场跨零点：
     * 10/8 深夜与 10/9 凌晨属于<b>同一个</b>夜场，用日期做键会拆成两个半场。
     */
    private final Map<Boolean, Map<LocalDateTime, BigDecimal>> used = new HashMap<>();

    /**
     * 取某个半场已经收掉的金额。
     *
     * @param halfPeriodStart 半场起点（该半场开始的时刻）
     * @param discounted      按哪条线取（优惠价 / 原价）
     * @return 已收金额；该半场还没收过时为 0
     */
    public BigDecimal usedIn(LocalDateTime halfPeriodStart, boolean discounted) {
        return used.getOrDefault(discounted, Map.of())
                .getOrDefault(halfPeriodStart, BigDecimal.ZERO);
    }

    /**
     * 记一笔实收。
     *
     * <p>被月卡 / 活动免掉的段记 0（调用方传 0 或不调，两者等价）——
     * 免掉的钱没有消耗额度，这一点由「记的是<b>实收</b>」天然保证。
     *
     * @param halfPeriodStart 半场起点
     * @param discounted      按哪条线记
     * @param amount          该段实收金额（元）
     */
    public void add(LocalDateTime halfPeriodStart, boolean discounted, BigDecimal amount) {
        used.computeIfAbsent(discounted, k -> new HashMap<>())
                .merge(halfPeriodStart, amount, BigDecimal::add);
    }
}
