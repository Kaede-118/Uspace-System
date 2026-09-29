package com.kaede.uspace.order.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaede.uspace.order.PaymentChannel;
import com.kaede.uspace.order.PaymentProperties;
import com.kaede.uspace.order.dto.PaymentCreateCommand;
import com.kaede.uspace.order.dto.PaymentCreateResult;
import com.kaede.uspace.order.dto.PaymentNotifyRequest;
import com.kaede.uspace.order.dto.PaymentNotifyResult;
import com.kaede.uspace.order.dto.PaymentQueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MockPaymentGatewayImpl} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连外网。</b>
 *
 * <p>这个模拟实现是要拿去演示与答辩的，所以它自己必须是对的：
 * <ol>
 *   <li><b>自签自验要闭环</b> —— 它签发的报文能被它自己的验签通过，
 *       否则回调链路在演示时第一步就断</li>
 *   <li><b>篡改必须被挡住</b> —— 改了报文里的金额再验签，一定要失败。
 *       这一条如果测不出来，说明「验签」只是走个过场，
 *       将来切成真实实现时照抄就会出事故</li>
 *   <li><b>元的换算只有一处</b> —— 微信与支付宝都收「分」，
 *       换算错了就是少收 100 倍的钱，而且不报任何错</li>
 * </ol>
 */
class MockPaymentGatewayImplTests {

    private static final String OUT_TRADE_NO = "OD202609281200000001";
    private static final BigDecimal AMOUNT = new BigDecimal("22.00");

    private final PaymentProperties properties = new PaymentProperties();
    private final MockPaymentGatewayImpl gateway =
            new MockPaymentGatewayImpl(properties, new ObjectMapper());

    // ==================================================================
    // 下单
    // ==================================================================

    @Test
    @DisplayName("下单：JSAPI 给预支付标识，不给跳转链接")
    void createPayment_jsapiReturnsPrepayId() {
        PaymentCreateResult result = gateway.createPayment(command(PaymentChannel.WXPAY_JSAPI, AMOUNT));

        assertTrue(result.isSuccess(), "正常下单应当成功");
        assertNotNull(result.getPrepayId(), "JSAPI 的产物是预支付交易会话标识");
        assertNull(result.getH5Url(), "JSAPI 不该同时给出跳转链接 —— 给了前端反而不知道该用哪个");
    }

    @Test
    @DisplayName("下单：H5 给跳转链接，不给预支付标识")
    void createPayment_h5ReturnsUrl() {
        PaymentCreateResult result = gateway.createPayment(command(PaymentChannel.WXPAY_H5, AMOUNT));

        assertTrue(result.isSuccess(), "正常下单应当成功");
        assertNotNull(result.getH5Url(), "H5 的产物是跳转链接");
        assertNull(result.getPrepayId(), "H5 不该给预支付标识");
    }

    @Test
    @DisplayName("下单：支付宝给一段自动提交的表单")
    void createPayment_alipayReturnsForm() {
        PaymentCreateResult result = gateway.createPayment(command(PaymentChannel.ALIPAY_WAP, AMOUNT));

        assertTrue(result.isSuccess(), "正常下单应当成功");
        assertNotNull(result.getFormHtml(), "支付宝这个接口不给链接，给的是一整段带表单的 HTML");
        assertTrue(result.getFormHtml().contains("<form"), "返回的应当是可提交的表单");
    }

    @Test
    @DisplayName("下单：人工核销通道不被接受")
    void createPayment_rejectsQrUpload() {
        PaymentCreateResult result = gateway.createPayment(command(PaymentChannel.QR_UPLOAD, AMOUNT));

        assertFalse(result.isSuccess(),
                "人工核销没有线上支付可发起 —— 这个分支要被挡住，而不是产出一个假的凭据");
    }

    @Test
    @DisplayName("下单：失败注入按配置生效")
    void createPayment_honoursFailureInjection() {
        properties.getMock().setFailureRate(1.0);

        PaymentCreateResult result = gateway.createPayment(command(PaymentChannel.WXPAY_H5, AMOUNT));

        assertFalse(result.isSuccess(), "失败率设为 1 时必定失败，否则异常分支在演示时走不到");
    }

    // ==================================================================
    // 回调：自签自验
    // ==================================================================

    @Test
    @DisplayName("回调：自己签发的微信报文能被自己验签通过，且金额按分还原为元")
    void notify_wxpayRoundTrip() {
        // 用 0.05 元这种「分与元换算会暴露精度问题」的金额
        BigDecimal small = new BigDecimal("0.05");
        gateway.createPayment(command(PaymentChannel.WXPAY_JSAPI, small));

        PaymentNotifyRequest notify = gateway.simulatePay(OUT_TRADE_NO);
        PaymentNotifyResult result = gateway.verifyAndParseWxpay(notify);

        assertTrue(result.isSuccess(), "自签自验必须闭环，否则演示时回调第一步就断");
        assertTrue(result.isPaid(), "报文里是支付成功");
        assertEquals(OUT_TRADE_NO, result.getOutTradeNo(), "单号要从报文里解出来");
        assertEquals(0, small.compareTo(result.getAmount()),
                "0.05 元发出去是 5 分，收回来也得是 0.05 元 —— 换算错了就是少收 100 倍的钱");
        assertEquals(PaymentChannel.WXPAY_JSAPI, result.getChannel(), "通道从 trade_type 判定");
    }

