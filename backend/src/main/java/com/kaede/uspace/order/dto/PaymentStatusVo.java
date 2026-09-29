package com.kaede.uspace.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付状态查询结果。
 *
 * <p>供两个场景使用：用户付完款回到页面时确认到账（此时多半已经通过回调处理完了），
 * 以及用户说「我付过了但订单还是待支付」时触发<b>主动查单补偿</b>。
 */
@Data
public class PaymentStatusVo {

    /** 商户订单号 */
    private String outTradeNo;

    /** 是否已支付（本地状态，若刚从平台补偿回来则已是最新） */
    private boolean paid;

    /** 目标当前状态名 */
    private String status;

    /** 状态中文说明 */
    private String statusText;

    /** 支付通道名，未支付时为 null */
    private String paymentMethod;

    /** 平台交易号 */
    private String paymentNo;

    /** 支付完成时刻 */
    private LocalDateTime paidAt;

    /** 应付金额（元） */
    private BigDecimal amount;

    /**
     * 给用户看的一句话。
     *
     * <p>区分「已到账」「尚未到账」「平台已扣款但本地状态更新失败」这几种情形 ——
     * 用户最需要知道的恰恰是第三种该怎么办（联系客服，而不是再付一次）。
     */
    private String message;
}
