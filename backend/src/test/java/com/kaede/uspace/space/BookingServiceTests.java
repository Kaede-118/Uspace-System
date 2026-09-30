package com.kaede.uspace.space;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.space.dto.BookingParticipantVo;
import com.kaede.uspace.space.dto.BookingVo;
import com.kaede.uspace.space.dto.CreateBookingRequest;
import com.kaede.uspace.space.dto.JoinResultVo;
import com.kaede.uspace.space.dto.UpdateBookingRequest;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.entity.Store;
import com.kaede.uspace.user.FakeSysUserMapper;
import com.kaede.uspace.user.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BookingService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库。</b>
 *
 * <p>覆盖重点在<b>两种状态口径的区分</b>，它是包场逻辑里最容易写错的地方：
 * <ul>
 *   <li>排期冲突校验认「待付款 + 已付款」—— 未付款的包场也占着时段，
 *       否则两场重叠的包场各自付款后会撞车</li>
 *   <li>准入生效只认「已付款」—— 管理员排了期但对方一直不付款的话，
 *       散客照常能进店，不该白空一个时段</li>
 * </ul>
 * 这两条写反了不会报错：前者会让两场包场撞车，后者会让店白白空着。
 *
 * <p>另外钉住「已付款的包场不能改期/取消」—— 那涉及退款，
 * 属于模块 8 与运营流程，不该由一个排期接口顺带完成。
 */
class BookingServiceTests {

    /** 测试门店的 ID */
    private static final Long STORE_ID = 1L;

    /** 测试包场人 ID */
    private static final Long HOST_ID = 7L;

    /** 测试被邀请者 ID */
    private static final Long GUEST_ID = 8L;

    /** 测试管理员 ID */
    private static final Long ADMIN_ID = 99L;

    /** 内存版数据访问层 */
    private final FakeStoreMapper storeMapper = new FakeStoreMapper();
    private final FakeBookingMapper bookingMapper = new FakeBookingMapper();
    private final FakeClosureMapper closureMapper = new FakeClosureMapper();
    private final FakeSysUserMapper userMapper = new FakeSysUserMapper();

    /**
     * 参与者表。它与 {@code bookingMapper} <b>共享同一份包场数据</b> ——
     * 因为它的「我参与的列表」与「下单时该挂哪一场」两条查询在真实 SQL 里
     * 都要连 {@code biz_booking}，两个假实现各存一份的话，JOIN 出来永远是空。
     */
    private final FakeBookingParticipantMapper participantMapper =
            new FakeBookingParticipantMapper(bookingMapper);

    /** 停业服务，供包场校验「不与停业时段撞车」 */
    private final ClosureService closureService =
            new ClosureService(closureMapper.asMapper(), storeMapper.asMapper());

    /** 被测服务 */
    private final BookingService bookingService = new BookingService(
            bookingMapper.asMapper(), storeMapper.asMapper(), closureService,
            userMapper.asMapper(), participantMapper.asMapper());

    /**
     * 每个用例前预置门店与包场人 —— 两者缺一都会让 Service 提前返回错误，
     * 后面的规则就测不到了。
     */
    @BeforeEach
    void setUp() {
        Store store = new Store();
        store.setId(STORE_ID);
        store.setName("测试门店");
        storeMapper.seed(store);

        SysUser host = new SysUser();
        host.setId(HOST_ID);
        host.setUsername("host");
        host.setNickname("包场人");
        userMapper.seed(host);
    }

    // ==================================================================
    // 新增
    // ==================================================================

