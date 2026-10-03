package com.kaede.uspace.order.dto;

import com.kaede.uspace.billing.dto.BillingResult;
import lombok.Data;

/**
 * 订单的分段账单快照 —— 写进 {@code biz_order.bill_snapshot} 那个 JSON 列的对象。
 *
 * <p><b>它不出现在任何接口响应里</b>，只是「存什么、读什么」的存储约定。
 * 接口上的 {@code bill} 与 {@code freeByBooking} 是平铺的两个字段
 * （见 {@link OrderSettleVo} 与 {@link OrderVo}），与结账页同构，
 * 前端才能用同一套 props 渲染账单组件。
 *
 * <p><b>为什么不直接把 {@link BillingResult} 序列化、要再包一层</b>：
 * {@code freeByBooking} 是「订单层的结论」（有没有命中包场而减免时长），
 * 不属于计费结果 —— 计费侧只负责把包场时段剪掉，它并不认识「包场」这个概念
 * （见 {@code OrderService.calculateBill} 的分工说明）。两者存一起，
 * 是因为它们同来同去：都由 {@code applySettlement} 一次算出、一次落库。
 */
@Data
public class OrderBillSnapshot {

    /** 结算那一刻算出的完整账单（含每段的档数、单价、封顶与免单标记） */
    private BillingResult bill;

    /** 本次是否因命中包场而减免了计费时长。展示时用它解释「计费时长 < 在店时长」 */
    private boolean freeByBooking;

    /**
     * 由账单与包场事实构造快照。
     *
     * @param bill          结算算出的账单
     * @param freeByBooking 是否命中包场而减免了计费时长
     * @return 快照对象
     */
    public static OrderBillSnapshot of(BillingResult bill, boolean freeByBooking) {
        OrderBillSnapshot snapshot = new OrderBillSnapshot();
        snapshot.setBill(bill);
        snapshot.setFreeByBooking(freeByBooking);
        return snapshot;
    }
}
