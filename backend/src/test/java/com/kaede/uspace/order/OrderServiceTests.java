package com.kaede.uspace.order;

import com.kaede.uspace.billing.BillingPeriod;
import com.kaede.uspace.billing.BillingProperties;
import com.kaede.uspace.billing.BillingService;
import com.kaede.uspace.billing.dto.BillingResult;
import com.kaede.uspace.billing.dto.SegmentBill;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.lock.LockProperties;
import com.kaede.uspace.lock.dto.AddPasscodeRequest;
import com.kaede.uspace.lock.mapper.FakeLockMapper;
import com.kaede.uspace.order.dto.AdjustOrderRequest;
import com.kaede.uspace.order.dto.CreateOrderRequest;
import com.kaede.uspace.order.dto.OrderOpenVo;
import com.kaede.uspace.order.dto.OrderPreviewVo;
import com.kaede.uspace.order.dto.OrderSettleVo;
import com.kaede.uspace.order.dto.OrderVo;
import com.kaede.uspace.order.dto.PaymentProofRequest;
import com.kaede.uspace.order.entity.Order;
import com.kaede.uspace.promotion.FakeMonthlyCardMapper;
import com.kaede.uspace.promotion.FakeMonthlyCardOrderMapper;
import com.kaede.uspace.promotion.MonthlyCardNo;
import com.kaede.uspace.promotion.MonthlyCardService;
import com.kaede.uspace.promotion.MonthlyCardStatus;
import com.kaede.uspace.promotion.MonthlyCardType;
import com.kaede.uspace.promotion.PromotionProperties;
import com.kaede.uspace.promotion.entity.MonthlyCard;
import com.kaede.uspace.space.BookingService;
import com.kaede.uspace.space.BookingStatus;
import com.kaede.uspace.space.ClosureService;
import com.kaede.uspace.space.FakeBookingMapper;
import com.kaede.uspace.space.FakeClosureMapper;
import com.kaede.uspace.space.FakeStoreMapper;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.entity.Closure;
import com.kaede.uspace.space.entity.Store;
import com.kaede.uspace.user.FakeSysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private final OrderProperties orderProperties = new OrderProperties();
    private final LockProperties lockProperties = new LockProperties();

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
    @DisplayName("下单：门店不存在时拒绝")
    void createOrder_failsWhenStoreMissing() {
        // 换一个没有门店的假 Mapper
        OrderService empty = new OrderService(orderMapper.asMapper(),
                new FakeStoreMapper().asMapper(), lockMapper.asMapper(),
                bookingMapper.asMapper(),
                new ClosureService(closureMapper.asMapper(), new FakeStoreMapper().asMapper()),
                new BookingService(bookingMapper.asMapper(), new FakeStoreMapper().asMapper(),
                        new ClosureService(closureMapper.asMapper(), new FakeStoreMapper().asMapper()),
                        userMapper.asMapper()),
                billingService, monthlyCardService, lockService,
                new InviteTokenService(bookingMapper.asMapper()),
                orderProperties, lockProperties);

        BizResult<OrderOpenVo> result = empty.createOrder(USER_ID, new CreateOrderRequest());

        assertEquals(ErrorCode.STORE_NOT_FOUND, result.getError(), "没有门店时无法下单");
    }

    @Test
    @DisplayName("下单：未配置门锁时拒绝")
    void createOrder_failsWhenLockMissing() {
        OrderService noLock = new OrderService(orderMapper.asMapper(), storeMapper.asMapper(),
                new FakeLockMapper().withoutLock().asMapper(),
                bookingMapper.asMapper(),
                new ClosureService(closureMapper.asMapper(), storeMapper.asMapper()),
                new BookingService(bookingMapper.asMapper(), storeMapper.asMapper(),
                        new ClosureService(closureMapper.asMapper(), storeMapper.asMapper()),
                        userMapper.asMapper()),
                billingService, monthlyCardService, lockService,
                new InviteTokenService(bookingMapper.asMapper()),
                orderProperties, lockProperties);

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
        seedCard(MonthlyCardType.ALL_DAY, LocalDate.now());
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE,
                LocalDateTime.now().minusHours(2), null);

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
        // 本月有一笔已支付订单，上月没有 —— 若按当前时刻归集，就会错误地把本月的算进来
        seedPaidOrder(USER_ID, BigDecimal.valueOf(300), LocalDateTime.now().minusDays(1));

        BizResult<OrderSettleVo> result = service.settleOrder(USER_ID, order.getId());

        assertTrue(result.isSuccess(), "结算应当成功");
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getData().getBill().getMonthSpentBefore()),
                "上月的订单要按上月的累计额判定优惠，否则历史订单的优惠会随当前月份漂移");
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

    @Test
    @DisplayName("凭证：只有待支付的订单能提交支付凭证")
    void submitPaymentProof_rejectsNonPendingOrder() {
        Order order = seedOrder(USER_ID, OrderStatus.IN_USE, LocalDateTime.now().minusHours(1), null);

        BizResult<Void> result = service.submitPaymentProof(USER_ID, order.getId(), proofRequest());

        assertEquals(ErrorCode.ORDER_STATUS_INVALID, result.getError(),
                "还在玩的订单没有账单可付，提交凭证没有意义");
    }

    @Test
    @DisplayName("凭证：提交后订单标记为人工核销并留在待支付")
    void submitPaymentProof_marksQrUploadAndKeepsPending() {
        Order order = seedOrder(USER_ID, OrderStatus.PENDING_PAYMENT, LocalDateTime.now().minusHours(2), null);

        BizResult<Void> result = service.submitPaymentProof(USER_ID, order.getId(), proofRequest());

        assertTrue(result.isSuccess(), "提交凭证应当成功");
        Order saved = orderMapper.get(order.getId());
        assertEquals("QR_UPLOAD", saved.getPaymentMethod(),
                "上传截图等于声明走人工核销，顺手把通道标上");
        assertEquals(OrderStatus.PENDING_PAYMENT.name(), saved.getStatus(),
                "等管理员核对到账才转已支付 —— 转账动作是管理员做的");
    }

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
                new ClosureService(closureMapper.asMapper(), storeMapper.asMapper()),
                new BookingService(bookingMapper.asMapper(), storeMapper.asMapper(),
                        new ClosureService(closureMapper.asMapper(), storeMapper.asMapper()),
                        userMapper.asMapper()),
                billingService, monthlyCardService, lockService,
                new InviteTokenService(bookingMapper.asMapper()),
                orderProperties, lockProperties);
    }

    /**
     * 构造被测的清场调度器。
     *
     * <p>各自新建一份 {@code BookingService} 而不是复用 {@code service} 里的那个：
     * 两者背后是<b>同一个</b> {@link FakeBookingMapper} 实例，
     * 数据本就是共享的，没必要为了省一个对象去改 {@link #newService} 的签名。
     *
     * @return 挂在当前假 Mapper 数据上的调度器
     */
    private BookingClearScheduler newScheduler() {
        return new BookingClearScheduler(
                new BookingService(bookingMapper.asMapper(), storeMapper.asMapper(),
                        new ClosureService(closureMapper.asMapper(), storeMapper.asMapper()),
                        userMapper.asMapper()),
                service);
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
     */
    private void seedPaidOrder(Long userId, BigDecimal amount, LocalDateTime startTime) {
        Order order = seedOrder(userId, OrderStatus.PAID, startTime, startTime.plusHours(1));
        order.setPayableAmount(amount);
        order.setTotalAmount(amount);
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
     * 构造一个支付凭证请求。
     *
     * @return 请求对象
     */
    private static PaymentProofRequest proofRequest() {
        PaymentProofRequest request = new PaymentProofRequest();
        request.setPaymentProof("/uploads/proof/2026/09/abc.png");
        return request;
    }

    /**
     * 带调用计数的计费服务。
     *
     * <p>用来断言「整段被包场覆盖时根本没有调用计费」——
     * 这比断言返回金额为 0 更强：后者在「调用了但算出 0」时也成立，
     * 而那种调用在区间为空的情况下会直接抛异常。
     */
    private static class CountingBillingService extends BillingService {

        private int calls = 0;

        CountingBillingService(BillingProperties properties) {
            super(properties);
        }

        @Override
        public BillingResult calculate(LocalDateTime startTime, LocalDateTime endTime,
                                       BigDecimal monthSpent) {
            calls++;
            return super.calculate(startTime, endTime, monthSpent);
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
