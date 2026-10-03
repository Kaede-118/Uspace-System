package com.kaede.uspace.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PayQrChannel} 的单元测试。
 *
 * <p><b>最要紧的一条是「不认支付通道的取值」</b>：{@code PaymentChannel} 与
 * 本枚举刻意是两套，混用的代价很具体 —— {@code WXPAY_JSAPI} 与 {@code WXPAY_H5}
 * 在顾客眼里是同一个微信收款码，而对账时它们又确实不是同一条流水。
 * 一旦有人图省事让两者共用取值，那条区分就悄悄没了，且不会有任何报错。
 */
class PayQrChannelTests {

    @Test
    @DisplayName("合法取值：两个枚举名都认，支付通道的取值一律不认")
    void isValid() {
        assertTrue(PayQrChannel.isValid("WXPAY"));
        assertTrue(PayQrChannel.isValid("ALIPAY"));

        assertFalse(PayQrChannel.isValid("WXPAY_JSAPI"),
                "支付通道的取值不该被当成收款账号渠道 —— 两者刻意用两套枚举");
        assertFalse(PayQrChannel.isValid("QR_UPLOAD"));
        assertFalse(PayQrChannel.isValid("wxpay"), "大小写不匹配就不算数");
        assertFalse(PayQrChannel.isValid(""));
        assertFalse(PayQrChannel.isValid(null));
    }

    @Test
    @DisplayName("中文名：认出给名字，认不出的原样返回，null 不抛异常")
    void labelOf() {
        assertEquals("微信", PayQrChannel.labelOf("WXPAY"));
        assertEquals("支付宝", PayQrChannel.labelOf("ALIPAY"));

        assertEquals("UNIONPAY", PayQrChannel.labelOf("UNIONPAY"),
                "认不出的取值原样返回 —— 让库里的异常数据在界面上一眼看得出来，"
                        + "与 PaymentChannel.userLabelOf 是同一条契约");
        assertNull(PayQrChannel.labelOf(null),
                "渠道列可空，null 要安全地落到「认不出」这一支而不是抛 NPE");
    }
}
