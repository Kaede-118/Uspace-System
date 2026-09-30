package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.PaymentTarget;
import com.kaede.uspace.product.ProductOrderStatus;
import com.kaede.uspace.product.entity.ProductOrder;
import com.kaede.uspace.product.mapper.ProductMapper;
import com.kaede.uspace.product.mapper.ProductOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 商品的支付目标处理器。
 *
 * <p>收款归模块 8（商品的售卖归 {@code product} 包），所以这个处理器虽然操作
 * {@code biz_product_order} 与 {@code biz_product} 两张表，
 * 代码却住在 {@code order} 包 —— 与 {@code MonthlyCardPaymentTargetHandler}、
 * {@code BookingPaymentTargetHandler} 同理，依赖方向固定为单向的
 * {@code order → product}，不会形成包级循环。
 *
 * <p><b>付款成功后要做的事是「扣库存」</b>：
 * <pre>
 *   ① 购买单 PENDING_PAYMENT → PAID（带状态守卫，幂等第一道）
 *   ② 条件 UPDATE 扣减库存（{@code WHERE stock >= 数量}）
 * </pre>
 * 两步都在 {@code PaymentService#applyNotify} 的同一个事务里。
 *
 * <p><b>为什么扣库存放在这里、而不是由 Service 事后补</b>：
 * 支付回调是唯一知道「这笔钱确实收到了」的地方。
 * 交给别处补，就得维护一套「哪些购买单付过款但没扣库存」的对账逻辑。
 *
 * <p><b>算哪一类累计消费</b>：记 {@link PaidCategory#ORDER} ——
 * 商品是「在店里花钱」，与房间使用费同属一类；
 * 而 {@code CARD} 那一列是月卡的预付卡费，两者口径完全不同。
 */
@Slf4j
@Component
public class ProductPaymentTargetHandler implements PaymentTargetHandler {

    private final ProductOrderMapper orderMapper;
    private final ProductMapper productMapper;

    public ProductPaymentTargetHandler(ProductOrderMapper orderMapper,
                                       ProductMapper productMapper) {
        this.orderMapper = orderMapper;
        this.productMapper = productMapper;
    }

    @Override
    public PaymentTargetType type() {
        return PaymentTargetType.PRODUCT;
    }

    @Override
    public PaidCategory paidCategory() {
        return PaidCategory.ORDER;
    }

    /**
     * {@inheritDoc}
     *
     * <p>商品只受理线上通道。它没有「上传凭证 + 管理员核销」这条降级路径
     * （人工核销接口是订单专用的），放行 {@code QR_UPLOAD} 的话，
     * 发起支付会返回一个没有任何支付参数的「成功」，单子则永远停在待支付 ——
     * 表现为「买了但付不了款」，且线上没有任何报错。
     */
    @Override
    public boolean supportsChannel(PaymentChannel channel) {
        return channel != null && PaymentChannel.isOnline(channel.name());
    }

    /**
     * {@inheritDoc}
     *
     * <p>这里校验的是「发起支付的人是不是购买人」。
     */
    @Override
    public BizResult<PaymentTarget> loadForPay(Long id, Long userId) {
        ProductOrder order = orderMapper.selectById(id);
        if (order == null || !order.getUserId().equals(userId)) {
            return BizResult.fail(ErrorCode.PRODUCT_ORDER_NOT_FOUND);
        }
        if (!ProductOrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
            return BizResult.fail(ErrorCode.PRODUCT_STATUS_INVALID, "该商品订单当前不需要支付");
        }
        return BizResult.ok(toTarget(order));
    }

    @Override
    public PaymentTarget loadByOutTradeNo(String outTradeNo) {
        return toTarget(orderMapper.selectByOrderNo(outTradeNo));
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>顺序是「先改状态、后扣库存」，反过来不行</b>：
     * 先扣库存再改状态的话，状态那一跳失败时整笔回滚、平台重推，
     * 而库存已经被扣过一次（重推时再扣一次）—— 库存凭空少掉。
     * 反过来，状态改成功而扣库存失败时，重推会被状态守卫挡住、
     * 不会重复扣 —— 少扣是可见的（error 日志里有单号），多扣是隐形的。
     *
     * <p><b>扣减失败绝不抛异常</b>：那会让支付平台不断重推一笔永远处理不了的通知。
     * 记一条 error 日志让它进入人工处理 —— 钱已经收了，这单不能因为没货
     * 就从账上消失，该做的是补货或退款，那是人的决定。
     */
    @Override
    public boolean markPaid(PaymentTarget target, PaymentChannel channel,
                            String transactionNo, LocalDateTime paidAt, Long confirmedBy) {
        int affected = orderMapper.markPaid(target.getId(), channel.name(), transactionNo, paidAt);
        if (affected == 0) {
            log.info("[支付] 商品购买单未被本次回调改动，视为已处理 orderNo={}",
                    target.getOutTradeNo());
            return false;
        }

        ProductOrder order = orderMapper.selectById(target.getId());
        if (order == null) {
            // 理论上到不了这里：target 就是刚从这张表读出来的。
            // 真出现说明有人在回调处理中途删了数据，必须让人看见
            throw new IllegalStateException(
                    "商品购买单在回调处理过程中消失：" + target.getOutTradeNo());
        }

        int deducted = productMapper.deductStock(order.getProductId(), order.getQuantity());
        if (deducted == 0) {
            // 两种可能：库存被别的订单抢先扣光了，或商品已被删除。
            // 两种都只能人工处理，且都不能抛异常（见方法注释）
            log.error("[支付] ⚠️ 商品库存扣减失败，需人工处理：orderNo={} productId={} 数量={} 金额={}",
                    target.getOutTradeNo(), order.getProductId(), order.getQuantity(),
                    target.getAmount());
        } else {
            log.info("[支付] 商品付款成功并已扣库存 orderNo={} 商品={} ×{} 金额={} 通道={}",
                    target.getOutTradeNo(), order.getProductName(), order.getQuantity(),
                    target.getAmount(), channel);
        }
        return true;
    }

    /**
     * 把购买单实体翻译成统一的支付目标。
     *
     * <p>金额取 {@code amount} —— 下单时按当时的单价算好的快照，
     * 与用户看到的应付金额一致。事后调价不影响这一笔。
     *
     * @param order 购买单实体，可为 null
     * @return 支付目标；入参为 null 时返回 null
     */
    private static PaymentTarget toTarget(ProductOrder order) {
        if (order == null) {
            return null;
        }
        PaymentTarget target = new PaymentTarget();
        target.setType(PaymentTargetType.PRODUCT);
        target.setId(order.getId());
        target.setUserId(order.getUserId());
        target.setAmount(order.getAmount());
        target.setOutTradeNo(order.getOrderNo());
        target.setStatus(order.getStatus());
        target.setPaymentMethod(order.getPaymentMethod());
        target.setPaymentNo(order.getPaymentNo());
        target.setPaidAt(order.getPaidAt());
        target.setDescription("共享娱乐空间商品：" + order.getProductName()
                + " ×" + order.getQuantity());
        return target;
    }
}
