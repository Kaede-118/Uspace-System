package com.kaede.uspace.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 本月累计消费与优惠资格。
 *
 * <p>给用户看「我这个月消费了多少、还差多少能享优惠价」——
 * 门槛是 200 元这种规则，用户不主动查是猜不到的，而它直接决定下一单的价格。
 *
 * <p><b>口径与结算完全一致</b>：只统计本月<b>已支付</b>订单的实付额、
 * 按订单的开始时间归集、不含月卡卡费 —— 这三条与
 * {@code OrderMapper#selectMonthPaidAmount} 的 SQL 是同一套。
 * 判定也直接调计费服务的方法，而不是在这里再写一遍比较 ——
 * 两处实现分岔的表现是「页面说已达标、结算却没优惠」，且不报任何错。
 *
 * <p>注意它<b>不含在店未结账的那一单</b>：那笔还没支付，按规则不算「消费」。
 */
@Data
public class MonthSpentVo {

    /** 统计月份的第一天，让前端知道这个数是从哪天算起的 */
    private LocalDate monthStart;

    /** 本月已支付订单的实付额之和（元） */
    private BigDecimal monthSpent;

    /** 触发优惠价的门槛（元） */
    private BigDecimal threshold;

    /** 本月后续订单是否已按优惠价计费 */
    private boolean discounted;

    /** 还差多少达到门槛（元）。已达标时为 0，不会是负数 */
    private BigDecimal remaining;
}
