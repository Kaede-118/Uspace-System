package com.kaede.uspace.product.event;

import com.kaede.uspace.common.trade.TradeSource;

import java.math.BigDecimal;

/**
 * 一笔<b>未付款</b>的商品购买单被取消（模块 商品包 → 模块 8 的流水）。
 *
 * <p>它在 {@code ProductService#cancelOrder} 里发布，唯一的听众是
 * {@code order} 包的 {@code TradeLogListener} —— 由它把这件事记进交易流水。
 *
 * <p><b>为什么事件类住在发布方所在的包</b>（而不是 {@code order/event/}）：
 * 事件一旦放进 order，product 就得 import order，而两个业务包之间的依赖
 * 是单向的（{@code order → product} 允许、反过来成环）。
 * 与 {@code order/event/}、{@code space/event/} 那几组事件是同一条规矩。
 *
 * <p>⚠️ <b>只有「未付款」的取消会发这个事件</b>：已支付的购买单没有取消接口
 * （{@code closePending} 带状态守卫），凭证驳回走的是另一条路
 * （{@code ProductOrderStatus.REJECTED}），两者都不该记成「未付款取消」。
 *
 * @param orderNo 购买单号（对外可读的那一串，流水靠它与业务单据对齐）
 * @param userId  单子的主人
 * @param amount  单子原本的应付额（元），可为空
 * @param source  来源渠道：网页端还是群内
 */
public record ProductOrderCancelledEvent(String orderNo, Long userId,
                                         BigDecimal amount, TradeSource source) {
}
