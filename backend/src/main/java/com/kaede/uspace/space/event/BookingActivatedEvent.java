package com.kaede.uspace.space.event;

import java.time.LocalDateTime;

/**
 * 一场包场生效（模块 3 发布，模块 11 的 QQ 群播报监听）。
 *
 * <h3>什么叫「生效」</h3>
 *
 * <p>包场从「安排」变成「事实」的那一刻：状态转 {@code PAID}。
 * 未付款的包场（{@code PENDING_PAYMENT}）只是占着时段，<b>不产生排他性、
 * 也不该播报</b> —— 它随时可能被取消，群里提前说一句「某时段有包场」
 * 就成了空头安排。0 元场次是唯一的例外：它在建单那一刻就是终态 {@code PAID}，
 * 因此「排期」与「生效」是同一时刻，也照样播报。
 *
 * <h3>为什么这个类住在 space 包，而不是 order 包或 qqbot 包</h3>
 *
 * <p>本事件有<b>两个发布点</b>，分居两个包：
 * <ul>
 *   <li>{@code BookingService#createBooking}（0 元场次即刻生效）—— {@code space} 包</li>
 *   <li>{@code BookingPaymentTargetHandler#markPaid}（付款成功）—— {@code order} 包</li>
 * </ul>
 * {@code space → order} 是<b>禁止方向</b>（钱的事归 order），所以事件不可能住
 * {@code order/event}；而 {@code order → space} 允许，因此住 {@code space}
 * 一个类就能被两处共用，不必为同一件事写两个事件、两个监听器。
 * {@code qqbot} 照例反向 import 监听。
 *
 * <h3>为什么只带这几个字段</h3>
 *
 * <p><b>不带金额</b>：群消息对所有群成员可见，而「这场包场收了多少钱」
 * 属于经营信息，只该进店主群 —— 那是 {@code QqbotProperties#isAmountVisible}
 * 的管辖范围。播报的受众是「想知道某时段能不能来」的群友，他们不需要金额。
 *
 * <p><b>不带包场人</b>：与 {@code BookingScheduleVo} 同一条披露边界 ——
 * 那个 VO 里压根没有 {@code hostUserId} 字段，群里点名「谁包了场」
 * 不是本系统的设计意图。
 *
 * <p><b>不带状态</b>：发布点上它恒为 {@code PAID}，带一个恒量字段只会
 * 诱使监听器去判断它（理由同 {@code OrderEnteredEvent}）。
 *
 * <h3>⚠️ 包场转 PAID 共三条 SQL，第三条刻意不播</h3>
 *
 * <p>{@code BookingMapper#revertRefund}（线上退款失败时把 {@code REFUNDED}
 * 翻回 {@code PAID}）<b>不发布本事件</b>：那场包场在付款的那一刻已经播过一次，
 * 再播一条「已安排包场」就是同一件事说两遍 —— 而群里最容易被察觉的正是假消息。
 * 将来若在别处新增「包场变 PAID」的写入路径，先想清楚它该不该播。
 *
 * @param bookingId 包场 ID
 * @param bookingNo 包场单号。<b>只为日志</b>：两个模块的日志里 grep 同一个单号，
 *                  就能回答「这条播报为什么没发出去」
 * @param startAt   包场开始时刻（含）
 * @param endAt     包场结束时刻（不含）
 */
public record BookingActivatedEvent(
        Long bookingId,
        String bookingNo,
        LocalDateTime startAt,
        LocalDateTime endAt) {
}
