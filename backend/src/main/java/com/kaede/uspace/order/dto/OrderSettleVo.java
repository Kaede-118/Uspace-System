package com.kaede.uspace.order.dto;

import com.kaede.uspace.billing.dto.BillingResult;
import com.kaede.uspace.order.OrderStatus;
import com.kaede.uspace.order.entity.Order;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 结算结果 —— 结束使用或人工调整后返回的账单。
 *
 * <p><b>直接持有计费服务算出的 {@link BillingResult}</b>，而不是把它拆成几个金额字段。
 * 这样前端账单页、订单详情页、将来的 QQ 播报都能复用同一份分段数据，
 * 各自不必重新拆分时段 —— 计费结果的展示结构在模块 7 已经定了：
 * 每段给出「时长」「单价」「档数」与「该段价格」，最后再给合计。
 */
@Data
public class OrderSettleVo {

    /** 订单 ID */
    private Long orderId;

    /** 订单号 */
    private String orderNo;

    /** 计费起点 = 点击开门的时刻 */
    private LocalDateTime startTime;

    /** 离场时刻 */
    private LocalDateTime endTime;

    /**
     * 实际参与计费的起点。
     *
     * <p>通常等于 {@link #startTime}；<b>但若这段时间里用户有已付款的包场，
     * 包场时段不计费，本字段会晚于 {@code startTime}</b>（即包场结束的时刻）。
     *
     * <p>把它返回给前端，是为了让前端<b>不必自己推算</b> ——
     * 剪切规则若在前端也实现一份，两边一旦漂移就会展示出与实际收费不符的账单，
     * 而且不会报错。
     */
    private LocalDateTime billingStart;

    /**
     * 本次是否因包场而减免了时长。
     *
     * <p>为 true 时，账单页应当说明「包场时段不计费」，否则用户会疑惑
     * 「我明明待了 5 小时，怎么只算了 1 小时的钱」。
     *
     * <p><b>由「是否命中包场」直接给出，不是从 {@link #billingStart} 推出来的</b>：
     * 包场人提前到店时，计费区间是「包场前 + 包场后」两段，第一段仍从开门时刻起算，
     * 于是 {@code billingStart} 等于 {@code startTime} —— 靠它推断会得出
     * 「没有减免」的错误结论，而实际上一整段包场时段都被免掉了。
     */
    private boolean freeByBooking;

    /** 结算后的订单状态名 */
    private String status;

    /** 状态中文说明 */
    private String statusText;

    /**
     * 分段账单。
     *
     * <p>包含每段的时段名（日场/夜场）、时长、单价、封顶、档数、封顶前金额、
     * 该段金额与是否触发封顶，以及整单合计、是否按优惠价、本单优惠金额。
     * 明细字段见 {@link BillingResult} 与 {@code SegmentBill}。
     */
    private BillingResult bill;

    /**
     * 由实体与账单构造视图。
     *
     * <p>调用方需先把重算后的字段写回 {@code order} 对象再调本方法 ——
     * 它读的是内存里的值，不会再去查库。
     *
     * @param order         订单实体（{@code endTime} / {@code status} 应已是重算后的值）
     * @param billingStart  实际计费起点
     * @param freeByBooking 本次是否命中包场而减免了时长
     * @param bill          分段账单
     * @return 结算结果视图
     */
    public static OrderSettleVo of(Order order, LocalDateTime billingStart,
                                   boolean freeByBooking, BillingResult bill) {
        OrderSettleVo vo = new OrderSettleVo();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setStartTime(order.getStartTime());
        vo.setEndTime(order.getEndTime());
        vo.setBillingStart(billingStart);
        vo.setFreeByBooking(freeByBooking);
        vo.setStatus(order.getStatus());
        vo.setStatusText(OrderStatus.labelOf(order.getStatus()));
        vo.setBill(bill);
        return vo;
    }
}
