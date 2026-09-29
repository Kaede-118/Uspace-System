package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.PaymentChannel;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 验签并解析支付回调之后得到的结果。
 *
 * <p><b>{@link #success} 与 {@link #paid} 是两件事，不要混用</b>：
 * <ul>
 *   <li>{@code success} —— <b>这报文是不是平台发来的</b>（验签通过、格式正确）。
 *       为 false 时一切免谈，直接拒绝</li>
 *   <li>{@code paid} —— <b>用户到底付没付钱</b>。验签通过但 {@code paid=false}
 *       是正常情形：支付宝会推交易关闭、退款等通知，它们都是真的、但都不该
 *       把订单标成已支付</li>
 * </ul>
 * 把两者合成一个字段会导致「退款通知把订单又标成已支付」这类事故。
 */
@Data
public class PaymentNotifyResult {

    /** 是否验签通过且解析成功 */
    private boolean success;

    /** 验签或解析失败的原因，仅用于日志 */
    private String errmsg;

    /** 用户是否已完成支付。为 false 时下面的业务字段可能为空 */
    private boolean paid;

    /** 商户订单号。回调据此定位订单或包场 */
    private String outTradeNo;

    /**
     * 平台交易号：微信 {@code transaction_id} / 支付宝 {@code trade_no}。
     *
     * <p>它是对账的依据 —— 拿这个号去支付平台的商户后台能查到那一笔流水。
     * 会写进 {@code biz_order.payment_no}。
     */
    private String transactionNo;

    /**
     * 平台记录的实付金额，单位<b>元</b>。
     *
     * <p><b>必须与本地应付额核对</b>：这是防篡改的关键一步。回调端点匿名可达
     * （平台不带 JWT），验签是主要防线，但金额核对是第二道 ——
     * 无论报文怎么来的，只要金额对不上就拒绝入账。
     */
    private BigDecimal amount;

    /** 实际通道，由报文内容判定（微信端点下要区分这笔是 JSAPI 还是 H5） */
    private PaymentChannel channel;

    /** 平台记录的支付完成时刻 */
    private LocalDateTime paidAt;

    /**
     * 附加数据。
     *
     * <p>本系统在下单时把目标类型塞进微信的 {@code attach} / 支付宝的
     * {@code passback_params}，回调时原样带回来，用作目标类型的<b>交叉验证</b> ——
     * 主判据仍是商户订单号的前缀，这里只是再确认一次，以防单号规则改动后
     * 路由到错误的目标上。
     *
     * <p>顺带一个实现要点：支付宝的 {@code passback_params} <b>只在异步通知里回传</b>，
     * 同步通知（{@code return_url}）不返回 —— 要往回调里带自定义参数，
     * 就得走这个字段，而不是拼接到 {@code return_url} 上。
     */
    private String attach;
}
