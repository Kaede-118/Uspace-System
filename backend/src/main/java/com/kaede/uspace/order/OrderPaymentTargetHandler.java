package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.PaymentTarget;
import com.kaede.uspace.order.entity.Order;
import com.kaede.uspace.order.entity.PaymentProof;
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

    @Override
    public PaidCategory paidCategory() {
        return PaidCategory.ORDER;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>订单是「提交即交付」的</b>：用户传了付款截图就算钱收到了 ——
     * 订单当场转 {@code PAID}，欠费拦截随之解除（{@code selectUnsettledByUser}
     * 只认 {@code IN_USE} 与 {@code PENDING_PAYMENT}，已支付的单子不再拦他），
     * 他紧接着就能再开一单进店。
     *
     * <p>为什么订单可以这么激进：它买的是一段<b>已经发生过的</b>服务
     * （用户已经玩过了，才结账出账单），交付物在提交之前就已经消耗掉了，
     * 没有「发出去收不回来」的资产可言。这与包场、月卡正好相反。
     *
     * <p>代价是复核不通过时<b>不能自动回退</b>，只能人工处置 ——
     * 后台的 {@code AdminProofVo.delivered} 会把这一点标出来。
     */
    @Override
    public boolean deliverOnSubmit() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>订单买的是一段<b>已经发生过</b>的服务，交付物在提交凭证之前就被消耗掉了，
     * 所以「退回去」退的不是货而是<b>状态</b>：把这笔单子标成
     * {@link OrderStatus#REJECTED 凭证未通过}。
     *
     * <p>⚠️ <b>刻意不退成 {@code PENDING_PAYMENT}</b>：那个状态的处置动作是
     * 「去支付」，而这里要用户做的是「重新上传一张截图」。合并成一个状态的话，
     * 用户看到「待支付 ¥8.00」很可能再付一次钱，而两个动作在页面上的入口
     * 长得一模一样。<b>状态分开，提示才能分开。</b>
     *
     * <p>⚠️ <b>累计消费的冲减不在这里</b>，在 {@code PaymentService#revertByProof}：
     * 记账口径（{@code paidCategory}）是那个类说了算的，两处各减一次就会减成两倍，
     * 而表现只是「用户的累计消费比实际少了一笔」，不会有任何报错。
     */
    @Override
    public boolean revertDelivery(PaymentTarget target, PaymentProof proof) {
        if (orderMapper.markRejected(target.getId()) == 0) {
            // 状态守卫没放行：另一个管理员已经驳回过，或这单又被别处改过。
            // 返回 false，调用方据此跳过减累计消费那一步
            log.info("[支付] 订单标记为凭证未通过未生效，视为已处理 orderNo={}",
                    target.getOutTradeNo());
            return false;
        }
        log.info("[支付] 订单已标记为凭证未通过 orderNo={} 用户={} 金额={}",
                target.getOutTradeNo(), proof.getUserId(), proof.getAmount());
        return true;
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

    /**
     * {@inheritDoc}
     *
     * <p>与 {@link #loadForPay} 只差一处：<b>不校验订单状态</b>。
     * 「提交凭证时订单该处于什么状态」由 {@code PaymentProofService} 判断 ——
     * 它要区分五种情形（待支付、已支付 + 待复核、已支付 + 已核对……），
     * 各有各的去向；而这里只负责「找到它、并确认它是你的」。
     *
     * <p>这个区别对本处理器尤其要紧：订单提交即交付，所以用户提交完的那一刻
     * 订单就已经是 {@code PAID} 了。他若想补一个流水号或换张更清楚的图，
     * 走 {@code loadForPay} 会被「该订单当前不需要支付」直接拒掉 ——
     * 而那是正常操作，不是异常。
     */
    @Override
    public BizResult<PaymentTarget> loadForProof(Long id, Long userId) {
        Order order = orderMapper.selectById(id);
        if (order == null || !order.getUserId().equals(userId)) {
            return BizResult.fail(ErrorCode.ORDER_NOT_FOUND);
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