    @Test
    @DisplayName("回调：报文里的金额被篡改后验签不通过")
    void notify_rejectsTamperedWxpayBody() {
        gateway.createPayment(command(PaymentChannel.WXPAY_H5, AMOUNT));
        PaymentNotifyRequest notify = gateway.simulatePay(OUT_TRADE_NO);

        // 把报文里的金额改小 —— 这正是伪造回调最直接的动机
        String tampered = notify.getBody().replace("\"total\":2200", "\"total\":1");
        notify.setBody(tampered);

        PaymentNotifyResult result = gateway.verifyAndParseWxpay(notify);

        assertFalse(result.isSuccess(),
                "签名针对的是原始报文串，改一个字符就该验不过 —— "
                        + "这条测不出来说明验签只是走个过场");
    }

    @Test
    @DisplayName("回调：缺少签名请求头时拒绝")
    void notify_rejectsMissingSignatureHeader() {
        gateway.createPayment(command(PaymentChannel.WXPAY_H5, AMOUNT));
        PaymentNotifyRequest notify = gateway.simulatePay(OUT_TRADE_NO);
        notify.setHeaders(Map.of());

        PaymentNotifyResult result = gateway.verifyAndParseWxpay(notify);

        assertFalse(result.isSuccess(), "没有签名就没法验证来源，一律拒绝");
    }

    @Test
    @DisplayName("回调：支付宝表单同样能自签自验")
    void notify_alipayRoundTrip() {
        gateway.createPayment(command(PaymentChannel.ALIPAY_WAP, AMOUNT));

        PaymentNotifyRequest notify = gateway.simulatePay(OUT_TRADE_NO);
        PaymentNotifyResult result = gateway.verifyAndParseAlipay(notify);

        assertTrue(result.isSuccess(), "支付宝路径的自签自验也要闭环");
        assertTrue(result.isPaid(), "trade_status 是 TRADE_SUCCESS");
        assertEquals(0, AMOUNT.compareTo(result.getAmount()), "金额要能还原");
        assertEquals(PaymentChannel.ALIPAY_WAP, result.getChannel(), "支付宝通道是确定的");
    }

    @Test
    @DisplayName("回调：支付宝表单里的签名被篡改后验签不通过")
    void notify_rejectsTamperedAlipayParams() {
        gateway.createPayment(command(PaymentChannel.ALIPAY_WAP, AMOUNT));
        PaymentNotifyRequest notify = gateway.simulatePay(OUT_TRADE_NO);
        notify.getParams().put("total_amount", "0.01");

        PaymentNotifyResult result = gateway.verifyAndParseAlipay(notify);

        assertFalse(result.isSuccess(), "改了金额再验签必须失败");
    }

    // ==================================================================
    // 查单与开关
    // ==================================================================

    @Test
    @DisplayName("查单：能读出模拟支付的结果")
    void queryPayment_readsSimulatedResult() {
        gateway.createPayment(command(PaymentChannel.WXPAY_H5, AMOUNT));
        gateway.simulatePay(OUT_TRADE_NO);

        PaymentQueryResult result = gateway.queryPayment(OUT_TRADE_NO, PaymentChannel.WXPAY_H5);

        assertTrue(result.isSuccess(), "查询应当成功");
        assertTrue(result.isPaid(), "模拟支付后查询应当是已支付");
        assertNotNull(result.getTransactionNo(), "平台交易号要能查到 —— 对账靠它");
    }

    @Test
    @DisplayName("查单：未支付的单返回「已查到但未付款」")
    void queryPayment_reportsUnpaid() {
        gateway.createPayment(command(PaymentChannel.WXPAY_H5, AMOUNT));

        PaymentQueryResult result = gateway.queryPayment(OUT_TRADE_NO, PaymentChannel.WXPAY_H5);

        assertTrue(result.isSuccess(),
                "「查询失败」与「查到了但没付款」是两回事，混成一个会让补偿逻辑判断不了该不该重试");
        assertFalse(result.isPaid(), "还没付款");
    }

    @Test
    @DisplayName("查单：不存在的单号返回失败")
    void queryPayment_failsForUnknownOrder() {
        PaymentQueryResult result = gateway.queryPayment("OD000000000000000000", PaymentChannel.WXPAY_H5);

        assertFalse(result.isSuccess(), "单号不存在是查询失败，不是「未支付」");
    }

    @Test
    @DisplayName("模拟支付：对同一笔单重复调用会被拒绝")
    void simulatePay_rejectsDuplicate() {
        gateway.createPayment(command(PaymentChannel.WXPAY_H5, AMOUNT));
        gateway.simulatePay(OUT_TRADE_NO);

        assertThrows(IllegalArgumentException.class, () -> gateway.simulatePay(OUT_TRADE_NO),
                "同一笔单不该能「付两次」—— 这会掩盖真实的重复支付问题");
    }

    @Test
    @DisplayName("模拟支付：单号不存在时抛异常")
    void simulatePay_rejectsUnknownOrder() {
        assertThrows(IllegalArgumentException.class, () -> gateway.simulatePay("OD000000000000000000"),
                "没下过单就模拟支付，说明调用方用错了");
    }

    @Test
    @DisplayName("回调丢弃：按配置生效，用于演示「钱付了、回调没到」")
    void shouldDropNotify_honoursConfig() {
        assertFalse(gateway.shouldDropNotify(), "默认不丢弃");

        properties.getMock().setNotifyDropRate(1.0);

        assertTrue(gateway.shouldDropNotify(),
                "设为 1 时必定丢弃 —— 这是复现「回调丢失」与验证主动查单补偿的手段");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 构造一个下单命令。
     *
     * @param channel 通道
     * @param amount  金额（元）
     * @return 命令对象
     */
    private static PaymentCreateCommand command(PaymentChannel channel, BigDecimal amount) {
        PaymentCreateCommand command = new PaymentCreateCommand();
        command.setOutTradeNo(OUT_TRADE_NO);
        command.setAmount(amount);
        command.setDescription("共享娱乐空间使用费");
        command.setChannel(channel);
        return command;
    }
}
