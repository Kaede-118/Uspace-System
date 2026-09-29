package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.PaymentTarget;
import com.kaede.uspace.order.entity.Order;
import com.kaede.uspace.order.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 普通订单的支付目标处理器。
 *
 * <p>把 {@code biz_order} 翻译成统一的 {@link PaymentTarget}，
 * 并把「标记已支付」落到订单表的对应字段上。
 *
 * @see PaymentTargetHandler 接口上写明了这一层为什么存在
 */
@Slf4j
@Component
public class OrderPaymentTargetHandler implements PaymentTargetHandler {

    /** 商品描述里的时间格式。只到分钟 —— 账单上不需要秒 */
    private static final DateTimeFormatter DESC_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final OrderMapper orderMapper;

    public OrderPaymentTargetHandler(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    public PaymentTargetType type() {
        return PaymentTargetType.ORDER;
    }

    /**
     * {@inheritDoc}
     *
     * <p>三件事依次校验：订单存在、属于本人、当前处于待支付状态。
     * <b>前两条合并成同一个 404</b>：区分「不存在」与「不是你的」等于
     * 让人能靠错误码枚举出系统里有哪些订单号。
     */
    @Override
    public BizResult<PaymentTarget> loadForPay(Long id, Long userId) {
        Order order = orderMapper.selectById(id);
        if (order == null || !order.getUserId().equals(userId)) {
            return BizResult.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!OrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
            return BizResult.fail(ErrorCode.ORDER_STATUS_INVALID, "该订单当前不需要支付");
        }
        return BizResult.ok(toTarget(order));
    }

    @Override
    public PaymentTarget loadByOutTradeNo(String outTradeNo) {
        return toTarget(orderMapper.selectByOrderNo(outTradeNo));
    }

    @Override
    public boolean markPaid(PaymentTarget target, PaymentChannel channel,
                            String transactionNo, LocalDateTime paidAt, Long confirmedBy) {
        int affected = orderMapper.markPaid(target.getId(), channel.name(),
                transactionNo, paidAt, confirmedBy);
        if (affected == 0) {
            // 并发下已被另一条回调处理，或状态已被改动。两种情况都当作
            // 「不必再记账」处理，由调用方返回幂等成功
            log.info("[支付] 订单状态未被本次回调改动，视为已处理 orderNo={}", target.getOutTradeNo());
            return false;
        }
        log.info("[支付] 订单付款成功 orderNo={} 通道={} 交易号={} 金额={} 核销人={}",
                target.getOutTradeNo(), channel, transactionNo, target.getAmount(),
                confirmedBy == null ? "系统自动" : confirmedBy);
        return true;
    }

    /**
     * 把订单实体翻译成统一的支付目标。
     *
     * <p>金额取 {@code payableAmount} 而不是 {@code totalAmount}：
     * 两者当前恒等，但前者是为「将来可能有优惠券、押金这类不进入计费规则的费用」
     * 预留的独立列，收款认准它才不会在加新费用时改错地方。
     *
     * @param order 订单实体，可为 null
     * @return 支付目标；入参为 null 时返回 null
     */
    private static PaymentTarget toTarget(Order order) {
        if (order == null) {
            return null;
        }
        PaymentTarget target = new PaymentTarget();
        target.setType(PaymentTargetType.ORDER);
        target.setId(order.getId());
        target.setUserId(order.getUserId());
        target.setAmount(order.getPayableAmount());
        target.setOutTradeNo(order.getOrderNo());
        target.setStatus(order.getStatus());
        target.setPaymentMethod(order.getPaymentMethod());
        target.setPaymentNo(order.getPaymentNo());
        target.setPaidAt(order.getPaidAt());
        target.setDescription("共享娱乐空间使用费"
                + (order.getStartTime() == null ? "" : "（" + DESC_FORMATTER.format(order.getStartTime()) + "）"));
        return target;
    }
}
