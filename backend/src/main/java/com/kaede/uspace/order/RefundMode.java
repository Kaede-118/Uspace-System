package com.kaede.uspace.order;

import java.util.Arrays;

/**
 * 退款方式 —— 管理员撤销一场已付款的包场时，钱怎么退回去。
 *
 * <p>两种方式对应两种现实处境，<b>不是「先做哪个后做哪个」的权宜之计</b>：
 * <ul>
 *   <li>{@link #MANUAL} <b>人工退</b> —— 管理员在微信 / 支付宝里直接转给顾客，
 *       然后在后台点一下「确认已人工退款」。适用于：顾客付的是转账截图核销的、
 *       平台退款权限还没开通的、或者干脆是熟客走线下更方便的</li>
 *   <li>{@link #ONLINE} <b>原路退回</b> —— 调支付平台的退款接口，钱沿着原来付款的
 *       那条通道退回去。这是**正经的**那条路：不用管理员先垫钱，也留得下平台侧的凭证</li>
 * </ul>
 *
 * <p>为什么两种都要有：<b>线上退款不是随时都能用的</b> ——
 * 它要求这笔包场当初是走线上通道付的（人工核销的没有平台交易号，退不了），
 * 也要求商户号已经开通了退款权限。只做线上，遇到这两种情况就没有出路；
 * 只做人工，则把「垫钱 + 手工记账」变成常态。
 *
 * <p>与 {@code PaymentChannel}（用户付款走哪条通道）不是一回事：
 * 一个是钱进来的路，一个是钱出去的路。
 */
public enum RefundMode {

    /** 人工退款：管理员线下把钱退给顾客，后台只登记这件事 */
    MANUAL,

    /** 原路退回：调支付平台的退款接口 */
    ONLINE;

    /**
     * 判断一个字符串是否为合法的退款方式。
     *
     * <p>用于校验接口入参 —— 退款方式落库是 {@code VARCHAR}，
     * 库不会替我们挡住非法值，得在动钱之前拦一道。
     *
     * @param name 待校验的取值，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(m -> m.name().equals(name));
    }
}
