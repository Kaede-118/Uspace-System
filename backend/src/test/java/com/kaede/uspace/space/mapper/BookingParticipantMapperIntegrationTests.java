package com.kaede.uspace.space.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.space.BookingParticipantRole;
import com.kaede.uspace.space.BookingStatus;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.entity.BookingParticipant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BookingParticipantMapper} 的集成测试，连本机真实 MySQL。
 *
 * <p><b>三重保护不污染开发库</b>：{@code @Transactional} 让每个用例结束后自动回滚；
 * {@code @EnabledIfEnvironmentVariable} 让没配 {@code MYSQL_PASSWORD} 的机器整个跳过；
 * ID 取 9999xx 这类可辨认的大数，万一回滚失效也一眼能认出来。
 *
 * <p><b>为什么假 Mapper 不够</b>：本 Mapper 里有两条查询要 <b>JOIN</b>
 * {@code biz_booking}（「我参与的列表」与「下单时该挂哪一场」），
 * 还有一条带 {@code CASE WHEN} 的排序。假实现模拟的是这些 SQL 的<b>语义</b>，
 * 但连表条件写错、{@code deleted = 0} 漏在某一侧、排序表达式写反 ——
 * 这些问题在假 Mapper 面前统统不会暴露，只有真跑一遍才知道。
 *
 * <p>本表是「谁在这场包场里」的权威来源，准入与计费都读它，
 * 所以唯一键与连表这两件事必须钉死。
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class BookingParticipantMapperIntegrationTests {

    /** 测试用的 ID 段。取大数以免与真实数据混淆 */
    private static final Long STORE_ID = 999901L;
    private static final Long HOST_ID = 999902L;
    private static final Long GUEST_ID = 999903L;
    private static final Long OTHER_ID = 999904L;

    @Autowired
    private BookingParticipantMapper participantMapper;

    /** 连表查询的另一半 —— 没有它，「我参与的」与「该挂哪一场」两条测试都造不出数据 */
    @Autowired
    private BookingMapper bookingMapper;

    // ==================================================================
    // 落库与唯一键
    // ==================================================================

    @Test
    @DisplayName("插入时自动填充审计字段并回填主键")
    void insert_fillsAuditFieldsAndId() {
        BookingParticipant row = participant(999999L, GUEST_ID, BookingParticipantRole.PARTICIPANT);

        int affected = participantMapper.insert(row);

        assertEquals(1, affected, "插入一行");
        assertNotNull(row.getId(), "主键应当由数据库自增填回实体");
        assertNotNull(row.getCreatedAt(), "created_at 是 NOT NULL 且无默认值，靠自动填充补上");
        assertNotNull(row.getUpdatedAt(), "updated_at 同理");
    }

    @Test
    @DisplayName("唯一键挡住同一场同一人的第二行")
    void insert_rejectsDuplicateBookingAndUser() {
        Booking booking = seedBooking(hoursFromNow(1), hoursFromNow(5), BookingStatus.PAID);
        participantMapper.insert(participant(booking.getId(), GUEST_ID,
                BookingParticipantRole.PARTICIPANT));

        BookingParticipant again = participant(booking.getId(), GUEST_ID,
                BookingParticipantRole.PARTICIPANT);

        assertThrows(DuplicateKeyException.class, () -> participantMapper.insert(again),
                "唯一键是「重复点邀请链接不会插出第二行」的最终防线 —— "
                        + "应用层先查后插挡不住并发，两人同时点链接时只有它挡得住");
    }

    // ==================================================================
    // 按场次查
    // ==================================================================

    @Test
    @DisplayName("逻辑删除的行不参与计数")
    void countByBookingAndUser_ignoresDeletedRows() {
        Booking booking = seedBooking(hoursFromNow(1), hoursFromNow(5), BookingStatus.PAID);
        BookingParticipant row = participant(booking.getId(), GUEST_ID,
                BookingParticipantRole.PARTICIPANT);
        participantMapper.insert(row);

        assertEquals(1, participantMapper.countByBookingAndUser(booking.getId(), GUEST_ID),
                "未删除时算一个人");

        participantMapper.deleteById(row.getId());

        assertEquals(0, participantMapper.countByBookingAndUser(booking.getId(), GUEST_ID),
                "手写 SQL 必须自己带 deleted = 0（BaseEntity 的逻辑删除只管自动生成的语句）—— "
                        + "漏了它，被移除的人仍会被准入判定认作参与者");
    }

    @Test
    @DisplayName("名单里发起人排在最前，即使他加入得最晚")
    void selectByBookingId_putsHostFirst() {
        Booking booking = seedBooking(hoursFromNow(1), hoursFromNow(5), BookingStatus.PAID);
        // 刻意让被邀请者的加入时刻早于包场人 —— 只按 joined_at 排的话发起人会掉到后面
        participantMapper.insert(participantAt(booking.getId(), GUEST_ID,
                BookingParticipantRole.PARTICIPANT, hoursFromNow(-3)));
        participantMapper.insert(participantAt(booking.getId(), HOST_ID,
                BookingParticipantRole.HOST, hoursFromNow(-1)));

        List<BookingParticipant> rows = participantMapper.selectByBookingId(booking.getId());

        assertEquals(2, rows.size(), "两个人都应当在名单里");
        assertEquals(HOST_ID, rows.get(0).getUserId(),
                "发起人固定排在最前 —— 邀请页要靠这个位置给他打「发起人」标记");
        assertEquals(GUEST_ID, rows.get(1).getUserId(), "其后按加入时刻升序");
    }

    @Test
    @DisplayName("人数统计含包场人")
    void countByBooking_includesHost() {
        Booking booking = seedBooking(hoursFromNow(1), hoursFromNow(5), BookingStatus.PAID);
        participantMapper.insert(participant(booking.getId(), HOST_ID,
                BookingParticipantRole.HOST));
        participantMapper.insert(participant(booking.getId(), GUEST_ID,
                BookingParticipantRole.PARTICIPANT));

        assertEquals(2, participantMapper.countByBooking(booking.getId()),
                "「当前人数」要含发起人 —— 否则邀请页显示 2 个人，人数却说 1");
    }

    // ==================================================================
    // 按人查（要连 biz_booking）
    // ==================================================================

    @Test
    @DisplayName("我参与的列表只认 PARTICIPANT，不混进自己发起的场次")
    void selectPageByUserRole_filtersByRole() {
        Booking mine = seedBooking(hoursFromNow(1), hoursFromNow(5), BookingStatus.PAID);
        Booking invited = seedBooking(hoursFromNow(8), hoursFromNow(12), BookingStatus.PAID);
        participantMapper.insert(participant(mine.getId(), HOST_ID, BookingParticipantRole.HOST));
        participantMapper.insert(participant(invited.getId(), HOST_ID,
                BookingParticipantRole.PARTICIPANT));

        var page = participantMapper.selectPageByUserRole(
                new Page<>(1, 10), HOST_ID, BookingParticipantRole.PARTICIPANT.name());

        assertEquals(1, page.getRecords().size(),
                "「我参与的」与「我创建的」是两个列表，同一场不该在两边各出现一次");
        assertEquals(invited.getId(), page.getRecords().get(0).getId(), "只剩被邀请的那一场");
    }

    @Test
    @DisplayName("我参与的列表按开始时间倒序")
    void selectPageByUserRole_ordersByStartAtDesc() {
        Booking early = seedBooking(hoursFromNow(1), hoursFromNow(5), BookingStatus.PAID);
        Booking late = seedBooking(hoursFromNow(8), hoursFromNow(12), BookingStatus.PAID);
        participantMapper.insert(participant(early.getId(), GUEST_ID,
                BookingParticipantRole.PARTICIPANT));
        participantMapper.insert(participant(late.getId(), GUEST_ID,
                BookingParticipantRole.PARTICIPANT));

        var page = participantMapper.selectPageByUserRole(
                new Page<>(1, 10), GUEST_ID, BookingParticipantRole.PARTICIPANT.name());

        assertEquals(late.getId(), page.getRecords().get(0).getId(),
                "最近的排在最前 —— 与「我创建的」列表保持同一个口径");
    }

    @Test
    @DisplayName("下单前查包场：取最近的、尚未结束的已付款场次")
    void selectUpcomingByParticipant_picksNearestUnfinished() {
        LocalDateTime now = LocalDateTime.now();
        Booking later = seedBooking(now.plusDays(2), now.plusDays(2).plusHours(4),
                BookingStatus.PAID);
        Booking nearer = seedBooking(now.plusHours(3), now.plusHours(7), BookingStatus.PAID);
        participantMapper.insert(participant(later.getId(), GUEST_ID,
                BookingParticipantRole.PARTICIPANT));
        participantMapper.insert(participant(nearer.getId(), GUEST_ID,
                BookingParticipantRole.PARTICIPANT));

        Booking found = participantMapper.selectUpcomingByParticipant(STORE_ID, GUEST_ID, now);

        assertNotNull(found, "他参与了一场尚未结束的包场，应当查得到");
        assertEquals(nearer.getId(), found.getId(),
                "取最近的一场 —— ORDER BY start_at LIMIT 1 这条最容易写成取最后一条");
    }

    @Test
    @DisplayName("下单前查包场：未付款、已结束、别人的场次都不算")
    void selectUpcomingByParticipant_respectsAllConditions() {
        LocalDateTime now = LocalDateTime.now();
        Booking unpaid = seedBooking(now.plusHours(1), now.plusHours(5),
                BookingStatus.PENDING_PAYMENT);
        Booking ended = seedBooking(now.minusHours(5), now.minusHours(1), BookingStatus.PAID);
        Booking others = seedBooking(now.plusHours(1), now.plusHours(5), BookingStatus.PAID);

        participantMapper.insert(participant(unpaid.getId(), GUEST_ID,
                BookingParticipantRole.PARTICIPANT));
        participantMapper.insert(participant(ended.getId(), GUEST_ID,
                BookingParticipantRole.PARTICIPANT));
        participantMapper.insert(participant(others.getId(), OTHER_ID,
                BookingParticipantRole.PARTICIPANT));

        assertNull(participantMapper.selectUpcomingByParticipant(STORE_ID, GUEST_ID, now),
                "未付款的不产生排他性、已结束的没有意义、别人的场次更不该挂到他的订单上 —— "
                        + "这四条筛选条件（status / end_at / user_id / 连表）漏掉任何一条，"
                        + "订单都会凭空多出一个出处不明的包场 ID，且不报任何错");
        assertNull(participantMapper.selectUpcomingByParticipant(STORE_ID, HOST_ID, now),
                "没有任何参与者行的人，查不到任何场次");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 构造一条参与者记录（加入时刻取一小时前）。
     *
     * @param bookingId 包场 ID
     * @param userId    用户 ID
     * @param role      角色
     * @return 参与者实体
     */
    private static BookingParticipant participant(Long bookingId, Long userId,
                                                  BookingParticipantRole role) {
        return participantAt(bookingId, userId, role, hoursFromNow(-1));
    }

    /**
     * 构造一条指定加入时刻的参与者记录。
     *
     * @param bookingId 包场 ID
     * @param userId    用户 ID
     * @param role      角色
     * @param joinedAt  加入时刻
     * @return 参与者实体
     */
    private static BookingParticipant participantAt(Long bookingId, Long userId,
                                                    BookingParticipantRole role,
                                                    LocalDateTime joinedAt) {
        BookingParticipant row = new BookingParticipant();
        row.setBookingId(bookingId);
        row.setUserId(userId);
        row.setRole(role.name());
        row.setJoinedAt(joinedAt);
        return row;
    }

    /**
     * 直接插一场包场（绕过 Service）。
     *
     * <p>单号用纳秒拼出来，保证同一用例内多次构造不会撞上 {@code uk_booking_no}。
     *
     * @param startAt 开始时刻
     * @param endAt   结束时刻
     * @param status  状态
     * @return 已落库的包场（含自增主键）
     */
    private Booking seedBooking(LocalDateTime startAt, LocalDateTime endAt,
                                BookingStatus status) {
        Booking booking = new Booking();
        booking.setBookingNo("BK" + System.nanoTime());
        booking.setStoreId(STORE_ID);
        booking.setHostUserId(HOST_ID);
        booking.setStartAt(startAt);
        booking.setEndAt(endAt);
        booking.setPrice(new BigDecimal("500.00"));
        booking.setStatus(status.name());
        bookingMapper.insert(booking);
        assertTrue(booking.getId() != null, "包场要落库拿到主键，后续查询都靠它");
        return booking;
    }

    /**
     * 相对当前时刻的小时数。
     *
     * @param hours 小时数，可为负
     * @return 时刻
     */
    private static LocalDateTime hoursFromNow(int hours) {
        return LocalDateTime.now().plusHours(hours).withNano(0);
    }
}
