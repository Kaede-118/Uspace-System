package com.kaede.uspace.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PaymentChannel} 的单元测试。
 *
 * <p>两个静态判断各有用处，都值得钉住：
 * <ol>
 *   <li>{@code isOnline} —— 决定「要不要去调支付网关」。把 {@code QR_UPLOAD}
 *       判成线上通道，发起支付就会调一个必然失败的网关；
 *       反过来把线上通道判成非线上，用户点支付时什么都不会发生</li>
 *   <li>{@code isValid} —— 从回调报文里取通道名时没有 Jackson 把关，
 *       靠它挡住非法取值</li>
 * </ol>
 *
 * <p><b>本类最要紧的是 {@code isOnline(null)}</b>：它的实现原先写作
 * {@code Set.of(...).contains(name)}，JDK 的不可变集合对 null 查询会抛
 * {@link NullPointerException}，与「不是线上通道就返回 false」的契约正好相反。
 * 而 null 是真实会出现的：{@code payment_method} 列可空，
 * 0 元结清或尚未支付的订单读出来的就是 null。
 */
class PaymentChannelTests {

    @Test
    @DisplayName("线上通道判定：三个线上通道为真，传截图人工核销为假")
    void isOnline() {
        assertTrue(PaymentChannel.isOnline("WXPAY_JSAPI"), "微信内浏览器");
        assertTrue(PaymentChannel.isOnline("WXPAY_H5"), "微信外手机浏览器");
        assertTrue(PaymentChannel.isOnline("ALIPAY_WAP"), "支付宝手机网站支付");

        assertFalse(PaymentChannel.isOnline("QR_UPLOAD"),
                "传截图加人工核销不走网关 —— 判成线上会去调一个必然失败的接口");
    }

    @Test
    @DisplayName("线上通道判定：null 返回 false 而不是抛异常")
    void isOnline_toleratesNull() {
        assertFalse(PaymentChannel.isOnline(null),
                "payment_method 列可空，0 元结清与尚未支付的订单读出来就是 null —— "
                        + "那时问一句「这是不是线上通道」是正常调用，不该崩");
    }

    @Test
    @DisplayName("线上通道判定：认不出的取值不当作线上")
    void isOnline_rejectsUnknown() {
        assertFalse(PaymentChannel.isOnline("wxpay_jsapi"), "大小写不匹配就不算数");
        assertFalse(PaymentChannel.isOnline("CASH"), "没见过的取值不能当成线上放过去");
        assertFalse(PaymentChannel.isOnline(""));
    }

    @Test
    @DisplayName("合法取值：四个枚举名都认，其余一律不认")
    void isValid() {
        assertTrue(PaymentChannel.isValid("WXPAY_JSAPI"));
        assertTrue(PaymentChannel.isValid("WXPAY_H5"));
        assertTrue(PaymentChannel.isValid("ALIPAY_WAP"));
        assertTrue(PaymentChannel.isValid("QR_UPLOAD"));

        assertFalse(PaymentChannel.isValid("WXPAY_NATIVE"),
                "Native 扫码已被整体弃用（手机端没有第二台设备可扫），不该被认成合法通道");
        assertFalse(PaymentChannel.isValid("wxpay_h5"));
        assertFalse(PaymentChannel.isValid(""));
        assertFalse(PaymentChannel.isValid(null));
    }

    @Test
    @DisplayName("中文说明：四个取值各有标签，供前端展示")
    void getLabel() {
        assertEquals("微信内浏览器", PaymentChannel.WXPAY_JSAPI.getLabel());
        assertEquals("微信外手机浏览器", PaymentChannel.WXPAY_H5.getLabel());
        assertEquals("支付宝手机网站支付", PaymentChannel.ALIPAY_WAP.getLabel());
        assertEquals("传截图人工核销", PaymentChannel.QR_UPLOAD.getLabel(),
                "降级路径的标签要写得直白，前端展示时用户才知道这不是线上支付");
    }
}
