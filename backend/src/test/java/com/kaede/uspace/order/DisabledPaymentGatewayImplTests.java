package com.kaede.uspace.order;

import com.kaede.uspace.order.dto.PaymentCreateCommand;
import com.kaede.uspace.order.dto.PaymentCreateResult;
import com.kaede.uspace.order.dto.PaymentNotifyRequest;
import com.kaede.uspace.order.dto.PaymentNotifyResult;
import com.kaede.uspace.order.dto.PaymentQueryResult;
import com.kaede.uspace.order.dto.RefundCommand;
import com.kaede.uspace.order.dto.RefundResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DisabledPaymentGatewayImpl} 的单元测试。
 *
 * <p>这个类看起来「所有方法都返回失败」，没什么可测的。但它的失败<b>是有内容要求的</b>，
 * 每一条都由下面的用例钉着：
 * <ol>
 *   <li><b>失败原因必须可行动</b> —— 「本店未开通在线支付，请使用扫码转账」能告诉用户
 *       下一步做什么；而「支付暂不可用」这种话说了等于没说。这是 12 月投产时
 *       用户唯一会看到的支付错误，措辞值得测</li>
 *   <li><b>不能抛异常</b> —— {@code PaymentGateway} 的契约是「失败用返回对象表达」。
 *       它挂在回调链路上，抛异常会让回调端点返回 500、让支付平台反复重推</li>
 *   <li><b>{@code paid} 必须是假</b> —— 拒绝处理时若 {@code paid} 为真，
 *       上层就可能把这笔当成已支付入账</li>
 * </ol>
 */
class DisabledPaymentGatewayImplTests {

    private final DisabledPaymentGatewayImpl gateway = new DisabledPaymentGatewayImpl();

    @Test
    @DisplayName("下单：一律失败，且提示要指向扫码转账这个可行动作")
    void createPayment_alwaysFailsWithActionableMessage() {
        PaymentCreateCommand command = new PaymentCreateCommand();
        command.setOutTradeNo("OD20260930001");
        command.setAmount(new BigDecimal("22.00"));
        command.setChannel(PaymentChannel.WXPAY_JSAPI);

        PaymentCreateResult result = gateway.createPayment(command);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrmsg());
        assertTrue(result.getErrmsg().contains("扫码转账"),
                "投产时这是用户唯一会看到的支付失败提示，必须告诉他下一步干什么");
    }

    @Test
    @DisplayName("微信回调：验签不通过，且 paid 为假")
    void verifyAndParseWxpay_alwaysRejected() {
        PaymentNotifyResult result = gateway.verifyAndParseWxpay(new PaymentNotifyRequest());

        assertFalse(result.isSuccess(), "线上支付未开通时不该认任何回调");
        assertFalse(result.isPaid(),
                "拒绝处理时必须连 paid 一起置假 —— 只置 success 的话，"
                        + "上层一旦只看 paid 就会把没付的款标成已付");
        assertNotNull(result.getErrmsg());
    }

    @Test
    @DisplayName("支付宝回调：同样一律拒绝")
    void verifyAndParseAlipay_alwaysRejected() {
        PaymentNotifyResult result = gateway.verifyAndParseAlipay(new PaymentNotifyRequest());

        assertFalse(result.isSuccess());
        assertFalse(result.isPaid());
    }

    @Test
    @DisplayName("退款：失败并引导走人工退款，而不是让调用方以为能原路退回")
    void refund_alwaysFailsAndPointsToManual() {
        RefundCommand command = new RefundCommand();
        command.setRefundNo("RF20260930001");
        command.setOutTradeNo("BK20260930001");
        command.setAmount(new BigDecimal("120.00"));
        command.setChannel(PaymentChannel.WXPAY_JSAPI);

        RefundResult result = gateway.refund(command);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrmsg().contains("人工退款"),
                "线下收款的钱不在支付平台上，想原路退回也没有路 —— "
                        + "提示必须把调用方引到人工退款那条路上去");
    }

    @Test
    @DisplayName("查单：失败，因为根本没有可查的平台流水")
    void queryPayment_alwaysFails() {
        PaymentQueryResult result = gateway.queryPayment("OD20260930001", PaymentChannel.WXPAY_JSAPI);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrmsg());
    }
}
