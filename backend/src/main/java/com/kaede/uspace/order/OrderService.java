package com.kaede.uspace.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.billing.BillingPeriod;
import com.kaede.uspace.billing.BillingService;
import com.kaede.uspace.billing.CardCoverage;
import com.kaede.uspace.billing.dto.BillingResult;
import com.kaede.uspace.billing.dto.NextChange;
import com.kaede.uspace.billing.dto.SegmentBill;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.lock.LockProperties;
import com.kaede.uspace.lock.LockService;
import com.kaede.uspace.lock.dto.AddPasscodeRequest;
import com.kaede.uspace.lock.dto.PasscodeResult;
import com.kaede.uspace.lock.mapper.LockMapper;
import com.kaede.uspace.order.dto.AdjustOrderRequest;
import com.kaede.uspace.order.dto.CreateOrderRequest;
import com.kaede.uspace.order.dto.MonthSpentVo;
import com.kaede.uspace.order.dto.OrderOpenVo;
import com.kaede.uspace.order.dto.OrderPreviewVo;
import com.kaede.uspace.order.dto.OrderSettleVo;
import com.kaede.uspace.order.dto.OrderStatsVo;
import com.kaede.uspace.order.dto.OrderVo;
import com.kaede.uspace.order.dto.PaymentProofRequest;
import com.kaede.uspace.order.entity.Order;
import com.kaede.uspace.order.mapper.OrderMapper;
import com.kaede.uspace.promotion.MonthlyCardService;
import com.kaede.uspace.space.BookingService;
import com.kaede.uspace.space.BookingStatus;
import com.kaede.uspace.space.ClosureService;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.mapper.BookingMapper;
import com.kaede.uspace.space.mapper.StoreMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * 订单服务（模块 8）。
 *
 * <p>职责：订单的完整生命周期 —— 点击开门、查看密码、结束使用、查询、
 * 管理员人工调整、提交支付凭证。
 *
 * <p><b>支付不在这里</b>：发起支付、回调处理、人工核销在 {@code PaymentService}。
 * 这样切开是因为两者的生命周期不同 —— 订单会「结束使用」后停在待支付状态，
 * 而支付可能几分钟后、也可能第二天才来（回调重推、主动查单）。
 * 分开之后，{@code OrderService} 不认识任何支付网关，将来换支付通道不必碰它。
 *
 * <p><b>计费也不在这里</b>：算钱是 {@code BillingService}（模块 7）的纯计算，
 * 本类只负责「决定算哪段时间」—— 也就是把包场时段从计费区间里剪掉，
 * 再把剩下的区间交给它。这条分工是清晰的：模块 7 管「怎么算」，模块 8 管「算哪段」。
 *
 * <h3>状态流转只有两跳</h3>
 * <pre>
 *   （点击开门 → 创建订单即进入）IN_USE ──结束使用──> PENDING_PAYMENT ──支付成功──> PAID
 *                                     └──── 结算为 0 元时直通 ────┘
 * </pre>
 * 点一次「开门」就同时完成「创建订单 + 下发密码 + 开始计费」，不存在
 * 「订单已创建但没开门」的挂起态。因此也没有「取消订单」——
 * 误点一下不想进店，点「结束使用」即可，5 分钟内落在免费档、0 元自动结清。
 *
 * <h3>门锁云额度</h3>
 * 通通锁免费额度 30,000 次/月是硬约束，本类把单次订单的调用控制在 2~3 次：
 * 开门时下发 1 次，结算时撤销 1 次，密码超过 12 小时才续期（第 3 次）。
 * <b>准入校验全部通过之后才下发密码</b> —— 被拒绝的请求一次额度都不消耗。
 */
@Slf4j
@Service
public class OrderService {

