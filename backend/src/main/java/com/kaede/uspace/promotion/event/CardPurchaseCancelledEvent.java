package com.kaede.uspace.promotion.event;

import com.kaede.uspace.common.trade.TradeSource;

import java.math.BigDecimal;

/**
 * 一笔<b>未付款</b>的月卡购买单被取消（模块 优惠管理 → 模块 8 的流水）。
 *
 * <p>它在 {@code MonthlyCardService#cancelPurchase} 里发布，唯一的听众是
 * {@code order} 包的 {@code TradeLogListener}。事件类住在发布方的包、
 * 而不是 {@code order/event/}，理由见 {@code ProductOrderCancelledEvent}
 * 的类注释（依赖方向：{@code order → promotion} 单向）。
 *
 * <p>⚠️ 月卡还有一处<b>超时自动关闭</b>（下次购买时顺手关掉过期的待支付单），
 * 那<b>不发本事件</b>：它不是谁做出的决定，而是系统在清理过期数据 ——
 * 流水记的是「发生过什么决定」，把自动清理也记进去只会让这本账变吵。
 *
 * @param orderNo 月卡购买单号
 * @param userId  单子的主人
 * @param amount  单子原本的卡费（元），可为空
 * @param source  来源渠道：网页端还是群内
 */
public record CardPurchaseCancelledEvent(String orderNo, Long userId,
                                         BigDecimal amount, TradeSource source) {
}
