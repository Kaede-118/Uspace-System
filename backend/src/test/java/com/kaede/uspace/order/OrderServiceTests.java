package com.kaede.uspace.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kaede.uspace.billing.BillingPeriod;
import com.kaede.uspace.billing.BillingProperties;
import com.kaede.uspace.billing.BillingService;
import com.kaede.uspace.billing.CardCoverage;
import com.kaede.uspace.billing.FakeFreePeriodMapper;
import com.kaede.uspace.billing.FreePeriodService;
import com.kaede.uspace.billing.FreeRange;
import com.kaede.uspace.billing.dto.BillingResult;
import com.kaede.uspace.billing.dto.SegmentBill;
import com.kaede.uspace.billing.entity.FreePeriod;
import com.kaede.uspace.common.config.WebProperties;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.lock.LockProperties;
import com.kaede.uspace.lock.dto.AddPasscodeRequest;
import com.kaede.uspace.lock.mapper.FakeLockMapper;
import com.kaede.uspace.order.dto.AdjustOrderRequest;
import com.kaede.uspace.order.dto.CreateOrderRequest;
import com.kaede.uspace.order.dto.MonthSpentVo;
import com.kaede.uspace.order.dto.OrderBillSnapshot;
import com.kaede.uspace.order.dto.OrderOpenVo;
import com.kaede.uspace.order.dto.OrderPreviewVo;
import com.kaede.uspace.order.dto.OrderSettleVo;
import com.kaede.uspace.order.dto.OrderStatsVo;
import com.kaede.uspace.order.dto.OrderVo;
import com.kaede.uspace.order.entity.Order;
import com.kaede.uspace.order.event.OrderEnteredEvent;
import com.kaede.uspace.order.event.OrderLeftEvent;
import com.kaede.uspace.promotion.FakeMonthlyCardMapper;
import com.kaede.uspace.promotion.FakeMonthlyCardOrderMapper;
import com.kaede.uspace.promotion.MonthlyCardNo;
import com.kaede.uspace.promotion.MonthlyCardService;
import com.kaede.uspace.promotion.MonthlyCardStatus;
import com.kaede.uspace.promotion.MonthlyCardType;
import com.kaede.uspace.promotion.PromotionProperties;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import com.kaede.uspace.space.BookingParticipantRole;
import com.kaede.uspace.space.BookingService;
import com.kaede.uspace.space.BookingStatus;
import com.kaede.uspace.space.ClosureService;
import com.kaede.uspace.space.FakeBookingMapper;
import com.kaede.uspace.space.FakeBookingParticipantMapper;
import com.kaede.uspace.space.FakeClosureMapper;
import com.kaede.uspace.space.FakeStoreMapper;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.entity.Closure;
import com.kaede.uspace.space.entity.Store;
import com.kaede.uspace.user.FakeSysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OrderService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库。</b>数据访问由假 Mapper 顶替，
 * 门锁由 {@link FakeLockService} 顶替（它带调用计数，用来断言门锁云额度没有被白白消耗）。
 *
 * <p>本类覆盖的四块最容易写错的地方：
 * <ol>
 *   <li><b>三层准入的顺序与边界</b> —— 停业 → 包场 → 普通。包场那一层的
 *       「包场人放行」与「持令牌者放行」是两条独立路径，少一条就有整类人被挡在门外</li>
 *   <li><b>计费区间剪切</b> —— 包场时段不计费，但包场人可能<b>提前到店</b>，
 *       那时订单没挂包场 ID，只能按包场人身份回查。漏了这条，他会被重复计费：
 *       既付了包场费，又在包场时段内按分钟被收一次钱，而且不会报任何错</li>
 *   <li><b>门锁云额度</b> —— 被拒绝的请求绝不能消耗调用次数，
 *       所以断言的是「假门锁的调用计数为零」而不只是返回码</li>
 *   <li><b>失败不留脏数据</b> —— 密码下发失败时订单不能落库；
 *       密码已下发但落库失败时要把它撤销回来</li>
 * </ol>
 */
class OrderServiceTests {

    private static final Long USER_ID = 1001L;
    private static final Long OTHER_USER = 1002L;
    private static final Long STORE_ID = 1L;
    private static final Long LOCK_ID = 2001L;

    private final FakeOrderMapper orderMapper = new FakeOrderMapper();
    private final FakeStoreMapper storeMapper = new FakeStoreMapper();
    private final FakeBookingMapper bookingMapper = new FakeBookingMapper();
    private final FakeClosureMapper closureMapper = new FakeClosureMapper();
    private final FakeSysUserMapper userMapper = new FakeSysUserMapper();
    private final FakeLockService lockService = new FakeLockService();
    private final FakeLockMapper lockMapper = new FakeLockMapper();
    private final CountingBillingService billingService = new CountingBillingService(new BillingProperties());

    /** 月卡的两张表。默认没有任何卡，即绝大多数既有用例的场景 */
    private final FakeMonthlyCardMapper cardMapper = new FakeMonthlyCardMapper();
    private final FakeMonthlyCardOrderMapper cardOrderMapper = new FakeMonthlyCardOrderMapper();
    private final MonthlyCardService monthlyCardService = new MonthlyCardService(
            cardMapper.asMapper(), cardOrderMapper.asMapper(),
            new PromotionProperties(), new BillingProperties());

    /** 免费活动。默认一场都没有 —— 即绝大多数既有用例的场景 */
    private final FakeFreePeriodMapper freePeriodMapper = new FakeFreePeriodMapper();
    private final FreePeriodService freePeriodService =
            new FreePeriodService(freePeriodMapper.asMapper(), event -> { });

    private final OrderProperties orderProperties = new OrderProperties();
    private final LockProperties lockProperties = new LockProperties();

    /**
     * 收下被测服务发布的事件，供「该发的发了没有」这类断言使用。
     *
     * <p>{@code ApplicationEventPublisher} 是<b>函数式接口</b>（唯一抽象方法是
     * {@code publishEvent(Object)}），所以假发布器一个方法引用就够了，
     * 不必为它另写一个类。用「记下来」而不是「丢掉」，是因为事件的<b>缺席</b>
     * 同样是缺陷（被拒绝的开门不该播报「谁到店了」），而丢掉的假实现看不见缺席。
     */
    private final List<Object> publishedEvents = new ArrayList<>();

    /** 假的事件发布器 */
    private final ApplicationEventPublisher eventPublisher = publishedEvents::add;

    /**
     * 账单快照的序列化器。
     *
     * <p>测试里要手动注册 {@code JavaTimeModule}：生产环境那个 bean 由 Spring Boot
     * 自动装配把模块装齐，而 {@code new ObjectMapper()} 不认识 {@code LocalDateTime}，
     * 第一次写快照就会抛异常。生产与测试「谁来装模块」不同，但两边都遵守同一条规矩：
     * 写与读用同一个 mapper，格式因此自洽。
     */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    /**
     * 参与者表。与 {@code bookingMapper} 共享同一份包场数据 ——
     * 它的「我参与的列表」与「下单时该挂哪一场」在真实 SQL 里都要连 {@code biz_booking}。
     */
    private final FakeBookingParticipantMapper participantMapper =
            new FakeBookingParticipantMapper(bookingMapper);

    /**
     * 包场服务。<b>无状态</b>（只持有几个 Mapper 引用），整套用例共用一个即可 ——
     * 背后那套假 Mapper 本就是同一份数据，多 new 几个也看不到不同的东西。
     */
    private final BookingService bookingService = new BookingService(
            bookingMapper.asMapper(), storeMapper.asMapper(),
            new ClosureService(closureMapper.asMapper(), storeMapper.asMapper(), event -> { }),
            userMapper.asMapper(), participantMapper.asMapper(), event -> { });

    /** 邀请令牌服务。同样无状态，供包场付款与准入判定使用 */
    private final InviteTokenService inviteTokenService =
            new InviteTokenService(bookingMapper.asMapper(), bookingService, new WebProperties());

    /** 被测服务，每个用例前重建 */
    private OrderService service;

    @BeforeEach
    void setUp() {
        Store store = new Store();
        store.setId(STORE_ID);
        store.setName("测试门店");
        storeMapper.seed(store);
        lockMapper.withLock(LOCK_ID);

        service = newService("mock");
    }

    // ==================================================================
    // 下单：准入校验
    // ==================================================================

    @Test
    @DisplayName("下单：停业时段一律拒绝")
    void createOrder_rejectsWhenClosed() {
        seedClosure(LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertEquals(ErrorCode.STORE_CLOSED, result.getError(), "停业时段应当拒绝下单");
        assertEquals(0, orderMapper.size(), "被拒绝的请求不该留下订单");
        assertEquals(0, lockService.addCalls(), "被拒绝的请求不该消耗门锁云额度");
    }

    @Test
    @DisplayName("下单：包场时段拒绝路人，且不消耗门锁云额度")
    void createOrder_rejectsOutsiderDuringBooking() {
        seedActiveBooking(USER_ID + 99, null);

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertEquals(ErrorCode.BOOKING_ACCESS_DENIED, result.getError(),
                "包场时段内，既非包场人又没带令牌的请求应当被拒绝");
        assertEquals(0, lockService.addCalls(),
                "准入校验必须在密码下发之前 —— 被拒绝的请求一次额度都不该花");
    }

    @Test
    @DisplayName("下单：包场人本人放行，且订单挂上包场 ID")
    void createOrder_allowsHostDuringBooking() {
        Booking booking = seedActiveBooking(USER_ID, "token-abc");

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertTrue(result.isSuccess(), "包场人应当被放行");
        Order saved = orderMapper.get(result.getData().getOrderId());
        assertEquals(booking.getId(), saved.getBookingId(),
                "订单要记下关联的包场，结算时据此剪切计费区间");
    }

    @Test
    @DisplayName("下单：持有效邀请令牌的被邀请者放行")
    void createOrder_allowsGuestWithValidToken() {
        Booking booking = seedActiveBooking(USER_ID + 99, "token-abc");

        CreateOrderRequest request = new CreateOrderRequest();
        request.setInviteToken("token-abc");
        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, request);

        assertTrue(result.isSuccess(), "持有效令牌的被邀请者应当被放行");
        Order saved = orderMapper.get(result.getData().getOrderId());
        assertEquals(booking.getId(), saved.getBookingId(), "被邀请者的订单也要挂上包场 ID");
    }

    @Test
    @DisplayName("下单：在参与者表里的被邀请者，不带令牌也放行")
    void createOrder_allowsParticipantFromTable() {
        Booking booking = seedActiveBooking(USER_ID + 99, "token-abc");
        participantMapper.seed(booking.getId(), USER_ID,
                BookingParticipantRole.PARTICIPANT.name());

        // 刻意不带令牌 —— 落地页加载后已经自动 join 过，下单时不必再带着它
        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertTrue(result.isSuccess(),
                "他点过邀请链接、已经在名单里，系统没有理由再要求他带上令牌");
        Order saved = orderMapper.get(result.getData().getOrderId());
        assertEquals(booking.getId(), saved.getBookingId(), "订单同样要挂上包场 ID");
    }

    @Test
    @DisplayName("下单：比准入窗口更早到店的参与者，订单也要挂上那一场")
    void createOrder_attachesUpcomingBookingForEarlyParticipant() {
        // 包场三小时后才开始 —— 此刻还在准入窗口之外，谁都能进店
        LocalDateTime start = LocalDateTime.now().plusHours(3);
        Booking later = seedPaidBookingAt(USER_ID + 99, start, start.plusHours(4));
        participantMapper.seed(later.getId(), USER_ID, BookingParticipantRole.PARTICIPANT.name());

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertTrue(result.isSuccess(), "包场还没进窗口，他此刻就是个普通顾客，应当放行");
        Order saved = orderMapper.get(result.getData().getOrderId());
        assertEquals(later.getId(), saved.getBookingId(),
                "订单要提前挂上他参与的那一场 —— 否则包场开始时他会被当散客清场，"
                        + "结算时那个时段还会被重复计费（包场费已经付过一次）");
    }

