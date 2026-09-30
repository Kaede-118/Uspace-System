package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.space.BookingStatus;
import com.kaede.uspace.space.FakeBookingMapper;
import com.kaede.uspace.space.dto.BookingVo;
import com.kaede.uspace.space.entity.Booking;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撤销包场并退款的单元测试。
 *
 * <p>这一组用例盯的是<b>钱</b>，所以重点不在「顺路能不能走通」，
 * 而在几条不到位的分支上：
 * <ul>
 *   <li>原路退回失败时，本地状态<b>必须回滚</b> ——
 *       状态写着已退款、钱却没出去，是最坏的那种错</li>
 *   <li>只有已付款的能撤 —— 待付款的、已撤销的、已取消的都得挡住</li>
 *   <li>人工核销收的钱没有平台流水，原路退回要<b>明确拒绝</b>并引导走人工</li>
 *   <li>并发下第二个请求不能把钱退第二次（{@code WHERE status = 'PAID'} 守卫）</li>
 * </ul>
 */
class BookingRefundServiceTests {

    private static final Long ADMIN_ID = 1L;
    private static final Long HOST_ID = 100L;

    private FakeBookingMapper bookingMapper;
    private FakePaymentGateway paymentGateway;
    private BookingRefundService service;

    @BeforeEach
    void setUp() {
        bookingMapper = new FakeBookingMapper();
        paymentGateway = new FakePaymentGateway();
        service = new BookingRefundService(bookingMapper.asMapper(), paymentGateway);
    }

    /**
     * 预置一场包场。
     *
     * @param status  状态
     * @param method  支付通道名，可为 null
     * @param payNo   平台交易号，可为 null
     * @return 预置的记录
     */
    private Booking seedBooking(String status, String method, String payNo) {
        Booking booking = new Booking();
        booking.setBookingNo("BK" + System.nanoTime());
        booking.setStoreId(1L);
        booking.setHostUserId(HOST_ID);
        booking.setStartAt(LocalDateTime.now().plusDays(1));
        booking.setEndAt(LocalDateTime.now().plusDays(1).plusHours(2));
        booking.setPrice(new BigDecimal("168.00"));
        booking.setStatus(status);
        booking.setPaymentMethod(method);
        booking.setPaymentNo(payNo);
        return bookingMapper.seed(booking);
    }

    /* ---------------- 人工退款 ---------------- */

    @Test
    @DisplayName("人工退款：状态转已退款，记下方式、金额、操作人，不调支付网关")
    void manualRefund_recordsWithoutCallingGateway() {
        Booking booking = seedBooking(BookingStatus.PAID.name(), null, null);

        BizResult<BookingVo> result = service.revoke(booking.getId(), "MANUAL", ADMIN_ID);

        assertTrue(result.isSuccess(), "人工退款不需要平台流水，应当成功");
        BookingVo vo = result.getData();
        assertEquals(BookingStatus.REFUNDED.name(), vo.getStatus());
        assertEquals("MANUAL", vo.getRefundMode());
        assertEquals(0, new BigDecimal("168.00").compareTo(vo.getRefundAmount()), "退款金额是全额");
        assertEquals(ADMIN_ID, vo.getRefundedBy(), "要留下是谁办的");
        assertNotNull(vo.getRefundedAt());
        assertNull(vo.getRefundNo(), "人工退没有平台退款单号");
        assertEquals(0, paymentGateway.refundCalls(), "人工退款不该碰支付网关");
    }

    /* ---------------- 原路退回 ---------------- */

    @Test
    @DisplayName("原路退回：调网关并记下退款单号，单号由包场单号派生（重试才幂等）")
    void onlineRefund_callsGatewayAndRecordsRefundNo() {
        Booking booking = seedBooking(BookingStatus.PAID.name(), "WXPAY_JSAPI", "wx_txn_001");
        String expectedRefundNo = "RF" + booking.getBookingNo().substring(2);

        BizResult<BookingVo> result = service.revoke(booking.getId(), "ONLINE", ADMIN_ID);

        assertTrue(result.isSuccess());
        assertEquals(BookingStatus.REFUNDED.name(), result.getData().getStatus());
        assertEquals("ONLINE", result.getData().getRefundMode());
        assertEquals(1, paymentGateway.refundCalls());

        // 退款单号必须由包场单号派生：失败后重试时同一个单号，
        // 平台按它幂等 —— 随机单号会让每次重试都变成一笔新的退款
        assertEquals(expectedRefundNo, paymentGateway.lastRefundCommand().getRefundNo());
        assertEquals(booking.getBookingNo(), paymentGateway.lastRefundCommand().getOutTradeNo());

        // ⚠️ 这一条是补上的：光断言「传给网关的单号对不对」漏掉了
        // 「单号有没有落库」—— 曾经真的没落上（补写那一步被自己的状态守卫挡下了），
        // 而当时的用例是全绿的。凡是发给外部系统的东西，都要回头看一眼库里有没有
        assertEquals(expectedRefundNo, result.getData().getRefundNo(),
                "退款单号要落库，对账时要凭它去平台查这笔退款");
        assertEquals(expectedRefundNo, bookingMapper.get(booking.getId()).getRefundNo());
    }

