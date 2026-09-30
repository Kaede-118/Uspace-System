package com.kaede.uspace.order;

import com.kaede.uspace.order.dto.PaymentCreateCommand;
import com.kaede.uspace.order.dto.PaymentCreateResult;
import com.kaede.uspace.order.dto.PaymentNotifyRequest;
import com.kaede.uspace.order.dto.PaymentNotifyResult;
import com.kaede.uspace.order.dto.PaymentQueryResult;
import com.kaede.uspace.order.dto.RefundCommand;
import com.kaede.uspace.order.dto.RefundResult;

import java.time.LocalDateTime;

/**
 * 内存版的 {@link PaymentGateway}，供模块 8 的单元测试使用。
 *
 * <p>真实实现在 {@code MockPaymentGatewayImpl}，那个是给「跑起来演示」用的；
 * 这个则只服务于单元测试 —— 它把「返回什么」变成测试可以直接摆布的东西，
 * 于是验签失败、金额不符、查单补偿这些分支都能被精确地构造出来。
 * 两者刻意分开：让演示用的模拟实现承担「可被测试任意摆布」的职责，
 * 会把它搞得面目全非，反过来也一样。
 *
 * <p>所有回调解析方法都返回调用方用 {@link #withNotifyResult} 设定的结果，
 * 默认是「验签通过且已支付」—— 让绝大多数用例只关心业务分支。
 */
public class FakePaymentGateway implements PaymentGateway {

    /** 回调解析的返回值，默认验签通过且已支付 */
    private PaymentNotifyResult notifyResult = paidNotify();

    /** 查单的返回值 */
    private PaymentQueryResult queryResult = new PaymentQueryResult();

    /** 下一次下单是否失败 */
    private boolean nextCreateFails = false;

    /** 最后一次下单收到的参数，供断言「传给网关的单号与金额对不对」 */
    private PaymentCreateCommand lastCommand;

    private int createCalls = 0;

    /** 下一次退款是否失败 */
    private boolean nextRefundFails = false;

    /** 退款失败时返回的原因 */
    private String refundFailReason = "模拟网络异常：退款失败";

    /** 最后一次退款收到的参数 */
    private RefundCommand lastRefundCommand;

    private int refundCalls = 0;

    /**
     * 设定回调解析的返回值。
     *
     * @param result 解析结果
     * @return 本对象，便于链式调用
     */
    public FakePaymentGateway withNotifyResult(PaymentNotifyResult result) {
        this.notifyResult = result;
        return this;
    }

    /**
     * 设定查单的返回值。
     *
     * @param result 查单结果
     * @return 本对象，便于链式调用
     */
    public FakePaymentGateway withQueryResult(PaymentQueryResult result) {
        this.queryResult = result;
        return this;
    }

    /**
     * 让下一次下单失败。
     *
     * @return 本对象，便于链式调用
     */
    public FakePaymentGateway failNextCreate() {
        this.nextCreateFails = true;
        return this;
    }

    /** @return 下单方法的累计调用次数 */
    public int createCalls() {
        return createCalls;
    }

    /** @return 最后一次下单的参数；从未调用过时为 null */
    public PaymentCreateCommand lastCommand() {
        return lastCommand;
    }

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现：按通道填上对应的凭据，具体内容不重要 ——
     * 单元测试关心的是「Service 有没有把它透传给调用方」，不是凭据长什么样。
     */
    @Override
    public PaymentCreateResult createPayment(PaymentCreateCommand command) {
        createCalls++;
        lastCommand = command;

        if (nextCreateFails) {
            nextCreateFails = false;
            return PaymentCreateResult.fail(-1, "模拟网络异常：支付下单失败");
        }

        PaymentCreateResult result = PaymentCreateResult.ok();
        switch (command.getChannel()) {
            case WXPAY_JSAPI -> result.setPrepayId("prepay_" + command.getOutTradeNo());
            case WXPAY_H5 -> result.setH5Url("https://mock/h5/" + command.getOutTradeNo());
            case ALIPAY_WAP -> result.setFormHtml("<form>" + command.getOutTradeNo() + "</form>");
            case QR_UPLOAD -> { /* 不该被调用 */ }
        }
        result.setMockPayUrl("/api/payments/mock/pay");
        return result;
    }

    @Override
    public PaymentNotifyResult verifyAndParseWxpay(PaymentNotifyRequest request) {
        return notifyResult;
    }

    @Override
    public PaymentNotifyResult verifyAndParseAlipay(PaymentNotifyRequest request) {
        return notifyResult;
    }

    @Override
    public PaymentQueryResult queryPayment(String outTradeNo, PaymentChannel channel) {
        return queryResult;
    }

    /**
     * {@inheritDoc}
     *
     * <p>模拟实现：默认成功，可用 {@link #failNextRefund} 让下一次失败 ——
     * 「退款被平台拒绝」是必须走得到的分支，它决定了本地状态该不该回滚。
     */
    @Override
    public RefundResult refund(RefundCommand command) {
        refundCalls++;
        lastRefundCommand = command;

        if (nextRefundFails) {
            nextRefundFails = false;
            return RefundResult.fail(refundFailReason);
        }
        return RefundResult.ok(command.getRefundNo(),
                "platform_" + command.getRefundNo(), LocalDateTime.now());
    }

    /**
     * 让下一次退款失败。
     *
     * @param reason 失败原因
     * @return 本对象，便于链式调用
     */
    public FakePaymentGateway failNextRefund(String reason) {
        this.nextRefundFails = true;
        this.refundFailReason = reason;
        return this;
    }

    /** @return 退款方法的累计调用次数 */
    public int refundCalls() {
        return refundCalls;
    }

    /** @return 最后一次退款的参数；从未调用过时为 null */
    public RefundCommand lastRefundCommand() {
        return lastRefundCommand;
    }

    /**
     * 构造一个「验签通过且已支付」的回调结果。
     *
     * <p>刻意不预填单号与金额 —— 它们由测试按用例设定，
     * 预填反而会让人误以为默认值是可用的。
     *
     * @return 回调结果
     */
    public static PaymentNotifyResult paidNotify() {
        PaymentNotifyResult result = new PaymentNotifyResult();
        result.setSuccess(true);
        result.setPaid(true);
        return result;
    }

    /**
     * 构造一个「验签失败」的回调结果。
     *
     * @param errmsg 失败原因
     * @return 回调结果
     */
    public static PaymentNotifyResult invalidNotify(String errmsg) {
        PaymentNotifyResult result = new PaymentNotifyResult();
        result.setSuccess(false);
        result.setErrmsg(errmsg);
        return result;
    }
}