    @Test
    @DisplayName("下单：与包场无关的散客，订单不挂包场 ID")
    void createOrder_leavesBookingIdNullWhenNotParticipant() {
        LocalDateTime start = LocalDateTime.now().plusHours(3);
        seedPaidBookingAt(USER_ID + 99, start, start.plusHours(4));

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertTrue(result.isSuccess(), "包场还没进窗口，散客照常进店");
        Order saved = orderMapper.get(result.getData().getOrderId());
        assertNull(saved.getBookingId(),
                "他不是那场包场的人，订单不该凭空挂上一个与本次消费无关的包场 ID —— "
                        + "那会让账单上多出一个说不清的包场出处");
    }

    @Test
    @DisplayName("早到店的被邀请者：下单时就挂上包场，包场开始时因此不会被清场")
    void earlyParticipant_isNotClearedAtBookingStart() {
        LocalDateTime start = LocalDateTime.now().plusHours(3);
        Booking later = seedPaidBookingAt(USER_ID + 99, start, start.plusHours(4));
        participantMapper.seed(later.getId(), USER_ID, BookingParticipantRole.PARTICIPANT.name());

        // ① 包场还没进准入窗口，他先到店 —— 订单在这一步挂上了那一场
        BizResult<OrderOpenVo> open = service.createOrder(USER_ID, new CreateOrderRequest());
        Order order = orderMapper.get(open.getData().getOrderId());
        assertEquals(later.getId(), order.getBookingId(), "前置条件：订单已挂上包场 ID");

        // ② 把时间往前推：他已经玩了一会儿，包场也开始了
        order.setStartTime(LocalDateTime.now().minusHours(1));
        later.setStartAt(LocalDateTime.now().minusMinutes(1));
        later.setEndAt(LocalDateTime.now().plusHours(4));

        int settled = service.settleNonParticipants(later);

        assertEquals(0, settled,
                "他是这场包场的参与者，不该被自己的包场清出去 —— 而这靠的就是"
                        + "下单时挂上的那个包场 ID，清场逻辑本身一行未改");
        assertEquals(OrderStatus.IN_USE.name(), orderMapper.get(order.getId()).getStatus(),
                "订单应当仍在进行中，密码也不该被撤销");
    }

    @Test
    @DisplayName("包场快结束时：给一条「之后按时长计费」的预告")
    void previewOrder_warnsWhenBookingIsAboutToEnd() {
        // 包场正在进行，且 12 分钟后结束 —— 小于默认的 15 分钟提前量
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        Booking ongoing = seedPaidBookingAt(USER_ID + 99, start, LocalDateTime.now().plusMinutes(12));
        participantMapper.seed(ongoing.getId(), USER_ID, BookingParticipantRole.PARTICIPANT.name());

        BizResult<OrderOpenVo> open = service.createOrder(USER_ID, new CreateOrderRequest());
        assertTrue(open.isSuccess(), "参与者在包场时段内应当能进店");

        BizResult<OrderPreviewVo> preview =
                service.previewOrder(USER_ID, open.getData().getOrderId());

        assertTrue(preview.isSuccess());
        assertNotNull(preview.getData().getNextChangeInSeconds(),
                "包场快结束了，要提前告诉用户「之后就开始计费」—— "
                        + "否则他会看着一个不动的 ¥0.00 毫无预兆地跳档");
        assertTrue(preview.getData().getNextChangeText().contains("包场结束"),
                "文案要说清是包场结束，而不是「进入下一档」：" + preview.getData().getNextChangeText());
    }

    @Test
    @DisplayName("下单：令牌错误时拒绝")
    void createOrder_rejectsWrongToken() {
        seedActiveBooking(USER_ID + 99, "token-abc");

        CreateOrderRequest request = new CreateOrderRequest();
        request.setInviteToken("wrong-token");
        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, request);

