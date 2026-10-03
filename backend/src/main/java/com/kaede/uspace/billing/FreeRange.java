package com.kaede.uspace.billing;

import java.time.LocalDateTime;

/**
 * 一段免费区间（活动免费），半开区间 {@code [from, to)}。
 *
 * <p>「免费时段」是运营者排的活动（如跨年 20:00 – 次日 02:00 全场免费）。
 * 它与 {@link CardCoverage}（月卡）在计费侧走<b>同一条路</b>：段照常算档数、
 * 单价、封顶，只把实收置 0 —— 账单因此说得清「这段为什么免费、省了多少」。
 * 这与包场不同（包场是把区间整段剪掉、不产生分段，见 OrderService 的
 * {@code billableRanges}）。
 *
 * <p>⚠️ <b>半开区间的口径与停业、包场严格一致</b>（{@code [start, end)}）：
 * 两场活动首尾相接（18:00–20:00 与 20:00–22:00）不算重叠，
 * 20:00 整这一瞬间归后一场。
 *
 * <p>⚠️ <b>刻意不叫 FreePeriod</b>：那个名字留给数据库实体（{@code billing.entity.FreePeriod}），
 * 这个是由实体转换来的<b>值对象</b> —— 计费侧不认识「谁在什么时候排的这场活动」，
 * 它只需要「哪些时刻之间不收钱」。与 {@code CardCoverage} 的定位一致。
 *
 * @param from 区间起点（含）
 * @param to   区间终点（不含）
 */
public record FreeRange(LocalDateTime from, LocalDateTime to) {

    /** 紧凑构造器：计费侧的所有判定都假设区间有效，坏数据必须在这里挡住 */
    public FreeRange {
        if (from == null || to == null) {
            throw new IllegalArgumentException("免费区间的起止时刻不能为空");
        }
        if (!to.isAfter(from)) {
            // 零长度区间会让切段逻辑切出时长为 0 的段、游标原地不动
            throw new IllegalArgumentException(
                    "免费区间的结束时刻必须晚于开始时刻：from=" + from + ", to=" + to);
        }
    }

    /**
     * 某个时刻是否落在本区间内。
     *
     * <p>半开：含起点、不含终点 —— 判「段是否整段免费」时传的是<b>段的起点</b>，
     * 而段已经按本区间的边界切过（见 {@code BillingService.splitByPeriod}），
     * 所以起点在内即整段都在。
     *
     * @param at 待判断的时刻
     * @return 落在区间内返回 true
     */
    public boolean covers(LocalDateTime at) {
        return at != null && !at.isBefore(from) && at.isBefore(to);
    }

    /** 展示用（日志与调试），如「10-05 20:00 ~ 10-06 02:00」。 */
    public String describe() {
        return from.toLocalDate() + " " + from.toLocalTime()
                + " ~ " + to.toLocalDate() + " " + to.toLocalTime();
    }
}
