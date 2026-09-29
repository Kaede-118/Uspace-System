package com.kaede.uspace.order;

import java.util.Arrays;
import java.util.Set;

/**
 * 支付通道。
 *
 * <p>取值与建表脚本里 {@code biz_order.payment_method} 的注释一一对应。
 *
 * <p><b>通道由前端探测用户所处的浏览器环境后自动选择，不让用户自己选</b> ——
 * 因为<b>微信内打不开支付宝、支付宝内打不开微信</b>（双方互相屏蔽外链），
 * 让用户选必然出现「选了却调不起来」的死路。判断依据是 User-Agent：
 * 含 {@code MicroMessenger} → 微信内；含 {@code AlipayClient} → 支付宝内；
 * 其余 → 微信外手机浏览器，此时微信 H5 与支付宝 WAP 都可用。
 *
 * <p>三个线上通道共用同一套回调处理与订单状态机，只是协议不同：
 * <ul>
 *   <li>微信的 JSAPI 与 H5 共用一个 {@code notify_url}（同一个 API v3 协议）</li>
 *   <li>支付宝的异步通知因协议不同（表单验签 vs. 微信的 RSA 验签 + AES 解密）
 *       单独一个端点</li>
 * </ul>
 * {@code payment_method} 列记下具体走的哪条通道，对账时能区分来源。
 */
public enum PaymentChannel {

    /** 微信内浏览器 → JSAPI 支付。下单返回 {@code prepay_id}，前端用 WeixinJSBridge 调起 */
    WXPAY_JSAPI("微信内浏览器"),

    /** 微信外的手机浏览器 → H5 支付。下单返回 {@code h5_url}，前端跳转拉起微信 App */
    WXPAY_H5("微信外手机浏览器"),

    /** 支付宝内置浏览器 / 支付宝场景 → 手机网站支付（WAP）。下单返回一段自动提交的 form HTML */
    ALIPAY_WAP("支付宝手机网站支付"),

    /**
     * 传截图 + 管理员人工核销。
     *
     * <p><b>这是降级路径，不是主路径</b>：运营方尚无支付商户号时（个人主体开不了商户号，
     * 而办执照、开户、备案是一串以月计的外部流程），可退化为「用户上传付款截图 →
     * 管理员核销」。两条路径共用同一套订单状态机与字段，切换不需改造数据模型。
     *
     * <p>它<b>不是线上通道</b>：不调网关、不产生支付平台交易号，也不需要回调。
     */
    QR_UPLOAD("传截图人工核销");

    /** 面向用户的中文说明，供前端展示 */
    private final String label;

    PaymentChannel(String label) {
        this.label = label;
    }

    /**
     * 取中文说明。
     *
     * @return 通道的中文名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 判断一个字符串是否为合法通道名。
     *
     * <p>用于校验发起支付的入参。非法值会让 Jackson 反序列化直接失败（走 400），
     * 但本方法仍需要 —— 从回调报文里取通道名时没有 Jackson 把关，
     * 那里拿到的是支付平台给的字符串。
     *
     * @param name 待校验的通道名，可为 null
     * @return 合法返回 true；null 或不在枚举内返回 false
     */
    public static boolean isValid(String name) {
        return name != null && Arrays.stream(values()).anyMatch(c -> c.name().equals(name));
    }

    /**
     * 判断某个通道是否属于「线上通道」。
     *
     * <p>线上通道要调支付网关下单、要等回调；{@link #QR_UPLOAD} 不走网关，
     * 发起支付时不该去调网关（调了必然失败）。
     *
     * @param name 通道名，可为 null
     * @return 线上通道返回 true
     */
    public static boolean isOnline(String name) {
        return Set.of(WXPAY_JSAPI.name(), WXPAY_H5.name(), ALIPAY_WAP.name()).contains(name);
    }
}
