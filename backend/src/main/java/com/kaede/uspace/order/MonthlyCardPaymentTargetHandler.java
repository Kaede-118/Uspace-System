package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.PaymentTarget;
import com.kaede.uspace.order.entity.PaymentProof;
import com.kaede.uspace.promotion.CardOrderStatus;
import com.kaede.uspace.promotion.MonthlyCardNo;
import com.kaede.uspace.promotion.MonthlyCardStatus;
import com.kaede.uspace.promotion.PromotionProperties;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import com.kaede.uspace.promotion.entity.MonthlyCardOrder;
import com.kaede.uspace.promotion.mapper.MonthlyCardMapper;
import com.kaede.uspace.promotion.mapper.MonthlyCardOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 月卡的支付目标处理器。
 *
 * <p>收款归模块 8（月卡的售卖归模块 9），所以这个处理器虽然操作
 * {@code biz_monthly_card_order} 与 {@code biz_monthly_card} 两张表，
 * 代码却住在 {@code order} 包 —— 与 {@code BookingPaymentTargetHandler} 同理，
 * 依赖方向固定为单向的 {@code order → promotion}，不会形成包级循环。
 *
 * <p><b>付款成功后要做的事比订单多一跳</b>：订单与包场付款成功只是把本表状态改掉，
 * 而月卡付款成功要<b>生成一份资产</b>，于是「改状态」与「发卡」必须原子完成：
 * <pre>
 *   ① 购买单 PENDING_PAYMENT → PAID（带状态守卫，幂等第一道）
 *   ② 往月卡表插一张 ACTIVE 卡（生效日 = 支付当日）
 * </pre>
 * 少了原子性，就会出现「钱收了、单子转已支付、卡却没发」—— 用户付了钱什么也没拿到。
 * 两步都在 {@code PaymentService#applyNotify} 的同一个事务里，
 * 任一步失败整体回滚、平台重推。
 *
 * <p><b>为什么卡的生成也放在这里、而不是由 Service 事后补</b>：
 * 支付回调是唯一知道「这笔钱确实收到了」的地方。
 * 交给别处补，就得维护一套「哪些购买单付过款但没发卡」的对账逻辑。
 */
@Slf4j
@Component
public class MonthlyCardPaymentTargetHandler implements PaymentTargetHandler {

    private final MonthlyCardOrderMapper orderMapper;
    private final MonthlyCardMapper cardMapper;
    private final PromotionProperties properties;

    public MonthlyCardPaymentTargetHandler(MonthlyCardOrderMapper orderMapper,
                                           MonthlyCardMapper cardMapper,
                                           PromotionProperties properties) {
        this.orderMapper = orderMapper;
        this.cardMapper = cardMapper;
        this.properties = properties;
    }

    @Override
    public PaymentTargetType type() {
        return PaymentTargetType.MONTHLY_CARD;
    }

    @Override
    public PaidCategory paidCategory() {
        return PaidCategory.CARD;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>月卡等复核，不即交付</b> —— 它交付的是一张 30 天的卡，而权益一旦
     * 发出就收不回来（见 {@link #markPaid}：付款成功会在同一个事务里插入一张
     * {@code ACTIVE} 的月卡）。一次误放行等于白送一个月的免费时长，
     * 所以宁可让管理员扫一眼截图。
     *
     * <p>还要注意一点：月卡是「一人一卡」，未关闭的旧购买单会把这个用户自己
     * 卡死（{@code CARD_PENDING_PAYMENT_EXISTS}）。所以这里的「等复核」
     * 是有代价的 —— 用户提交凭证之后、管理员确认之前，他买不了第二张卡。
     * 这是可接受的：同一个人本来也不该同时买两张。
     */
    @Override
    public boolean deliverOnSubmit() {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>什么都不做，而且这是对的</b>：月卡 {@link #deliverOnSubmit()} 返回 false ——
     * 用户在提交凭证那一刻并没有拿到卡（卡是管理员复核通过时才插进
     * {@code biz_monthly_card} 的，见 {@link #markPaid}）。所以驳回时没有
     * 「已经发出去的卡」要收回，目标本来就还停在待支付上。
     *
     * <p>⚠️ 这与订单、商品形成对照：那两样是「提交即交付」，驳回时
     * <b>必须</b>把交付退回去，否则用户既不能再付、也不能重交凭证。
     *
     * <p>返回 {@code false} 表示「没退任何东西」，调用方据此跳过冲减累计消费 ——
     * 月卡的卡费本来也记在 {@code card_paid} 而不是 {@code order_paid} 上，
     * 而那一列只在复核通过时才累加。
     */
    @Override
    public boolean revertDelivery(PaymentTarget target, PaymentProof proof) {
        log.debug("[支付] 月卡驳回无需冲销（未交付过）orderNo={}", target.getOutTradeNo());
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>这里校验的是「发起支付的人是不是购买人」—— 月卡绑定本人使用，
     * 别人不能替他付款。
     */
    @Override
    public BizResult<PaymentTarget> loadForPay(Long id, Long userId) {
        MonthlyCardOrder order = orderMapper.selectById(id);
        if (order == null || !order.getUserId().equals(userId)) {
            return BizResult.fail(ErrorCode.CARD_NOT_FOUND);
        }
        if (!CardOrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
            return BizResult.fail(ErrorCode.BUSINESS_REJECTED, "该月卡购买单当前不需要支付");
        }
        return BizResult.ok(toTarget(order));
    }

    /**
     * {@inheritDoc}
     *
     * <p>归属校验仍认购买人 —— 月卡绑定本人使用，别人不能替他付款，
     * 自然也不能替他提交凭证（那等于替他伪造了付款证明）。
     */
    @Override
    public BizResult<PaymentTarget> loadForProof(Long id, Long userId) {
        MonthlyCardOrder order = orderMapper.selectById(id);
        if (order == null || !order.getUserId().equals(userId)) {
            return BizResult.fail(ErrorCode.CARD_NOT_FOUND);
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
     * <p>第一跳用状态守卫改单据状态，返回 0 即说明已被并发处理过
     * （支付平台会重推通知），此时<b>绝不能再去发卡</b> —— 否则用户付一次钱拿到两张卡。
     * 第二跳把购买单上的价格快照原样写进卡，<b>不重新读配置</b>：
     * 用户看到的是下单时的价，付款过程中恰好调价也不该改变这一单的金额。
     */
    @Override
    public boolean markPaid(PaymentTarget target, PaymentChannel channel,
                            String transactionNo, LocalDateTime paidAt, Long confirmedBy) {
        int affected = orderMapper.markPaid(target.getId(), channel.name(), transactionNo, paidAt);
        if (affected == 0) {
            log.info("[支付] 月卡购买单未被本次回调改动，视为已处理 cardOrderNo={}",
                    target.getOutTradeNo());
            return false;
        }

        MonthlyCardOrder order = orderMapper.selectById(target.getId());
        if (order == null) {
            // 理论上到不了这里：target 就是刚从这张表读出来的。
            // 真出现说明有人在回调处理中途删了数据，必须让人看见
            throw new IllegalStateException(
                    "月卡购买单在回调处理过程中消失：" + target.getOutTradeNo());
        }

        LocalDateTime paidTime = paidAt == null ? LocalDateTime.now() : paidAt;
        LocalDate startDate = paidTime.toLocalDate();
        // 含首尾：30 天 → end = start + 29
        LocalDate endDate = startDate.plusDays(properties.getMonthlyCard().getValidDays() - 1L);

        MonthlyCard card = new MonthlyCard();
        card.setCardNo(MonthlyCardNo.generate());
        card.setUserId(order.getUserId());
        card.setCardType(order.getCardType());
        card.setPrice(order.getPrice());
        card.setStartDate(startDate);
        card.setEndDate(endDate);
        card.setStatus(MonthlyCardStatus.ACTIVE.name());
        card.setPaymentMethod(channel.name());
        card.setPayOrderNo(target.getOutTradeNo());
        card.setPaidAt(paidTime);
        cardMapper.insert(card);

        log.info("[支付] 月卡付款成功并已发卡 cardOrderNo={} 卡号={} 有效期 {} ~ {} 金额={} 通道={}",
                target.getOutTradeNo(), card.getCardNo(), startDate, endDate,
                order.getPrice(), channel);
        return true;
    }

    /**
     * 把购买单实体翻译成统一的支付目标。
     *
     * <p>金额取 {@code price} —— 下单时的快照，与用户看到的一致。
     *
     * @param order 购买单实体，可为 null
     * @return 支付目标；入参为 null 时返回 null
     */
    private static PaymentTarget toTarget(MonthlyCardOrder order) {
        if (order == null) {
            return null;
        }
        PaymentTarget target = new PaymentTarget();
        target.setType(PaymentTargetType.MONTHLY_CARD);
        target.setId(order.getId());
        target.setUserId(order.getUserId());
        target.setAmount(order.getPrice());
        target.setOutTradeNo(order.getOrderNo());
        target.setStatus(order.getStatus());
        target.setPaymentMethod(order.getPaymentMethod());
        target.setPaymentNo(order.getPaymentNo());
        target.setPaidAt(order.getPaidAt());
        target.setDescription("共享娱乐空间月卡");
        return target;
    }
}