        assertEquals(ErrorCode.BOOKING_ACCESS_DENIED, result.getError(), "令牌对不上应当拒绝");
    }

    @Test
    @DisplayName("下单：包场还没付款时不产生排他性，普通用户照常放行")
    void createOrder_allowsEveryoneWhenBookingUnpaid() {
        // 库中有一场包场，但状态仍是待付款 —— 不该把散客挡在门外
        Booking unpaid = new Booking();
        unpaid.setBookingNo("BK202609281200000001");
        unpaid.setStoreId(STORE_ID);
        unpaid.setHostUserId(USER_ID + 99);
        unpaid.setStartAt(LocalDateTime.now().minusMinutes(10));
        unpaid.setEndAt(LocalDateTime.now().plusHours(2));
        unpaid.setStatus(BookingStatus.PENDING_PAYMENT.name());
        bookingMapper.seed(unpaid);

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertTrue(result.isSuccess(), "未付款的包场不产生排他性，散客应当能进店");
        assertNull(orderMapper.get(result.getData().getOrderId()).getBookingId(),
                "没有命中生效的包场，订单不该挂包场 ID");
    }

    // ------------------------------------------------------------------
    // 包场开始前的「预备窗口」——准入比包场本身更早开始生效
    //
    // 下面几条都用「相对当前时刻」拼包场区间，并留出分钟级余量：
    // 用例从构造包场到真正下单之间隔着几毫秒，卡在分界线上的断言会时灵时不灵。
    // 窗口的精确边界（含「恰好等于」的归属）由 BookingServiceTests 直接钉住，
    // 那里传的是固定时刻，不受执行耗时影响。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("下单：包场开始前 15 分钟内，散客不再被放行")
    void createOrder_rejectsOutsiderInPreparingWindow() {
        // 包场 10 分钟后开始 —— 落在预备窗口里。
        // 这时候放他进来，他打不完一局就要被清场，钱花了却没玩尽兴
        LocalDateTime now = LocalDateTime.now();
        seedPaidBookingAt(USER_ID + 99, now.plusMinutes(10), now.plusHours(2));

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertEquals(ErrorCode.BOOKING_PREPARING, result.getError(),
                "包场开始前的预备窗口内，散客应当被拒绝");
        assertEquals(0, lockService.addCalls(),
                "准入校验在密码下发之前 —— 被拒绝的请求一次额度都不该花");
    }

    @Test
    @DisplayName("下单：包场开始前 15 分钟之外，散客照常放行")
    void createOrder_allowsOutsiderBeforePreparingWindow() {
        // 包场 20 分钟后才开始，离预备窗口还有 5 分钟余量 —— 当下仍是普通营业时段
        LocalDateTime now = LocalDateTime.now();
        seedPaidBookingAt(USER_ID + 99, now.plusMinutes(20), now.plusHours(2));

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertTrue(result.isSuccess(), "预备窗口之外散客照常能进店，包场的排他性还不到时候");
    }

    @Test
    @DisplayName("下单：参与者到得太早同样会被拒绝")
    void createOrder_rejectsParticipantBeforeParticipantWindow() {
        // 包场 10 分钟后开始：已进预备窗口，但还没到参与者的提前量（5 分钟）
        LocalDateTime now = LocalDateTime.now();
        seedPaidBookingAt(USER_ID, now.plusMinutes(10), now.plusHours(2));

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertEquals(ErrorCode.BOOKING_NOT_STARTED, result.getError(),
                "参与者的提前量只有 5 分钟，再早进店仍按普通营业时段处理");
        assertEquals(0, lockService.addCalls(), "被拒绝的请求同样不该消耗门锁云额度");
    }

    @Test
    @DisplayName("下单：参与者可在包场开始前 5 分钟内入场")
    void createOrder_allowsParticipantInsideParticipantWindow() {
        // 包场 3 分钟后开始 —— 已在参与者的提前量之内，进店放东西、做开场准备
        LocalDateTime now = LocalDateTime.now();
        Booking booking = seedPaidBookingAt(USER_ID, now.plusMinutes(3), now.plusHours(2));

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertTrue(result.isSuccess(), "参与者应当可以提前几分钟进店");
        assertEquals(booking.getId(),
                orderMapper.get(result.getData().getOrderId()).getBookingId(),
                "提前入场的参与者订单同样要挂上包场 ID，结算时据此免除包场时段");
    }

    @Test
    @DisplayName("下单：包场结束后恢复普通放行")
    void createOrder_allowsEveryoneAfterBookingEnded() {
        LocalDateTime now = LocalDateTime.now();
        seedPaidBookingAt(USER_ID + 99, now.minusHours(2), now.minusMinutes(30));

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertTrue(result.isSuccess(), "包场已经结束，准入窗口随之关闭，散客照常能进");
    }

    @Test
    @DisplayName("下单：普通时段放行，订单不挂包场 ID")
    void createOrder_allowsNormalTime() {
        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertTrue(result.isSuccess(), "没有停业也没有包场时应当直接放行");
        Order saved = orderMapper.get(result.getData().getOrderId());
        assertEquals(OrderStatus.IN_USE.name(), saved.getStatus(),
                "点一次开门就进入使用中，不存在「已创建但没开门」的中间状态");
        assertNotNull(saved.getStartTime(), "开门即开始计费，计费起点必须有值");
        assertEquals("123456", saved.getPasscode(), "下发的密码要落库，供后续续期与撤销");
    }

    @Test
    @DisplayName("下单：传给锁的有效期窗口是配置的时长")
    void createOrder_requestsConfiguredValidity() {
        orderProperties.setPasscodeValidDuration(Duration.ofHours(12));

        service.createOrder(USER_ID, new CreateOrderRequest());

        AddPasscodeRequest sent = lockService.lastAddRequest();
        assertNotNull(sent, "应当向门锁下发了密码");
        assertEquals(LOCK_ID, sent.getLockId(), "下发的目标应当是当前门店的锁");
        assertEquals(720, Duration.between(sent.getStartTime(), sent.getEndTime()).toMinutes(),
                "有效期窗口应当是 12 小时 = 720 分钟");
    }

    @Test
    @DisplayName("下单：已有进行中的订单时拒绝，防连点两下拿到两个密码")
    void createOrder_rejectsWhenAlreadyInUse() {
        seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusMinutes(30), null);

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertEquals(ErrorCode.ORDER_ALREADY_ACTIVE, result.getError(),
                "连点两下会拿到两个密码、产生两条并行计费的订单，必须挡住");
        assertEquals(0, lockService.addCalls(), "防连点校验要在下发密码之前");
    }

    @Test
    @DisplayName("下单：有未支付的订单时拒绝 —— 欠着费不能接着玩")
    void createOrder_rejectsWhenUnpaidOrderExists() {
        // 另一个人也欠着费。挡的必须是「自己的」未付单，查漏了 user_id 会误伤所有人
        seedOrder(OTHER_USER, OrderStatus.PENDING_PAYMENT,
                LocalDateTime.now().minusHours(3), LocalDateTime.now().minusHours(2));
        Order unpaid = seedOrder(USER_ID, OrderStatus.PENDING_PAYMENT,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertEquals(ErrorCode.ORDER_UNPAID_EXISTS, result.getError(),
                "结算了但不付款就再开一单，等于欠着费接着玩 —— 这条原先没有拦");
        assertEquals(0, lockService.addCalls(), "被拒绝的请求一次门锁云额度都不该花");
        assertTrue(result.getMessage().contains(unpaid.getOrderNo()),
                "提示里要带上那笔订单的订单号，用户才知道该去付哪一笔");
    }

    @Test
    @DisplayName("下单：未支付的订单在错误码上与「进行中」区分开")
    void createOrder_unpaidOrderUsesDistinctErrorCode() {
        seedOrder(USER_ID, OrderStatus.PENDING_PAYMENT,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        // 两个码对应两个不同的可行动作：40919 去「结束使用」、本码去「去支付」。
        // 合并成一个码的话，前端只能给一句模糊提示，用户不知道自己该点哪个
        assertNotEquals(ErrorCode.ORDER_ALREADY_ACTIVE, result.getError(),
                "人已经走了、密码也撤销了，再提示「去结束使用」会把他引到一个点不动的按钮上");
        assertEquals(ErrorCode.ORDER_UNPAID_EXISTS, result.getError());
    }

    @Test
    @DisplayName("下单：上一笔已结清时不拦，老顾客照常能开新单")
    void createOrder_allowsWhenPreviousOrderPaid() {
        seedOrder(USER_ID, OrderStatus.PAID,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().minusDays(1).plusHours(2));

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertTrue(result.isSuccess(),
                "付过的单是已经了结的 —— 把 PAID 也算进「未结清」会让所有老顾客都进不了门");
    }

    @Test
    @DisplayName("下单：门店不存在时拒绝")
    void createOrder_failsWhenStoreMissing() {
        // 换一个没有门店的假 Mapper
        OrderService empty = new OrderService(orderMapper.asMapper(),
                new FakeStoreMapper().asMapper(), lockMapper.asMapper(),
                bookingMapper.asMapper(),
                new ClosureService(closureMapper.asMapper(), new FakeStoreMapper().asMapper(), event -> { }),
                bookingService,
                billingService, freePeriodService, monthlyCardService, lockService,
                inviteTokenService,
                orderProperties, lockProperties, eventPublisher, objectMapper);

        BizResult<OrderOpenVo> result = empty.createOrder(USER_ID, new CreateOrderRequest());

        assertEquals(ErrorCode.STORE_NOT_FOUND, result.getError(), "没有门店时无法下单");
    }

    @Test
    @DisplayName("下单：未配置门锁时拒绝")
    void createOrder_failsWhenLockMissing() {
        OrderService noLock = new OrderService(orderMapper.asMapper(), storeMapper.asMapper(),
                new FakeLockMapper().withoutLock().asMapper(),
                bookingMapper.asMapper(),
                new ClosureService(closureMapper.asMapper(), storeMapper.asMapper(), event -> { }),
                bookingService,
                billingService, freePeriodService, monthlyCardService, lockService,
                inviteTokenService,
                orderProperties, lockProperties, eventPublisher, objectMapper);

        BizResult<OrderOpenVo> result = noLock.createOrder(USER_ID, new CreateOrderRequest());

        assertEquals(ErrorCode.LOCK_NOT_CONFIGURED, result.getError(),
                "没有门锁就发不出密码，主链路在第一步就断了");
    }

    @Test
    @DisplayName("下单：密码下发失败时拒绝，且订单不落库")
    void createOrder_doesNotPersistWhenPasscodeFails() {
        lockService.failNextAdd();

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertEquals(ErrorCode.LOCK_CLOUD_UNAVAILABLE, result.getError(),
                "密码下发失败应当如实告知，而不是给一个语焉不详的 500");
        assertEquals(0, orderMapper.size(),
                "开门失败就不要产生订单 —— 否则会留下一条永远拿不到密码的挂单");
    }

    // ==================================================================
    // 事件发布（模块 11 的 QQ 群播报监听它们）
    // ==================================================================

    @Test
    @DisplayName("下单：开门成功会发布到店事件")
    void createOrder_publishesEnteredEvent() {
        publishedEvents.clear();

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertTrue(result.isSuccess(), "前置条件：这次开门应当成功");
        assertEquals(1, publishedEvents.size(), "一次成功的开门只发一条事件");
        OrderEnteredEvent event = (OrderEnteredEvent) publishedEvents.get(0);
        assertEquals(USER_ID, event.userId());
        assertNotNull(event.orderId(), "事件要带订单 ID —— 监听器靠它回溯");
        assertNotNull(event.enteredAt());
    }

    @Test
    @DisplayName("下单：被拒绝的开门不发布任何事件")
    void createOrder_doesNotPublishWhenRejected() {
        // 停业时段：走到准入那一步就被拒了，离落库还远
        seedClosure(LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        publishedEvents.clear();

        BizResult<OrderOpenVo> result = service.createOrder(USER_ID, new CreateOrderRequest());

        assertFalse(result.isSuccess(), "前置条件：停业时应当被拒");
        assertTrue(publishedEvents.isEmpty(),
                "被拒绝的请求绝不能播「谁谁到店了」—— 那条播报是假的，而且发到群里就撤不回来");
    }

    @Test
    @DisplayName("结算：发布离店事件，来源标记为用户自助")
    void settleOrder_publishesLeftEvent() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE,
                LocalDateTime.now().minusHours(2), null);
        publishedEvents.clear();

        service.settleOrder(USER_ID, order.getId());

        assertEquals(1, publishedEvents.size(), "一次结算只发一条事件");
        OrderLeftEvent event = (OrderLeftEvent) publishedEvents.get(0);
        assertEquals(USER_ID, event.userId());
        assertEquals(OrderLeftEvent.Source.USER, event.source(),
                "三条离店路径里只有「用户自助」是真的刚离店 —— 管理员补录那条是几小时前的事，"
                        + "播报措辞必须能分开，否则群里会出现假消息");
        assertNotNull(event.amount(), "金额随事件带出，供店主群的播报使用");
    }

    @Test
    @DisplayName("结算：⚠️ 两串密码都被撤销（固定密码 + 群指令发的一次性密码）")
    void settleOrder_revokesBothPasscodes() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE,
                LocalDateTime.now().minusHours(2), null);
        order.setPasscode("111111");
        order.setOneTimePasscode("222222");

        service.settleOrder(USER_ID, order.getId());

        assertTrue(lockService.wasDeleted(order.getLockId(), "111111"),
                "固定密码要撤 —— 不撤的话用户结算完还能再进去玩，账单与在店事实就对不上了");
        assertTrue(lockService.wasDeleted(order.getLockId(), "222222"),
                "⚠️ 一次性密码也要撤：它在群里发出去过、传播面最广，"
                        + "不撤就成了「传播越广的密码活得越久」—— 正好反了");
    }

    // ==================================================================
    // 查看密码与续期
    // ==================================================================

    @Test
    @DisplayName("查看密码：非本人一律 404（不泄露订单是否存在）")
    void currentPasscode_returns404ForOthersOrder() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(1), null);

        BizResult<OrderOpenVo> result = service.currentPasscode(OTHER_USER, order.getId());

        assertEquals(ErrorCode.ORDER_NOT_FOUND, result.getError(),
                "查别人的订单要返回和「不存在」一样的结果，否则可以被用来枚举订单号");
    }

    @Test
    @DisplayName("查看密码：订单不在使用中时拒绝")
    void currentPasscode_rejectsSettledOrder() {
        Order order = seedOrder(USER_ID, OrderStatus.PENDING_PAYMENT, LocalDateTime.now().minusHours(1), null);

        BizResult<OrderOpenVo> result = service.currentPasscode(USER_ID, order.getId());

        assertEquals(ErrorCode.ORDER_STATUS_INVALID, result.getError(),
                "结算时密码已被撤销，不该再展示给用户");
    }

    @Test
    @DisplayName("查看密码：未过期时直接返回，不调用门锁云")
    void currentPasscode_returnsUnexpiredWithoutCallingLock() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(1), null);
        order.setPasscode("654321");
        order.setPasscodeStart(LocalDateTime.now().minusMinutes(5));
        order.setPasscodeEnd(LocalDateTime.now().plusHours(6));

        BizResult<OrderOpenVo> result = service.currentPasscode(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "密码还有效，应当直接返回");
        assertEquals("654321", result.getData().getPasscode(), "返回的应当是原密码");
        assertFalse(result.getData().isRenewed(), "没有过期就不该标成续期");
        assertEquals(0, lockService.changeCalls(), "未过期时不该白花一次额度去改有效期");
    }

    @Test
    @DisplayName("查看密码：已过期时续期，密码本身不变")
    void currentPasscode_renewsExpiredWithoutChangingPasscode() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(20), null);
        order.setPasscode("654321");
        order.setPasscodeStart(LocalDateTime.now().minusHours(20));
        order.setPasscodeEnd(LocalDateTime.now().minusMinutes(1));

        BizResult<OrderOpenVo> result = service.currentPasscode(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "过期后应当自动续期，而不是让用户在门口干瞪眼");
        assertEquals("654321", result.getData().getPasscode(),
                "续期不换密码 —— 用户截图里的那串数字始终有效");
        assertFalse(result.getData().isRegenerated(), "走的是改有效期的主路径，没有重新下发");
        assertEquals(1, lockService.changeCalls(), "续期走 changePasscode，只花一次额度");
        assertEquals(0, lockService.deleteCalls(), "主路径下不该删除密码");
        assertEquals(720, Duration.between(lockService.lastChangeStart(),
                lockService.lastChangeEnd()).toMinutes(), "新的有效期窗口也该是配置的时长");
    }

    @Test
    @DisplayName("查看密码：续期失败时在 mock 下降级为重新下发")
    void currentPasscode_fallsBackToReissueWhenRenewFails() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(20), null);
        order.setPasscode("654321");
        order.setPasscodeEnd(LocalDateTime.now().minusMinutes(1));
        lockService.failAllChanges();
        lockService.withPasscode("999999");

        BizResult<OrderOpenVo> result = service.currentPasscode(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "模拟实现下续期失败应当降级为重新下发，否则开发期没法演示");
        assertEquals("999999", result.getData().getPasscode(), "降级后返回的是新密码");
        assertTrue(result.getData().isRegenerated(),
                "要告诉用户密码换了 —— 只标成 renewed 会让他继续用旧密码");
        assertEquals(1, lockService.deleteCalls(), "重新下发前要先撤销旧密码，锁上不该留下孤儿密码");
    }

    @Test
    @DisplayName("查看密码：真实 provider 下续期失败直接报错，不降级")
    void currentPasscode_doesNotFallBackForRealProvider() {
        service = newService("ttlock");
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(20), null);
        order.setPasscode("654321");
        order.setPasscodeEnd(LocalDateTime.now().minusMinutes(1));
        lockService.failAllChanges();

        BizResult<OrderOpenVo> result = service.currentPasscode(USER_ID, order.getId());

        assertEquals(ErrorCode.LOCK_CLOUD_UNAVAILABLE, result.getError(),
                "真实场景下续期失败通常是锁离线，重新下发同样会失败，只会白花一次额度");
        assertEquals(0, lockService.addCalls(), "不该尝试重新下发");
    }

    // ==================================================================
    // 结算
    // ==================================================================

    @Test
    @DisplayName("结算：非本人一律 404")
    void settleOrder_returns404ForOthersOrder() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(2), null);

        BizResult<OrderSettleVo> result = service.settleOrder(OTHER_USER, order.getId());

        assertEquals(ErrorCode.ORDER_NOT_FOUND, result.getError(), "别人的订单不该能结算");
    }

    @Test
    @DisplayName("结算：已经结束过的订单再点一次会被拒绝")
    void settleOrder_rejectsAlreadySettled() {
        Order order = seedOrder(USER_ID, OrderStatus.PENDING_PAYMENT, LocalDateTime.now().minusHours(2), null);

        BizResult<OrderSettleVo> result = service.settleOrder(USER_ID, order.getId());

        assertEquals(ErrorCode.ORDER_STATUS_INVALID, result.getError(), "重复结算应当被拒绝");
    }

    @Test
    @DisplayName("结算：正常算出分段金额并写入订单")
    void settleOrder_writesSegmentAmounts() {
        // 用相对时刻而非「今天的 11:00」—— 结算取的是当前时间，把开始时刻写成固定钟点，
        // 会让用例在 11:00 之前运行时算出负时长、金额被夹到 0，落到「0 元直通已支付」那条分支上。
        // 同理「此刻」落在哪个计费时段也随运行时刻漂移，所以这里只断言金额结构，不钉具体数字。
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, start, null);
        order.setPasscode("123456");

        BizResult<OrderSettleVo> result = service.settleOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "正常结算应当成功");
        Order saved = orderMapper.get(order.getId());
        assertEquals(OrderStatus.PENDING_PAYMENT.name(), saved.getStatus(), "结算后转入待支付");
        assertNotNull(saved.getEndTime(), "离场时刻要落库");
        assertNotNull(saved.getTotalAmount(), "实收合计要落库");
        assertEquals(saved.getTotalAmount(), saved.getPayableAmount(),
                "应付当前恒等于合计，将来有优惠券时才会分叉");
    }

    @Test
    @DisplayName("结算：活动期间整单免费，免单额落库")
    void settleOrder_withFreeActivity_zeroesAmount() {
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, start, null);
        order.setPasscode("123456");

        // 活动区间比订单宽两头：无论此刻落在哪个计费时段都被覆盖。
        // 写成固定钟点的话，某些时刻运行会算出负时长、落到「0 元直通」那条分支上，
        // 用例就测不到「算出金额再被活动免掉」这条真正的路径了
        FreePeriod activity = new FreePeriod();
        activity.setStoreId(STORE_ID);
        activity.setStartAt(start.minusHours(1));
        activity.setEndAt(LocalDateTime.now().plusHours(1));
        activity.setReason("测试活动");
        freePeriodMapper.seed(activity);

        BizResult<OrderSettleVo> result = service.settleOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "活动期间的订单照常能结算");
        Order saved = orderMapper.get(order.getId());
        assertEquals(0, BigDecimal.ZERO.compareTo(saved.getTotalAmount()),
                "活动期间不计费 —— 人照进、门照开，只是账单算 0");
        assertTrue(saved.getActivityFreeAmount().signum() > 0,
                "活动免掉的钱要落库：活动办完要复盘「送出去多少」，"
                        + "不记的话只能看到「这单 0 元」，事后说不出为什么");
    }

    @Test
    @DisplayName("结算：撤销密码，避免用户结算完还能再进去")
    void settleOrder_revokesPasscode() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(2), null);
        order.setPasscode("123456");

        service.settleOrder(USER_ID, order.getId());

        assertEquals(1, lockService.deleteCalls(),
                "结算要撤销密码 —— 不撤的话用户结算完还能再进去玩，账单与在店事实就对不上了");
    }

    @Test
    @DisplayName("结算：金额为 0 时直接结清，不留在待支付")
    void settleOrder_settlesZeroAmountDirectly() {
        // 刚开门 1 分钟就结束，落在免费档内
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now(), null);

        BizResult<OrderSettleVo> result = service.settleOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "免费档内结算应当成功");
        assertEquals(OrderStatus.PAID.name(), orderMapper.get(order.getId()).getStatus(),
                "0 元单没有可支付的通道，卡在待支付只会让用户看到一个「0 元去支付」的按钮");
        assertNotNull(orderMapper.get(order.getId()).getPaidAt(),
                "0 元结清也要记支付时刻，对账时要能区分「不用付」与「没记录」");
    }

    @Test
    @DisplayName("结算：月卡覆盖的时段免费，账单金额为 0 并直接结清")
    void settleOrder_freesTimeCoveredByCard() {
        // ⚠️ 起点先算出来，卡的生效日取【订单开始那一天】而不是「今天」：
        // 月卡的免单判定按【订单的开始日期】算（与在店名册、结算同源），
        // 而「2 小时前」在凌晨 0:00~2:00 之间会落到昨天 —— 卡是今天生效的，
        // 于是覆盖不到、金额不再是 0，用例只在深夜挂，白天怎么跑都是绿的。
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        seedCard(MonthlyCardType.ALL_DAY, start.toLocalDate());
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, start, null);

        BizResult<OrderSettleVo> result = service.settleOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "有月卡时结算应当成功");
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getData().getBill().getTotalAmount()),
                "全天卡覆盖所有时段 —— 这条走通即说明覆盖范围确实被传进了计费");
        assertEquals(OrderStatus.PAID.name(), orderMapper.get(order.getId()).getStatus(),
                "0 元单没有可支付的通道，直通已支付");

        Order saved = orderMapper.get(order.getId());
        assertTrue(saved.getCardFreeAmount().compareTo(BigDecimal.ZERO) > 0,
                "免掉的金额要落库，否则事后说不清「这单为什么是 0 元」");
        assertEquals(0, BigDecimal.ZERO.compareTo(saved.getDiscountAmount()),
                "月卡免掉的金额不能混进 discount_amount —— 那是月度优惠的口径，"
                        + "混进去不会报错，只会让统计里「优惠活动的效果」虚高");
    }

    @Test
    @DisplayName("结算：卡的生效日在订单之后时不免费（判定按订单开始日）")
    void settleOrder_ignoresCardNotYetEffective() {
        // 卡明天才生效，而这一单是今天开的
        seedCard(MonthlyCardType.ALL_DAY, LocalDate.now().plusDays(1));
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE,
                LocalDateTime.now().minusHours(2), null);

        BizResult<OrderSettleVo> result = service.settleOrder(USER_ID, order.getId());

        assertTrue(result.getData().getBill().getTotalAmount().compareTo(BigDecimal.ZERO) > 0,
                "判定用的是订单的开始日期而非「现在」—— 否则历史订单的免单结论"
                        + "会随当前时间漂移，事后重算还会变");
    }

    @Test
    @DisplayName("结算：整段落在包场内时金额为 0，且不调用计费服务")
    void settleOrder_skipsBillingWhenFullyCoveredByBooking() {
        LocalDateTime start = LocalDateTime.now().minusMinutes(30);
        Booking booking = seedPaidBookingAt(USER_ID, start.minusMinutes(10), LocalDateTime.now().plusHours(2));
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, start, null);
        order.setBookingId(booking.getId());
        int before = billingService.calls();

        BizResult<OrderSettleVo> result = service.settleOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "整段被包场覆盖时应当结算成功");
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getData().getBill().getTotalAmount()),
                "包场时段不计费，这段时间该收 0 元");
        assertEquals(before, billingService.calls(),
                "区间为空时绝不能交给计费服务 —— 它在结束早于开始时抛异常");
    }

    @Test
    @DisplayName("结算：包场结束后仍逗留的部分照常计费")
    void settleOrder_chargesTimeAfterBooking() {
        LocalDateTime bookingEnd = LocalDateTime.now().minusMinutes(30);
        LocalDateTime start = bookingEnd.minusHours(1);
        Booking booking = seedPaidBookingAt(USER_ID, start.minusMinutes(10), bookingEnd);
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, start, null);
        order.setBookingId(booking.getId());

        BizResult<OrderSettleVo> result = service.settleOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "包场结束后逗留的部分要正常计费");
        assertEquals(bookingEnd, result.getData().getBillingStart(),
                "计费起点被推到包场结束时刻");
        assertTrue(result.getData().isFreeByBooking(), "要告知用户包场时段被免除了");
    }

    @Test
    @DisplayName("结算：包场人提前到店时按包场人身份回查，不被重复计费")
    void settleOrder_findsBookingByHostWhenStartedEarly() {
        // 场景：10:00 到店（那时包场还没开始，订单不会挂包场 ID），
        //       14:00–18:00 是他的包场，19:00 走。
        //
        // 刻意走「人工调整」而不是「用户结算」：后者用的是当前时刻，
        // 而「此刻」落在哪个计费时段会让账单的段数随运行时刻变化 ——
        // 用相对时间写的用例会时灵时不灵，这种用例比没有还糟。
        // 取昨天是因为调整接口不接受未来时刻。
        LocalDate day = LocalDate.now().minusDays(1);
        LocalDateTime start = day.atTime(10, 0);
        LocalDateTime bookingStart = day.atTime(14, 0);
        LocalDateTime bookingEnd = day.atTime(18, 0);
        LocalDateTime end = day.atTime(19, 0);

        seedPaidBookingAt(USER_ID, bookingStart, bookingEnd);
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, start, null);
        assertNull(order.getBookingId(), "下单时包场还没开始，订单不会挂包场 ID");

        BizResult<OrderSettleVo> result = service.adjustOrder(order.getId(), adjustRequest(end), 9L);

        assertTrue(result.isSuccess(), "结算应当成功");
        assertTrue(result.getData().isFreeByBooking(),
                "少了这条回查，包场人会被重复计费：既付了包场费，又在包场时段内按分钟被收一次");
        assertEquals(300, result.getData().getBill().getTotalMinutes(),
                "10:00–14:00 共 240 分钟、18:00–19:00 共 60 分钟，合计 300 分钟 —— "
                        + "包场那 4 小时必须被剪掉");
    }

    // ------------------------------------------------------------------
    // 包场开始的清场
    //
    // 准入只挡得住「新订单」，挡不住「已经在店里的人」。包场是独占时段，
    // 散客必须在此之前离场 —— 这件事由 settleNonParticipants 完成，
    // 调度器每分钟叫它一次（见 BookingClearScheduler）。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("清场：包场开始时，仍在店内的散客订单按包场开始时刻结算")
    void settleNonParticipants_settlesOutsiderAtBookingStart() {
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        LocalDateTime bookingStart = LocalDateTime.now().minusMinutes(5);
        Booking booking = seedPaidBookingAt(USER_ID + 99, bookingStart,
                LocalDateTime.now().plusHours(2));
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, start, null);
        order.setPasscode("123456");

        int settled = service.settleNonParticipants(booking);

        assertEquals(1, settled, "店里那名散客应当被结算掉");
        Order saved = orderMapper.get(order.getId());
        assertEquals(bookingStart, saved.getEndTime(),
                "计费截止到包场开始时刻，而不是清场实际执行的时刻 —— "
                        + "后者会让金额随调度器的执行时机浮动，同样的场景重算一遍就对不上");
        assertNotNull(saved.getTotalAmount(), "账单要落库");
        assertEquals(1, lockService.deleteCalls(),
                "清场要撤销密码 —— 不撤的话他结算完还能再进去，而包场时段只该有参与者在场");
    }

    @Test
    @DisplayName("清场：包场人自己不受影响")
    void settleNonParticipants_skipsHost() {
        LocalDateTime bookingStart = LocalDateTime.now().minusMinutes(5);
        Booking booking = seedPaidBookingAt(USER_ID, bookingStart,
                LocalDateTime.now().plusHours(2));
        // 包场人提前到店：下单时包场还没开始，订单不会挂 bookingId
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE,
                LocalDateTime.now().minusHours(2), null);

        int settled = service.settleNonParticipants(booking);

        assertEquals(0, settled, "包场人是参与者，不该被自己的包场清出去");
        assertEquals(OrderStatus.IN_USE.name(), orderMapper.get(order.getId()).getStatus(),
                "订单应当原样留在使用中");
    }

    @Test
    @DisplayName("清场：被邀请者不受影响（订单挂在这场包场上）")
    void settleNonParticipants_skipsInvitedGuest() {
        LocalDateTime bookingStart = LocalDateTime.now().minusMinutes(5);
        Booking booking = seedPaidBookingAt(USER_ID + 99, bookingStart,
                LocalDateTime.now().plusHours(2));
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE,
                LocalDateTime.now().minusHours(2), null);
        order.setBookingId(booking.getId());

        int settled = service.settleNonParticipants(booking);

        assertEquals(0, settled, "订单挂着这场包场的顾客就是被邀请者，不该被清场");
        assertEquals(OrderStatus.IN_USE.name(), orderMapper.get(order.getId()).getStatus(),
                "订单应当原样留在使用中");
    }

    @Test
    @DisplayName("清场：重复执行不会重复结算")
    void settleNonParticipants_isIdempotent() {
        LocalDateTime bookingStart = LocalDateTime.now().minusMinutes(5);
        Booking booking = seedPaidBookingAt(USER_ID + 99, bookingStart,
                LocalDateTime.now().plusHours(2));
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE,
                LocalDateTime.now().minusHours(2), null);
        order.setPasscode("123456");

        assertEquals(1, service.settleNonParticipants(booking), "第一轮把散客结算掉");
        assertEquals(0, service.settleNonParticipants(booking),
                "第二轮查不到仍在使用的订单 —— 调度器每分钟重复调用是安全的，"
                        + "也正因如此，系统不必维护「这场包场清过场没有」的状态");
        assertEquals(1, lockService.deleteCalls(), "密码只该被撤销一次，重复撤销是白花额度");
    }

    @Test
    @DisplayName("清场：包场开始后才开的单不按包场开始时刻倒着结算")
    void settleNonParticipants_skipsOrdersStartedAfterBooking() {
        LocalDateTime bookingStart = LocalDateTime.now().minusMinutes(5);
        Booking booking = seedPaidBookingAt(USER_ID + 99, bookingStart,
                LocalDateTime.now().plusHours(2));
        // 理论上不该存在 —— 包场开始后非参与者已经下不了单。
        // 真出现（比如人工补录）也不能把结算时刻倒着填到离场之前
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now(), null);

        int settled = service.settleNonParticipants(booking);

        assertEquals(0, settled, "开门时刻晚于包场开始的订单要被跳过");
        assertEquals(OrderStatus.IN_USE.name(), orderMapper.get(order.getId()).getStatus(),
                "订单应当原样留在使用中");
    }

    @Test
    @DisplayName("清场：店内没有散客时什么都不做")
    void settleNonParticipants_doesNothingWhenStoreEmpty() {
        Booking booking = seedPaidBookingAt(USER_ID + 99, LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now().plusHours(2));

        assertEquals(0, service.settleNonParticipants(booking), "店里没人，清场无事可做");
        assertEquals(0, lockService.deleteCalls(), "没有订单要结算时不该碰门锁云接口");
    }

    @Test
    @DisplayName("清场调度：有包场正在进行时把散客清出去")
    void scheduler_settlesOutsidersDuringBooking() {
        // seedActiveBooking 排的是 [now-1h, now+2h)，当前时刻正落在包场里；
        // 散客的订单在两小时前就开了，早于包场开始
        seedActiveBooking(USER_ID + 99, null);
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE,
                LocalDateTime.now().minusHours(2), null);
        order.setPasscode("123456");

        newScheduler().clearNonParticipants();

        assertEquals(OrderStatus.PENDING_PAYMENT.name(), orderMapper.get(order.getId()).getStatus(),
                "调度器要把「查当前包场」与「清场」串起来 —— 只测 settleNonParticipants "
                        + "验证不了这一环，它会不会被调用取决于这里");
        assertEquals(1, lockService.deleteCalls(), "清场同时撤销密码");
    }

    @Test
    @DisplayName("清场调度：店里没有包场时不碰任何订单")
    void scheduler_doesNothingWithoutBooking() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE,
                LocalDateTime.now().minusHours(2), null);

        newScheduler().clearNonParticipants();

        assertEquals(OrderStatus.IN_USE.name(), orderMapper.get(order.getId()).getStatus(),
                "没有包场在进行，散客照常玩");
        assertEquals(0, lockService.deleteCalls(), "也不该碰门锁云接口");
    }

    @Test
    @DisplayName("结算：月累计额按订单起始时间所属月份查，不按当前时刻")
    void settleOrder_queriesMonthSpentByOrderStartTime() {
        LocalDateTime lastMonth = LocalDateTime.now().minusMonths(1).withDayOfMonth(15).withHour(11);
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, lastMonth, null);
        // 本月有一笔已支付订单，上月没有 —— 若按当前时刻归集，就会错误地把本月的算进来。
        // ⚠️ 时刻取「本月 1 号 00:00」而不是「昨天」：月初的凌晨跑测试时，
        // 「昨天」还属于上个月，那笔钱会被算进上月、与订单同月，monthSpentBefore 就不再是 0
        seedPaidOrder(USER_ID, BigDecimal.valueOf(300),
                LocalDate.now().withDayOfMonth(1).atStartOfDay());

        BizResult<OrderSettleVo> result = service.settleOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "结算应当成功");
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getData().getBill().getMonthSpentBefore()),
                "上月的订单要按上月的累计额判定优惠，否则历史订单的优惠会随当前月份漂移");
    }

    @Test
    @DisplayName("在店时长：包场免掉的那几小时照样算在店里待着的时间")
    void settle_recordsStayMinutesIncludingBookingTime() {
        // 用人工调整把离场时刻钉死在过去，用例结果才不随运行时刻浮动
        LocalDateTime day = LocalDateTime.now().minusDays(1).toLocalDate().atTime(10, 0);
        LocalDateTime bookingStart = day.plusHours(4);
        LocalDateTime bookingEnd = day.plusHours(8);
        LocalDateTime leaveAt = day.plusHours(11);
        Booking booking = seedPaidBookingAt(USER_ID, bookingStart, bookingEnd);
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, day, null);
        order.setBookingId(booking.getId());

        service.adjustOrder(order.getId(), adjustRequest(leaveAt), 9L);

        Order saved = orderMapper.get(order.getId());
        assertEquals(660, saved.getStayMinutes(),
                "10:00 来、21:00 走，在店 11 小时 —— 包场那 4 小时他没离开过店");
        assertEquals(420, saved.getDayMinutes() + saved.getNightMinutes(),
                "计费只有 7 小时（10–14 与 18–21）—— 两个口径的差别正是这一列存在的理由，"
                        + "拿计费时长冒充在店时长，包场用户会看到「在店 0 分钟」");
    }

    @Test
    @DisplayName("在店时长：人工调整后按核实到的离场时刻重算")
    void adjust_recomputesStayMinutesFromVerifiedEndTime() {
        LocalDateTime start = LocalDateTime.now().minusHours(5).truncatedTo(ChronoUnit.SECONDS);
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, start, null);
        // 场景就是这一列注释里写的那个：用户忘了点结束，监控核实 2.5 小时后已离场
        LocalDateTime verified = start.plusMinutes(150);

        service.adjustOrder(order.getId(), adjustRequest(verified), 9L);

        assertEquals(150, orderMapper.get(order.getId()).getStayMinutes(),
                "按核实后的离场时刻重算 —— 沿用原值的话，改过的订单在「我的」页里还是旧时长");
    }

    // ==================================================================
    // 时长统计
    // ==================================================================

    @Test
    @DisplayName("统计：只算已支付的订单，在店未结账的那一单不算")
    void stats_countsOnlyPaidOrders() {
        // ⚠️ 时刻取「本月 1 号 00:00」而不是「昨天」：月初的凌晨跑测试时，
        // 「昨天」属于上个月，这一单会被算进上月，下面那条「本月同理」的断言就恒为 0
        seedPaidOrder(USER_ID, BigDecimal.TEN, LocalDate.now().withDayOfMonth(1).atStartOfDay())
                .setStayMinutes(90);
        // 还在玩的这一单：算进去的话，数字每刷新一次就往上跳一次，而它还没定局
        seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(2), null)
                .setStayMinutes(999);

        BizResult<OrderStatsVo> result = service.stats(USER_ID);

        assertTrue(result.isSuccess(), "统计应当成功");
        assertEquals(90L, result.getData().getTotalMinutes(), "只认已支付");
        assertEquals(90L, result.getData().getMonthMinutes(), "本月同理");
    }

    @Test
    @DisplayName("统计：累计含全部历史，本月只含本月，归月口径与月累计消费一致")
    void stats_splitsTotalAndMonthWithSameRangeAsMonthSpent() {
        LocalDateTime thisMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        // 紧挨着下月起点之前一秒 —— 半开区间下仍属于本月
        seedPaidOrder(USER_ID, BigDecimal.TEN, thisMonth.plusMonths(1).minusSeconds(1))
                .setStayMinutes(20);
        seedPaidOrder(USER_ID, BigDecimal.TEN, thisMonth.plusDays(1)).setStayMinutes(120);
        seedPaidOrder(USER_ID, BigDecimal.TEN, thisMonth.minusDays(5)).setStayMinutes(300);

        OrderStatsVo stats = service.stats(USER_ID).getData();
        MonthSpentVo spent = service.monthSpent(USER_ID).getData();

        assertEquals(440L, stats.getTotalMinutes(), "累计是全部历史，不受月份影响");
        assertEquals(140L, stats.getMonthMinutes(), "上月那单只进累计，不进本月");
        assertEquals(spent.getMonthStart(), stats.getMonthStart(),
                "两个数字在「我的」页并排显示，月份起点必须同源 —— "
                        + "各自算一遍的话，跨月那一刻会出现「消费算上月、时长算本月」的错位");
    }

    @Test
    @DisplayName("统计：没有任何订单时返回 0，不返回 null")
    void stats_returnsZeroWhenNoOrders() {
        OrderStatsVo stats = service.stats(USER_ID).getData();

        assertEquals(0L, stats.getTotalMinutes(), "0 与 null 在前端是「暂无数据」与「查询失败」的区别");
        assertEquals(0L, stats.getMonthMinutes());
        assertNotNull(stats.getMonthStart(), "月份起点照给，前端才知道这个 0 是哪个月的");
    }

    // ==================================================================
    // 结账预览
    // ==================================================================

    @Test
    @DisplayName("预览：非本人一律 404（不泄露订单是否存在）")
    void previewOrder_returns404ForOthersOrder() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(1), null);

        BizResult<OrderPreviewVo> result = service.previewOrder(OTHER_USER, order.getId());

        assertEquals(ErrorCode.ORDER_NOT_FOUND, result.getError(),
                "查别人的订单要返回和「不存在」一样的结果，否则可以被用来枚举订单号");
        assertEquals(0, lockService.deleteCalls(), "连被拒绝的预览也不该碰门锁");
    }

    @Test
    @DisplayName("预览：不在使用中的订单拒绝，已结束的看详情即可")
    void previewOrder_rejectsSettledOrder() {
        LocalDateTime settledAt = LocalDateTime.now().minusMinutes(10);
        Order order = seedOrder(USER_ID, OrderStatus.PENDING_PAYMENT,
                LocalDateTime.now().minusHours(2), settledAt);

        BizResult<OrderPreviewVo> result = service.previewOrder(USER_ID, order.getId());

        assertEquals(ErrorCode.ORDER_STATUS_INVALID, result.getError(),
                "待支付的订单已有确定的账单，看订单详情即可 —— 那里的金额比预估更准");
        assertEquals(settledAt, orderMapper.get(order.getId()).getEndTime(),
                "被拒绝的预览也不该动订单的任何字段");
    }

    @Test
    @DisplayName("预览：不撤销密码、不写库、不改状态（门锁云额度与用户进门的能力都不能动）")
    void previewOrder_leavesOrderAndLockUntouched() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(2), null);
        order.setPasscode("123456");

        BizResult<OrderPreviewVo> result = service.previewOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "预览应当成功");
        assertEquals(0, lockService.deleteCalls(),
                "预览绝不能撤销密码 —— 那会消耗一次门锁云额度（30000 次/月是硬约束），"
                        + "更要命的是用户点一下「结账」就再也进不去门了");
        assertEquals(0, lockService.addCalls(), "预览不该下发密码");
        assertEquals(0, lockService.changeCalls(), "预览不该续期密码");

        // 断言的是字段值而不是「没抛异常」：假 Mapper 的 updateSettlement 守卫只认使用中，
        // 若预览误调了它，假实现会安安静静地写成功 —— 只有逐字段比对才拦得住
        Order saved = orderMapper.get(order.getId());
        assertEquals(OrderStatus.IN_USE.name(), saved.getStatus(), "预览不改状态，计时还在走");
        assertNull(saved.getEndTime(), "预览不写离场时刻 —— 用户还没说他要走");
        assertNull(saved.getTotalAmount(), "预览不把金额落到订单上，那会污染结算时的真实账单");
        assertEquals("123456", saved.getPasscode(), "密码原样保留");
    }

    @Test
    @DisplayName("预览：预览金额与随后真实结算的金额一致（两处同源，不许各写一份）")
    void previewOrder_matchesSubsequentSettleAmount() {
        // 手法：用一场已付款包场把「可计费区间的尾巴」钉死在过去 ——
        // 计费区间 = [开门时刻, 包场开始时刻)，两端都是固定时刻，与「此刻」无关。
        // 于是预览与结算之间隔的那几秒不会改变任何一段的分钟数与金额，
        // 这条用例才不会时灵时不灵。
        //
        // 刻意不用「已封顶的订单」：封顶只能保证那一段不再涨，跨过日场夜场的边界
        // 仍会多出一段，遇到恰好卡在档位边界的秒数照样翻车。
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime orderStart = now.minusHours(6);
        LocalDateTime bookingStart = now.minusHours(2);
        Booking booking = seedPaidBookingAt(USER_ID, bookingStart, now.plusHours(3));
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, orderStart, null);
        order.setBookingId(booking.getId());

        BizResult<OrderPreviewVo> preview = service.previewOrder(USER_ID, order.getId());
        BizResult<OrderSettleVo> settle = service.settleOrder(USER_ID, order.getId());

        assertTrue(preview.isSuccess(), "预览应当成功");
        assertTrue(settle.isSuccess(), "结算应当成功");
        BillingResult previewBill = preview.getData().getBill();
        BillingResult settleBill = settle.getData().getBill();

        assertTrue(previewBill.getTotalAmount().compareTo(BigDecimal.ZERO) > 0,
                "本用例要有实际金额 —— 0 元账单等于什么都没验");
        assertEquals(240L, previewBill.getTotalMinutes(),
                "6 小时前开门、2 小时前包场，可计费的就是中间那 4 小时");
        // 金额比较用 compareTo 而非 equals：两边标度可能不同
        assertEquals(0, previewBill.getTotalAmount().compareTo(settleBill.getTotalAmount()),
                "预览金额必须等于实际结算金额 —— 两处各写一份剪切与计费逻辑的话，"
                        + "会算出看起来合理的错价且不报任何错");
        assertEquals(previewBill.getTotalMinutes(), settleBill.getTotalMinutes(),
                "计费时长也要一致");
        assertEquals(previewBill.getSegments(), settleBill.getSegments(),
                "分段明细逐字段一致，前端才能拿预览的结果解释最终账单");
        // 刻意不比账单的 endTime：一个是预览时刻、一个是真正停机的时刻，本来就该不同。
        // 要比的是「算出来的钱」，不是「算到哪一秒」
    }

    @Test
    @DisplayName("预览：整段被包场覆盖时，在店时长照给、计费时长为 0")
    void previewOrder_reportsStayDurationWhenFullyCoveredByBooking() {
        LocalDateTime start = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(30);
        Booking booking = seedPaidBookingAt(USER_ID, start.minusMinutes(10),
                LocalDateTime.now().plusHours(2));
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, start, null);
        order.setBookingId(booking.getId());
        int before = billingService.calls();

        BizResult<OrderPreviewVo> result = service.previewOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "预览应当成功");
        OrderPreviewVo vo = result.getData();
        assertTrue(vo.getStayMinutes() >= 30,
                "在店时长照给 —— 只给计费时长的话，包场用户会看到「本次用时 0 分钟」，像个 bug");
        assertEquals(0L, vo.getBill().getTotalMinutes(), "包场时段不计费，计费时长为 0");
        assertTrue(vo.isFreeByBooking(), "要告知用户包场时段被免除了");
        assertTrue(vo.isWillAutoSettle(), "0 元账单无需支付");
        assertEquals(before, billingService.calls(),
                "区间为空时绝不能交给计费服务 —— 它在结束早于开始时抛异常");
    }

    @Test
    @DisplayName("预览：包场结束后仍逗留的部分照常计费，计费起点推到包场结束")
    void previewOrder_reportsBookingFreeWhenPartiallyCovered() {
        LocalDateTime bookingEnd = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusHours(1);
        LocalDateTime start = bookingEnd.minusHours(1);
        Booking booking = seedPaidBookingAt(USER_ID, start.minusMinutes(10), bookingEnd);
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, start, null);
        order.setBookingId(booking.getId());

        BizResult<OrderPreviewVo> result = service.previewOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "预览应当成功");
        assertEquals(bookingEnd, result.getData().getBillingStart(),
                "计费起点被推到包场结束时刻 —— 前端不必自己推算剪切规则");
        assertTrue(result.getData().isFreeByBooking(), "要告知用户包场时段被免除了");
        assertTrue(result.getData().getBill().getTotalMinutes() >= 60,
                "包场结束后那 1 小时照常计费");
    }

    @Test
    @DisplayName("预览：0 元账单告知「无需支付」，停止后直接结清")
    void previewOrder_marksZeroAmountAsAutoSettle() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now(), null);

        BizResult<OrderPreviewVo> result = service.previewOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "刚开门的订单应当能预览");
        assertTrue(result.getData().isWillAutoSettle(),
                "免费档内停止计时不用付钱，前端要提示「本次无需支付」而不是引导去「去支付」");
        assertEquals(OrderStatus.PAID.name(), result.getData().getStatusAfterStop(),
                "0 元单直通已支付，不经过待支付");
        assertEquals("已支付", result.getData().getStatusAfterStopText(), "状态要给中文说明");
    }

    @Test
    @DisplayName("预览：有金额时告知停止后将转入待支付")
    void previewOrder_marksPayableAmountAsPendingPayment() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE,
                LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusHours(3), null);

        BizResult<OrderPreviewVo> result = service.previewOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "预览应当成功");
        assertTrue(result.getData().getBill().getTotalAmount().compareTo(BigDecimal.ZERO) > 0,
                "3 小时该有金额了");
        assertFalse(result.getData().isWillAutoSettle(), "有金额就要付钱");
        assertEquals(OrderStatus.PENDING_PAYMENT.name(), result.getData().getStatusAfterStop(),
                "停止计时后转待支付，与结算的状态流转一致");
    }

    @Test
    @DisplayName("预览：刚开始的订单不会标成已封顶")
    void previewOrder_reportsNotCappedForFreshOrder() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE,
                LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusHours(1), null);

        BizResult<OrderPreviewVo> result = service.previewOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "预览应当成功");
        assertFalse(result.getData().isCappedNow(),
                "1 小时远未到封顶起点（4 小时 36 分），不该标成已到顶");
    }

    @Test
    @DisplayName("预览：正在计费时给出「下一次账单变化」的预告")
    void previewOrder_reportsNextChangeWhileBilling() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE,
                LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(2), null);

        OrderPreviewVo vo = service.previewOrder(USER_ID, order.getId()).getData();

        assertNotNull(vo.getNextChangeText(),
                "用户看着计时器时最想知道的就是这个：现在停，还是再玩一会儿");
        assertNotNull(vo.getNextChangeInSeconds(),
                "刚开场 2 分钟，无论落在哪个档位都还有下一步（最近的是第 6 分钟那档）");
        assertTrue(vo.getNextChangeInSeconds() > 0, "剩余秒数必须是正数");
        assertTrue(vo.getNextChangeInSeconds() <= Duration.ofHours(12).toSeconds(),
                "再远也超不过一个完整的时段 —— 日场与夜场各 12 小时");
    }

    @Test
    @DisplayName("预览：全天卡覆盖时预告说的是「月卡免费」，而不是跳档")
    void previewOrder_reportsCardFreeInsteadOfNextTier() {
        seedCard(MonthlyCardType.ALL_DAY, LocalDate.now());
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE,
                LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(2), null);

        OrderPreviewVo vo = service.previewOrder(USER_ID, order.getId()).getData();

        assertEquals("当前时段月卡免费", vo.getNextChangeText(),
                "被月卡覆盖的段实收恒为 0 —— 这时给一句「还有 4 分钟进入下一档 ¥4.00」"
                        + "是假消息。本用例同时验证月卡覆盖范围确实被传进了预告");
        assertNull(vo.getNextChangeInSeconds(), "段内不会再有金额变化，不给倒计时");
    }

    @Test
    @DisplayName("预览：此刻落在包场时段里时不给跳档预告")
    void previewOrder_skipsNextChangeWhileInBooking() {
        // 计费区间在包场开始那一刻就定格了，而那一刻在过去 ——
        // 照常算会得出一个只对「包场前那段」成立的时刻，而那段早已结束
        LocalDateTime bookingStart = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusHours(1);
        Booking booking = seedPaidBookingAt(USER_ID, bookingStart, LocalDateTime.now().plusHours(3));
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, bookingStart.minusHours(4), null);
        order.setBookingId(booking.getId());

        OrderPreviewVo vo = service.previewOrder(USER_ID, order.getId()).getData();

        assertNull(vo.getNextChangeText(), "此刻没有正在计费的段，也就没有「下一档」可言");
        assertNull(vo.getNextChangeInSeconds(), "秒数一并留空，前端据此不显示这一行");
        assertTrue(vo.isFreeByBooking(), "这时候该说的是「包场时段不计费」，而不是跳档");
    }

    @Test
    @DisplayName("封顶判定：实收恰好等于封顶也算已封顶（与「超顶」不是一回事）")
    void allSegmentsCapped_trueWhenAmountReachesCap() {
        assertTrue(OrderService.allSegmentsCapped(
                        List.of(segment(new BigDecimal("40"), new BigDecimal("40"), false))),
                "第 10 档（4 小时 36 分起）金额恰好等于封顶、段上的 capped 是 false，"
                        + "但那之后金额不会再涨 —— 靠 capped 判断会漏报");
    }

    @Test
    @DisplayName("封顶判定：未达封顶时为 false")
    void allSegmentsCapped_falseWhenBelowCap() {
        assertFalse(OrderService.allSegmentsCapped(
                List.of(segment(new BigDecimal("40"), new BigDecimal("36"), false))));
    }

    @Test
    @DisplayName("封顶判定：没有计费段时为 false（整段被包场覆盖）")
    void allSegmentsCapped_falseWhenNoBillableSegment() {
        assertFalse(OrderService.allSegmentsCapped(List.of()),
                "零账单不是「已封顶」—— 包场结束后照样会重新计费");
    }

    @Test
    @DisplayName("封顶判定：多段中只要有一段未到顶就是 false")
    void allSegmentsCapped_falseWhenAnySegmentBelowCap() {
        assertFalse(OrderService.allSegmentsCapped(List.of(
                segment(new BigDecimal("40"), new BigDecimal("40"), false),
                segment(new BigDecimal("35"), new BigDecimal("21"), false))));
    }

    // ==================================================================
    // 计费区间剪切（纯函数）
    // ==================================================================

    @Test
    @DisplayName("剪切：没有包场时是完整的一段")
    void billableRanges_withoutBooking() {
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        LocalDateTime end = LocalDateTime.now();

        List<OrderService.TimeRange> ranges = OrderService.billableRanges(start, end, List.of());

        assertEquals(1, ranges.size(), "没有包场就是一段");
        assertEquals(start, ranges.get(0).from(), "起点不变");
        assertEquals(end, ranges.get(0).to(), "终点不变");
    }

    @Test
    @DisplayName("剪切：包场在中间时切成前后两段")
    void billableRanges_splitsAroundBooking() {
        LocalDateTime start = LocalDateTime.now().minusHours(6);
        LocalDateTime bookingStart = start.plusHours(2);
        LocalDateTime bookingEnd = start.plusHours(4);
        LocalDateTime end = start.plusHours(6);
        Booking booking = booking(bookingStart, bookingEnd);

        List<OrderService.TimeRange> ranges = OrderService.billableRanges(start, end, List.of(booking));

        assertEquals(2, ranges.size(), "包场前后的两段都要计费");
        assertEquals(bookingStart, ranges.get(0).to(), "第一段到包场开始为止");
        assertEquals(bookingEnd, ranges.get(1).from(), "第二段从包场结束开始");
        assertEquals(end, ranges.get(1).to(), "第二段到离场为止");
    }

    @Test
    @DisplayName("剪切：整段落在包场内时没有可计费区间")
    void billableRanges_fullyCovered() {
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        LocalDateTime end = LocalDateTime.now();
        Booking booking = booking(start.minusHours(1), end.plusHours(1));

        List<OrderService.TimeRange> ranges = OrderService.billableRanges(start, end, List.of(booking));

        assertTrue(ranges.isEmpty(), "全被包场覆盖时没有可计费区间");
    }

    @Test
    @DisplayName("剪切：包场与订单只是相邻时不算相交")
    void billableRanges_adjacentBookingDoesNotOverlap() {
        LocalDateTime start = LocalDateTime.now().minusHours(4);
        LocalDateTime end = LocalDateTime.now().minusHours(2);
        // 包场恰好从离场时刻开始 —— 半开区间下不算相交
        Booking booking = booking(end, end.plusHours(2));

        List<OrderService.TimeRange> ranges = OrderService.billableRanges(start, end, List.of(booking));

        assertEquals(1, ranges.size(), "相邻不算重叠是半开区间的直接结果");
        assertEquals(end, ranges.get(0).to(), "整段都要计费");
    }

    // ==================================================================
    // 人工调整
    // ==================================================================

    @Test
    @DisplayName("调整：已支付的订单不允许调整")
    void adjustOrder_rejectsPaidOrder() {
        Order order = seedOrder(USER_ID, OrderStatus.PAID, LocalDateTime.now().minusHours(3), null);

        BizResult<OrderSettleVo> result = service.adjustOrder(order.getId(), adjustRequest(LocalDateTime.now().minusHours(1)), 9L);

        assertEquals(ErrorCode.ORDER_NOT_ADJUSTABLE, result.getError(),
                "已入账的订单调整必然涉及退款，属于人工运营流程");
    }

    @Test
    @DisplayName("调整：使用中的订单调整后转待支付，账单才能收得回来")
    void adjustOrder_movesInUseToPendingPayment() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(5), null);
        // 服务端会把时间截断到秒（与库列精度一致），断言前也要对齐
        LocalDateTime realEnd = LocalDateTime.now().minusHours(2).truncatedTo(ChronoUnit.SECONDS);

        BizResult<OrderSettleVo> result = service.adjustOrder(order.getId(), adjustRequest(realEnd), 9L);

        assertTrue(result.isSuccess(), "使用中的订单应当可以调整");
        Order saved = orderMapper.get(order.getId());
        assertEquals(OrderStatus.PENDING_PAYMENT.name(), saved.getStatus(),
                "不转待支付的话，一条永远不会被点结束的订单就永远收不到钱");
        assertEquals(realEnd, saved.getEndTime(), "离场时刻按管理员核实的结果落库");
    }

    @Test
    @DisplayName("调整：写入四个调整字段留痕")
    void adjustOrder_recordsAuditFields() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(5), null);

        service.adjustOrder(order.getId(), adjustRequest(LocalDateTime.now().minusHours(2)), 9L);

        Order saved = orderMapper.get(order.getId());
        assertEquals(1, saved.getAdjusted(), "要标记为经过人工调整");
        assertEquals(9L, saved.getAdjustedBy(), "记下是谁改的");
        assertNotNull(saved.getAdjustedAt(), "记下改的时间");
        assertEquals("用户忘记结束，监控核实已离场", saved.getAdjustReason(), "记下为什么改");
    }

    @Test
    @DisplayName("调整：离场时刻早于开门时刻时拒绝")
    void adjustOrder_rejectsEndBeforeStart() {
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, start, null);

        BizResult<OrderSettleVo> result = service.adjustOrder(order.getId(), adjustRequest(start.minusMinutes(1)), 9L);

        assertEquals(ErrorCode.ORDER_STATUS_INVALID, result.getError(), "离场不能早于开门");
    }

    @Test
    @DisplayName("调整：离场时刻在未来时拒绝")
    void adjustOrder_rejectsFutureEnd() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(2), null);

        BizResult<OrderSettleVo> result = service.adjustOrder(order.getId(), adjustRequest(LocalDateTime.now().plusHours(1)), 9L);

        assertEquals(ErrorCode.ORDER_STATUS_INVALID, result.getError(),
                "不允许填未来时刻 —— 那等于凭空给出一条还没发生的账单");
    }

    // ==================================================================
    // 查询与凭证
    // ==================================================================

    @Test
    @DisplayName("查询：我的订单列表只包含本人的")
    void listMyOrders_returnsOnlyOwnOrders() {
        seedOrder(USER_ID, OrderStatus.PAID, LocalDateTime.now().minusHours(3), null);
        seedOrder(OTHER_USER, OrderStatus.PAID, LocalDateTime.now().minusHours(3), null);

        BizResult<?> result = service.listMyOrders(USER_ID, 1, 10, null);

        assertTrue(result.isSuccess(), "查询应当成功");
        assertEquals(1, ((com.kaede.uspace.common.result.PageResult<?>) result.getData()).getTotal(),
                "别人的订单不该出现在我的列表里");
    }

    @Test
    @DisplayName("查询：当前订单为 null 时不报错，前端据此决定按钮语义")
    void findCurrentOrder_returnsNullWhenIdle() {
        BizResult<OrderVo> result = service.findCurrentOrder(USER_ID);

        assertTrue(result.isSuccess(), "没有进行中的订单不是错误");
        assertNull(result.getData(), "没有就返回 null，让前端知道该显示「开门」而不是「查看密码」");
    }

    // ==================================================================
    // 分段账单：快照与老订单重算
    // ==================================================================

    @Test
    @DisplayName("快照：结算时把分段账单写进订单，详情据此展示「几档 × 单价」")
    void settleOrder_writesBillSnapshot() throws Exception {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(2), null);

        BizResult<OrderSettleVo> result = service.settleOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "正常结算应当成功");
        String json = orderMapper.get(order.getId()).getBillSnapshot();
        assertNotNull(json, "结算要把分段账单落库 —— 详情页的「几档 × 单价」全靠它");
        OrderBillSnapshot snapshot = objectMapper.readValue(json, OrderBillSnapshot.class);
        assertEquals(0, result.getData().getBill().getTotalAmount()
                        .compareTo(snapshot.getBill().getTotalAmount()),
                "快照与结算返回的是同一份账单的两个形态");
        assertFalse(snapshot.getBill().getSegments().isEmpty(),
                "segments 是快照存在的全部理由 —— 空列表等于什么都没存");
        assertFalse(snapshot.isFreeByBooking(), "这批用例没有包场，不该误标成包场减免");
    }

    @Test
    @DisplayName("快照：包场覆盖时记下 freeByBooking，供账单解释「计费时长 < 在店时长」")
    void settleOrder_snapshotRecordsBookingFree() throws Exception {
        LocalDateTime start = LocalDateTime.now().minusMinutes(30);
        Booking booking = seedPaidBookingAt(USER_ID, start.minusMinutes(10), LocalDateTime.now().plusHours(2));
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, start, null);
        order.setBookingId(booking.getId());

        service.settleOrder(USER_ID, order.getId());

        OrderBillSnapshot snapshot = objectMapper.readValue(
                orderMapper.get(order.getId()).getBillSnapshot(), OrderBillSnapshot.class);
        assertTrue(snapshot.isFreeByBooking(),
                "包场用户看到「在店 30 分钟、计费 0 分钟」时，全靠这个标记解释为什么");
    }

    @Test
    @DisplayName("详情：命中快照就直接用，不再重算")
    void getMyOrder_usesSnapshotWithoutRecomputing() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(2), null);
        service.settleOrder(USER_ID, order.getId());
        int before = billingService.calls();

        BizResult<OrderVo> result = service.getMyOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "详情查询应当成功");
        OrderVo vo = result.getData();
        assertNotNull(vo.getBill(), "详情要带上分段账单");
        assertFalse(vo.getBill().getSegments().isEmpty(), "分段明细是账单存在的理由");
        assertFalse(vo.getFreeByBooking(), "没有包场时为 false，前端据此决定要不要显示那句解释");
        assertEquals(before, billingService.calls(),
                "快照命中时不该再算一遍 —— 重算只留给没有快照的老订单");
    }

    @Test
    @DisplayName("详情：老订单（无快照）按当前规则重算，金额一致才展示")
    void getMyOrder_recomputesBillForLegacyOrder() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(2), null);
        service.settleOrder(USER_ID, order.getId());
        // 模拟快照机制上线前结算的老订单：金额都在，就是没有快照
        orderMapper.get(order.getId()).setBillSnapshot(null);

        BizResult<OrderVo> result = service.getMyOrder(USER_ID, order.getId());

        OrderVo vo = result.getData();
        assertNotNull(vo.getBill(), "重算金额与落库一致时照常展示分段");
        assertNull(vo.getBill().getMonthSpentBefore(),
                "重算无从得知「结算前」的月累计额，宁可空着也不显示一个编造的数字");
    }

    @Test
    @DisplayName("详情：老订单重算对不上时不展示账单（不拿重算冒充历史）")
    void getMyOrder_hidesBillWhenRecomputeMismatch() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(2), null);
        service.settleOrder(USER_ID, order.getId());
        Order saved = orderMapper.get(order.getId());
        saved.setBillSnapshot(null);
        saved.setTotalAmount(saved.getTotalAmount().add(new BigDecimal("10")));

        BizResult<OrderVo> result = service.getMyOrder(USER_ID, order.getId());

        assertNull(result.getData().getBill(),
                "重算对不上就不展示 —— 宁可回落到日场/夜场汇总，也不给一份假明细");
        assertNull(result.getData().getFreeByBooking(), "没有账单就不该有包场减免的说法");
    }

    @Test
    @DisplayName("详情：重算走「两算取一」，不受本单自身顶穿优惠门槛的影响")
    void getMyOrder_recomputeIgnoresMonthSelfCount() {
        // 复刻真实发生过的那类订单：跨 3 天，金额本身就超过 200 元优惠门槛
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusDays(3), null);
        service.settleOrder(USER_ID, order.getId());
        Order saved = orderMapper.get(order.getId());
        BigDecimal total = saved.getTotalAmount();
        assertTrue(total.compareTo(new BigDecimal("200")) >= 0,
                "用例前提：本单金额超过优惠门槛 —— 直接拿「该月已付之和」重算，"
                        + "本单会把自己顶过门槛算出优惠价，与落库对不上");
        // 结算后用户付了款（自身从此计入「该月已付」），快照则按老订单清掉
        saved.setStatus(OrderStatus.PAID.name());
        saved.setBillSnapshot(null);

        BizResult<OrderVo> result = service.getMyOrder(USER_ID, order.getId());

        OrderVo vo = result.getData();
        assertNotNull(vo.getBill(),
                "两种优惠状态各算一遍就该能复现 —— 用「该月已付之和」的话，"
                        + "本单自身会把门槛顶穿、算出优惠价，详情什么都看不到");
        assertEquals(0, total.compareTo(vo.getBill().getTotalAmount()),
                "命中的那一份必须与落库金额一致");
        assertFalse(vo.getBill().isDiscounted(), "命中的应当是原价口径那一份");
    }

    @Test
    @DisplayName("详情：结算之后才买的卡 / 才排的活动，不参与老订单重算")
    void getMyOrder_recomputeIgnoresLaterAddedCardAndActivity() {
        // 结算时既没有卡也没有活动 —— 按原价结算
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, start, null);
        service.settleOrder(USER_ID, order.getId());
        Order saved = orderMapper.get(order.getId());
        BigDecimal total = saved.getTotalAmount();
        assertTrue(total.signum() > 0, "这条用例需要一个非零金额的订单");
        saved.setBillSnapshot(null);

        // 结算之后：买了全天月卡，又排了一场覆盖订单区间的活动
        seedCard(MonthlyCardType.ALL_DAY, LocalDate.now().minusDays(1));
        FreePeriod activity = new FreePeriod();
        activity.setStoreId(STORE_ID);
        activity.setStartAt(start.minusHours(1));
        activity.setEndAt(LocalDateTime.now().plusHours(1));
        activity.setReason("事后补排的活动");
        freePeriodMapper.seed(activity);

        BizResult<OrderVo> result = service.getMyOrder(USER_ID, order.getId());

        OrderVo vo = result.getData();
        assertNotNull(vo.getBill(),
                "落库没减过卡与活动，重算就不该把它们算进来 —— 否则一个人只要买了卡，"
                        + "他买卡之前的全部历史订单都会重算失败、看不到分段");
        assertEquals(0, total.compareTo(vo.getBill().getTotalAmount()), "重算金额要与落库一致");
    }

    @Test
    @DisplayName("详情：快照损坏时回落重算，不把整页拽下来")
    void getMyOrder_toleratesCorruptSnapshot() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(2), null);
        service.settleOrder(USER_ID, order.getId());
        orderMapper.get(order.getId()).setBillSnapshot("{ 这不是 JSON");

        BizResult<OrderVo> result = service.getMyOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "快照坏掉不该让详情 500");
        assertNotNull(result.getData().getBill(), "应当落到重算那一路上 —— 金额本来就对得上");
    }

    @Test
    @DisplayName("详情：使用中的订单没有账单，费用去结账预览看")
    void getMyOrder_noBillForInUseOrder() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusMinutes(30), null);

        BizResult<OrderVo> result = service.getMyOrder(USER_ID, order.getId());

        assertNull(result.getData().getBill(), "费用还在走，那一刻的账单在预览接口里现算");
        assertNull(result.getData().getFreeByBooking(), "没有账单就不该有包场减免的说法");
    }

    @Test
    @DisplayName("后台详情：与用户端同一形状，同样返回分段账单")
    void getOrderForAdmin_returnsBill() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(2), null);
        service.settleOrder(USER_ID, order.getId());

        BizResult<OrderVo> result = service.getOrderForAdmin(order.getId());

        assertNotNull(result.getData().getBill(), "后台订单详情复用同一个 OrderVo");
    }

    @Test
    @DisplayName("调整：快照跟着重写，不留下调整前那一份")
    void adjustOrder_rewritesSnapshot() throws Exception {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(5), null);
        service.settleOrder(USER_ID, order.getId());

        // 管理员把离场时刻往后改一小时 —— 账单变大，快照必须跟着变
        service.adjustOrder(order.getId(), adjustRequest(LocalDateTime.now().minusHours(1)), 9L);

        Order saved = orderMapper.get(order.getId());
        OrderBillSnapshot snapshot = objectMapper.readValue(
                saved.getBillSnapshot(), OrderBillSnapshot.class);
        assertEquals(0, saved.getTotalAmount().compareTo(snapshot.getBill().getTotalAmount()),
                "快照是「当时那份账单」—— 调整重算了账单却不重写快照的话，"
                        + "详情页展示的还是调整前那一份，且不对任何人有提示");
    }

    // 说明：本类不再测「提交支付凭证」—— 2026-09-30 起凭证由
    // PaymentProofService 统一受理（四类收款共用），测试也随之搬到了
    // PaymentProofServiceTests。订单这一侧只保留「计费与结算」。

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 组装被测服务。
     *
     * @param provider 门锁 provider，用于验证续期的降级边界
     * @return 服务实例
     */
    private OrderService newService(String provider) {
        lockProperties.setProvider(provider);
        return new OrderService(orderMapper.asMapper(), storeMapper.asMapper(), lockMapper.asMapper(),
                bookingMapper.asMapper(),
                new ClosureService(closureMapper.asMapper(), storeMapper.asMapper(), event -> { }),
                bookingService,
                billingService, freePeriodService, monthlyCardService, lockService,
                inviteTokenService,
                orderProperties, lockProperties, eventPublisher,
                objectMapper);
    }

    /**
     * 构造被测的清场调度器。
     *
     * <p>复用字段里那份 {@code BookingService}：它无状态，背后连的也是同一套假 Mapper，
     * 与 被测的 {@code service} 共享同一份数据。
     *
     * @return 挂在当前假 Mapper 数据上的调度器
     */
    private BookingClearScheduler newScheduler() {
        return new BookingClearScheduler(bookingService, service);
    }

    /**
     * 预置一张生效中的月卡。
     *
     * @param type  卡种
     * @param start 生效日期。失效日按配置的 30 天（含首尾）推算
     */
    private void seedCard(MonthlyCardType type, LocalDate start) {
        MonthlyCard card = new MonthlyCard();
        card.setCardNo(MonthlyCardNo.generate());
        card.setUserId(USER_ID);
        card.setCardType(type.name());
        card.setPrice(new BigDecimal("600"));
        card.setStartDate(start);
        card.setEndDate(start.plusDays(29));
        card.setStatus(MonthlyCardStatus.ACTIVE.name());
        cardMapper.seed(card);
    }

    /**
     * 预置一条订单。
     *
     * @param userId  用户 ID
     * @param status  状态
     * @param start   计费起点
     * @param end     离场时刻，可为 null
     * @return 订单
     */
    private Order seedOrder(Long userId, OrderStatus status, LocalDateTime start, LocalDateTime end) {
        Order order = new Order();
        order.setOrderNo("OD" + System.nanoTime());
        order.setUserId(userId);
        order.setStoreId(STORE_ID);
        order.setLockId(LOCK_ID);
        order.setStartTime(start);
        order.setEndTime(end);
        order.setStatus(status.name());
        order.setDiscountAmount(BigDecimal.ZERO);
        return orderMapper.seed(order);
    }

    /**
     * 预置一条已支付的历史订单。
     *
     * @param userId    用户 ID
     * @param amount    实付金额
     * @param startTime 计费起点，决定它属于哪个月
     * @return 订单，供调用方继续补字段（如 {@code stayMinutes}）
     */
    private Order seedPaidOrder(Long userId, BigDecimal amount, LocalDateTime startTime) {
        Order order = seedOrder(userId, OrderStatus.PAID, startTime, startTime.plusHours(1));
        order.setPayableAmount(amount);
        order.setTotalAmount(amount);
        return order;
    }

    /**
     * 预置一段覆盖当前时刻的停业记录。
     *
     * @param start 开始时刻
     * @param end   结束时刻
     */
    private void seedClosure(LocalDateTime start, LocalDateTime end) {
        Closure closure = new Closure();
        closure.setStoreId(STORE_ID);
        closure.setStartAt(start);
        closure.setEndAt(end);
        closureMapper.seed(closure);
    }

    /**
     * 预置一场覆盖当前时刻的已付款包场。
     *
     * @param hostUserId  包场人
     * @param inviteToken 邀请令牌，可为 null
     * @return 包场
     */
    private Booking seedActiveBooking(Long hostUserId, String inviteToken) {
        return seedPaidBookingAt(hostUserId,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(2), inviteToken);
    }

    /**
     * 预置一场指定时段的已付款包场。
     *
     * @param hostUserId 包场人
     * @param startAt    开始时刻
     * @param endAt      结束时刻
     * @return 包场
     */
    private Booking seedPaidBookingAt(Long hostUserId, LocalDateTime startAt, LocalDateTime endAt) {
        return seedPaidBookingAt(hostUserId, startAt, endAt, null);
    }

    /**
     * 预置一场指定时段、指定令牌的已付款包场。
     *
     * @param hostUserId  包场人
     * @param startAt     开始时刻
     * @param endAt       结束时刻
     * @param inviteToken 邀请令牌，可为 null
     * @return 包场
     */
    private Booking seedPaidBookingAt(Long hostUserId, LocalDateTime startAt,
                                      LocalDateTime endAt, String inviteToken) {
        Booking booking = booking(startAt, endAt);
        booking.setHostUserId(hostUserId);
        booking.setInviteToken(inviteToken);
        booking.setStatus(BookingStatus.PAID.name());
        return bookingMapper.seed(booking);
    }

    /**
     * 构造一个未入库的包场对象。
     *
     * @param startAt 开始时刻
     * @param endAt   结束时刻
     * @return 包场
     */
    private Booking booking(LocalDateTime startAt, LocalDateTime endAt) {
        Booking booking = new Booking();
        booking.setBookingNo("BK" + System.nanoTime());
        booking.setStoreId(STORE_ID);
        booking.setStartAt(startAt);
        booking.setEndAt(endAt);
        booking.setPrice(BigDecimal.valueOf(100));
        booking.setStatus(BookingStatus.PAID.name());
        return booking;
    }

    /**
     * 构造一个只填了「封顶判定」所需字段的计费段。
     *
     * <p>只服务于 {@link OrderService#allSegmentsCapped} 的断言 ——
     * 它只看实收与封顶两个数，其余字段填占位值不影响结论。
     *
     * @param cap    该段封顶金额
     * @param amount 该段实收金额
     * @param capped 是否已超顶（注意：实收恰好等于封顶时这里应为 false）
     * @return 计费段
     */
    private static SegmentBill segment(BigDecimal cap, BigDecimal amount, boolean capped) {
        SegmentBill segment = new SegmentBill();
        segment.setPeriod(BillingPeriod.DAY);
        segment.setUnitPrice(new BigDecimal("4"));
        segment.setCapAmount(cap);
        segment.setRawAmount(amount);
        segment.setAmount(amount);
        segment.setCapped(capped);
        return segment;
    }

    /**
     * 构造一个人工调整请求。
     *
     * @param endTime 核实后的离场时刻
     * @return 请求对象
     */
    private static AdjustOrderRequest adjustRequest(LocalDateTime endTime) {
        AdjustOrderRequest request = new AdjustOrderRequest();
        request.setEndTime(endTime);
        request.setReason("用户忘记结束，监控核实已离场");
        return request;
    }

    /**
     * 带调用计数的计费服务。
     *
     * <p>用来断言「整段被包场覆盖时根本没有调用计费」——
     * 这比断言返回金额为 0 更强：后者在「调用了但算出 0」时也成立，
     * 而那种调用在区间为空的情况下会直接抛异常。
     *
     * <p>⚠️ 覆写的必须是<b>参数最全的 5 参重载</b>：{@code BillingService} 的那几层
     * 重载是 2 参 → 3 参 → 4 参 → 5 参<b>单向委派</b>的，而 {@code OrderService}
     * 调的是 5 参。覆写 3 参（本类最初的写法）根本拦不到任何一次调用，
     * 计数恒为 0 —— 两条计数断言因此静默变成了假绿，直到这一轮加「老订单重算」
     * 时才发现。新增覆写时先看清调用方真正调的是哪一层。
     */
    private static class CountingBillingService extends BillingService {

        private int calls = 0;

        CountingBillingService(BillingProperties properties) {
            super(properties);
        }

        @Override
        public BillingResult calculate(LocalDateTime startTime, LocalDateTime endTime,
                                       BigDecimal monthSpent, CardCoverage cardCoverage,
                                       List<FreeRange> freeRanges) {
            calls++;
            return super.calculate(startTime, endTime, monthSpent, cardCoverage, freeRanges);
        }

        /**
         * 取计费被调用的次数。
         *
         * @return 调用次数
         */
        int calls() {
            return calls;
        }
    }
}
