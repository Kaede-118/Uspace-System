package com.kaede.uspace.order.dto;

import com.kaede.uspace.order.PaymentChannel;
import com.kaede.uspace.order.PaymentTargetType;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 发起支付的返回体。
 *
 * <p><b>三个支付参数按通道二选一有值</b>，前端拿到后按 {@link #channel} 分支处理：
 * <ul>
 *   <li>{@code WXPAY_JSAPI} → 用 {@link #prepayId} 调 {@code WeixinJSBridge}</li>
 *   <li>{@code WXPAY_H5} → 把浏览器跳到 {@link #h5Url}</li>
 *   <li>{@code ALIPAY_WAP} → 把 {@link #formHtml} 插入页面并提交表单</li>
 * </ul>
 * 模拟实现下还会多一个 {@link #mockPayUrl}，指向模拟收银台。
 */
@Data
public class PaymentCreateVo {

    /**
     * 商户订单号。
     *
     * <p>前端拿它调「主动查单」做补偿（用户说「我付过了」时用），
     * 也是与客服核对时的凭据。
     */
    private String outTradeNo;

    /** 支付目标类型 */
    private PaymentTargetType targetType;

    /** 支付目标主键 */
    private Long targetId;

    /** 通道名 */
    private String channel;

    /** 通道中文说明，供页面展示 */
    private String channelLabel;

    /** 应付金额（元） */
    private BigDecimal amount;

    /**
     * 微信 JSAPI 的预支付交易会话标识。
     *
     * <p>前端用它和另外几个参数（时间戳、随机串、包名、签名）一起调起支付。
     * <b>有效期 2 小时</b>，未支付时可多次拉起。
     */
    private String prepayId;

    /**
     * 微信 H5 支付的跳转链接。
     *
     * <p><b>有效期只有 5 分钟</b>，前端拿到后应当<b>立刻</b>跳转，不要缓存、不要等待。
     * 官方明确严禁篡改、拆分或截断该链接。
     */
    private String h5Url;

    /** 支付宝手机网站支付的自动提交表单 HTML。前端插入页面即会拉起支付宝 App */
    private String formHtml;

    /** 模拟收银台地址（仅 {@code provider=mock} 时非空） */
    private String mockPayUrl;

    /**
     * 有效期提示文案。
     *
     * <p>由后端给而不是前端写死：三个通道的有效期差别很大（2 小时 / 5 分钟），
     * 前端按通道写死文案，改参数的通道一多就会对不上。
     */
    private String expireHint;
}
