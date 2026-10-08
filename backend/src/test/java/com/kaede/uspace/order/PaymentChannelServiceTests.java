package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.order.dto.PayChannelVo;
import com.kaede.uspace.order.dto.PaymentTarget;
import com.kaede.uspace.order.entity.PaymentProof;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PaymentChannelService} 的单元测试。
 *
 * <p>本类钉住的是<b>「页面上选得到」与「点下去不报错」的等价性</b> ——
 * 那正是把两个过滤条件（本店收不收、这类收款受不受理）收到一处的全部理由。
 * 少了任何一个过滤，用户都会看到一个点了必然失败的选项。
 *
 * <p>用的处理器是假的：本类测的是「哪些通道会被列出来」，
 * 与具体收款类型无关，真实处理器的业务逻辑由各自的测试覆盖。
 */
class PaymentChannelServiceTests {

    @Test
    @DisplayName("全通道开放 + 全受理：四个通道都列出来，顺序取枚举声明顺序")
    void listFor_allChannelsAvailable() {
        PaymentChannelService service = new PaymentChannelService(
                allChannelsEnabled(),
                List.of(handler(PaymentTargetType.ORDER, PaymentChannel.values())));

        List<PayChannelVo> channels = service.listFor(PaymentTargetType.ORDER);

        assertEquals(PaymentChannel.values().length, channels.size());
        assertEquals(PaymentChannel.values()[0].name(), channels.get(0).getChannel(),
                "顺序原样取枚举声明顺序，前端不重排 —— 那是「历史与推荐程度」的顺序");
    }

    @Test
    @DisplayName("只开放扫码转账：线上通道一个都不列 —— 这就是 12 月投产时的样子")
    void listFor_onlyQrUploadEnabled() {
        // 默认配置就是只开 QR_UPLOAD，不额外设置
        PaymentChannelService service = new PaymentChannelService(
                new PaymentProperties(),
                List.of(handler(PaymentTargetType.ORDER, PaymentChannel.values())));

        List<PayChannelVo> channels = service.listFor(PaymentTargetType.ORDER);

        assertEquals(1, channels.size());
        assertEquals("QR_UPLOAD", channels.get(0).getChannel());
        assertTrue(channels.get(0).isNeedProof(), "扫码转账需要用户上传付款凭证");
        assertFalse(channels.get(0).isOnline(), "它不是线上通道 —— 不调网关、不等回调");
    }

    @Test
    @DisplayName("通道开着但这类型不受理：不列出来")
    void listFor_filtersOutUnsupportedChannels() {
        PaymentChannelService service = new PaymentChannelService(
                allChannelsEnabled(),
                List.of(handler(PaymentTargetType.MONTHLY_CARD, PaymentChannel.WXPAY_JSAPI)));

        List<PayChannelVo> channels = service.listFor(PaymentTargetType.MONTHLY_CARD);

        assertEquals(1, channels.size());
        assertEquals("WXPAY_JSAPI", channels.get(0).getChannel());
        assertTrue(channels.stream().noneMatch(vo -> "QR_UPLOAD".equals(vo.getChannel())),
                "QR_UPLOAD 虽然开着，但这个收款类型不受理它 —— "
                        + "列出来的话用户点下去会拿到一个没有支付参数的「成功」");
    }

    @Test
    @DisplayName("全通道开放但没有任何处理器受理：返回空列表而非 null")
    void listFor_noHandlerReturnsEmpty() {
        PaymentChannelService service = new PaymentChannelService(
                allChannelsEnabled(),
                List.of(handler(PaymentTargetType.ORDER, PaymentChannel.values())));

        assertTrue(service.listFor(PaymentTargetType.PRODUCT).isEmpty(),
                "没有处理器受理商品类型时列表为空，而不是把订单的通道也列给它");
        assertTrue(service.listFor(null).isEmpty(),
                "类型为 null 时同样返回空列表，调用方不必判空");
    }

    @Test
    @DisplayName("VO 字段：label 取用户口径，needProof 与 online 各按通道给")
    void listFor_voFields() {
        PaymentChannelService service = new PaymentChannelService(
                allChannelsEnabled(),
                List.of(handler(PaymentTargetType.ORDER, PaymentChannel.values())));

        Map<String, PayChannelVo> byChannel = service.listFor(PaymentTargetType.ORDER).stream()
                .collect(Collectors.toMap(PayChannelVo::getChannel, vo -> vo));

        PayChannelVo jsapi = byChannel.get("WXPAY_JSAPI");
        assertEquals("微信支付", jsapi.getLabel(),
                "给的是用户口径 —— 用户不需要知道 JSAPI 与 H5 的区别");
        assertTrue(jsapi.isOnline());
        assertFalse(jsapi.isNeedProof());

        PayChannelVo qr = byChannel.get("QR_UPLOAD");
        assertEquals("扫码转账", qr.getLabel());
        assertFalse(qr.isOnline());
        assertTrue(qr.isNeedProof(),
                "needProof 与 online 在当前取值下互补，但语义不同 —— "
                        + "将来加一条「现金」通道时，它既非线上也不需要凭证");
    }

    /**
     * 造一份「四条通道全开」的配置。
     *
     * <p>用 {@code values()} 而不是逐个列出：新增通道时本类自动跟上。
     *
     * @return 全部通道都启用的配置对象
     */
    private static PaymentProperties allChannelsEnabled() {
        PaymentProperties properties = new PaymentProperties();
        properties.setEnabledChannels(
                Arrays.stream(PaymentChannel.values()).map(Enum::name).toList());
        return properties;
    }

    /**
     * 造一个只声明「受理哪些通道」的假处理器。
     *
     * <p>本类只关心 {@code type} 与 {@code supportsChannel} 两个方法，
     * 其余方法一律退化成「被调用即失败」—— 真要有人在本类里调到它们，
     * 说明测试写歪了，直接炸出来比静默返回 null 好。
     *
     * @param type     本处理器负责的收款类型
     * @param accepted 受理的通道，可传多个
     * @return 假的处理器
     */
    private static PaymentTargetHandler handler(PaymentTargetType type, PaymentChannel... accepted) {
        return new PaymentTargetHandler() {
            @Override
            public PaymentTargetType type() {
                return type;
            }

            @Override
            public PaidCategory paidCategory() {
                return PaidCategory.ORDER;
            }

            @Override
            public boolean deliverOnSubmit() {
                throw new UnsupportedOperationException("本测试用不到");
            }

            @Override
            public boolean revertDelivery(PaymentTarget target, PaymentProof proof) {
                throw new UnsupportedOperationException("本测试用不到");
            }

            @Override
            public BizResult<PaymentTarget> loadForPay(Long id, Long userId) {
                throw new UnsupportedOperationException("本测试用不到");
            }

            @Override
            public BizResult<PaymentTarget> loadForProof(Long id, Long userId) {
                throw new UnsupportedOperationException("本测试用不到");
            }

            @Override
            public PaymentTarget loadByOutTradeNo(String outTradeNo) {
                throw new UnsupportedOperationException("本测试用不到");
            }

            @Override
            public boolean markPaid(PaymentTarget target, PaymentChannel channel,
                                    String transactionNo, LocalDateTime paidAt, Long confirmedBy) {
                throw new UnsupportedOperationException("本测试用不到");
            }

            @Override
            public boolean supportsChannel(PaymentChannel channel) {
                return Arrays.asList(accepted).contains(channel);
            }
        };
    }
}
