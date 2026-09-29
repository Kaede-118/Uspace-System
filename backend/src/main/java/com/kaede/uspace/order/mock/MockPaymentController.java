package com.kaede.uspace.order.mock;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.order.PaymentService;
import com.kaede.uspace.order.dto.MockPayRequest;
import com.kaede.uspace.order.dto.MockPayVo;
import com.kaede.uspace.order.dto.PaymentNotifyRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟支付接口，让演示与测试可以脱离外网走完整条支付链路。
 *
 * <p><b>它模拟的是「用户在微信 / 支付宝的收银台上点完成支付」这个动作</b>，
 * 而不是「把订单改成已支付」。区别至关重要：本接口触发的是
 * {@link MockPaymentGatewayImpl#simulatePay} 生成的一条<b>带签名的回调报文</b>，
 * 再交给 {@link PaymentService} 走与真实回调完全相同的处理 ——
 * 验签、幂等、金额核对、状态守卫一个不少。若这里直接改订单状态，
 * 那条链路上的分支在演示时就永远走不到，而它们恰恰是最需要验证的部分。
 *
 * <p><b>需要登录，且校验支付单归属</b>：模拟支付没有真实平台的身份校验，
 * 少了归属校验，任何登录用户只要知道订单号就能把别人的订单标记成已支付。
 *
 * <p>用 {@code @ConditionalOnProperty} 限定在 {@code provider=mock} 下存在 ——
 * 切到真实支付时，这个接口与实现类一起从容器里消失，不会留下一个
 * 「谁都能调的把订单标成已支付」的后门。
 *
 * @see MockPaymentGatewayImpl
 */
@Slf4j
@RestController
@RequestMapping("/api/payments/mock")
@ConditionalOnProperty(prefix = "uspace.payment", name = "provider",
        havingValue = "mock", matchIfMissing = true)
@Validated
public class MockPaymentController {

    private final MockPaymentGatewayImpl mockGateway;
    private final PaymentService paymentService;

    public MockPaymentController(MockPaymentGatewayImpl mockGateway, PaymentService paymentService) {
        this.mockGateway = mockGateway;
        this.paymentService = paymentService;
    }

    /**
     * 模拟用户完成支付，并投递回调。
     *
     * @param request 支付单号
     * @param me      当前登录用户
     * @return 支付与回调的完成情况
     */
    @PostMapping("/pay")
    public ResponseEntity<ApiResult<MockPayVo>> pay(@Valid @RequestBody MockPayRequest request,
                                                    @AuthenticationPrincipal UserPrincipal me) {
        String outTradeNo = request.getOutTradeNo();

        // 归属校验。用的是与真实支付同一套判断 —— 模拟环境不该比真实环境更宽松
        if (!paymentService.isOwnedBy(me.id(), outTradeNo)) {
            log.warn("[Mock支付] 拒绝：支付单不属于当前用户 outTradeNo={} userId={}",
                    outTradeNo, me.id());
            return ApiResult.of(ErrorCode.NOT_FOUND, "支付单号不存在");
        }

        // ① 模拟用户在收银台完成支付，拿到一条已签名的回调报文
        PaymentNotifyRequest notify = mockGateway.simulatePay(outTradeNo);

        // ② 按配置决定是否丢弃这次回调 —— 演示「钱付了、回调没到」
        if (mockGateway.shouldDropNotify()) {
            log.warn("[Mock支付] 按配置丢弃本次回调，订单将停留在待支付 outTradeNo={}", outTradeNo);
            return ApiResult.of(BizResult.ok(
                    result(false, "支付已完成，但回调按配置被丢弃；订单仍为待支付，可调查单接口补偿")));
        }

        // ③ 交给与真实回调完全相同的处理链路
        boolean ok = switch (notify.getChannel()) {
            case WXPAY_JSAPI, WXPAY_H5 -> paymentService.handleWxpayNotify(notify);
            case ALIPAY_WAP -> paymentService.handleAlipayNotify(notify);
            case QR_UPLOAD -> false;
        };

        if (!ok) {
            // 走到这里说明金额不符、状态冲突或验签失败 —— 都是要人工介入的情形，
            // handleXxxNotify 内部已经记了 error 日志
            log.error("[Mock支付] 回调未被接受 outTradeNo={}", outTradeNo);
            return ApiResult.of(BizResult.ok(
                    result(true, "支付已完成，但回调未被接受（金额不符或状态冲突），请查看服务端日志")));
        }
        return ApiResult.of(BizResult.ok(
                result(true, "支付已完成，订单状态已更新")));
    }

    /**
     * 组装返回视图。
     *
     * @param notified 回调是否投递
     * @param message  给演示者看的说明
     * @return 结果视图
     */
    private static MockPayVo result(boolean notified, String message) {
        MockPayVo vo = new MockPayVo();
        vo.setPaid(true);
        vo.setNotified(notified);
        vo.setMessage(message);
        return vo;
    }
}
