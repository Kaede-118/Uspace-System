package com.kaede.uspace.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PaymentProperties} 的单元测试。
 *
 * <p><b>最要紧的一条是「默认值取安全侧」</b>：配置文件里漏了
 * {@code uspace.payment.enabled-channels} 时，代码默认只开扫码转账。
 * 反过来（默认全开）会让「配置漏了」表现为「页面上出现了几条点不通的通道」——
 * 那是用户看得见的故障，而少开一条通道只是少收一种钱。
 *
 * <p>其次是 {@code isChannelEnabled(null)}：{@code payment_method} 列可空，
 * 而判断「这个通道开着吗」时手上那个值完全可能来自这一列。
 * 与 {@code PaymentChannel.isOnline} 是同一类 null 安全要求。
 */
class PaymentPropertiesTests {

    @Test
    @DisplayName("默认只开放扫码转账")
    void defaultOnlyQrUpload() {
        PaymentProperties properties = new PaymentProperties();

        assertTrue(properties.isChannelEnabled("QR_UPLOAD"));
        assertFalse(properties.isChannelEnabled("WXPAY_JSAPI"),
                "默认值要取安全侧：配置漏了这一项时，结果应是「少收了一种钱」，"
                        + "而不是「页面上多出几条点不通的通道」");
        assertFalse(properties.isChannelEnabled("WXPAY_H5"));
        assertFalse(properties.isChannelEnabled("ALIPAY_WAP"));
    }

    @Test
    @DisplayName("通道开关：未列入的取值返回 false，null 也返回 false 而不抛异常")
    void isChannelEnabled_toleratesNullAndUnknown() {
        PaymentProperties properties = new PaymentProperties();
        properties.setEnabledChannels(List.of("QR_UPLOAD", "WXPAY_JSAPI"));

        assertTrue(properties.isChannelEnabled("QR_UPLOAD"));
        assertTrue(properties.isChannelEnabled("WXPAY_JSAPI"));
        assertFalse(properties.isChannelEnabled("WXPAY_H5"));
        assertFalse(properties.isChannelEnabled("NOT_A_CHANNEL"));
        assertFalse(properties.isChannelEnabled(""),
                "空串不是「没限制」的意思 —— 它就是一个认不出的通道名");
        assertFalse(properties.isChannelEnabled(null),
                "这个判断的入参可能直接来自 payment_method 列，那一列可空");
    }

    @Test
    @DisplayName("一致性检查：通道名拼错、网关关了而通道还开着，都只警告不抛异常")
    void warnOnInconsistentConfig_neverThrows() {
        PaymentProperties properties = new PaymentProperties();
        properties.setProvider("disabled");
        // 最后一个是把 WXPAY_JSAPI 少打了一个字母 —— 模拟手抖拼错的配置
        properties.setEnabledChannels(List.of("QR_UPLOAD", "WXPAY_JSAPI", "WXPAY_JSAP"));

        assertDoesNotThrow(properties::warnOnInconsistentConfig,
                "配置写错一个字就不让服务起来，比少收一种钱严重得多 —— 只警告");
    }

    @Test
    @DisplayName("一致性检查：正常配置下也不抛异常")
    void warnOnInconsistentConfig_quietOnSaneConfig() {
        PaymentProperties properties = new PaymentProperties();
        properties.setProvider("mock");

        assertDoesNotThrow(properties::warnOnInconsistentConfig);
    }
}
