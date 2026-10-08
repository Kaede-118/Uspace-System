package com.kaede.uspace.order.event;

import java.time.LocalDateTime;

/**
 * 一场已付款的包场被撤销并退款（模块 8 发布，模块 11 的 QQ 群播报监听）。
 *
 * <h3>为什么这个类住在 order 包，而包场生效的事件住在 space 包</h3>
 *
 * <p>规矩是「事件住在发布方所在的包」。包场生效（{@code BookingActivatedEvent}）
 * 有<b>两个</b>发布点（{@code space} 的 0 元建单、{@code order} 的付款成功），
 * 只能住 space；而<b>撤销只有一个发布点</b> —— {@code BookingRefundService#revoke}
 * （钱的事归 order），所以这个类住 order 包。
 *
 * <h3>为什么要播</h3>
 *
 * <p>包场生效时群里已经播过一条「该时段仅限包场人与被邀请者入场」。
 * 撤销（管理员线下协商好转场、或顾客退了款）之后那条消息就<b>过期了</b>——
 * 而群消息不会自己消失，看到旧消息的人仍会以为那个时段进不去。
 * 补一条撤销播报，是让群里的信息自己闭合。
 *
 * <p>⚠️ {@code BookingMapper#revertRefund}（原路退回失败时的状态回滚）
 * <b>刻意不播</b>：那条路径意味着撤销<b>没有成功</b>，包场仍是已付款状态，
 * 群里那条「已安排包场」依然有效 —— 为一次失败的撤销发一条「已撤销」是假消息。
 *
 * <p><b>不带金额</b>：退了多少属于经营信息，与其他播报同一条披露边界。
 * <b>不带包场人</b>：与包场生效的播报一致（用户端的包场时间表里也没有包场人）。
 *
 * @param bookingId 包场 ID
 * @param bookingNo 包场单号。<b>只为日志</b>：两个模块的日志里 grep 同一个单号，
 *                  就能回答「这条播报为什么没发出去」
 * @param startAt   包场开始时刻
 * @param endAt     包场结束时刻
 */
public record BookingRevokedEvent(
        Long bookingId,
        String bookingNo,
        LocalDateTime startAt,
        LocalDateTime endAt) {
}