    /** 订单号的时间部分格式 */
    private static final DateTimeFormatter NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 门锁 provider 取该值时，密码续期失败可以降级为重新下发。理由见 {@link #renewPasscode} */
    private static final String PROVIDER_MOCK = "mock";

    private final OrderMapper orderMapper;
    private final StoreMapper storeMapper;
    private final LockMapper lockMapper;
    private final BookingMapper bookingMapper;
    private final ClosureService closureService;
    private final BookingService bookingService;
    private final BillingService billingService;
    private final MonthlyCardService monthlyCardService;
    private final LockService lockService;
    private final InviteTokenService inviteTokenService;
    private final OrderProperties orderProperties;
    private final LockProperties lockProperties;

    public OrderService(OrderMapper orderMapper,
                        StoreMapper storeMapper,
                        LockMapper lockMapper,
                        BookingMapper bookingMapper,
                        ClosureService closureService,
                        BookingService bookingService,
                        BillingService billingService,
                        MonthlyCardService monthlyCardService,
                        LockService lockService,
                        InviteTokenService inviteTokenService,
                        OrderProperties orderProperties,
                        LockProperties lockProperties) {
        this.orderMapper = orderMapper;
        this.storeMapper = storeMapper;
        this.lockMapper = lockMapper;
        this.bookingMapper = bookingMapper;
        this.closureService = closureService;
        this.bookingService = bookingService;
        this.billingService = billingService;
        this.monthlyCardService = monthlyCardService;
        this.lockService = lockService;
        this.inviteTokenService = inviteTokenService;
        this.orderProperties = orderProperties;
        this.lockProperties = lockProperties;
    }

    // ==================================================================
    // 点击开门
    // ==================================================================

    /**
     * 点击「开门」：创建订单、下发限时密码、开始计费 —— 一个事务里完成。
     *
     * <p>用户端只在这一次点击里做完所有事，不存在「先下单、再开门」两步。
     * 前端可以加二次确认弹窗，那是前端的事，后端只有这一个入口。
     *
     * <p><b>校验顺序是固定的：停业 → 包场 → 普通</b>（与设计文档的准入模型一致）。
     * 先看当前时刻是否落在停业区间，再看是否落在某个已付款包场区间内，
     * 都不命中就按普通订单放行。
     *
     * <p><b>为什么密码要在全部校验通过之后才下发</b>：门锁云额度是 30,000 次/月的
     * 硬约束，被拒绝的请求（停业、包场挡人、连点、没配锁）一次都不该消耗。
     *
     * <p><b>失败时不产生订单</b>：密码下发失败直接返回失败，订单根本不会写入。
     * 万一走到「密码已下发、落库却失败」这一步，会在 catch 里补一次撤销 ——
     * 门锁调用是外部副作用、不回滚，不补的话密码会孤零零留在锁上 12 小时。
     *
     * <p><b>一人同时只该有一条未结清的订单</b>：已经有 {@code IN_USE} 或
     * {@code PENDING_PAYMENT} 的订单时不再放行新单（见第 ④ 步）。
     * 前者是防连点，后者是防「欠着费接着玩」—— 两种情形给的是不同的错误码，
     * 因为用户看到之后该做的事不一样。
     *
     * @param userId  当前登录用户 ID
     * @param request 请求体，可携带包场邀请令牌
     * @return 成功时返回密码与有效期；失败时返回具体原因
     */
    @Transactional
    public BizResult<OrderOpenVo> createOrder(Long userId, CreateOrderRequest request) {
        // ① 门店与门锁必须就位。两者都缺一不可 —— 没有门店不知道去哪玩，
        //    没有门锁发不出密码。它们的解法相同（去执行建表脚本），但报错要分得清。
        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }
        Long lockId = lockMapper.selectCurrentId();
        if (lockId == null) {
            return BizResult.fail(ErrorCode.LOCK_NOT_CONFIGURED);
        }

        // 时间截断到秒：库列是 DATETIME（秒精度），而 MySQL 对亚秒是【四舍五入】而非截断，
        // 不截断会让写进去的值与内存里的值差最多 1 秒，参与区间比较时得出意外结论。
        // 模块 6 已经踩过这个坑，这里沿用同一习惯。
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        // ② 准入第一层：停业。停业期间一律拒绝新订单，已在店内的不受影响
        if (closureService.isClosedAt(now)) {
            return BizResult.fail(ErrorCode.STORE_CLOSED);
        }

        // ③ 准入第二层：包场。命中的窗口比「此刻正在包场中」更宽 —— 包场开始前的
        //    一段提前量内就停止接待新顾客，否则顾客刚付钱进场就被清场，钱花了却没玩尽兴
        Booking booking = bookingService.findAdmissionBookingAt(now,
                orderProperties.getBookingLeadDuration());
        if (booking != null) {
            BizResult<OrderOpenVo> denied = checkBookingAdmission(booking, now, userId,
                    request.getInviteToken());
            if (denied != null) {
                return denied;
            }
        } else {
            // 准入窗口没命中，他现在以散客身份进店 —— 但他可能参与了某场尚未结束的包场，
            // 只是来得比准入窗口更早（比如提前一小时到店里等人）。
            // 那一场要挂到订单上：清场与计费剪切读的都是订单上的 bookingId
            // （见 isBookingParticipant 与 findCoveringBookings），挂上这两条链路就都
            // 认得出他；不挂，包场开始时他会被当散客清场，结算时那个时段还会被重复计费 ——
            // 包场费已经付过一次了。
            //
            // 挂上一场还没到时间的包场是无害的：billableRanges 会先把包场区间夹到订单
            // 区间内，夹完为空就整段跳过，不会少收一分钱。
            booking = bookingService.findUpcomingBookingForParticipant(userId, now);
        }

        // ④ 上一单还没了结就不要再开：查的是 IN_USE + PENDING_PAYMENT 两种状态，
        //    对应两件不同的事，错误码与提示都分开给 ——
        //      · IN_USE 是「连点两下开门」：点一次就计费，抖一下手指会拿到两个密码、
        //        产生两条并行计费的订单，用户要付两份钱；
        //      · PENDING_PAYMENT 是「结算了但不付款」：账单已经出来还欠着，
        //        再开一单就是欠着费接着玩。这条是本次补上的缺口 ——
        //        早先只查 IN_USE，所以欠费的账号可以再开一单进场。
        //    两者对用户的可行动作完全不同（一个去「结束使用」、一个去「去支付」），
        //    合并成一个错误码的话，前端只能给一句模糊提示，用户不知道该点哪个。
        //
        //    ⚠️ 不用 selectActiveByUser：那个只认 IN_USE，是给 GET /api/orders/current 用的
        //    （首页靠它决定按钮是「开门」还是「查看密码」，把已结算的单塞进去会让用户
        //    看到一个「查看密码」，而那时密码早随结算撤销了）。两个查询不要合并。
        //
        //    这道校验必须在【下发密码之前】—— 放在后面就等于白白消耗一次额度。
        Order unsettled = orderMapper.selectUnsettledByUser(userId);
        if (unsettled != null) {
            if (OrderStatus.PENDING_PAYMENT.name().equals(unsettled.getStatus())) {
                return BizResult.fail(ErrorCode.ORDER_UNPAID_EXISTS,
                        "你有一笔未支付的订单（" + unsettled.getOrderNo() + "），请先完成支付");
            }
            return BizResult.fail(ErrorCode.ORDER_ALREADY_ACTIVE,
                    "你有一笔进行中的订单（" + unsettled.getOrderNo() + "），请先点「结束使用」");
        }

        // ⑤ 单号与密码有效期
        String orderNo = generateOrderNo();
        LocalDateTime passcodeEnd = now.plus(orderProperties.getPasscodeValidDuration());

        // ⑥ 下发限时密码。留空 keyboardPwd 让锁云随机生成 6 位数字 ——
        //    由锁云生成比自己造更贴近真实接口的行为（真实通通锁也是这个语义）
        AddPasscodeRequest passcodeRequest = new AddPasscodeRequest();
        passcodeRequest.setLockId(lockId);
        passcodeRequest.setStartTime(now);
        passcodeRequest.setEndTime(passcodeEnd);
        passcodeRequest.setKeyboardPwdName("订单 " + orderNo);

        PasscodeResult passcodeResult = lockService.addPasscode(passcodeRequest);
        if (!passcodeResult.isSuccess()) {
            log.warn("[订单] 下发密码失败，拒绝开门 orderNo={} errcode={} errmsg={}",
                    orderNo, passcodeResult.getErrcode(), passcodeResult.getErrmsg());
            return BizResult.fail(ErrorCode.LOCK_CLOUD_UNAVAILABLE,
                    "开门密码下发失败：" + passcodeResult.getErrmsg());
        }
        String passcode = passcodeResult.getKeyboardPwd();

        // ⑦ 落库。走到这里密码已经真的下发了，落库失败就必须把密码收回来
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setStoreId(storeId);
        order.setLockId(lockId);
        order.setBookingId(booking == null ? null : booking.getId());
        order.setPasscode(passcode);
        order.setPasscodeStart(now);
        order.setPasscodeEnd(passcodeEnd);
        order.setStartTime(now);
        order.setStatus(OrderStatus.IN_USE.name());
        try {
            orderMapper.insert(order);
        } catch (RuntimeException e) {
            lockService.deletePasscode(lockId, passcode);
            log.error("[订单] 落库失败，已撤销刚下发的密码 orderNo={}", orderNo, e);
            throw e;
        }

        log.info("[订单] 开门 orderNo={} userId={} 门店={} 密码有效期 {} ~ {} 包场={}",
                orderNo, userId, storeId, now, passcodeEnd,
                booking == null ? "无" : booking.getBookingNo());
        return BizResult.ok(OrderOpenVo.of(order, false, false));
    }

    // ==================================================================
    // 查看 / 续期密码
    // ==================================================================

