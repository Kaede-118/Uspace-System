package com.kaede.uspace.order;

import com.kaede.uspace.order.dto.PaymentCreateCommand;
import com.kaede.uspace.order.dto.PaymentCreateResult;
import com.kaede.uspace.order.dto.PaymentNotifyRequest;
import com.kaede.uspace.order.dto.PaymentNotifyResult;
import com.kaede.uspace.order.dto.PaymentQueryResult;
import com.kaede.uspace.order.dto.RefundCommand;
import com.kaede.uspace.order.dto.RefundResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 未开通线上支付时的网关实现，对应 {@code uspace.payment.provider=disabled}。
 *
 * <p><b>它存在的唯一理由是让容器里有一个 {@code PaymentGateway} bean。</b>
 * {@code PaymentService} 的构造器依赖这个接口，而模拟实现与将来的真实实现
 * 都挂在 {@code @ConditionalOnProperty} 上 —— 没有本类的话，把 provider
 * 改成任何一个非 {@code mock} 的值都会让容器装配失败、<b>应用直接起不来</b>，
 * 而不是「优雅地关掉支付」。
 *
 * <p>注意它<b>不是「什么都不做的空实现」</b>，语义是精确的：
 * <ul>
 *   <li>所有方法一律返回失败，且失败原因是<b>「本店没开这个业务」</b>，
 *       不是「上游挂了」—— 所以 {@code createPayment} 的错误提示指向
 *       扫码转账，而不是让用户「稍后重试」</li>
 *   <li>退款明确提示走人工退款。线下收款的钱本来就不在支付平台上，
 *       {@code BookingRefundService} 的「原路退回」模式在这一档下
 *       必定失败并保持原状 —— 这是正确行为，不是 bug</li>
 * </ul>
 *
 * <p><b>正经路径下不会有请求打进来</b>：{@code enabled-channels} 里没有线上通道时，
 * 前端根本不会展示那些选项。真打进来了，说明要么配置矛盾
 * （{@link PaymentProperties#warnOnInconsistentConfig()} 会在启动时警告），
 * 要么有人在直接探接口 —— 两种都该留一条 WARN 日志。
 *
 * @see PaymentProperties#provider
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "uspace.payment", name = "provider", havingValue = "disabled")
public class DisabledPaymentGatewayImpl implements PaymentGateway {

    /**
     * {@inheritDoc}
     *
     * <p>返回的失败原因指向扫码转账 —— 用户看到的提示要能告诉他下一步该干什么，
     * 而不是「支付暂不可用」这种说了等于没说的话。
     */
    @Override
    public PaymentCreateResult createPayment(PaymentCreateCommand command) {
        log.warn("[支付] 线上支付未开通（provider=disabled），拒绝下单 outTradeNo={} 通道={} —— "
                        + "若这是用户点出来的，说明 enabled-channels 里还留着线上通道",
                command.getOutTradeNo(), command.getChannel());
        return PaymentCreateResult.fail(null, "本店未开通在线支付，请使用扫码转账");
    }

    /**
     * {@inheritDoc}
     *
     * <p>回调端点在 {@code SecurityConfig.PUBLIC_PATHS} 里仍然匿名可达，
     * 所以这个方法真的可能被调用 —— 一律拒绝，让平台收到失败应答。
     * 应用没有商户号，那条端点上本来也不会收到任何真实通知。
     */
    @Override
    public PaymentNotifyResult verifyAndParseWxpay(PaymentNotifyRequest request) {
        log.warn("[支付] 收到微信回调，但本店未开通线上支付，一律拒绝");
        return rejected();
    }

    /** {@inheritDoc} */
    @Override
    public PaymentNotifyResult verifyAndParseAlipay(PaymentNotifyRequest request) {
        log.warn("[支付] 收到支付宝回调，但本店未开通线上支付，一律拒绝");
        return rejected();
    }

    /**
     * {@inheritDoc}
     *
     * <p>失败原因明确指向人工退款：线下收款的钱进的是收款码，
     * 系统里没有对应的支付平台流水，<b>想原路退回也没有路</b>。
     */
    @Override
    public RefundResult refund(RefundCommand command) {
        log.warn("[支付] 线上支付未开通，无法原路退款 refundNo={} —— 线下收款请走人工退款",
                command.getRefundNo());
        return RefundResult.fail("本店未开通在线支付，无法原路退款；线下收款请选择人工退款");
    }

    /** {@inheritDoc} */
    @Override
    public PaymentQueryResult queryPayment(String outTradeNo, PaymentChannel channel) {
        return PaymentQueryResult.fail("本店未开通在线支付，没有可查询的平台流水");
    }

    /**
     * 构造一个「验签不通过」的结果。
     *
     * <p>{@code PaymentNotifyResult} 没有静态工厂，两处拒绝分支的形状又完全一样，
     * 所以在这里收成一处。
     *
     * <p><b>{@code success=false} 与 {@code paid=false} 都要显式设上</b>：
     * 后者是 boolean 的默认值、不设也对，但让「这笔钱没收到」这件事
     * 在代码里明确写出来，比依赖默认值可靠。
     *
     * @return 拒绝处理的结果对象
     */
    private static PaymentNotifyResult rejected() {
        PaymentNotifyResult result = new PaymentNotifyResult();
        result.setSuccess(false);
        result.setPaid(false);
        result.setErrmsg("本店未开通在线支付");
        return result;
    }
}