    @Test
    @DisplayName("原路退回失败：状态回滚成已付款，钱与单子始终对得上")
    void onlineRefundFailure_revertsStatus() {
        Booking booking = seedBooking(BookingStatus.PAID.name(), "ALIPAY_WAP", "ali_txn_001");
        paymentGateway.failNextRefund("原订单超过可退款期限");

        BizResult<BookingVo> result = service.revoke(booking.getId(), "ONLINE", ADMIN_ID);

        assertEquals(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, result.getError(),
                "上游退款失败应当报 502，而不是业务冲突");
        assertTrue(result.getMessage().contains("原订单超过可退款期限"),
                "要把平台给的原因带出来，否则管理员不知道该找谁");

        Booking after = bookingMapper.get(booking.getId());
        assertEquals(BookingStatus.PAID.name(), after.getStatus(),
                "退不成就不能记成退成了 —— 这是本用例存在的全部意义");
        assertNull(after.getRefundMode());
        assertNull(after.getRefundedAt());
        assertNull(after.getRefundedBy());
    }

    /* ---------------- 不该退的几种情形 ---------------- */

    @Test
    @DisplayName("待付款的包场不能退款（那是「取消」，没有钱的事）")
    void pendingPayment_isNotRefundable() {
        Booking booking = seedBooking(BookingStatus.PENDING_PAYMENT.name(), null, null);

        BizResult<BookingVo> result = service.revoke(booking.getId(), "MANUAL", ADMIN_ID);

        assertEquals(ErrorCode.BOOKING_NOT_REFUNDABLE, result.getError());
        assertEquals(0, paymentGateway.refundCalls());
    }

    @Test
    @DisplayName("已经退过的不能再退一次（并发下第二个请求会被状态守卫挡下）")
    void alreadyRefunded_cannotBeRefundedAgain() {
        Booking booking = seedBooking(BookingStatus.PAID.name(), "WXPAY_JSAPI", "wx_txn_002");

        assertTrue(service.revoke(booking.getId(), "ONLINE", ADMIN_ID).isSuccess());
        BizResult<BookingVo> second = service.revoke(booking.getId(), "ONLINE", ADMIN_ID);

        assertEquals(ErrorCode.BOOKING_NOT_REFUNDABLE, second.getError());
        assertEquals(1, paymentGateway.refundCalls(), "第二次绝不能再调一次退款 —— 那就是把钱退两遍");
    }

    @Test
    @DisplayName("取消掉的、已结束的都不能退款")
    void cancelledAndClosed_areNotRefundable() {
        Booking cancelled = seedBooking(BookingStatus.CANCELLED.name(), null, null);
        Booking closed = seedBooking(BookingStatus.CLOSED.name(), "WXPAY_JSAPI", "wx_txn_003");

        assertEquals(ErrorCode.BOOKING_NOT_REFUNDABLE,
                service.revoke(cancelled.getId(), "MANUAL", ADMIN_ID).getError());
        assertEquals(ErrorCode.BOOKING_NOT_REFUNDABLE,
                service.revoke(closed.getId(), "ONLINE", ADMIN_ID).getError());
    }

    @Test
    @DisplayName("人工核销收的钱退不回去：没有平台流水，要引导走人工退款")
    void manualVerifiedPayment_cannotRefundOnline() {
        // QR_UPLOAD 是「传截图人工核销」，钱根本没经过支付平台
        Booking booking = seedBooking(BookingStatus.PAID.name(), "QR_UPLOAD", "manual_001");

        BizResult<BookingVo> result = service.revoke(booking.getId(), "ONLINE", ADMIN_ID);

        assertEquals(ErrorCode.BOOKING_NOT_REFUNDABLE, result.getError());
        assertTrue(result.getMessage().contains("人工退款"), "提示要指向正确的出路：改选人工退款");
        assertEquals(0, paymentGateway.refundCalls());
        assertEquals(BookingStatus.PAID.name(), bookingMapper.get(booking.getId()).getStatus(),
                "被拒绝时状态不该被动过");
    }

    @Test
    @DisplayName("没有平台交易号的老数据也退不了（回滚路径要认得出）")
    void paidWithoutTransactionNo_cannotRefundOnline() {
        Booking booking = seedBooking(BookingStatus.PAID.name(), "WXPAY_JSAPI", null);

        assertEquals(ErrorCode.BOOKING_NOT_REFUNDABLE,
                service.revoke(booking.getId(), "ONLINE", ADMIN_ID).getError());
        assertEquals(0, paymentGateway.refundCalls());
    }

    @Test
    @DisplayName("包场不存在返回 404；退款方式不合法返回 400")
    void badInputs() {
        assertEquals(ErrorCode.BOOKING_NOT_FOUND,
                service.revoke(99999L, "MANUAL", ADMIN_ID).getError());

        Booking booking = seedBooking(BookingStatus.PAID.name(), null, null);
        assertEquals(ErrorCode.PARAM_INVALID,
                service.revoke(booking.getId(), "WECHAT", ADMIN_ID).getError());
        assertEquals(ErrorCode.PARAM_INVALID,
                service.revoke(booking.getId(), null, ADMIN_ID).getError(),
                "null 也要挡下 —— 否则会写进库一条没有方式的退款记录");
    }
}
