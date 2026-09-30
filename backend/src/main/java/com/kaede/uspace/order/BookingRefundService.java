package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.RefundCommand;
import com.kaede.uspace.order.dto.RefundResult;
import com.kaede.uspace.space.BookingStatus;
import com.kaede.uspace.space.dto.BookingVo;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.mapper.BookingMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 撤销已付款的包场并退款（模块 8 —— 钱的事归这里）。
 *
 * <p><b>为什么这个类住在 order 包而不是 space 包</b>：它要做两件事 ——
 * 改包场的状态（模块 3）与调支付平台退款（模块 8）。而包级依赖是单向的
 * {@code order → space}，反过来会把 {@code space} 拽进支付模块里。
 * 这与 {@code BookingPaymentTargetHandler}（收款处理器住 order 包、
 * 却操作 {@code biz_booking} 表）是同一个模式的镜像：<b>钱进钱出都归 order</b>。
 *
 * <p><b>两种退款方式</b>（见 {@link RefundMode}）：
 * <ul>
 *   <li>{@code MANUAL} —— 管理员线下把钱转给顾客，这里只登记这件事</li>
 *   <li>{@code ONLINE} —— 调 {@link PaymentGateway#refund} 原路退回</li>
 * </ul>
 *
 * <p><b>顺序刻意是「先占位、再退钱」</b>：
 * <ol>
 *   <li>先用 {@code markRefunded} 把状态从 {@code PAID} 原子地翻成 {@code REFUNDED}
 *       （SQL 里带 {@code WHERE status = 'PAID'}）—— 两个人同时点，只有一个能翻成功</li>
 *   <li>再去调支付平台退款</li>
 *   <li>平台失败就 {@code revertRefund} 把占位撤掉，状态退回 {@code PAID}</li>
 * </ol>
 * 反过来（先退钱再改状态）看着更顺，但有个致命窗口：两个管理员同时点，
 * 两笔退款请求都会到平台、<b>钱退两次</b>，而本地只记一次。
 * 先占位则第二个请求根本到不了平台。
 *
 * <p>代价是有一个几百毫秒的窗口，状态显示「已退款」而钱还没到 ——
 * 这一步失败会当场退回并报错，进程中途挂掉则留下一条
 * {@code refund_no} 为空的已退款记录（肉眼可辨、日志里有原因）。
 * <b>两害相权：宁可退不成，也不能记成退成了。</b>
 */
@Slf4j
@Service
public class BookingRefundService {

    private final BookingMapper bookingMapper;
    private final PaymentGateway paymentGateway;

    public BookingRefundService(BookingMapper bookingMapper, PaymentGateway paymentGateway) {
        this.bookingMapper = bookingMapper;
        this.paymentGateway = paymentGateway;
    }

    /**
     * 撤销一场已付款的包场并退款。
     *
     * <p>退款金额<b>固定为全额</b>（{@code price}）：包场是一口价，
     * 撤销就是把这一场整个作废。字段 {@code refund_amount} 照样落库，
     * 将来做部分退款时不必改表结构。
     *
     * @param bookingId  包场 ID
     * @param refundMode 退款方式，取值见 {@link RefundMode}
     * @param adminId    操作的管理员用户 ID
     * @return 成功时返回撤销后的包场视图
     */
    public BizResult<BookingVo> revoke(Long bookingId, String refundMode, Long adminId) {
        if (!RefundMode.isValid(refundMode)) {
            return BizResult.fail(ErrorCode.PARAM_INVALID, "退款方式取值不合法");
        }

        Booking booking = bookingMapper.selectById(bookingId);
        if (booking == null) {
            return BizResult.fail(ErrorCode.BOOKING_NOT_FOUND);
        }
        if (!BookingStatus.PAID.name().equals(booking.getStatus())) {
            // 待付款的走「取消」，已结束的不在这个接口的语义里。
            // 也包括「刚刚被另一个管理员撤掉了」—— 那正是我们要挡住的
            return BizResult.fail(ErrorCode.BOOKING_NOT_REFUNDABLE);
        }

        boolean online = RefundMode.ONLINE.name().equals(refundMode);
        PaymentChannel channel = null;
        String refundNo = null;
        if (online) {
            channel = onlineChannelOf(booking);
            if (channel == null) {
                return BizResult.fail(ErrorCode.BOOKING_NOT_REFUNDABLE,
                        "这场包场不是走线上通道收的款（或没有平台交易号），请改选「人工退款」");
            }
            // 退款单号在占位之前就算好：它是确定性的（由包场单号派生），
            // 于是占位那一条 SQL 就能把它一起写下去，不必等平台返回后再补一次
            refundNo = refundNoOf(booking);
        }

        LocalDateTime now = LocalDateTime.now();

        // 第一步：占位。这一步同时是并发守卫与幂等守卫
        //
        // ⚠️ 退款单号在这里就写进去，<b>不要等平台返回后再补写一次</b> ——
        // 补写用的是同一条带 `WHERE status = 'PAID'` 的 SQL，而那时状态已经是
        // REFUNDED 了，会被自己的守卫挡下（affected = 0），于是钱退了、
        // 退款单号却是空的，且不报任何错。这个坑真踩过一次（契约核对脚本逮到的）
        int affected = bookingMapper.markRefunded(bookingId, refundMode, booking.getPrice(),
                now, adminId, refundNo);
        if (affected == 0) {
            log.info("[退款] 包场 {} 已不是可退款状态，本次撤销未生效（可能已被他人撤销）",
                    booking.getBookingNo());
            return BizResult.fail(ErrorCode.BOOKING_NOT_REFUNDABLE);
        }

        // 第二步：线上退款。失败就把占位撤掉（连同刚写进去的退款单号一起清掉）
        if (online) {
            RefundCommand command = new RefundCommand();
            command.setOutTradeNo(booking.getBookingNo());
            command.setRefundNo(refundNo);
            command.setAmount(booking.getPrice());
            command.setTotalAmount(booking.getPrice());
            command.setChannel(channel);
            command.setReason("管理员撤销包场");

            RefundResult result = paymentGateway.refund(command);
            if (!result.isSuccess()) {
                int reverted = bookingMapper.revertRefund(bookingId);
                log.error("[退款] 包场 {} 原路退回失败，已撤销退款状态回滚（回滚行数={}）：{}",
                        booking.getBookingNo(), reverted, result.getErrmsg());
                return BizResult.fail(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                        "原路退回失败：" + result.getErrmsg() + "。状态已回滚，可稍后重试或改走人工退款。");
            }
            log.info("[退款] 包场 {} 原路退回成功 退款单号={} 金额={} 元",
                    booking.getBookingNo(), refundNo, booking.getPrice());
        } else {
            log.info("[退款] 包场 {} 人工退款已登记 金额={} 元 操作人={}",
                    booking.getBookingNo(), booking.getPrice(), adminId);
        }

        return BizResult.ok(BookingVo.from(bookingMapper.selectById(bookingId)));
    }

    /**
     * 取这笔包场当初付款用的线上通道；不是线上收款（或没有交易号）时返回 null。
     *
     * <p>两种情况都退不了：人工核销（{@code QR_UPLOAD}）的钱不在支付平台上，
     * 没有可退的流水；{@code payment_no} 为空则说明当时压根没拿到平台交易号。
     *
     * @param booking 包场记录
     * @return 线上通道；退不了时返回 null
     */
    private PaymentChannel onlineChannelOf(Booking booking) {
        if (booking.getPaymentMethod() == null || booking.getPaymentNo() == null) {
            return null;
        }
        if (!PaymentChannel.isValid(booking.getPaymentMethod())) {
            return null;
        }
        PaymentChannel channel = PaymentChannel.valueOf(booking.getPaymentMethod());
        return PaymentChannel.isOnline(channel.name()) ? channel : null;
    }

    /**
     * 生成商户退款单号。
     *
     * <p><b>刻意做成确定性的</b>（由包场单号派生出 {@code RF...}），而不是随机生成：
     * 退款失败后管理员会重试，而支付平台按退款单号做幂等 ——
     * 单号稳定，重试才落在「同一笔退款」上，而不是变成第二笔。
     * 随机单号会让每次重试都成为一笔新的退款请求，那正是我们要防的事。
     *
     * <p>将来支持部分退款时，这里要带上退款批次（如 {@code RF...-1}），
     * 否则两笔部分退款会被平台当成同一笔。
     *
     * @param booking 包场记录
     * @return 商户退款单号
     */
    private String refundNoOf(Booking booking) {
        String bookingNo = booking.getBookingNo();
        return bookingNo.startsWith("BK") ? "RF" + bookingNo.substring(2) : "RF" + bookingNo;
    }
}
