package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.PaymentTarget;
import com.kaede.uspace.space.BookingParticipantRole;
import com.kaede.uspace.space.BookingStatus;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.entity.BookingParticipant;
import com.kaede.uspace.space.mapper.BookingMapper;
import com.kaede.uspace.space.mapper.BookingParticipantMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

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
    private final BookingParticipantMapper participantMapper;

    public BookingPaymentTargetHandler(BookingMapper bookingMapper,
                                       InviteTokenService inviteTokenService,
                                       BookingParticipantMapper participantMapper) {
        this.bookingMapper = bookingMapper;
        this.inviteTokenService = inviteTokenService;
        this.participantMapper = participantMapper;
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
     * <p><b>包场等复核，不即交付</b> —— 它交付的是一份<b>排他性</b>：
     * 付款成功会生成邀请令牌，此后这场时段只有持令牌的人进得来
     * （见 {@link #markPaid}）。令牌一旦发出去就收不回来，而它同时意味着
     * 「这个时段别人不能来玩了」—— 一次误放行的代价是一场包场被搅局，
     * 所以宁可让管理员扫一眼截图。
     *
     * <p>这与订单正好相反：订单买的是一段已经发生过的服务，交付物早就消耗掉了；
     * 包场买的是一份<b>还未行使的权利</b>，发出去就作数。
     */
    @Override
    public boolean deliverOnSubmit() {
        return false;
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

    /**
     * {@inheritDoc}
     *
     * <p>归属校验仍认 {@code hostUserId} —— 包场费只有包场人付，
     * 被邀请者不需要付款（那是包场人预付掉的）。状态则不校验：
     * 提交凭证时包场应当处于什么状态，由 {@code PaymentProofService} 判断。
     */
    @Override
    public BizResult<PaymentTarget> loadForProof(Long id, Long userId) {
        Booking booking = bookingMapper.selectById(id);
        if (booking == null || !booking.getHostUserId().equals(userId)) {
            return BizResult.fail(ErrorCode.BOOKING_NOT_FOUND);
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
     *
     * <p>付款成功之后还要多写一行：<b>把包场人记进参与者表</b>（{@code HOST} 行）。
     * 参与者表是「谁在这场包场里」的权威来源，被邀请者的准入判定要从它出发；
     * 包场人虽然另有 {@code host_user_id} 可认，但让他在表里也占一行，
     * 名单与「我参与的」列表才能把发起人一并列出来。
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
                // 放在 affected > 0 之后：重复回调在上一行就已经 return 了，
                // 走不到这里，所以不必额外判断「是不是第一次付成功」
                ensureHostParticipant(target.getId(), target.getUserId(), paidAt);
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
     * 把包场人写进参与者表（{@code HOST} 行）。
     *
     * <p>时机是付款成功的那一刻，与「转已付款 + 生成邀请令牌」同一个事务 ——
     * 那是包场从「安排」变成「事实」的唯一时刻。所以待付款的包场没有这一行，
     * 「我创建的包场」列表也因此仍按 {@code biz_booking.host_user_id} 查。
     *
     * <p><b>本方法自己吞掉唯一键冲突，绝不让它冒泡。</b>外层那个
     * {@code catch (DuplicateKeyException)} 是给「邀请令牌撞唯一索引 → 换一个重试」
     * 用的：异常一旦冒到那里，整条 UPDATE 会重试一遍，而那时状态已经不是
     * {@code PENDING_PAYMENT} 了 —— {@code affected} 为 0，整笔付款被当作
     * 「已处理」返回，<b>而 HOST 行永远没插上，且不报任何错</b>。
     * 撞键只可能来自重复回调（第一次已经插过），那时什么都不用做。
     *
     * @param bookingId  包场 ID
     * @param hostUserId 包场人用户 ID
     * @param joinedAt   加入时刻，取付款时刻；为 null 时回落到当前时刻
     */
    private void ensureHostParticipant(Long bookingId, Long hostUserId, LocalDateTime joinedAt) {
        BookingParticipant row = new BookingParticipant();
        row.setBookingId(bookingId);
        row.setUserId(hostUserId);
        row.setRole(BookingParticipantRole.HOST.name());
        // paidAt 正常一定非空（PaymentService 已兜过底），这里再兜一次是因为
        // joined_at 是 NOT NULL 列：真传了 null，插入会失败、整个回调事务回滚，
        // 而平台会一直重推这笔已经收到钱的支付 —— 比记错一个时刻严重得多
        row.setJoinedAt(joinedAt == null
                ? LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                : joinedAt);
        try {
            participantMapper.insert(row);
        } catch (DuplicateKeyException e) {
            log.info("[支付] 包场人已在参与者表中，跳过 bookingId={} userId={}", bookingId, hostUserId);
        }
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