    /**
     * 查看当前订单的门锁密码，密码过期时自动续期。
     *
     * <p><b>为什么需要这个入口</b>：用户玩到一半出门买水、吃饭，回来还要输密码。
     * 密码有效期 12 小时，跨过它就进不去了。这里让他再点一次就能拿到可用密码，
     * 而不必先结算再重新开一单（那会把一次到店切成两条订单，数据也难看）。
     *
     * <p><b>续期不换密码</b>：调门锁云的 {@code changePasscode} 改的是有效期窗口，
     * 密码数字不变 —— 用户截图转发出去的那串始终有效，锁上也不会越积越多密码，
     * 额度消耗也只有 1 次。只有续期失败时才降级为「撤销旧密码 + 下发新密码」。
     *
     * <p>本方法名为「查看」但有写副作用（续期要落库），所以加了事务。
     * 这与项目里「查询方法不加 {@code @Transactional}」的惯例不冲突 ——
     * 那条惯例针对的是纯读方法。
     *
     * @param userId  当前登录用户 ID
     * @param orderId 订单 ID
     * @return 成功时返回密码与有效期，并标明本次是否续期、是否换了密码；
     *         订单不存在或不属于该用户时返回 {@link ErrorCode#ORDER_NOT_FOUND}
     */
    @Transactional
    public BizResult<OrderOpenVo> currentPasscode(Long userId, Long orderId) {
        Order order = findOwnedOrder(userId, orderId);
        if (order == null) {
            return BizResult.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!OrderStatus.IN_USE.name().equals(order.getStatus())) {
            return BizResult.fail(ErrorCode.ORDER_STATUS_INVALID,
                    "只有进行中的订单才能查看开门密码");
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        if (order.getPasscodeEnd() != null && order.getPasscodeEnd().isAfter(now)) {
            return BizResult.ok(OrderOpenVo.of(order, false, false));
        }

        return renewPasscode(order, now);
    }

    /**
     * 把已过期（或即将过期）的密码续上一个新的有效期窗口。
     *
     * <p>两条路径：
     * <ol>
     *   <li><b>改有效期（主路径）</b> —— 密码数字不变，锁上不累积新密码</li>
     *   <li><b>重新下发（降级路径，仅 mock 实现）</b> —— 撤销旧密码、下发一个新的</li>
     * </ol>
     *
     * <p><b>为什么降级只在 mock 下启用</b>：模拟实现的密码存在内存 Map 里，
     * <b>进程重启即清空</b>，所以开发期「密码不存在」是常态，不降级就没法演示。
     * 而真实场景下 {@code changePasscode} 失败通常意味着锁离线或网关故障 ——
     * 那时重新下发同样会失败，只会白花一次额度（30,000 次/月是硬约束）。
     * 所以真实 provider 下直接报错，让管理员走应急接口人工补发。
     *
     * @param order 订单
     * @param now   当前时刻
     * @return 新的密码信息
     */
    private BizResult<OrderOpenVo> renewPasscode(Order order, LocalDateTime now) {
        LocalDateTime end = now.plus(orderProperties.getPasscodeValidDuration());

        PasscodeResult changed = lockService.changePasscode(
                order.getLockId(), order.getPasscode(), now, end);
        if (changed.isSuccess()) {
            orderMapper.updatePasscode(order.getId(), order.getPasscode(), now, end);
            // 同步内存对象，供下面的视图构造使用（不再回查一次库）
            order.setPasscodeStart(now);
            order.setPasscodeEnd(end);
            log.info("[订单] 续期密码（不换密码）orderNo={} 新有效期 {} ~ {}",
                    order.getOrderNo(), now, end);
            return BizResult.ok(OrderOpenVo.of(order, true, false));
        }

        if (!PROVIDER_MOCK.equals(lockProperties.getProvider())) {
            log.warn("[订单] 密码续期失败 orderNo={} errmsg={}", order.getOrderNo(), changed.getErrmsg());
            return BizResult.fail(ErrorCode.LOCK_CLOUD_UNAVAILABLE,
                    "密码续期失败：" + changed.getErrmsg());
        }

        // 降级：撤销旧密码（幂等，失败也无妨）后重新下发
        lockService.deletePasscode(order.getLockId(), order.getPasscode());

        AddPasscodeRequest request = new AddPasscodeRequest();
        request.setLockId(order.getLockId());
        request.setStartTime(now);
        request.setEndTime(end);
        request.setKeyboardPwdName("订单续期 " + order.getOrderNo());

        PasscodeResult reissued = lockService.addPasscode(request);
        if (!reissued.isSuccess()) {
            log.warn("[订单] 重新下发密码失败 orderNo={} errmsg={}",
                    order.getOrderNo(), reissued.getErrmsg());
            return BizResult.fail(ErrorCode.LOCK_CLOUD_UNAVAILABLE,
                    "密码续期失败：" + reissued.getErrmsg());
        }

        orderMapper.updatePasscode(order.getId(), reissued.getKeyboardPwd(), now, end);
        order.setPasscode(reissued.getKeyboardPwd());
        order.setPasscodeStart(now);
        order.setPasscodeEnd(end);
        log.warn("[订单] 续期失败已降级为重新下发密码 orderNo={}（旧密码作废）", order.getOrderNo());
        return BizResult.ok(OrderOpenVo.of(order, true, true));
    }

    // ==================================================================
    // 当前订单
    // ==================================================================

    /**
     * 查询当前进行中的订单。
     *
     * <p>用户端首页靠它决定「开门」按钮的语义：没有进行中的订单就是「创建并开门」，
     * 有就是「查看密码」。没有这个方法的话，前端只能靠试错 ——
     * 先调一遍开门、拿到 {@code ORDER_ALREADY_ACTIVE} 再改调查看密码。
     *
     * @param userId 当前登录用户 ID
     * @return 成功时返回订单视图，没有进行中的订单时 {@code data} 为 null
     */
    public BizResult<OrderVo> findCurrentOrder(Long userId) {
        return BizResult.ok(OrderVo.from(orderMapper.selectActiveByUser(userId)));
    }

    // ==================================================================
    // 结账预览
    // ==================================================================

    /**
     * 结账预览：算出「此刻停止计时的话要付多少」，计时照走、状态不变。
     *
     * <p><b>这是四步结账流程的第一步</b>：预览（本方法）→ 停止计时
     * （{@link #settleOrder}）→ 支付 → 订单结束。「结账」按钮点进来看到的就是本方法的返回，
     * 它展示的「时长 + 金额 + 是否封顶」就是那个确认环节 —— 用户看完再点「停止计时」
     * 即视为确认离场，后端不设二次确认弹窗，也没有反悔路径。
     *
     * <p><b>绝不能改用 {@code applySettlement}</b>：那个方法会写库（把订单转成
     * 待支付或已支付）并撤销门锁密码。用户只是点一下「结账」看看多少钱，密码就没了 ——
     * 他出不了门、也回不了店；而且每误触一次都要消耗一次门锁云额度
     * （30,000 次/月是硬约束）。本方法走 {@link #calculateBill}：
     * 只读、无副作用、不落库、不碰门锁云。
     *
     * <p><b>为什么预览价必然等于实际价</b>：两者都经 {@link #calculateBill}，
     * 对同一段区间套同一套规则（含包场剪除与月度优惠判定）。唯一的差别是截止时刻 ——
     * 预览用此刻，结算用用户真正按下按钮的那一刻，中间隔了几秒，金额可能因为
     * 跨过档位边界而跳一档。这不是缺陷：金额本来就随时长增长，用户在确认页看到的
     * 始终是那一刻的快照。也正因如此，前端要持续轮询本接口而不是打开页面就不管了 ——
     * 屏幕上的数字始终不超过十几秒旧，「确认」才是对当下一刻的确认。
     *
     * <p>本方法不加 {@code @Transactional}：全程只读，不改任何一行数据。
     *
     * @param userId  当前登录用户 ID
     * @param orderId 订单 ID
     * @return 预览视图；订单不存在或不属于该用户时返回
     *         {@link ErrorCode#ORDER_NOT_FOUND}（非本人一律 404，不泄露订单是否存在），
     *         订单不在使用中时返回 {@link ErrorCode#ORDER_STATUS_INVALID} ——
     *         已结束的订单看详情即可，那里的金额是真实的结算结果，没有预览的意义
     */
    public BizResult<OrderPreviewVo> previewOrder(Long userId, Long orderId) {
        Order order = findOwnedOrder(userId, orderId);
        if (order == null) {
            return BizResult.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!OrderStatus.IN_USE.name().equals(order.getStatus())) {
            return BizResult.fail(ErrorCode.ORDER_STATUS_INVALID,
                    "只有使用中的订单才能结账预览");
        }

        // 与真实结算同一个口径：截断到秒。库列是 DATETIME（秒精度），
        // MySQL 对亚秒是四舍五入而非截断，不截断会让预览的截止时刻
        // 与随后结算写入的 end_time 差最多 1 秒
        LocalDateTime previewAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        // 查一次包场、算一次账单 —— 与 applySettlement 走的是同一个三参重载。
        // 预览不得另写一份剪切或计费逻辑：两处一旦漂移，算出来的是看起来合理的错价，
        // 不报任何错，只在用户对比前后两次金额时才会被发现
        List<Booking> bookings = findCoveringBookings(order, previewAt);
        BillingResult bill = calculateBill(order, previewAt, bookings);

        return BizResult.ok(OrderPreviewVo.of(order, previewAt, bill,
                !bookings.isEmpty(), allSegmentsCapped(bill.getSegments()),
                statusAfterSettle(bill.getTotalAmount()), nextChange(order, bill, previewAt)));
    }

    /**
     * 算「下一次账单变化」的预告。
     *
     * <p><b>只在真正有段在计费时才给预告</b>：包场时段内不产生计费段，
     * 此时若照常算，会得出一个只对「包场前那段」成立的时刻 ——
     * 而那段早已结束，用户看到的是「还有 0 秒进入下一档」这类坏掉的信息。
     * 判据取账单最后一段的结束时刻是否就是此刻：包场挡在中间时它会更早。
     *
     * <p>起点传<b>当前计费段的起点</b>而不是整单的计费起点：订单可能已经跨过时段
     * （21:00 开始、现在 23:00），拿整单起点去算会得出一个属于日场的答案。
     *
     * <p>这里再查一次月卡覆盖范围，虽然 {@link #calculateBill} 刚查过一次 ——
     * 一次按用户与日期的单表查询，代价远小于为此把计费结果的结构撑大
     * （那会让模块 7 认识「月卡」这个业务名词，而它刻意不认识）。
     *
     * @param order 订单
     * @param bill  本次预览的账单
     * @param at    预览时刻
     * @return 预告；此刻没有正在计费的段时返回 null
     */
    private NextChange nextChange(Order order, BillingResult bill, LocalDateTime at) {
        List<SegmentBill> segments = bill.getSegments();
        if (segments.isEmpty()) {
            return null;
        }
        SegmentBill ongoing = segments.get(segments.size() - 1);
        if (ongoing.getEndTime().isBefore(at)) {
            // 当前时刻落在包场里：最后一段在包场开始那一刻就结束了
            return null;
        }
        return billingService.nextChange(ongoing.getStartTime(), at,
                bill.getMonthSpentBefore(), queryCardCoverage(order));
    }

    // ==================================================================
    // 结束使用
    // ==================================================================

    /**
     * 结束使用：算出账单、转待支付、撤销密码。
     *
     * <p>密码在结算时撤销 —— 不撤的话用户结算完还能再进去玩，账单与在店事实就对不上了。
     * 撤销是幂等的，失败只记警告<b>不回滚</b>：密码本身是限时的，
     * 撤销失败不构成安全漏洞（最多 12 小时后自动失效），而为此把已经算好的账单
     * 回滚掉、让用户重来一次，是更坏的选择。
     *
     * @param userId  当前登录用户 ID
     * @param orderId 订单 ID
     * @return 成功时返回分段账单；订单状态不对时返回 {@link ErrorCode#ORDER_STATUS_INVALID}
     */
    @Transactional
    public BizResult<OrderSettleVo> settleOrder(Long userId, Long orderId) {
        Order order = findOwnedOrder(userId, orderId);
        if (order == null) {
            return BizResult.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!OrderStatus.IN_USE.name().equals(order.getStatus())) {
            return BizResult.fail(ErrorCode.ORDER_STATUS_INVALID, "该订单已经结束过了");
        }

        LocalDateTime endTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        return BizResult.ok(applySettlement(order, endTime, null, null));
    }

    /**
     * 清场：包场开始时，把仍在店里的非参与者订单结算掉。
     *
     * <p><b>为什么需要它</b>：准入只能挡住「新订单」，挡不住「已经在店里的人」。
     * 包场是独占时段，散客必须在那之前离开 —— 包场开始时由定时任务触发本方法
     * （见 {@code BookingClearScheduler}），把人清出去、密码一并撤销。
     *
     * <p><b>计费截止到包场开始时刻</b>，而不是执行结算的当前时刻：包场开始之后
     * 这段时间本该只有参与者能用，把定时任务的执行延迟算进顾客账上并不合理。
     * 代价是任务偶尔晚跑几十秒、那几十秒不计费 —— 这一取舍对顾客有利，
     * 更实际的好处是<b>金额不随执行时机浮动</b>，同样的场景每次算出来都一样，
     * 事后核对账单时不会出现「同一笔单重算一遍金额变了」的困惑。
     *
     * <p><b>参与者不受影响</b>：包场人与被邀请者都要跳过 —— 少了这条，
     * 付了包场费的人会被自己的包场清出去。
     *
     * <p><b>天然幂等</b>：结算后订单不再是 {@code IN_USE}，下次扫描查不出来，
     * 因此调度器每分钟重复调用不会重复结算。也正因如此，本方法不必维护
     * 「这场包场清过场没有」的状态 —— 状态越多，越容易在重启、改期、
     * 并发扫描之间出现不一致。
     *
     * <p>结算走的是与用户自助结算同一个 {@code applySettlement}，不另写一份算钱逻辑：
     * 清场同样是「算账 + 转待支付 + 撤销密码」，唯一的差别是结算时刻的取值。
     *
     * @param booking 刚开始的包场；为 null 或缺少开始时刻时不做任何事
     * @return 本次被结算的订单数（0 表示店里没有需要清场的散客）
     */
    @Transactional
    public int settleNonParticipants(Booking booking) {
        if (booking == null || booking.getStartAt() == null) {
            return 0;
        }
        LocalDateTime startAt = booking.getStartAt();
        List<Order> active = orderMapper.selectActiveByStore(booking.getStoreId());

        int settled = 0;
        for (Order order : active) {
            // 包场开始后才开的单，在准入上就不该存在（那时非参与者已下不了单）。
            // 真出现（比如管理员补录）也不能按包场开始时刻倒着结算，直接跳过
            if (!order.getStartTime().isBefore(startAt)) {
                continue;
            }
            if (isBookingParticipant(booking, order)) {
                continue;
            }
            applySettlement(order, startAt, null, null);
            settled++;
            log.info("[订单] 包场清场 orderNo={} userId={} 计费截止={}",
                    order.getOrderNo(), order.getUserId(), startAt);
        }
        return settled;
    }

    /**
     * 判断某订单的顾客是不是这场包场的参与者。
     *
     * <p>两个条件任一成立即是：
     * <ol>
     *   <li>订单挂在这场包场上 —— 被邀请者凭令牌进店时记下的 {@code bookingId}</li>
     *   <li>下单人就是包场人本人 —— 覆盖「包场人提前到店、订单还没挂上 bookingId」
     *       的情形。少了这条，付了包场费的人会被自己的包场清出去</li>
     * </ol>
     *
     * @param booking 包场
     * @param order   订单
     * @return 是参与者时返回 true
     */
    private boolean isBookingParticipant(Booking booking, Order order) {
        if (booking.getId() != null && booking.getId().equals(order.getBookingId())) {
            return true;
        }
        return booking.getHostUserId() != null
                && booking.getHostUserId().equals(order.getUserId());
    }

    /**
     * 算出账单并落库，返回结算视图。
     *
     * <p><b>结算与人工调整共用这一段</b>，两条路径不允许各写一份算钱逻辑：
     * 「包场时段不计费」这条规则只要有一处实现漂移，就会算出看起来合理的错价，
     * 而不会报任何错 —— 这是最难发现的一类缺陷。
     *
     * @param order        订单（{@code endTime} / {@code status} 会被就地更新）
     * @param endTime      离场时刻
     * @param operatorId   操作的管理员 ID；为 null 表示用户自己结算
     * @param adjustReason 调整原因；为 null 表示用户自己结算
     * @return 结算视图
     */
    private OrderSettleVo applySettlement(Order order, LocalDateTime endTime,
                                          Long operatorId, String adjustReason) {
        List<Booking> bookings = findCoveringBookings(order, endTime);
        BillingResult bill = calculateBill(order, endTime, bookings);
        LocalDateTime billingStart = bill.getStartTime();

        BigDecimal total = bill.getTotalAmount();
        // 0 元单直接结清（判定见 statusAfterSettle）：它没有任何可支付的通道，
        // 卡在待支付只会让用户看到一个「0 元去支付」的按钮。
        // 判定抽出去是为了与结账预览共用 —— 预览说「本次无需支付」、结算却让用户付钱，
        // 是这类规则重复实现最典型的事故，且只在用户认真核对时才会被发现。
        String targetStatus = statusAfterSettle(total);
        boolean free = OrderStatus.PAID.name().equals(targetStatus);

        int dayMinutes = minutesOf(bill, BillingPeriod.DAY);
        int nightMinutes = minutesOf(bill, BillingPeriod.NIGHT);
        BigDecimal dayAmount = amountOf(bill, BillingPeriod.DAY);
        BigDecimal nightAmount = amountOf(bill, BillingPeriod.NIGHT);

        // 在店时长 = 离场时刻 − 开门时刻，与计费时长是两回事（包场时段被剪掉、
        // 宽限的 5 分钟也不计入）。它只用于展示与累计统计，不参与算钱。
        //
        // ⚠️ 起点取 order.getStartTime()（真正的开门时刻），不是 bill.getStartTime()
        // （实际计费起点）—— 包场人提前到店时后者晚几小时，用它会少算那段时间，
        // 而那正是「他明明在店里」的证据。同理终点取 endTime 而非 now()：
        // 包场清场的 endTime 是包场开始时刻，两者差着几十秒，取 now() 会与
        // end_time 列不自洽（看着像「离场时刻比在店时长算出来的还早」）
        int stayMinutes = (int) Duration.between(order.getStartTime(), endTime).toMinutes();

        BigDecimal cardFree = bill.getCardFreeAmount();
        if (operatorId == null) {
            orderMapper.updateSettlement(order.getId(), endTime, stayMinutes, dayMinutes,
                    dayAmount, nightMinutes, nightAmount, total, bill.getDiscountAmount(),
                    cardFree, total, targetStatus);
        } else {
            orderMapper.updateAdjustment(order.getId(), endTime, stayMinutes, dayMinutes,
                    dayAmount, nightMinutes, nightAmount, total, bill.getDiscountAmount(),
                    cardFree, total, targetStatus, operatorId, adjustReason);
        }

        // 同步内存对象，供视图构造使用
        order.setEndTime(endTime);
        order.setStayMinutes(stayMinutes);
        order.setStatus(targetStatus);
        order.setTotalAmount(total);
        order.setPayableAmount(total);
        order.setDiscountAmount(bill.getDiscountAmount());
        order.setCardFreeAmount(cardFree);
        if (free) {
            order.setPaidAt(endTime);
        }

        revokePasscode(order);
        log.info("[订单] 结算 orderNo={} 计费区间 {} ~ {} 合计={} 状态={}{}",
                order.getOrderNo(), billingStart, endTime, total, targetStatus,
                operatorId == null ? "" : "（管理员调整）");
        return OrderSettleVo.of(order, billingStart, !bookings.isEmpty(), bill);
    }

    /**
     * 撤销订单的门锁密码。
     *
     * <p><b>失败只记警告，绝不抛异常</b>：撤销是幂等的，且密码本身是限时的 ——
     * 撤不掉最多是「多有效一会儿」，而抛异常会把前面已经算好、写好的一切回滚掉。
     *
     * @param order 订单
     */
    private void revokePasscode(Order order) {
        if (order.getPasscode() == null || order.getLockId() == null) {
            return;
        }
        PasscodeResult result = lockService.deletePasscode(order.getLockId(), order.getPasscode());
        if (!result.isSuccess()) {
            log.warn("[订单] 撤销密码失败，将由有效期兜底 orderNo={} errmsg={}",
                    order.getOrderNo(), result.getErrmsg());
        }
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 分页查询我的订单。
     *
     * @param userId   当前登录用户 ID
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param status   状态筛选，可为 null
     * @return 分页结果
     */
    public BizResult<PageResult<OrderVo>> listMyOrders(Long userId, long pageNum,
                                                       long pageSize, String status) {
        IPage<Order> page = orderMapper.selectPageByUser(
                new Page<>(pageNum, pageSize), userId, trimToNull(status));
        return BizResult.ok(PageResult.of(page, OrderVo::from));
    }

    /**
     * 查询本月的累计消费与优惠资格。
     *
     * <p>与结算用的是同一套口径：只算本月<b>已支付</b>订单的实付额、
     * 不含月卡卡费、按订单开始时间归集。判定则直接调
     * {@link BillingService#isDiscounted}，不在别处重写一遍门槛比较。
     *
     * <p>月份取<b>当前自然月</b>（而不是某一单的月份）—— 这个接口回答的是
     * 「我现在算什么状态、下一单要花多少钱」，本来就是看当下。
     * 而结算时的判定仍按各单自己的开始时间，两者用途不同、口径不冲突。
     *
     * @param userId 当前登录用户
     * @return 本月累计额、门槛、是否已享优惠、还差多少
     */
    public BizResult<MonthSpentVo> monthSpent(Long userId) {
        MonthRange month = currentMonthRange();
        BigDecimal spent = orderMapper.selectMonthPaidAmount(userId, month.from(), month.to());
        BigDecimal actual = spent == null ? BigDecimal.ZERO : spent;

        BigDecimal threshold = billingService.discountThreshold();
        BigDecimal remaining = threshold.subtract(actual);

        MonthSpentVo vo = new MonthSpentVo();
        vo.setMonthStart(month.from().toLocalDate());
        vo.setMonthSpent(actual);
        vo.setThreshold(threshold);
        vo.setDiscounted(billingService.isDiscounted(actual));
        vo.setRemaining(remaining.compareTo(BigDecimal.ZERO) > 0 ? remaining : BigDecimal.ZERO);
        return BizResult.ok(vo);
    }

    /**
     * 查询我的累计在店时长与本月累计时长。
     *
     * <p>回答「我一共玩了多久、这个月玩了多久」——「我的」页与累计消费并排显示。
     * 消费答「花了多少钱」、时长答「玩了多久」，两个数字来自同一批订单，
     * 归月口径也必须一致，否则跨月那一刻会出现「消费算上月、时长算本月」的错位。
     *
     * <p><b>与 {@link #monthSpent} 分开而不是合并</b>：一个是时长、一个是金额，
     * 且后者还要算优惠资格（多两次门槛比较）。前端并发调两个只读聚合即可，
     * 合成一个接口只会让「只想要时长」的调用方白等一次金额查询。
     *
     * <p><b>只算已支付</b>，与消费口径一致 —— 把在店未结账的那一单算进去，
     * 数字每刷新一次就往上跳一次，而它还没定局。
     *
     * @param userId 当前登录用户
     * @return 累计在店分钟数、本月在店分钟数与本月起始日期
     */
    public BizResult<OrderStatsVo> stats(Long userId) {
        MonthRange month = currentMonthRange();
        Long total = orderMapper.selectTotalStayMinutes(userId);
        Long monthMinutes = orderMapper.selectMonthStayMinutes(userId, month.from(), month.to());

        OrderStatsVo vo = new OrderStatsVo();
        // 两个 COALESCE 兜底在 SQL 里，这里再兜一次 null 只为挡住假 Mapper 与
        // 将来换实现时的空值 —— 统计接口返回 null 会让前端显示成「--」，看起来像查询失败
        vo.setTotalMinutes(total == null ? 0L : total);
        vo.setMonthMinutes(monthMinutes == null ? 0L : monthMinutes);
        vo.setMonthStart(month.from().toLocalDate());
        return BizResult.ok(vo);
    }

    /**
     * 查询我的订单详情。
     *
     * <p>非本人的订单一律返回 {@link ErrorCode#ORDER_NOT_FOUND} 而非 403 ——
     * 403 等于承认「这个订单存在，只是不归你」，可以被用来枚举订单号。
     *
     * @param userId  当前登录用户 ID
     * @param orderId 订单 ID
     * @return 订单视图；不存在或不属于该用户时返回 404
     */
    public BizResult<OrderVo> getMyOrder(Long userId, Long orderId) {
        Order order = findOwnedOrder(userId, orderId);
        if (order == null) {
            return BizResult.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        return BizResult.ok(OrderVo.from(order));
    }

    /**
     * 后台分页查询订单。
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @param userId   用户 ID 筛选，可空
     * @param status   状态筛选，可空
     * @param from     计费起点下界（含），可空
     * @param to       计费起点上界（不含），可空
     * @param adjusted 是否经人工调整（0/1），可空
     * @return 分页结果
     */
    public BizResult<PageResult<OrderVo>> listOrders(long pageNum, long pageSize, Long userId,
                                                     String status, LocalDateTime from,
                                                     LocalDateTime to, Integer adjusted) {
        IPage<Order> page = orderMapper.selectPageForAdmin(new Page<>(pageNum, pageSize),
                userId, trimToNull(status), from, to, adjusted);
        return BizResult.ok(PageResult.of(page, OrderVo::from));
    }

    /**
     * 后台查询订单详情。
     *
     * @param orderId 订单 ID
     * @return 订单视图；不存在时返回 404
     */
    public BizResult<OrderVo> getOrderForAdmin(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return BizResult.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        return BizResult.ok(OrderVo.from(order));
    }

    // ==================================================================
    // 管理员人工调整
    // ==================================================================

    /**
     * 人工调整订单时长并重算金额。
     *
     * <p><b>典型场景</b>：顾客玩完直接走了，忘了点「结束使用」，订单一直挂在使用中。
     * 管理员查监控确认他 21:30 离场，把离场时刻改过来、让账单能出来收款。
     *
     * <p><b>为什么使用中的订单调整后要转待支付</b>：不转的话，这条订单会一直挂在使用中 ——
     * 而顾客已经走了，永远不会有人来点「结束使用」，这笔钱就永远收不到。
     * 「顾客已走、账单要出来收款」正是这个动作的语义。
     *
     * <p><b>已支付的订单不允许调整</b>：调整必然改变金额，已入账的就要走退款与对账，
     * 那是人工运营流程，本期不实现。
     *
     * @param orderId 订单 ID
     * @param request 调整请求（新的离场时刻 + 原因）
     * @param adminId 操作的管理员 ID
     * @return 重算后的账单
     */
    @Transactional
    public BizResult<OrderSettleVo> adjustOrder(Long orderId, AdjustOrderRequest request, Long adminId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return BizResult.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!OrderStatus.isUnpaid(order.getStatus())) {
            return BizResult.fail(ErrorCode.ORDER_NOT_ADJUSTABLE);
        }

        LocalDateTime endTime = request.getEndTime().truncatedTo(ChronoUnit.SECONDS);
        if (!endTime.isAfter(order.getStartTime())) {
            return BizResult.fail(ErrorCode.ORDER_STATUS_INVALID, "离场时刻必须晚于开门时刻");
        }
        if (endTime.isAfter(LocalDateTime.now())) {
            return BizResult.fail(ErrorCode.ORDER_STATUS_INVALID, "离场时刻不能是未来时刻");
        }

        return BizResult.ok(applySettlement(order, endTime, adminId, trimToNull(request.getReason())));
    }

    // ==================================================================
    // 支付凭证（人工核销降级路径）
    // ==================================================================

    /**
     * 用户提交支付凭证（付款截图）。
     *
     * <p>订单仍留在待支付状态，等管理员核销才转已支付 —— 转账动作是管理员做的，
     * 用户的提交只是「我付了，你看」。
     *
     * @param userId  当前登录用户 ID
     * @param orderId 订单 ID
     * @param request 凭证请求
     * @return 成功返回空数据
     */
    @Transactional
    public BizResult<Void> submitPaymentProof(Long userId, Long orderId, PaymentProofRequest request) {
        Order order = findOwnedOrder(userId, orderId);
        if (order == null) {
            return BizResult.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!OrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
            return BizResult.fail(ErrorCode.ORDER_STATUS_INVALID, "只有待支付的订单才能提交支付凭证");
        }

        orderMapper.updatePaymentProof(orderId, trimToNull(request.getPaymentProof()));
        log.info("[订单] 用户提交支付凭证 orderNo={}", order.getOrderNo());
        return BizResult.ok(null);
    }

    // ==================================================================
    // 算钱：剪切计费区间 + 调用计费服务
    // ==================================================================

    /**
     * 算出这张订单该收多少钱。
     *
     * <p>分三步：找出本单期间命中的包场 → 从计费区间里剪掉包场时段 →
     * 对剩下的每一段调用计费服务并合并结果。
     *
     * <p><b>为什么可能要算多段</b>：包场人可能提前到店。比如他 10:00 到店、
     * 14:00–18:00 是包场、19:00 走 —— 可计费的是 10:00–14:00 与 18:00–19:00 两段。
     * 若简单地把计费起点推到包场结束（只算 18:00–19:00），那 4 小时就白送了。
     *
     * @param order   订单
     * @param endTime 离场时刻
     * @return 计费结果。整段落在包场内时返回零账单，<b>不会调用计费服务</b> ——
     *         它在「结束早于开始」时会抛异常，那种情形必须在这里短路掉
     */
    BillingResult calculateBill(Order order, LocalDateTime endTime) {
        return calculateBill(order, endTime, findCoveringBookings(order, endTime));
    }

    /**
     * 算出这张订单该收多少钱（包场已由调用方查出）。
     *
     * <p>把查包场这一步留给调用方，是因为结算视图还要用到「有没有命中包场」
     * 这个事实 —— 让调用方查一次、两处共用，比在这里查一遍、出去再查一遍省一次查询，
     * 也避免两次结果不一致。
     *
     * @param order    订单
     * @param endTime  离场时刻
     * @param bookings 本单期间命中的已付款包场
     * @return 计费结果
     */
    private BillingResult calculateBill(Order order, LocalDateTime endTime, List<Booking> bookings) {
        List<TimeRange> ranges = billableRanges(order.getStartTime(), endTime, bookings);
        BigDecimal monthSpent = queryMonthSpent(order);
        CardCoverage coverage = queryCardCoverage(order);

        if (ranges.isEmpty()) {
            // 整段被包场覆盖：把计费起止都落在离场时刻，表示「这段没有任何计费」。
            // 若沿用 order.getStartTime()，账单上会显示出一个几小时的「计费起点」，
            // 与「一分钱没收」矛盾；前端也会算不出「确实是包场免掉的」。
            return zeroBill(endTime, endTime, monthSpent);
        }

        List<BillingResult> parts = new ArrayList<>();
        for (TimeRange range : ranges) {
            parts.add(billingService.calculate(range.from(), range.to(), monthSpent, coverage));
        }
        // 起点取【实际计费起点】（第一段的开始），不是订单的开门时刻 ——
        // 包场人提前到店时两者相差几小时，返回开门时刻会让前端展示出
        // 与实际收费不符的账单
        return mergeBills(parts, ranges.get(0).from(), endTime);
    }

    /**
     * 从订单区间里剪掉包场时段，得到真正计费的区间列表。
     *
     * <p><b>纯函数，不碰数据库</b>，因此可以脱离 Spring 直接单测 ——
     * 这条规则最容易算错，也最值得单独测。
     *
     * <p>用「游标推进」的写法而不是逐个求差集：包场时段之间互不重叠
     * （排期时已校验），所以从左到右扫一遍即可，游标记录「已经处理到哪儿」。
     *
     * @param start    订单计费起点
     * @param end      订单离场时刻
     * @param bookings 与之相交的已付款包场，<b>必须按开始时间升序</b>
     * @return 可计费区间，按时间先后排列；全部被包场覆盖时返回空列表
     */
    static List<TimeRange> billableRanges(LocalDateTime start, LocalDateTime end,
                                          List<Booking> bookings) {
        List<TimeRange> result = new ArrayList<>();
        if (start == null || end == null || !end.isAfter(start)) {
            return result;
        }

        LocalDateTime cursor = start;
        for (Booking booking : bookings) {
            // 把包场区间夹到订单区间内，再与游标比较
            LocalDateTime bookingStart = max(booking.getStartAt(), start);
            LocalDateTime bookingEnd = min(booking.getEndAt(), end);
            if (!bookingStart.isBefore(bookingEnd)) {
                continue; // 不相交（或只是挨着），跳过
            }
            if (cursor.isBefore(bookingStart)) {
                result.add(new TimeRange(cursor, bookingStart));
            }
            if (bookingEnd.isAfter(cursor)) {
                cursor = bookingEnd;
            }
        }
        if (cursor.isBefore(end)) {
            result.add(new TimeRange(cursor, end));
        }
        return result;
    }

    /**
     * 找出本单期间该用户能免费使用的包场时段。
     *
     * <p><b>两个来源缺一不可</b>：
     * <ol>
     *   <li>订单的 {@code bookingId} —— 被邀请者凭令牌进店时会记上，
     *       他自己不是包场人，按用户 ID 查不出来</li>
     *   <li>该用户是包场人 —— 包场人可能提前到店，那时包场还没开始，
     *       三层准入判断落到「普通」分支，订单不会挂包场 ID。
     *       少了这条回退，他会被重复计费：既付了包场费，又在包场时段内按分钟被收一次</li>
     * </ol>
     *
     * @param order   订单
     * @param endTime 离场时刻
     * @return 相交的已付款包场，按开始时间升序；没有则返回空列表
     */
    private List<Booking> findCoveringBookings(Order order, LocalDateTime endTime) {
        // 用 LinkedHashMap 按 ID 去重 —— 两个来源可能指向同一场
        // （包场人自己下的单恰好在包场时段内，两个条件同时成立）
        Map<Long, Booking> found = new LinkedHashMap<>();

        if (order.getBookingId() != null) {
            Booking byId = bookingMapper.selectById(order.getBookingId());
            // 包场可能已被取消（逻辑删除则查不到）或改期，所以状态要再确认一次
            if (byId != null && BookingStatus.PAID.name().equals(byId.getStatus())) {
                found.put(byId.getId(), byId);
            }
        }

        List<Booking> byHost = bookingMapper.selectHostBookingsInRange(
                order.getStoreId(), order.getUserId(), order.getStartTime(), endTime);
        for (Booking booking : byHost) {
            found.putIfAbsent(booking.getId(), booking);
        }

        return found.values().stream()
                .sorted(Comparator.comparing(Booking::getStartAt))
                .toList();
    }

    /**
     * 查询本单结算前的当月累计实付额。
     *
     * <p><b>月份由订单的 {@code startTime} 推出，不是 {@code now}</b> ——
     * 跨零点结算的夜单不会跳到下个月，管理员事后修正时长也不会让
     * 历史订单的优惠判定漂移。
     *
     * @param order 订单
     * @return 当月累计实付额（元）；无记录时为 0
     */
    private BigDecimal queryMonthSpent(Order order) {
        LocalDateTime monthStart = order.getStartTime().toLocalDate()
                .withDayOfMonth(1).atStartOfDay();
        BigDecimal spent = orderMapper.selectMonthPaidAmount(
                order.getUserId(), monthStart, monthStart.plusMonths(1));
        return spent == null ? BigDecimal.ZERO : spent;
    }

    /**
     * 查询本单适用的月卡覆盖范围。
     *
     * <p><b>用哪一天去查由订单的 {@code startTime} 推出，不是 {@code now}</b> ——
     * 与 {@link #queryMonthSpent} 同一套口径：用户 23:00 进场时卡还有效，
     * 这一单就该取那张卡；管理员事后修正时长，也不会让历史订单的免单结论漂移。
     *
     * <p><b>取回来的不只是「有没有卡」，还有那张卡的完整有效期</b> ——
     * 计费侧要用它的两个端点切段，跨零点的订单才能被切成
     * 「卡内免费」与「卡外收费」两段。查询用的那一天只决定取哪张卡。
     *
     * <p>解析放在这里而不是由调用方传入，是为了让「结账预览」与「实际结算」
     * 不可能各判一套 —— 两者都走 {@link #calculateBill}。
     *
     * @param order 订单
     * @return 覆盖范围（时段 + 卡的有效期）；无卡时返回 null
     */
    private CardCoverage queryCardCoverage(Order order) {
        return monthlyCardService.findCoverageAt(
                order.getUserId(), order.getStartTime().toLocalDate());
    }

    /**
     * 合并多段的计费结果为一份账单。
     *
     * <p>整单属性（是否优惠、结算前累计额）取第一段的 —— 各段传入的
     * {@code monthSpent} 相同，算出来的整单判定必然一致。
     *
     * @param parts 各段计费结果
     * @param start 整单计费起点
     * @param end   整单离场时刻
     * @return 合并后的账单
     */
    private static BillingResult mergeBills(List<BillingResult> parts,
                                            LocalDateTime start, LocalDateTime end) {
        BillingResult merged = new BillingResult();
        merged.setStartTime(start);
        merged.setEndTime(end);
        merged.setTotalMinutes(parts.stream().mapToLong(BillingResult::getTotalMinutes).sum());
        merged.setSegments(parts.stream().flatMap(p -> p.getSegments().stream()).toList());
        merged.setTotalAmount(sumOf(parts, BillingResult::getTotalAmount));
        merged.setDiscountAmount(sumOf(parts, BillingResult::getDiscountAmount));
        merged.setCardFreeAmount(sumOf(parts, BillingResult::getCardFreeAmount));
        merged.setMonthSpentBefore(parts.get(0).getMonthSpentBefore());
        merged.setDiscounted(parts.get(0).isDiscounted());
        return merged;
    }

    /**
     * 构造零金额账单。
     *
     * <p>用于「整段时间都落在包场里」的情形 —— 该收 0 元，但要给出一份
     * 结构完整的账单，而不是让前端拿到 null 再各自判空。
     *
     * @param start      计费起点
     * @param end        离场时刻
     * @param monthSpent 结算前当月累计额
     * @return 零账单
     */
    private static BillingResult zeroBill(LocalDateTime start, LocalDateTime end,
                                          BigDecimal monthSpent) {
        BillingResult bill = new BillingResult();
        bill.setStartTime(start);
        bill.setEndTime(end);
        bill.setTotalMinutes(0);
        bill.setSegments(List.of());
        bill.setTotalAmount(BigDecimal.ZERO);
        bill.setDiscountAmount(BigDecimal.ZERO);
        bill.setCardFreeAmount(BigDecimal.ZERO);
        bill.setMonthSpentBefore(monthSpent);
        bill.setDiscounted(false);
        return bill;
    }

    /**
     * 从账单里汇总某个时段的计费时长。
     *
     * @param bill   账单
     * @param period 时段
     * @return 该时段的总分钟数；账单里没有该段时返回 0
     */
    private static int minutesOf(BillingResult bill, BillingPeriod period) {
        return (int) bill.getSegments().stream()
                .filter(s -> s.getPeriod() == period)
                .mapToLong(SegmentBill::getMinutes)
                .sum();
    }

    /**
     * 从账单里汇总某个时段的金额。
     *
     * @param bill   账单
     * @param period 时段
     * @return 该时段的实收金额；账单里没有该段时返回 0
     */
    private static BigDecimal amountOf(BillingResult bill,
                                       BillingPeriod period) {
        return bill.getSegments().stream()
                .filter(s -> s.getPeriod() == period)
                .map(SegmentBill::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 按取数函数汇总各段的某个金额字段。
     *
     * @param parts  各段计费结果
     * @param getter 取数函数
     * @return 合计
     */
    private static BigDecimal sumOf(List<BillingResult> parts,
                                    Function<BillingResult, BigDecimal> getter) {
        return parts.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 就账单金额判定停止计时后订单应处于的状态。
     *
     * <p><b>结算与结账预览共用本方法。</b>0 元单没有任何可支付的通道，
     * 卡在待支付只会让用户看到一个「0 元去支付」的按钮，所以它直接结清为已支付。
     * 这条同时覆盖「5 分钟内免费出场」与「整段被包场覆盖」两种情形。
     *
     * <p>之所以要抽出来：预览页告诉用户「本次无需支付」、结算却让他付钱，
     * 是这类规则重复实现最典型的事故 —— 而且只在用户认真核对时才会被发现。
     *
     * @param total 账单实收合计
     * @return {@link OrderStatus#PAID} 或 {@link OrderStatus#PENDING_PAYMENT} 的状态名
     */
    private static String statusAfterSettle(BigDecimal total) {
        return total == null || total.compareTo(BigDecimal.ZERO) <= 0
                ? OrderStatus.PAID.name()
                : OrderStatus.PENDING_PAYMENT.name();
    }

    /**
     * 判断账单当前是否已全部达到封顶价 —— 各计费段的实收金额都已等于其封顶值。
     *
     * <p><b>不是「是否超顶」</b>：计费段的 {@code capped} 表示封顶前金额已超过封顶，
     * 要到第 11 档（5 小时 6 分）起才为 true；而金额不再增长从第 10 档
     * （4 小时 36 分）就开始了，那段时间 {@code capped} 是 false，
     * 用它会漏报 ——「达到封顶」与「标记为超顶」是两回事，计费规则里专门写过这一条。
     *
     * <p>没有计费段时返回 false：整段被包场覆盖的账单是 0 元，
     * 但「封顶」在这里不适用（包场结束后照样会重新计费）。
     *
     * <p><b>包级可见而非私有</b>，是为了让单元测试能直接断言各种情形 ——
     * 与 {@link #billableRanges} 同一个理由：真要用订单去构造「已封顶」，
     * 就得依赖「此刻落在哪个计费时段」，那种用例时灵时不灵，比没有还糟。
     *
     * @param segments 账单分段
     * @return 各段实收均已达封顶返回 true
     */
    static boolean allSegmentsCapped(List<SegmentBill> segments) {
        return !segments.isEmpty()
                && segments.stream()
                .allMatch(s -> s.getAmount().compareTo(s.getCapAmount()) >= 0);
    }

    // ==================================================================
    // 杂项
    // ==================================================================

    /**
     * 判断请求者是否有资格在该包场时段内下单。
     *
     * <p>三条来源，按「权威性」从高到低：
     * <ol>
     *   <li><b>参与者表命中</b> —— 主路径。被邀请者点开邀请链接就会在里面留下一行，
     *       包场人也有一行（{@code HOST}），所以这一条本来就覆盖了包场人</li>
     *   <li><b>本人是包场人</b> —— 兜底。{@code HOST} 行是付款成功那一刻才写的，
     *       这条覆盖的是「参与者表上线之前就已付款」的历史包场；
     *       少了它，那些场次的包场人会被自己的包场挡在门外</li>
     *   <li><b>令牌匹配</b> —— 兜底。落地页加载后会自动调加入接口，
     *       万一那一次失败（网络抖动、用户在请求发出前就点了开门），
     *       被邀请者下单时带上令牌仍能进。令牌从「被邀请者的唯一凭证」
     *       降级成了这条兜底路径</li>
     * </ol>
     * 三条都不命中就拒绝 —— 准入的落地方式是「不下发密码」，
     * 所以拿不到密码的人进不去，门锁上不需要任何黑名单。
     *
     * @param booking     当前生效的包场
     * @param userId      请求者用户 ID
     * @param inviteToken 请求携带的邀请令牌，可为 null
     * @return 放行返回 true
     */
    private boolean isBookingAllowed(Booking booking, Long userId, String inviteToken) {
        if (bookingService.isParticipant(booking.getId(), userId)) {
            return true;
        }
        if (booking.getHostUserId() != null && booking.getHostUserId().equals(userId)) {
            return true;
        }
        return inviteTokenService.matches(booking, inviteToken);
    }

    /**
     * 包场准入的分档判定 —— 「身份 × 时刻」两个维度决定放行与否。
     *
     * <p>以包场开始时刻 {@code BS} 为基准，一共三档：
     * <ol>
     *   <li>{@code [BS - 15min, BS - 5min)} <b>预备期</b> —— 谁都不放行。
     *       散客进来打不完一局就要被清场，参与者则还没到能进的时刻</li>
     *   <li>{@code [BS - 5min, BS)} <b>准备期</b> —— 只放行参与者，进店放东西、做开场准备</li>
     *   <li>{@code [BS, BE)} <b>包场中</b> —— 仍然只放行参与者</li>
     * </ol>
     * 三档之外的时刻不会命中包场，落到「普通」分支由调用方直接放行。
     * 括号里的分钟数是默认配置值，实际取值见
     * {@link OrderProperties#getBookingLeadDuration()} 与
     * {@link OrderProperties#getParticipantLeadDuration()}。
     *
     * <p><b>预备期为什么连参与者也挡</b>：参与者的提前量就是那 5 分钟 ——
     * 进店放东西、做开场准备，再早店里还按普通营业安排。
     * 两个提前量的宽窄是配置项，但「谁在哪一档」的顺序是硬编码的：
     * 改配置只该改变窗口宽窄，不该改变规则结构。
     *
     * @param booking     命中的包场
     * @param now         当前时刻
     * @param userId      请求者
     * @param inviteToken 邀请令牌，可为 null
     * @return 放行时返回 null；否则返回带具体原因的拒绝结果
     */
    private BizResult<OrderOpenVo> checkBookingAdmission(Booking booking, LocalDateTime now,
                                                         Long userId, String inviteToken) {
        boolean participant = isBookingAllowed(booking, userId, inviteToken);
        LocalDateTime participantFrom = booking.getStartAt()
                .minus(orderProperties.getParticipantLeadDuration());

        if (now.isBefore(participantFrom)) {
            if (participant) {
                return BizResult.fail(ErrorCode.BOOKING_NOT_STARTED,
                        "包场尚未开始，参与者可在开始前 "
                                + orderProperties.getParticipantLeadDuration().toMinutes()
                                + " 分钟入场");
            }
            return BizResult.fail(ErrorCode.BOOKING_PREPARING,
                    "该时段即将包场，暂停接待新顾客");
        }

        if (!participant) {
            return BizResult.fail(ErrorCode.BOOKING_ACCESS_DENIED);
        }
        return null;
    }

    /**
     * 查出订单并确认属于该用户。
     *
     * <p>刻意返回 null 而不抛异常、也不区分「不存在」与「不是你的」：
     * 两种情形对外都应是 404，分开只会泄露订单号是否存在。
     *
     * @param userId  用户 ID
     * @param orderId 订单 ID
     * @return 订单；不存在或不属于该用户时返回 null
     */
    private Order findOwnedOrder(Long userId, Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            return null;
        }
        return order;
    }

    /**
     * 生成订单号。
     *
     * <p>格式 {@code OD + yyyyMMddHHmmss + 4 位随机} ——
     * 与包场单号（{@code BK} 前缀）同一套规则，便于人工一眼区分。
     * 时间戳让人看出大概什么时候的单，4 位随机避免同一秒内的并发撞车；
     * 真正的唯一性由库上的 {@code uk_order_no} 保证。
     *
     * @return 20 位订单号
     */
    private static String generateOrderNo() {
        // 前缀取自 PaymentTargetType —— 那里是它的唯一定义处，
        // 支付回调按同一个常量路由，不会因为两处各写一份而漂移
        return PaymentTargetType.ORDER.getOrderNoPrefix()
                + LocalDateTime.now().format(NO_FORMATTER)
                + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    /**
     * 去首尾空白，空串归一为 null。
     *
     * <p>避免把 {@code ""} 写进库 —— 它在 SQL 的 {@code IS NULL} 判断里
     * 走的是另一条分支，会让「没填」与「填空串」产生不同的查询结果。
     *
     * @param value 原值
     * @return 去空白后的值；原值为 null 或全空白时返回 null
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 取两个时刻中较晚的一个 */
    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    /** 取两个时刻中较早的一个 */
    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    /**
     * 一段连续的计费区间，半开 {@code [from, to)}。
     *
     * <p>刻意不用 {@code LocalDateTime[]} 之类的数组：数组没有字段名，
     * 读到 {@code range[0]} 时无法确定那是起点还是终点。
     *
     * <p>包级可见而非私有，是为了让单元测试能直接断言剪切结果。
     *
     * @param from 区间起点（含）
     * @param to   区间终点（不含）
     */
    record TimeRange(LocalDateTime from, LocalDateTime to) {
    }

    /**
     * 取当前自然月的半开区间 {@code [本月 1 日 00:00, 次月 1 日 00:00)}。
     *
     * <p><b>本月的两个统计口径（消费额、在店时长）必须共用本方法</b>，
     * 不能各自算一遍：两个数字在「我的」页并排显示，归月区间一旦有出入，
     * 跨月那一刻就会出现「消费算上月、时长算本月」的错位，而且不报任何错。
     *
     * <p>用 {@code LocalDate.now()} 取当天再归到月初，而不是在 {@code now} 上
     * 逐个字段归零 —— 后者要记得把时分秒全部清掉，漏一处区间起点就变成
     * 「本月 1 日的此刻」，把这个月第一天的记录整段漏掉。
     *
     * <p>注意它取的是<b>当前</b>月份，不是某张订单所属的月份 ——
     * {@link #queryMonthSpent} 用的是后者（历史订单的优惠判定要跟着订单走），
     * 两者用途不同，不要合并。
     *
     * @return 本月区间
     */
    private static MonthRange currentMonthRange() {
        LocalDateTime from = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        return new MonthRange(from, from.plusMonths(1));
    }

    /**
     * 一个自然月的半开区间。
     *
     * <p>与 {@link TimeRange} 一样刻意用 record 而不是两个 {@code LocalDateTime} ——
     * 两个同类型参数挨在一起时，调用处写反了顺序编译器不会吭声。
     *
     * @param from 区间起点（含）
     * @param to   区间终点（不含）
     */
    record MonthRange(LocalDateTime from, LocalDateTime to) {
    }
}