    @Test
    @DisplayName("创建包场：初始状态是待付款，且带上单号与排期人")
    void createBooking_persistsAsPendingPayment() {
        LocalDateTime start = tomorrowAt(14, 0);
        LocalDateTime end = tomorrowAt(18, 0);

        BizResult<BookingVo> result = bookingService.createBooking(
                newRequest(start, end, new BigDecimal("100.00")), ADMIN_ID);

        assertTrue(result.isSuccess(), "正常排期应当成功");

        Booking stored = bookingMapper.get(result.getData().getId());
        assertEquals(BookingStatus.PENDING_PAYMENT.name(), stored.getStatus(),
                "新排的场次处于待付款：时段占住了，但准入还没生效");
        assertEquals(start, stored.getStartAt());
        assertEquals(end, stored.getEndAt());
        assertEquals(0, new BigDecimal("100.00").compareTo(stored.getPrice()));
        assertEquals(HOST_ID, stored.getHostUserId());
        assertEquals(ADMIN_ID, stored.getCreatedBy());
        assertNull(stored.getPaidAt(), "还没付款，支付时刻应当为空");
        assertNull(stored.getInviteToken(), "邀请令牌要等付款后才生成");

        assertNotNull(stored.getBookingNo());
        assertTrue(stored.getBookingNo().startsWith("BK"),
                "单号带 BK 前缀，便于在日志与对账文件里一眼认出是包场而非普通订单");
        assertEquals(20, stored.getBookingNo().length(), "BK + 14 位时间戳 + 4 位随机数");
    }

    @Test
    @DisplayName("创建包场：结束时刻不晚于开始时刻时拒绝")
    void createBooking_rejectsReversedRange() {
        LocalDateTime start = tomorrowAt(14, 0);

        assertEquals(ErrorCode.BOOKING_TIME_INVALID,
                bookingService.createBooking(newRequest(start, start, BigDecimal.TEN), ADMIN_ID).getError());
        assertEquals(ErrorCode.BOOKING_TIME_INVALID,
                bookingService.createBooking(
                        newRequest(start, start.minusHours(1), BigDecimal.TEN), ADMIN_ID).getError());
    }

    @Test
    @DisplayName("创建包场：开始时间早于此刻时拒绝")
    void createBooking_rejectsPastStart() {
        LocalDateTime past = LocalDateTime.now().minusHours(1);

        BizResult<BookingVo> result = bookingService.createBooking(
                newRequest(past, past.plusHours(2), BigDecimal.TEN), ADMIN_ID);

        assertEquals(ErrorCode.BOOKING_START_IN_PAST, result.getError(),
                "包场是卖给用户的，排一个已经开始甚至过去的时段没有意义");
    }

    @Test
    @DisplayName("创建包场：包场人不存在时拒绝")
    void createBooking_rejectsUnknownHost() {
        CreateBookingRequest request = newRequest(
                tomorrowAt(14, 0), tomorrowAt(18, 0), BigDecimal.TEN);
        request.setHostUserId(404L);

        BizResult<BookingVo> result = bookingService.createBooking(request, ADMIN_ID);

        assertEquals(ErrorCode.USER_NOT_FOUND, result.getError(),
                "包场人要能登录自己的账号取邀请链接，必须是已存在的用户");
    }

    @Test
    @DisplayName("创建包场：与待付款的包场重叠也要拒绝")
    void createBooking_rejectsOverlapEvenWithUnpaidBooking() {
        bookingService.createBooking(
                newRequest(tomorrowAt(14, 0), tomorrowAt(18, 0), BigDecimal.TEN), ADMIN_ID);

        // 与上面那场部分相交
        BizResult<BookingVo> result = bookingService.createBooking(
                newRequest(tomorrowAt(17, 0), tomorrowAt(20, 0), BigDecimal.TEN), ADMIN_ID);

        assertEquals(ErrorCode.BOOKING_OVERLAP, result.getError(),
                "未付款的包场也已经把时段许出去了 —— 若允许再排一场重叠的，两笔都付款后会撞车");
    }

    @Test
    @DisplayName("创建包场：与停业时段重叠时拒绝")
    void createBooking_rejectsClosureOverlap() {
        LocalDateTime start = tomorrowAt(14, 0);
        closureMapper.seed(closure(start, start.plusHours(2), "设备维护"));

        BizResult<BookingVo> result = bookingService.createBooking(
                newRequest(start, start.plusHours(4), BigDecimal.TEN), ADMIN_ID);

        assertEquals(ErrorCode.BOOKING_CLOSURE_OVERLAP, result.getError(),
                "顾客付了钱包场，到店却发现大门锁着 —— 这种排期失误必须在源头拦住");
    }

