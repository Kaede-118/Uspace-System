package com.kaede.uspace.order.event;

/**
 * 付款凭证被管理员驳回（模块 8 发布，模块 11 的 QQ 群提醒监听）。
 *
 * <p><b>为什么需要这条事件</b>：订单与商品是「先交付后复核」——
 * 用户一提交凭证，订单当场转已支付；管理员事后驳回，只改了凭证的复核结论，
 * <b>订单状态刻意不回退</b>（回退要连带退库存、减累计消费、恢复欠费拦截，
 * 三件事每一件都得人判断）。于是「管理员不认这笔钱」这个结论
 * <b>没有回到用户那里的任何通路</b>：用户看到的仍是「已支付」，
 * 群里也一片安静 —— <b>复核环节等于白设</b>。
 *
 * <p>这条事件就是那条通路。它与 {@link OrderEnteredEvent} / {@link OrderLeftEvent}
 * 同源：类住在 {@code order} 包（放进 {@code qqbot} 会让 {@code order}
 * 反向 import 它，单向依赖就断了），由 {@code qqbot} 监听。
 *
 * <p><b>刻意不带金额</b>：群消息对所有群成员可见，而「谁花了多少钱」
 * 按播报的群分级纪律只进店主群（见 {@code QqbotProperties#isAmountVisible}）。
 * 这条提醒的受众是<b>被驳回的那个人</b>，他不缺自己花了多少的信息，
 * 所以两处都不带 —— 需要金额的场合由他自己点进网页端看。
 *
 * @param proofId    凭证 ID，只为日志
 * @param userId     提交人用户 ID —— 监听器靠它查 {@code sys_user.qq} 来 @ 人
 * @param orderNo    商户订单号，只为日志（群消息里不带：那串顾客认不出，且太长）
 * @param targetType 收款类型（ORDER / BOOKING / MONTHLY_CARD / PRODUCT）。
 *                   四种收款目前的措辞相同，带上是为了日志里分得清是哪一类
 * @param reason     驳回原因，<b>必填</b>（与差异处理的 {@code handle_note} 选填相对）——
 *                   它是管理员写给顾客看的话，也正是这条提醒的正文
 */
public record PaymentProofRejectedEvent(
        Long proofId,
        Long userId,
        String orderNo,
        String targetType,
        String reason) {
}
