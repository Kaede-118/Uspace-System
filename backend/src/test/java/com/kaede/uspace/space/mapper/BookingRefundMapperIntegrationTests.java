package com.kaede.uspace.space.mapper;

import com.kaede.uspace.space.BookingStatus;
import com.kaede.uspace.space.entity.Booking;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 撤销退款两条 SQL 的集成测试，连本机真实 MySQL、用例结束后自动回滚。
 *
 * <p><b>为什么这两条必须用真库验</b>：{@code markRefunded} 与 {@code revertRefund}
 * 的全部价值就在 {@code WHERE status = '...'} 那个守卫上 ——
 * 守卫少了或写错，表现得不是报错而是<b>把钱退两次</b>，
 * 而假 Mapper 里的守卫是我们自己照着写的，它「通过」不能说明真 SQL 也有守卫。
 * 这里直接对真表打，验的就是那句 {@code WHERE}。
 *
 * <p>本类不开事务以外的任何保护：{@code @Transactional} 回滚 +
 * 未配 {@code MYSQL_PASSWORD} 时整体跳过 + ID 取 9999xx 段，与同类测试一致。
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class BookingRefundMapperIntegrationTests {

    /** 测试用的 ID 段。取大数以免与真实数据混淆 */
    private static final Long STORE_ID = 999911L;
    private static final Long HOST_ID = 999912L;
    private static final Long ADMIN_ID = 999913L;

    @Autowired
    private BookingMapper bookingMapper;

    /**
     * 造一条包场记录。
     *
     * @param status 状态
     * @param price  价格
     * @return 已落库的记录（含自增 ID）
     */
    private Booking newBooking(String status, String price) {
        Booking booking = new Booking();
        booking.setBookingNo("BKREF" + System.nanoTime());
        booking.setStoreId(STORE_ID);
        booking.setHostUserId(HOST_ID);
        booking.setStartAt(LocalDateTime.now().plusDays(2));
        booking.setEndAt(LocalDateTime.now().plusDays(2).plusHours(2));
        booking.setPrice(new BigDecimal(price));
        booking.setStatus(status);
        bookingMapper.insert(booking);
        return booking;
    }

    @Test
    @DisplayName("撤销退款：状态与五个退款字段一条 SQL 写下去，且只从 PAID 出发")
    void markRefunded_writesAllFieldsAndGuardsStatus() {
        Booking booking = newBooking(BookingStatus.PAID.name(), "168.00");
        LocalDateTime refundedAt = LocalDateTime.now();

        int affected = bookingMapper.markRefunded(booking.getId(), "MANUAL",
                new BigDecimal("168.00"), refundedAt, ADMIN_ID, null);
        assertEquals(1, affected);

        Booking after = bookingMapper.selectById(booking.getId());
        assertEquals(BookingStatus.REFUNDED.name(), after.getStatus());
        assertEquals("MANUAL", after.getRefundMode());
        assertEquals(0, new BigDecimal("168.00").compareTo(after.getRefundAmount()));
        assertNotNull(after.getRefundedAt(), "退款时刻要落库 —— 账上得说得出什么时候退的");
        assertEquals(ADMIN_ID, after.getRefundedBy(), "操作人要落库，人工退尤其如此");
        assertNull(after.getRefundNo(), "人工退没有平台退款单号");

        // 第二次必须打不动：两个管理员同时点撤销时，后到的那个走的就是这条路
        assertEquals(0, bookingMapper.markRefunded(booking.getId(), "MANUAL",
                        new BigDecimal("168.00"), refundedAt, ADMIN_ID, null),
                "状态守卫若失效，同一笔钱会被退两次，而且不会有任何报错");
    }

    @Test
    @DisplayName("待付款的包场退不了：状态守卫挡住，字段一个都不写")
    void markRefunded_rejectsNonPaid() {
        Booking booking = newBooking(BookingStatus.PENDING_PAYMENT.name(), "100.00");

        assertEquals(0, bookingMapper.markRefunded(booking.getId(), "MANUAL",
                new BigDecimal("100.00"), LocalDateTime.now(), ADMIN_ID, null));

        Booking after = bookingMapper.selectById(booking.getId());
        assertEquals(BookingStatus.PENDING_PAYMENT.name(), after.getStatus());
        assertNull(after.getRefundMode(), "被拒绝时不能留下半截退款字段");
    }

    @Test
    @DisplayName("退款失败回滚：状态退回 PAID，五个退款字段一起清空")
    void revertRefund_restoresPaidAndClearsFields() {
        Booking booking = newBooking(BookingStatus.PAID.name(), "168.00");
        bookingMapper.markRefunded(booking.getId(), "ONLINE",
                new BigDecimal("168.00"), LocalDateTime.now(), ADMIN_ID, "RF123");

        assertEquals(1, bookingMapper.revertRefund(booking.getId()));

        Booking after = bookingMapper.selectById(booking.getId());
        assertEquals(BookingStatus.PAID.name(), after.getStatus(),
                "原路退回失败时状态要退回 —— 宁可退不成，也不能记成退成了");
        assertNull(after.getRefundMode());
        assertNull(after.getRefundAmount());
        assertNull(after.getRefundedAt());
        assertNull(after.getRefundedBy());
        assertNull(after.getRefundNo());

        assertEquals(0, bookingMapper.revertRefund(booking.getId()),
                "已经是已付款了，回滚不该再改动什么（守卫是 status = 'REFUNDED'）");
    }

    @Test
    @DisplayName("撤销后时段重新释放：不再计入重叠统计")
    void refundedBooking_noLongerOccupiesSlot() {
        Booking booking = newBooking(BookingStatus.PAID.name(), "168.00");
        LocalDateTime from = booking.getStartAt();
        LocalDateTime to = booking.getEndAt();

        assertEquals(1, bookingMapper.countOverlapping(STORE_ID, from, to, null),
                "已付款的场次占着时段");

        bookingMapper.markRefunded(booking.getId(), "MANUAL",
                new BigDecimal("168.00"), LocalDateTime.now(), ADMIN_ID, null);

        assertEquals(0, bookingMapper.countOverlapping(STORE_ID, from, to, null),
                "撤销就是把时段还回来 —— 判成还占着的话，那个时段再也排不出去，且没有任何报错");
    }
}