    @Test
    @DisplayName("创建包场：首尾相接的两场可以相邻")
    void createBooking_allowsAdjacentRanges() {
        bookingService.createBooking(
                newRequest(tomorrowAt(14, 0), tomorrowAt(16, 0), BigDecimal.TEN), ADMIN_ID);

        BizResult<BookingVo> result = bookingService.createBooking(
                newRequest(tomorrowAt(16, 0), tomorrowAt(18, 0), BigDecimal.TEN), ADMIN_ID);

        assertTrue(result.isSuccess(), "半开区间下 16:00 那一刻只属于后一场，不算重叠");
    }

    // ==================================================================
    // 修改与取消
    // ==================================================================

    @Test
    @DisplayName("修改包场：待付款的场次可以改期改价")
    void updateBooking_updatesScheduleOfPendingBooking() {
        Long id = bookingService.createBooking(
                newRequest(tomorrowAt(14, 0), tomorrowAt(18, 0), new BigDecimal("100.00")),
                ADMIN_ID).getData().getId();

        UpdateBookingRequest request = new UpdateBookingRequest();
        request.setStartAt(tomorrowAt(15, 0));
        request.setEndAt(tomorrowAt(19, 0));
        request.setPrice(new BigDecimal("120.00"));
        request.setRemark("客人要求顺延一小时");

        BizResult<Void> result = bookingService.updateBooking(id, request);

        assertTrue(result.isSuccess());
        Booking stored = bookingMapper.get(id);
        assertEquals(tomorrowAt(15, 0), stored.getStartAt());
        assertEquals(0, new BigDecimal("120.00").compareTo(stored.getPrice()));
        assertEquals("客人要求顺延一小时", stored.getRemark());
        assertEquals(BookingStatus.PENDING_PAYMENT.name(), stored.getStatus(),
                "改期不该动状态，状态由支付流程负责");
    }

    @Test
    @DisplayName("修改包场：已付款的场次拒绝修改（涉及退款，属模块 8）")
    void updateBooking_rejectsPaidBooking() {
        Long id = bookingMapper.seed(
                booking(tomorrowAt(14, 0), tomorrowAt(18, 0), BookingStatus.PAID)).getId();

        UpdateBookingRequest request = new UpdateBookingRequest();
        request.setStartAt(tomorrowAt(15, 0));
        request.setEndAt(tomorrowAt(19, 0));
        request.setPrice(BigDecimal.TEN);

        assertEquals(ErrorCode.BOOKING_NOT_EDITABLE, bookingService.updateBooking(id, request).getError());
    }

    @Test
    @DisplayName("取消包场：走逻辑删除，取消后时段可以再排给别人")
    void cancelBooking_freesTheSlot() {
        LocalDateTime start = tomorrowAt(14, 0);
        Long id = bookingService.createBooking(
                newRequest(start, tomorrowAt(18, 0), BigDecimal.TEN), ADMIN_ID).getData().getId();

        assertTrue(bookingService.cancelBooking(id).isSuccess());
        assertEquals(1, bookingMapper.get(id).getDeleted(), "应当是逻辑删除，取消记录仍留在库里");

        // 时段腾出来了，可以再排一场
        BizResult<BookingVo> again = bookingService.createBooking(
                newRequest(start, tomorrowAt(18, 0), BigDecimal.TEN), ADMIN_ID);
        assertTrue(again.isSuccess(), "已取消的包场不该继续占着时段");
    }

    @Test
    @DisplayName("取消包场：已付款的场次拒绝取消")
    void cancelBooking_rejectsPaidBooking() {
        Long id = bookingMapper.seed(
                booking(tomorrowAt(14, 0), tomorrowAt(18, 0), BookingStatus.PAID)).getId();

        assertEquals(ErrorCode.BOOKING_NOT_EDITABLE, bookingService.cancelBooking(id).getError());
    }

