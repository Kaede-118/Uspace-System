package com.kaede.uspace.order.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 我的累计时长统计。
 *
 * <p>给「我的」页展示「一共玩了多久、这个月玩了多久」，与
 * {@link MonthSpentVo}（花了多少钱）并排。
 *
 * <p><b>时长用「在店时长」而不是「计费时长」</b>：包场时段不计费，
 * 用 {@code dayMinutes + nightMinutes} 汇总的话，一场 2 小时的包场会算成
 * 「0 分钟」—— 人明明待了一下午。两个口径的差别见
 * {@code Order#getStayMinutes()}。
 *
 * <p><b>只算已支付订单</b>：在店未结账的那一单还没定局，计进去会让数字
 * 每刷新一次就往上跳一次。
 *
 * <p><b>归月口径与 {@link MonthSpentVo} 完全一致</b>（按离场时刻的自然月），
 * 两个数字在同一个页面上并排，跨月那一刻不能一个算上月、一个算本月。
 */
@Data
public class OrderStatsVo {

    /** 累计在店时长（分钟），含全部历史，只统计已支付订单 */
    private long totalMinutes;

    /** 本月在店时长（分钟），归月口径与月累计消费一致 */
    private long monthMinutes;

    /**
     * 统计月份的第一天，让前端知道 {@link #monthMinutes} 是从哪天算起的。
     *
     * <p>由后端给出而不是让前端自己算月初 —— 客户端时钟不可信，
     * 且「本月」的定义只应有一处。
     */
    private LocalDate monthStart;
}
