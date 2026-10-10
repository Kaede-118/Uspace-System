package com.kaede.uspace.space.event;

import com.kaede.uspace.common.trade.TradeSource;

import java.math.BigDecimal;

/**
 * 一场<b>未付款</b>的包场被取消（模块 空间管理 → 模块 8 的流水）。
 *
 * <p>两个发布点，都在 {@code BookingService} 里：
 * <ul>
 *   <li>{@link BookingService#cancelBooking(Long, TradeSource)} —— 管理员在后台取消</li>
 *   <li>{@link BookingService#cancelOwnBooking(Long, Long, TradeSource)} ——
 *       包场人在群里发 {@code fw取消 <单号>} 取消自己的场子</li>
 * </ul>
 * 唯一的听众是 {@code order} 包的 {@code TradeLogListener}。
 *
 * <p>⚠️ <b>已付款包场的「撤销 + 退款」刻意不发本事件</b>：那条路走的是
 * {@code BookingRefundService#revoke}，钱发生了真实的进出，
 * 与「单子没付钱就被关掉」是两回事。它的痕迹在包场自己的退款四列里，
 * 另有 {@code BookingRevokedEvent} 负责群播报。
 *
 * <p>事件类住在发布方的包，理由与 {@code order/event/} 那几组相同 ——
 * 见 {@code ProductOrderCancelledEvent} 的类注释。
 *
 * @param bookingNo  包场单号
 * @param hostUserId 包场人（未付款的场次没有参与者名单，认的只有他）
 * @param amount     包场费（元），可为空
 * @param source     来源渠道：管理后台还是群内
 * @param operatorId 操作人：管理员在后台取消时是他的 ID；包场人在群里取消自己的
 *                   场子时传 null（那一行的 user 就是他自己）。可空
 */
public record BookingCancelledEvent(String bookingNo, Long hostUserId,
                                    BigDecimal amount, TradeSource source, Long operatorId) {
}