    @Test
    @DisplayName("修改与取消：记录不存在时返回 404 类错误码")
    void updateAndCancel_failWhenNotFound() {
        UpdateBookingRequest request = new UpdateBookingRequest();
        request.setStartAt(tomorrowAt(14, 0));
        request.setEndAt(tomorrowAt(18, 0));
        request.setPrice(BigDecimal.TEN);

        assertEquals(ErrorCode.BOOKING_NOT_FOUND, bookingService.updateBooking(999L, request).getError());
        assertEquals(ErrorCode.BOOKING_NOT_FOUND, bookingService.cancelBooking(999L).getError());
    }

    // ==================================================================
    // 准入生效判断
    // ==================================================================

    @Test
    @DisplayName("生效判断：只有已付款的包场才产生排他性")
    void findActiveBookingAt_ignoresUnpaidBookings() {
        LocalDateTime start = tomorrowAt(14, 0);
        LocalDateTime end = tomorrowAt(18, 0);

        // 一场待付款的包场
        bookingService.createBooking(newRequest(start, end, BigDecimal.TEN), ADMIN_ID);
        assertNull(bookingService.findActiveBookingAt(start.plusHours(1)),
                "排了期但还没付款的包场不该把散客挡在门外 —— 否则店会白空一个时段");

        // 同一时段再来一场已付款的
        bookingMapper.seed(booking(start, end, BookingStatus.PAID));
        assertNotNull(bookingService.findActiveBookingAt(start.plusHours(1)));
    }

    @Test
    @DisplayName("生效判断：半开区间 —— 起点生效，终点已结束")
    void findActiveBookingAt_followsHalfOpenRange() {
        LocalDateTime start = tomorrowAt(14, 0);
        LocalDateTime end = tomorrowAt(18, 0);
        bookingMapper.seed(booking(start, end, BookingStatus.PAID));

        assertNull(bookingService.findActiveBookingAt(start.minusSeconds(1)));
        assertNotNull(bookingService.findActiveBookingAt(start));
        assertNotNull(bookingService.findActiveBookingAt(end.minusSeconds(1)));
        assertNull(bookingService.findActiveBookingAt(end));
    }

    // ------------------------------------------------------------------
    // 准入窗口：比「正在包场中」更早生效
    //
    // 下单准入用的窗口要往前挪一段（包场开始前的提前量内就停止接待新顾客）。
    // OrderService 那侧的用例用相对时刻写 —— 构造包场与真正下单之间隔着几毫秒，
    // 卡在分界线上的断言会时灵时不灵；窗口的精确边界在这里用固定时刻钉住，
    // 传进去的「当前时刻」是自己给的，不受执行耗时影响。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("准入窗口：提前量内命中，窗口右端之外不命中")
    void findAdmissionBookingAt_usesLeadWindow() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime bookingStart = now.plusMinutes(30);
        bookingMapper.seed(booking(bookingStart, bookingStart.plusHours(4), BookingStatus.PAID));
        Duration lead = Duration.ofMinutes(15);

        assertNull(bookingService.findAdmissionBookingAt(now, lead),
                "包场 30 分钟后才开始，离窗口右端还有 15 分钟，不该命中");

        assertNotNull(bookingService.findAdmissionBookingAt(bookingStart.minus(lead), lead),
                "开始时刻恰好落在窗口右端时算命中 —— 与 SQL 的 start_at <= windowEnd 一致");

