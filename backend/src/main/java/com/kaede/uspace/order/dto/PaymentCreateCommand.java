package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.PaymentChannel;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 向支付平台下单的入参。
 *
 * <p>字段按微信支付 API v3 与支付宝开放平台的真实要求设置 ——
 * 模拟实现也照着这套签名走，将来换成真实实现时调用方不必改。
 */
@Data
public class PaymentCreateCommand {

    /**
     * 商户订单号。
     *
     * <p>本系统用订单号 / 包场单号直接充当（都有唯一索引），
     * 不另生成一个支付单号 —— 多一套编号就多一处对不上的可能。
     */
    private String outTradeNo;

    /**
     * 应付金额，单位<b>元</b>。
     *
     * <p><b>单位换算在网关实现内部完成</b>：微信与支付宝的接口都收「分」，
     * 而本系统全程用「元」的 {@link BigDecimal}。让 {@code × 100} 这件事
     * 只在一处发生，比让每个调用方各自记得乘要可靠得多 ——
     * 忘了乘就是少收 100 倍的钱，而它不会报任何错。
     */
    private BigDecimal amount;

    /** 商品描述，展示在用户的支付账单上 */
    private String description;

    /** 支付通道。决定调哪个平台的哪个接口 */
    private PaymentChannel channel;

    /**
     * 用户端 IP。
     *
     * <p>微信 H5 支付的 {@code scene_info.payer_client_ip} 是<b>必填</b>参数，
     * 用于风控。当前模拟实现忽略它，真实接入时由 Controller 从请求里取。
     */
    private String clientIp;

    /**
     * 微信用户在本商户下的 openid。
     *
     * <p>JSAPI 支付<b>必需</b>，且必须与发起支付的公众号是同一个主体。
     * 获取它要经过网页授权（{@code snsapi_base}），而网页授权又要求
     * 「已认证的服务号 + 网页授权域名」—— 这正是 JSAPI 通道门槛最高的原因。
     *
     * <p>当前为 null：模拟实现不需要，真实接入时由前端在授权回调后带给后端。
     */
    private String openid;
}
