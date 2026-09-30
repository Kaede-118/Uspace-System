package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.PaymentTarget;
import com.kaede.uspace.space.BookingStatus;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.mapper.BookingMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 包场的支付目标处理器。
 *
 * <p>包场收款归模块 8（模块 3 只做排期），所以这个处理器虽然操作
 * {@code biz_booking} 表，代码却住在 {@code order} 包 ——
 * 依赖方向固定为 {@code order → space}，不会形成包级循环。
 *
 * <p><b>付款成功后要额外做一件事：生成邀请令牌。</b>令牌是包场排他性的载体 ——
 * 包场人把它分享给朋友，朋友凭链接被认作「被邀请者」，在包场时段内能下单拿到密码，
 * 而其他人拿不到。所以令牌生成与「转已付款」必须原子完成：
 * 先转已付款再写令牌而写令牌失败，就会留下一个「已生效但没人拿得到邀请链接」的包场。
 *
 * @see PaymentTargetHandler 接口上写明了这一层为什么存在
 */
@Slf4j
@Component
public class BookingPaymentTargetHandler implements PaymentTargetHandler {

    /**
     * 邀请令牌撞唯一索引时的最大重试次数。
     *
     * <p>令牌是 32 字节随机数的 Base64，撞一次的概率约 2^-256 —— 重试纯属防御。
     * 真撞到三次说明随机源或索引本身出了问题，那时抛异常让回调失败、
     * 平台继续重推，比静默吞掉要好。
     */
    private static final int MAX_TOKEN_ATTEMPTS = 3;

    private final BookingMapper bookingMapper;
    private final InviteTokenService inviteTokenService;

    public BookingPaymentTargetHandler(BookingMapper bookingMapper,
                                       InviteTokenService inviteTokenService) {
        this.bookingMapper = bookingMapper;
        this.inviteTokenService = inviteTokenService;
    }

    @Override
    public PaymentTargetType type() {
        return PaymentTargetType.BOOKING;
    }

    @Override
    public PaidCategory paidCategory() {
        return PaidCategory.ORDER;
    }

    /**
     * {@inheritDoc}
     *
     * <p>这里校验的是「发起支付的人是不是包场人」—— 包场由管理员排期时
     * 就指定了包场人，只有他能付这笔钱。被邀请者不需要付款（包场费已预付）。
     */
    @Override
    public BizResult<PaymentTarget> loadForPay(Long id, Long userId) {
        Booking booking = bookingMapper.selectById(id);
        if (booking == null || !booking.getHostUserId().equals(userId)) {
            return BizResult.fail(ErrorCode.BOOKING_NOT_FOUND);
        }
        if (!BookingStatus.PENDING_PAYMENT.name().equals(booking.getStatus())) {
            return BizResult.fail(ErrorCode.BUSINESS_REJECTED, "该包场当前不需要支付");
        }
        return BizResult.ok(toTarget(booking));
    }

    @Override
    public PaymentTarget loadByOutTradeNo(String outTradeNo) {
        return toTarget(bookingMapper.selectByBookingNo(outTradeNo));
    }

    /**
     * {@inheritDoc}
     *
     * <p>与订单不同的是：这里要<b>连状态带邀请令牌一起写</b>，且撞唯一索引时
     * 重新生成令牌重试。重试的是整个 UPDATE 而不只是写令牌 ——
     * 因为状态守卫（{@code AND status = 'PENDING_PAYMENT'}）与令牌写入
     * 在同一条 SQL 里，分成两步就失去了原子性。
     */
    @Override
    public boolean markPaid(PaymentTarget target, PaymentChannel channel,
                            String transactionNo, LocalDateTime paidAt, Long confirmedBy) {
        for (int attempt = 1; attempt <= MAX_TOKEN_ATTEMPTS; attempt++) {
            String inviteToken = inviteTokenService.generate();
            try {
                int affected = bookingMapper.markPaid(target.getId(), channel.name(),
                        transactionNo, paidAt, inviteToken);
                if (affected == 0) {
                    log.info("[支付] 包场状态未被本次回调改动，视为已处理 bookingNo={}",
                            target.getOutTradeNo());
                    return false;
                }
                log.info("[支付] 包场付款成功 bookingNo={} 通道={} 交易号={} 金额={}（已生成邀请令牌）",
                        target.getOutTradeNo(), channel, transactionNo, target.getAmount());
                return true;
            } catch (DuplicateKeyException e) {
                log.warn("[支付] 邀请令牌撞唯一索引，重试第 {}/{} 次 bookingNo={}",
                        attempt, MAX_TOKEN_ATTEMPTS, target.getOutTradeNo());
            }
        }
        // 抛异常让回调事务回滚、平台继续重推 —— 这是「钱收了但记录不了」，必须让人看见
        throw new IllegalStateException(
                "邀请令牌连续 " + MAX_TOKEN_ATTEMPTS + " 次冲突，包场付款未能落库：" + target.getOutTradeNo());
    }

    /**
     * 把包场实体翻译成统一的支付目标。
     *
     * <p>金额取 {@code price} —— 包场是一口价预付，不按分钟计。
     *
     * @param booking 包场实体，可为 null
     * @return 支付目标；入参为 null 时返回 null
     */
    private static PaymentTarget toTarget(Booking booking) {
        if (booking == null) {
            return null;
        }
        PaymentTarget target = new PaymentTarget();
        target.setType(PaymentTargetType.BOOKING);
        target.setId(booking.getId());
        target.setUserId(booking.getHostUserId());
        target.setAmount(booking.getPrice());
        target.setOutTradeNo(booking.getBookingNo());
        target.setStatus(booking.getStatus());
        target.setPaymentMethod(booking.getPaymentMethod());
        target.setPaymentNo(booking.getPaymentNo());
        target.setPaidAt(booking.getPaidAt());
        target.setDescription("共享娱乐空间包场费");
        return target;
    }
}