        assertNull(bookingService.findAdmissionBookingAt(
                        bookingStart.minus(lead).minusSeconds(1), lead),
                "再早 1 秒就还在窗口之外 —— 差这一秒，散客还来得及打完一局");
    }

    @Test
    @DisplayName("准入窗口：进行中的包场命中，结束时刻起不再命中")
    void findAdmissionBookingAt_coversOngoingButNotEnded() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime bookingStart = now.minusMinutes(10);
        LocalDateTime bookingEnd = now.plusHours(2);
        bookingMapper.seed(booking(bookingStart, bookingEnd, BookingStatus.PAID));
        Duration lead = Duration.ofMinutes(15);

        assertNotNull(bookingService.findAdmissionBookingAt(now, lead), "包场进行中要命中");
        assertNotNull(bookingService.findAdmissionBookingAt(bookingEnd.minusSeconds(1), lead),
                "结束前 1 秒仍在包场里");
        assertNull(bookingService.findAdmissionBookingAt(bookingEnd, lead),
                "到结束时刻就出窗口了 —— 与「生效判断」用的半开区间是同一套口径");
    }

    @Test
    @DisplayName("准入窗口：未付款的包场连预备窗口都不产生")
    void findAdmissionBookingAt_ignoresUnpaid() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime bookingStart = now.plusMinutes(10);
        bookingMapper.seed(booking(bookingStart, bookingStart.plusHours(4),
                BookingStatus.PENDING_PAYMENT));

        assertNull(bookingService.findAdmissionBookingAt(now, Duration.ofMinutes(15)),
                "排了期还没付款的包场不该提前把散客挡在门外 —— 那样店会白空一个时段");
    }

    // ==================================================================
    // ==================================================================
    // 参与者
    // ==================================================================

    @Test
    @DisplayName("加入：首次点开邀请链接即写入一行 PARTICIPANT")
    void joinByBooking_insertsParticipant() {
        Booking paid = seedPaidBooking();

        BizResult<JoinResultVo> result = bookingService.joinByBooking(paid, GUEST_ID);

        assertTrue(result.isSuccess(), "有效包场应当能加入");
        assertTrue(result.getData().isJoined(), "首次加入应当报告「新加入」");
        assertFalse(result.getData().isAlreadyJoined(),
                "joined 与 alreadyJoined 恒为一真一假，不能同时为真");
        assertEquals(1, result.getData().getParticipantCount(), "此时名单里只有他一个");
    }

    @Test
    @DisplayName("加入：重复点同一条链接返回「已加入」，而不是报错")
    void joinByBooking_reportsAlreadyJoinedWhenCalledTwice() {
        Booking paid = seedPaidBooking();

        bookingService.joinByBooking(paid, GUEST_ID);
        BizResult<JoinResultVo> second = bookingService.joinByBooking(paid, GUEST_ID);

        assertTrue(second.isSuccess(),
                "刷新页面、从聊天记录里再点一次，都不该弹一个错误框");
        assertTrue(second.getData().isAlreadyJoined(), "第二次应当报告「你已经在了」");
        assertFalse(second.getData().isJoined(), "第二次不是新加入");
        assertEquals(1, second.getData().getParticipantCount(),
                "重复加入不会把人数算成两个 —— 唯一键挡住了第二行");
    }

    @Test
    @DisplayName("加入：并发下撞唯一键也当作「已加入」，不让请求失败")
    void joinByBooking_treatsDuplicateKeyAsAlreadyJoined() {
        Booking paid = seedPaidBooking();

        // 模拟并发窗口：查的时候还没有，插的时候别人已经插进去了
        participantMapper.failNextInsertWithDuplicateKey();
        BizResult<JoinResultVo> result = bookingService.joinByBooking(paid, GUEST_ID);

        assertTrue(result.isSuccess(),
                "这条路径只在并发下走到，而它恰恰是唯一正确的那条 —— 撞键不是错误，是答案");
        assertTrue(result.getData().isAlreadyJoined(),
                "撞键说明别人先插进去了，对本次调用者而言就是「已加入」");
    }

    @Test
    @DisplayName("加入：解析不出包场时返回 404，而不是抛异常")
    void joinByBooking_failsForMissingBooking() {
        assertEquals(ErrorCode.NOT_FOUND, bookingService.joinByBooking(null, GUEST_ID).getError(),
                "令牌无效时调用方拿到的应当是 404");
    }

    @Test
    @DisplayName("名单：发起人固定排在最前，其后按加入时刻升序")
    void listParticipants_putsHostFirst() {
        Booking paid = seedPaidBooking();

        // 刻意把顺序颠倒：被邀请者先加入（时刻更早），包场人「付款」时才写入
        participantMapper.seed(paid.getId(), GUEST_ID, BookingParticipantRole.PARTICIPANT.name(),
                LocalDateTime.of(2026, 9, 30, 10, 0));
        participantMapper.seed(paid.getId(), HOST_ID, BookingParticipantRole.HOST.name(),
                LocalDateTime.of(2026, 9, 30, 18, 0));

        List<BookingParticipantVo> participants = bookingService.listParticipants(paid.getId());

        assertEquals(2, participants.size(), "两个人都应当在名单里");
        assertEquals(HOST_ID, participants.get(0).getUserId(),
                "发起人必须排在最前 —— 只按加入时刻排的话，"
                        + "包场人付款晚于别人点链接时他就会跑到名单中间去");
        assertEquals(BookingParticipantRole.HOST.getText(), participants.get(0).getRoleText(),
                "角色文案由后端翻译好，前端不必再维护一份映射");
    }

    @Test
    @DisplayName("判定：包场人与被邀请者都算参与者，外人不是")
    void isParticipant_coversBothRolesAndRejectsOutsider() {
        Booking paid = seedPaidBooking();
        participantMapper.seed(paid.getId(), HOST_ID, BookingParticipantRole.HOST.name());
        participantMapper.seed(paid.getId(), GUEST_ID, BookingParticipantRole.PARTICIPANT.name());

        assertTrue(bookingService.isParticipant(paid.getId(), HOST_ID), "包场人也是参与者");
        assertTrue(bookingService.isParticipant(paid.getId(), GUEST_ID), "被邀请者是参与者");
        assertFalse(bookingService.isParticipant(paid.getId(), ADMIN_ID),
                "没加入过的人不是参与者 —— 准入就是靠这一条把他挡在门外");
        assertFalse(bookingService.isParticipant(null, GUEST_ID), "包场 ID 为空时返回 false");
        assertFalse(bookingService.isParticipant(paid.getId(), null), "用户 ID 为空时返回 false");
    }

    @Test
    @DisplayName("下单前查包场：取最近的、尚未结束的已付款场次")
    void findUpcomingBookingForParticipant_picksNearestUnfinished() {
        LocalDateTime now = LocalDateTime.now();
        Booking later = bookingMapper.seed(booking(now.plusDays(2),
                now.plusDays(2).plusHours(4), BookingStatus.PAID));
        Booking nearer = bookingMapper.seed(booking(now.plusHours(3),
                now.plusHours(7), BookingStatus.PAID));
        participantMapper.seed(later.getId(), GUEST_ID, BookingParticipantRole.PARTICIPANT.name());
        participantMapper.seed(nearer.getId(), GUEST_ID, BookingParticipantRole.PARTICIPANT.name());

        Booking found = bookingService.findUpcomingBookingForParticipant(GUEST_ID, now);

        assertEquals(nearer.getId(), found.getId(),
                "取最近的一场 —— 挂哪一场对计费都无害（不相交会整段跳过），但越近越贴近他的意图");
    }

    @Test
    @DisplayName("下单前查包场：未付款与已结束的都不算")
    void findUpcomingBookingForParticipant_ignoresUnpaidAndEnded() {
        LocalDateTime now = LocalDateTime.now();
        Booking unpaid = bookingMapper.seed(booking(now.plusHours(1), now.plusHours(5),
                BookingStatus.PENDING_PAYMENT));
        Booking ended = bookingMapper.seed(booking(now.minusHours(5), now.minusHours(1),
                BookingStatus.PAID));
        participantMapper.seed(unpaid.getId(), GUEST_ID, BookingParticipantRole.PARTICIPANT.name());
        participantMapper.seed(ended.getId(), GUEST_ID, BookingParticipantRole.PARTICIPANT.name());

        assertNull(bookingService.findUpcomingBookingForParticipant(GUEST_ID, now),
                "未付款的不产生排他性、已结束的也没有意义 —— 挂上它们只会让订单"
                        + "凭空多出一个出处不明的包场 ID");
    }

    @Test
    @DisplayName("我参与的：只含被邀请的场次，不含自己发起的")
    void listJoinedBookings_returnsOnlyInvitedOnes() {
        LocalDateTime now = LocalDateTime.now();
        Booking mine = bookingMapper.seed(booking(now.plusHours(1), now.plusHours(5),
                BookingStatus.PAID));
        Booking invited = bookingMapper.seed(booking(now.plusHours(8), now.plusHours(12),
                BookingStatus.PAID));
        participantMapper.seed(mine.getId(), HOST_ID, BookingParticipantRole.HOST.name());
        participantMapper.seed(invited.getId(), HOST_ID, BookingParticipantRole.PARTICIPANT.name());

        BizResult<PageResult<BookingVo>> result = bookingService.listJoinedBookings(HOST_ID, 1, 10);

        assertTrue(result.isSuccess(), "查询应当成功");
        assertEquals(1, result.getData().getRecords().size(),
                "「我参与的」与「我创建的」是两个列表，同一场不该在两边各出现一次");
        assertEquals(invited.getId(), result.getData().getRecords().get(0).getId(),
                "只剩被邀请的那一场");
    }

    /**
     * 预置一场已付款的包场（一小时后开始）。
     *
     * @return 已落库的包场
     */
    private Booking seedPaidBooking() {
        LocalDateTime start = LocalDateTime.now().plusHours(1);
        return bookingMapper.seed(booking(start, start.plusHours(4), BookingStatus.PAID));
    }

    // 辅助
    // ==================================================================

    /**
     * 构造一个「明天某时刻」的时间点。
     *
     * <p>包场要求开始时间必须是将来，用固定的历史时刻会让创建接口提前失败，
     * 测不到后面的规则。
     *
     * @param hour   小时
     * @param minute 分钟
     * @return 明天该时刻
     */
    private static LocalDateTime tomorrowAt(int hour, int minute) {
        return LocalDateTime.now().plusDays(1).withHour(hour).withMinute(minute)
                .withSecond(0).withNano(0);
    }

    /**
     * 构造一个包场请求。
     *
     * @param startAt 开始时刻
     * @param endAt   结束时刻
     * @param price   价格
     * @return 请求对象，包场人固定为 {@link #HOST_ID}
     */
    private static CreateBookingRequest newRequest(LocalDateTime startAt, LocalDateTime endAt,
                                                   BigDecimal price) {
        CreateBookingRequest request = new CreateBookingRequest();
        request.setHostUserId(HOST_ID);
        request.setStartAt(startAt);
        request.setEndAt(endAt);
        request.setPrice(price);
        return request;
    }

    /**
     * 直接构造一条落在库里的包场记录（绕过 Service，用于预置特定状态）。
     *
     * @param startAt 开始时刻
     * @param endAt   结束时刻
     * @param status  状态
     * @return 包场实体
     */
    private static Booking booking(LocalDateTime startAt, LocalDateTime endAt, BookingStatus status) {
        Booking booking = new Booking();
        booking.setBookingNo("BK-TEST-" + status.name());
        booking.setStoreId(STORE_ID);
        booking.setHostUserId(HOST_ID);
        booking.setStartAt(startAt);
        booking.setEndAt(endAt);
        booking.setPrice(BigDecimal.TEN);
        booking.setStatus(status.name());
        return booking;
    }

    /**
     * 构造一条停业记录实体（绕过 Service，用于预置数据）。
     *
     * @param startAt 开始时刻
     * @param endAt   结束时刻
     * @param reason  原因
     * @return 停业实体
     */
    private static com.kaede.uspace.space.entity.Closure closure(
            LocalDateTime startAt, LocalDateTime endAt, String reason) {
        com.kaede.uspace.space.entity.Closure closure = new com.kaede.uspace.space.entity.Closure();
        closure.setStoreId(STORE_ID);
        closure.setStartAt(startAt);
        closure.setEndAt(endAt);
        closure.setReason(reason);
        return closure;
    }
}
