package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.PaymentChannel;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 一次退款请求。
 *
 * <p>字段照着微信 {@code POST /v3/refund/domestic/refunds} 与支付宝
 * {@code alipay.trade.refund} 的入参取：
 * <ul>
 *   <li><b>原商户订单号</b>（不是平台交易号）—— 两家都优先认这个</li>
 *   <li><b>商户退款单号</b> —— 我们自己生成，<b>平台按它做幂等</b>：
 *       同一个退款单号重复请求，平台只会退一次</li>
 *   <li><b>退款金额</b> —— 全额退款时两家的写法不同：
 *       微信要求回传原订单的 {@code amount.total}，支付宝则可以只传 {@code refund_amount}</li>
 * </ul>
 *
 * <p><b>为什么要把「商户退款单号」单独生成而不是复用包场单号</b>：
 * 一场包场理论上可能先部分退、再退剩下的（当前不支持，但字段留着），
 * 用一个单号会把两次退款在平台侧合并成一次。
 * 退多少件事、就有多少个退款单号。
 */
@Data
public class RefundCommand {

    /** 原商户订单号（包场单号） */
    private String outTradeNo;

    /** 商户退款单号，由本服务生成 */
    private String refundNo;

    /** 退款金额（元），必须大于 0 且不超过原订单金额 */
    private BigDecimal amount;

    /** 原订单的总金额（元）。微信要求回传，支付宝可省 */
    private BigDecimal totalAmount;

    /**
     * 原支付通道。
     *
     * <p>决定调哪个平台的退款接口。为 null 表示这笔钱不是走线上通道收的
     * （如人工核销），<b>退不了</b> —— 调用方应当先判掉这种情况并引导走人工退款。
     */
    private PaymentChannel channel;

    /** 退款原因，会传给平台并出现在商户后台 */
    private String reason;
}
