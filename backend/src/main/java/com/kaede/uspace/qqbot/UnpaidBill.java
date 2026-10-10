package com.kaede.uspace.qqbot;

import java.math.BigDecimal;

/**
 * 「fw未付款」清单里的一行（模块 11）。
 *
 * <p>四类收款（计时订单 / 包场 / 月卡 / 商品）在库里是四张表、各有各的 Vo，
 * 而群里的回复是一份统一的清单 —— 本 record 就是那层「统一」：
 * {@code QqCommandService} 把四个来源换算成它，{@link QqReplyText} 只管排版。
 * 排版逻辑因此可以脱离 Spring 单测（与 {@code QqReplyText} 的其余方法一致）。
 *
 * <p><b>字段刻意只有五个</b>：清单只需要「这是什么、哪一笔、多少钱、
 * 现在什么状态、能不能取消」。把四类单的完整视图传进来，排版时迟早会
 * 打印出不该进群的东西（如一串门锁密码）。
 *
 * @param typeLabel  类型的中文名（计时 / 包场 / 月卡 / 商品）——
 *                   与 {@code PaymentTargetType} 的中文名同源，由组装方传入
 * @param orderNo    单号。清单里唯一能拿去做下一步动作的抓手
 *                   （付款入口找它、{@code fw取消} 也找它）
 * @param amount     金额（元）。金额开关关着时排版会省略它
 * @param statusText 状态的中文说明（待支付 / 凭证未通过等）
 * @param cancelable 能不能用 {@code fw取消} 消掉 ——
 *                   计时订单恒为 false（欠费不能自消，
 *                   见 {@link QqCommand.Kind#CANCEL_ORDER} 的说明）
 */
public record UnpaidBill(String typeLabel, String orderNo, BigDecimal amount,
                         String statusText, boolean cancelable) {
}
