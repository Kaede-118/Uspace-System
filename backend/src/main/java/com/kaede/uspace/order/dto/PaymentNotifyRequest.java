package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.PaymentChannel;
import lombok.Data;

import java.util.Map;

/**
 * 支付平台回调的原始报文。
 *
 * <p><b>三个字段分别服务不同的通道</b>，由 Controller 按端点填充：
 * <ul>
 *   <li>微信：{@link #body} 是<b>未经任何处理的原始 JSON 串</b>，
 *       {@link #headers} 是签名相关的请求头</li>
 *   <li>支付宝：{@link #params} 是表单参数（本就是 key-value 形式）</li>
 * </ul>
 *
 * <p><b>微信的 body 必须是原始串，不能先反序列化再传进来</b>：
 * 微信的签名是对「时间戳 + 随机串 + 报文主体」这串文本本身做的，
 * 一旦经过 JSON 解析再重新序列化，字段顺序与空白字符都可能变，
 * 签名就对不上了。这类问题表现为「回调一直验签失败」而看不出原因。
 */
@Data
public class PaymentNotifyRequest {

    /** 回调来自哪条通道。由端点决定（微信两个通道共用端点，故这里可能是 JSAPI 或 H5） */
    private PaymentChannel channel;

    /** 微信：原始 JSON 报文 */
    private String body;

    /** 支付宝：表单参数，保持原始键值（验签要按字典序拼接，不能遗漏也不能多加） */
    private Map<String, String> params;

    /** 微信：请求头（从中取 Wechatpay-Timestamp / Nonce / Signature / Serial） */
    private Map<String, String> headers;
}
