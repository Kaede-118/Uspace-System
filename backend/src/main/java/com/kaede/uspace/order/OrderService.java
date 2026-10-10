package com.kaede.uspace.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaede.uspace.billing.BillingPeriod;
import com.kaede.uspace.billing.BillingService;
import com.kaede.uspace.billing.FreePeriodService;
import com.kaede.uspace.billing.FreeRange;
import com.kaede.uspace.billing.HalfPeriodUsage;
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
import com.kaede.uspace.order.dto.OrderBillSnapshot;
import com.kaede.uspace.order.dto.OrderOpenVo;
import com.kaede.uspace.order.dto.OrderPreviewVo;
import com.kaede.uspace.order.dto.OrderSettleVo;
import com.kaede.uspace.order.dto.OrderStatsVo;
import com.kaede.uspace.order.dto.OrderVo;
import com.kaede.uspace.order.entity.Order;
import com.kaede.uspace.order.event.OrderEnteredEvent;
import com.kaede.uspace.order.event.OrderLeftEvent;
import com.kaede.uspace.order.mapper.OrderMapper;
import com.kaede.uspace.promotion.MonthlyCardService;
import com.kaede.uspace.space.BookingService;
import com.kaede.uspace.space.BookingStatus;
import com.kaede.uspace.space.ClosureService;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.mapper.BookingMapper;
import com.kaede.uspace.space.mapper.StoreMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    /**
     * 免费活动（计费规则，模块 7）。
     *
     * <p>⚠️ 它住在 {@code billing} 包而不是 {@code space}：免费时段改的是<b>账单</b>，
     * 不是准入 —— 与停业 / 包场那两条规则性质不同（那两条在这张表里各有自己的 Service）。
     */
    private final FreePeriodService freePeriodService;
    private final MonthlyCardService monthlyCardService;
    private final LockService lockService;
    private final InviteTokenService inviteTokenService;
    private final OrderProperties orderProperties;
    private final LockProperties lockProperties;

    /**
     * JSON 序列化器 —— 只用于一件事：分段账单快照（{@code biz_order.bill_snapshot}）的写与读。
     *
     * <p>注入的是 Spring 容器里那个全局配置过的 bean（时间格式
     * {@code yyyy-MM-dd HH:mm:ss} 由公共层的 {@code JacksonConfig} 定制），不是自建一个 ——
     * 写与读必须是同一套格式，两处各造一个 ObjectMapper 的话，
     * 「写出去的时间格式读不回来」不会有任何提示，只会让快照静默地解析失败。
     *
     * <p>它不参与任何计算：账单怎么算在模块 7（{@link BillingService}），
     * 快照只是把算好的那份结果原样存下、原样读回。
     */
    private final ObjectMapper objectMapper;

    /**
     * 事件发布器。
     *
     * <p><b>这是本类唯一一个「不认识具体是谁在听」的依赖</b>：它发布
     * {@link OrderEnteredEvent} / {@link OrderLeftEvent}，至于有没有监听器、
     * 监听器要拿去做什么，订单模块一概不知。QQ 群播报（模块 11）就是这么接上去的 ——
     * 编译期 {@code order} 不认识 {@code qqbot}，将来把机器人拆成独立服务，
     * 这边一行都不用改。
     *
     * <p>注入的是 Spring 的框架接口而非自定义发布器：它是个函数式接口，
     * 测试里可以写成 {@code published::add} 一行，不必为它造一个假实现类。
     */
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(OrderMapper orderMapper,
                        StoreMapper storeMapper,
                        LockMapper lockMapper,
                        BookingMapper bookingMapper,
                        ClosureService closureService,
                        BookingService bookingService,
                        BillingService billingService,
                        FreePeriodService freePeriodService,
                        MonthlyCardService monthlyCardService,
                        LockService lockService,
                        InviteTokenService inviteTokenService,
                        OrderProperties orderProperties,
                        LockProperties lockProperties,
                        ApplicationEventPublisher eventPublisher,
                        ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.storeMapper = storeMapper;
        this.lockMapper = lockMapper;
        this.bookingMapper = bookingMapper;
        this.closureService = closureService;
        this.bookingService = bookingService;
        this.billingService = billingService;
        this.freePeriodService = freePeriodService;
        this.monthlyCardService = monthlyCardService;
        this.lockService = lockService;
        this.inviteTokenService = inviteTokenService;
        this.orderProperties = orderProperties;
        this.lockProperties = lockProperties;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
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
            /*
             * 三种状态三种提示，因为它们对用户的动作完全不同：
             *   REJECTED        重新上传一张付款截图（钱多半已经付过了）
             *   PENDING_PAYMENT 去支付
             *   IN_USE          去点「结束使用」
             * 合并任何一个，用户都只能对着一句模糊提示猜自己该点哪里 ——
             * 而「凭证被驳回」误报成「未支付」，他会再付一次钱。
             */
            if (OrderStatus.REJECTED.name().equals(unsettled.getStatus())) {
                /*
                 * ⚠️ 文案里**刻意不带单号**（用 ErrorCode 里的默认那句）。
                 *
                 * 他名下可能同时有好几笔被驳回的，而这个提示只报得出最先查到的
                 * 那一笔 —— 报一个单号反而像是在说「就是这一笔」。
                 * 何况那串字符用户本来就认不出是哪一个。
                 *
                 * 这里要说清的只是「有这回事、你该做什么」，
                 * 至于「是哪几笔」，首页那条提醒会逐条列给他看。
                 */
                return BizResult.fail(ErrorCode.ORDER_PROOF_REJECTED);
            }
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

        // 发布「到店」事件，QQ 群播报（模块 11）会监听它。
        // ⚠️ 只在这里发 —— 上面那几个 return BizResult.fail 的早退分支
        // （门店 / 门锁缺失、停业、包场拒绝、欠费、密码下发失败）都不是「到店」，
        // 在方法开头发的话，被拒绝的请求也会往群里播一条「谁谁到店了」。
        //
        // ⚠️ 监听器用的是 @TransactionalEventListener(AFTER_COMMIT)：此刻事务还没提交，
        // 事件也还没送达，用户这次「开门」的 HTTP 请求会一直等到播报发完才返回。
        // 这是刻意的 —— 监听器要读在店名册，必须等这条订单落库对别人可见。
        eventPublisher.publishEvent(new OrderEnteredEvent(
                order.getId(), userId, orderNo, now));
        return BizResult.ok(OrderOpenVo.of(order, false, false, inBookingAt(order, now)));
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
            return BizResult.ok(OrderOpenVo.of(order, false, false, inBookingAt(order, now)));
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
            return BizResult.ok(OrderOpenVo.of(order, true, false, inBookingAt(order, now)));
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
        return BizResult.ok(OrderOpenVo.of(order, true, true, inBookingAt(order, now)));
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

    /**
     * 查询当前<b>未结清</b>的订单：进行中（{@code IN_USE}）或待支付（{@code PENDING_PAYMENT}）。
     *
     * <p>与 {@link #findCurrentOrder} <b>刻意分开，不要合并</b>，理由与
     * {@code OrderMapper} 里那两个查询同源：那个只认 {@code IN_USE}，
     * 因为首页要拿它决定「开门 / 查看密码」——待支付的订单密码早已撤销，
     * 返回它会让首页给出一个点不动的「查看密码」。而本方法要的恰恰是
     * 「这个人手上还有一笔账没了结」，供群里的 {@code fw结账} 用：
     * 已经停过表、但还没付款时，再发一次 {@code fw结账} 应当重新撑起付款入口
     * （待付金额与网页端链接），而不是回一句「你没有在计时的订单」。
     *
     * @param userId 用户 ID
     * @return 成功时返回最近一条未结清的订单，一条都没有时 {@code data} 为 null
     */
    public BizResult<OrderVo> findUnsettledOrder(Long userId) {
        return BizResult.ok(OrderVo.from(orderMapper.selectUnsettledByUser(userId)));
    }

    /**
     * 列出某人全部<b>未付款</b>的订单（{@code PENDING_PAYMENT} 或 {@code REJECTED}）。
     *
     * <p>供群里的 {@code fw未付款} 用 —— 与 {@link #findUnsettledOrder} 分开：
     * 那个是「最近一笔」、还带上 {@code IN_USE}（{@code fw结账} 要靠它辨认
     * 「已经停过表」的情形），而这里只要「还没付钱的账」，且要列全。
     *
     * @param userId 用户 ID
     * @return 未付款的订单，最近的在前；没有时返回空列表
     */
    public BizResult<List<OrderVo>> findUnpaidOrders(Long userId) {
        return BizResult.ok(orderMapper.selectUnpaidByUser(userId).stream()
                .map(OrderVo::from)
                .toList());
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

        // 「已到封顶」的判定住在 BillingService（allHalfPeriodsCapped）——
        // 它要按半场分组求和，而半场的划分只在那儿有定义（2026-10-10 从本类挪过去）
        return BizResult.ok(OrderPreviewVo.of(order, previewAt, bill,
                !bookings.isEmpty(), billingService.allHalfPeriodsCapped(bill.getSegments()),
                statusAfterSettle(bill.getTotalAmount()), nextChange(order, bill, previewAt)));
    }

    /**
     * 算「下一次账单变化」的预告。
     *
     * <p><b>包场期间不给跳档预告</b>：那段时间不计费，照常算的话会得出一个
     * 只对「包场前那段」成立的时刻 —— 而那段早已结束，用户看到的是
     * 「还有 0 秒进入下一档」这类坏掉的信息。判据取账单最后一段的结束时刻
     * 是否就是此刻：包场挡在中间时它会更早。
     *
     * <p><b>但包场快结束时要给一条</b>：那之后就开始按分钟计费了，打算继续玩的人
     * 得提前知道，否则他会看着一个不动的 ¥0.00 突然跳档。见 {@link #bookingEndingChange}。
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
     * @return 预告；此刻没有正在计费的段、且包场也还没到该提醒的时候，返回 null
     */
    private NextChange nextChange(Order order, BillingResult bill, LocalDateTime at) {
        List<SegmentBill> segments = bill.getSegments();
        SegmentBill ongoing = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        if (ongoing == null || ongoing.getEndTime().isBefore(at)) {
            // 当前时刻落在包场里（或整单都在包场里）：那段时间不计费，
            // 报一句「还有 X 秒进入下一档 ¥4」是假话。但包场快结束时得说一声
            return bookingEndingChange(order, at);
        }
        // 封顶按半场累计、且跨订单：预告的「剩余额度」要把本单前面的段
        // 与历史订单的实收一起扣掉 —— 与 calculateBill 里的预置同源
        //（同一个 seedHalfPeriodUsage）。这里刻意再查一次库而不是把上一轮的
        // usage 缓存下来：缓存的失效时机（结算、订单变化）比这一次索引查询
        // 更需要小心，而预览本来就是低频轮询
        HalfPeriodUsage seeded = new HalfPeriodUsage();
        seedHalfPeriodUsage(seeded, order, at);
        return billingService.nextChange(ongoing.getStartTime(), at,
                bill.getMonthSpentBefore(), queryCardCoverage(order),
                queryFreeRanges(order, ongoing.getStartTime(), at),
                billingService.halfPeriodUsedBefore(segments, ongoing,
                        bill.isDiscounted(), seeded));
    }

    /**
     * 包场即将结束时的预告。
     *
     * <p><b>为什么包场期间平时不提示、快结束时反而要提示</b>：包场时段不计费，
     * 报「还有 X 秒进入下一档 ¥4」是句假话；而包场一结束就重新开始按分钟计费，
     * 打算继续玩的人需要提前知道。提前量见 {@code uspace.order.booking-end-warning}。
     *
     * @param order 订单
     * @param at    当前时刻
     * @return 预告；此刻不在包场里、或离包场结束还早时返回 null
     */
    private NextChange bookingEndingChange(Order order, LocalDateTime at) {
        Booking ongoing = ongoingBookingAt(order, at);
        if (ongoing == null) {
            return null;
        }
        long seconds = Duration.between(at, ongoing.getEndAt()).getSeconds();
        if (seconds <= 0 || seconds > orderProperties.getBookingEndWarning().getSeconds()) {
            return null;
        }
        return NextChange.at(seconds, "包场结束，之后按时长计费");
    }

    /**
     * 此刻是否落在某场包场时段内。
     *
     * @param order 订单
     * @param at    时刻
     * @return 在包场时段内返回 true
     */
    private boolean inBookingAt(Order order, LocalDateTime at) {
        return ongoingBookingAt(order, at) != null;
    }

    /**
     * 取此刻正在进行的那一场包场。
     *
     * <p>⚠️ <b>与 {@link #findCoveringBookings} 的区别</b>：那个返回的是
     * 「与订单区间有过交集的场次」，<b>包含已经结束的与还没开始的</b> ——
     * 它服务于计费剪切，剪切要的是「区间交叠」而不是「此刻在不在」。
     * 这里只要「此刻正在进行」的那一场。
     *
     * @param order 订单
     * @param at    时刻
     * @return 正在进行的那场包场；不在任何包场里时返回 null
     */
    private Booking ongoingBookingAt(Order order, LocalDateTime at) {
        return findCoveringBookings(order, at).stream()
                // 半开区间 [start, end)：开始时刻当刻算在里面，结束时刻当刻就不算了
                .filter(b -> !at.isBefore(b.getStartAt()) && at.isBefore(b.getEndAt()))
                .findFirst()
                .orElse(null);
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
        return BizResult.ok(applySettlement(order, endTime, null, null,
                OrderLeftEvent.Source.USER));
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
            applySettlement(order, startAt, null, null, OrderLeftEvent.Source.BOOKING_CLEAR);
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
     * @param source       这次结算由哪条路径产生。<b>不能由 {@code operatorId} 推出</b> ——
     *                     用户自助与包场清场都传 null，只有调用方自己知道是哪一种。
     *                     它只用于决定播报的措辞，不影响任何算钱结果
     * @return 结算视图
     */
    private OrderSettleVo applySettlement(Order order, LocalDateTime endTime,
                                          Long operatorId, String adjustReason,
                                          OrderLeftEvent.Source source) {
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
        BigDecimal activityFree = bill.getActivityFreeAmount();

        // 分段账单快照：日场/夜场两列只是汇总，段的边界、档数、单价、封顶与免单标记
        // 都装不进「合计」里，而订单详情页要按段展示。序列化失败只记警告存 null ——
        // 快照是展示用的副本，不该因为它写不进去而让整笔结算回滚（顾客结不了账）。
        String snapshot = serializeBill(bill, !bookings.isEmpty());

        if (operatorId == null) {
            orderMapper.updateSettlement(order.getId(), endTime, stayMinutes, dayMinutes,
                    dayAmount, nightMinutes, nightAmount, total, bill.getDiscountAmount(),
                    cardFree, activityFree, total, targetStatus, snapshot);
        } else {
            orderMapper.updateAdjustment(order.getId(), endTime, stayMinutes, dayMinutes,
                    dayAmount, nightMinutes, nightAmount, total, bill.getDiscountAmount(),
                    cardFree, activityFree, total, targetStatus, operatorId, adjustReason, snapshot);
        }

        // 同步内存对象，供视图构造使用
        order.setEndTime(endTime);
        order.setStayMinutes(stayMinutes);
        order.setStatus(targetStatus);
        order.setTotalAmount(total);
        order.setPayableAmount(total);
        order.setDiscountAmount(bill.getDiscountAmount());
        order.setCardFreeAmount(cardFree);
        order.setActivityFreeAmount(activityFree);
        order.setBillSnapshot(snapshot);
        if (free) {
            order.setPaidAt(endTime);
        }

        revokePasscode(order);
        log.info("[订单] 结算 orderNo={} 计费区间 {} ~ {} 合计={} 状态={}{}",
                order.getOrderNo(), billingStart, endTime, total, targetStatus,
                operatorId == null ? "" : "（管理员调整）");

        // 发布「离店」事件，QQ 群播报（模块 11）会监听它。
        // 放在这一个点上，三条离店路径（用户自助 / 包场清场 / 管理员补录）就都覆盖到了，
        // 算钱逻辑不必分叉；措辞的分叉交给 source，见 OrderLeftEvent.Source 的说明。
        //
        // ⚠️ 与到店事件同理：此时事务尚未提交，监听器是 AFTER_COMMIT 的，
        // 所以调用方会等播报发完才返回。
        eventPublisher.publishEvent(new OrderLeftEvent(
                order.getId(), order.getUserId(), order.getOrderNo(),
                endTime, stayMinutes, total, free, source));
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
    /**
     * 撤销这张订单上的两串密码：固定的限时密码，与群指令发过的一次性密码。
     *
     * <p><b>为什么一次性密码也要撤</b>：它虽然在群里发出去后传播面最广，
     * 有效期（6 小时）却比私聊那份限时密码（12 小时）还短 —— 不撤的话就成了
     * 「传播越广的密码活得越久」，正好反了。何况结算完人就不该再进
     * （进去就是一段没有订单的用电，计费口径也对不上）。
     *
     * <p>两串各自撤销、互不影响：一次性密码如果已经被用过（用后即焚），
     * 删除会失败，那正是它应有的状态 —— 只记 WARN，绝不抛。
     * 撤销本身是幂等的，抛异常会把已经算好写好的账单回滚掉，那是更坏的结果。
     *
     * @param order 已结算的订单（内存对象，两串密码的值取自它）
     */
    private void revokePasscode(Order order) {
        if (order.getLockId() == null) {
            return;
        }
        revokeOnePasscode(order, order.getPasscode(), "限时密码");
        revokeOnePasscode(order, order.getOneTimePasscode(), "一次性密码");
    }

    /**
     * 撤销单串密码，失败只记警告。
     *
     * @param order      订单
     * @param passcode   密码内容，为 null 表示这张订单没有这一串
     * @param label      日志里用的名字（「限时密码」/「一次性密码」）
     */
    private void revokeOnePasscode(Order order, String passcode, String label) {
        if (passcode == null) {
            return;
        }
        PasscodeResult result = lockService.deletePasscode(order.getLockId(), passcode);
        if (!result.isSuccess()) {
            log.warn("[订单] 撤销{}失败，将由有效期兜底 orderNo={} errmsg={}",
                    label, order.getOrderNo(), result.getErrmsg());
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
     * <p>与结算用的是同一套口径：只算<b>已支付</b>订单的实付额、不含月卡卡费，
     * 判定则直接调 {@link BillingService#isDiscounted}，
     * 不在别处重写一遍门槛比较。
     *
     * <p>月份取<b>当前自然月</b>（而不是某一单的月份）—— 这个接口回答的是
     * 「我现在算什么状态、下一单要花多少钱」，本来就是看当下。
     * 而「一笔消费归哪个自然月」由<b>离场时刻</b>定（见
     * {@code OrderMapper#selectMonthPaidAmount}）：8/31 进店、9/1 离店的夜单，
     * 9 月 1 日就已经出现在这里。结算时判本单走不走优惠价取的是
     * <b>订单开始月</b>，两者用途不同、口径不冲突。
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
        OrderVo vo = OrderVo.from(order);
        attachBill(vo, order);
        return BizResult.ok(vo);
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
        OrderVo vo = OrderVo.from(order);
        attachBill(vo, order);
        return BizResult.ok(vo);
    }

    // ==================================================================
    // 分段账单（快照写入、读取与老订单重算）
    // ==================================================================

    /**
     * 把一份账单序列化成快照 JSON，供 {@code biz_order.bill_snapshot} 落库。
     *
     * <p>序列化失败不抛异常、返回 null —— 快照是【展示用的副本】，
     * 不该因为它写不进去而让整笔结算回滚（顾客会结不了账）。
     *
     * @param bill          结算算出的账单
     * @param freeByBooking 是否因命中包场而减免了计费时长
     * @return JSON 字符串；序列化失败时为 null
     */
    private String serializeBill(BillingResult bill, boolean freeByBooking) {
        try {
            return objectMapper.writeValueAsString(OrderBillSnapshot.of(bill, freeByBooking));
        } catch (JsonProcessingException e) {
            log.warn("[订单] 账单快照序列化失败，本次结算不存快照 errmsg={}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析订单上的快照 JSON。
     *
     * <p><b>解析失败与「快照为空」是同一条回落路径</b>：记警告、返回 null，
     * 由调用方去试重算 —— 快照坏掉不该让订单详情整个打不开。
     *
     * @param order 订单
     * @return 快照；为空或解析失败时返回 null
     */
    private OrderBillSnapshot parseSnapshot(Order order) {
        String json = order.getBillSnapshot();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, OrderBillSnapshot.class);
        } catch (JsonProcessingException e) {
            log.warn("[订单] 账单快照解析失败，将尝试按当前规则重算 orderNo={} errmsg={}",
                    order.getOrderNo(), e.getMessage());
            return null;
        }
    }

    /**
     * 给订单视图附上分段账单（快照优先，老订单按当前规则重算兜底）。
     *
     * <p><b>只有详情接口用它，列表接口刻意不附</b>：一页 20 条订单各带一份账单
     * 会让响应体白白大出许多，老订单还要逐条重算。
     *
     * <p>回落的次序是「快照 → 重算 → 什么都不给」：
     * <ol>
     *   <li>快照存在且能解析 —— 用它。快照是结算那一刻的权威记录，
     *       计费规则与价格后来怎么调都不影响已结算订单的明细</li>
     *   <li>没有快照（2026-10-03 快照机制上线前结算的老订单）—— 按当前规则重算，
     *       但只有金额与落库完全一致才敢用，见 {@link #recomputeBill}</li>
     *   <li>都不行 —— {@code bill} 留空，前端回落到日场/夜场汇总行。
     *       <b>宁可不展示，也不展示一份与落库金额对不上的假明细</b></li>
     * </ol>
     *
     * <p>使用中的订单（没有 {@code endTime}）不附：费用还在走，
     * 那一刻的账单在结账预览接口里现算。
     *
     * @param vo    待填充的订单视图
     * @param order 订单实体
     */
    private void attachBill(OrderVo vo, Order order) {
        if (order.getEndTime() == null) {
            return;
        }
        OrderBillSnapshot snapshot = parseSnapshot(order);
        if (snapshot == null) {
            snapshot = recomputeBill(order);
        }
        if (snapshot == null) {
            return;
        }
        vo.setBill(snapshot.getBill());
        vo.setFreeByBooking(snapshot.isFreeByBooking());
    }

    /**
     * 为「快照机制之前结算的老订单」重算一份账单，作为详情展示的最后一路兜底。
     *
     * <p><b>两种优惠状态各算一遍，谁能复现落库金额就用谁。</b> 为什么不直接用
     * {@link #queryMonthSpent}：那个查询是「该月【全部】已支付订单之和」，
     * 重算时包含本单自身（结算时不含），可能把月累计顶过优惠门槛、算出优惠价来 ——
     * 实测数据里就有现成的例子：一笔 225 元的订单自己就把 200 元的门槛顶穿了。
     * 与其去猜「结算前的累计额是多少」，不如把「走原价」与「走优惠价」两种可能
     * 都算出来，直接问：哪一种能复现这一单的落库金额？这是个闭集，答案唯一或不存在。
     *
     * <p><b>月卡与活动同样是枚举，不是「按落库免额猜」。</b> 这两样都是可变输入，
     * 而落库数据分不清三种情形：(a) 结算时没有、后来才有（用户买卡、补排活动）；
     * (b) 结算时就有、也确实减免了；(c) 结算时有、但恰好没减免任何钱 ——
     * 它只通过<b>切段</b>影响了结果（活动边界也是切分线，多切一段就多享一次宽限，
     * 而「被月卡盖过的活动段只记月卡」会让免额显示为 0）。所以四种组合
     * （卡用/不用 × 活动用/不用）都算一遍，与「两算取一」是同一个思路：
     * 与其猜输入，不如枚举输入，谁能复现落库金额就用谁。
     *
     * <p><b>金额对不上就不返回</b>（活动被删改、包场被撤销、计费规则调整过，
     * 都会造成这种偏差）—— 重算只是给老订单的补偿，不能拿来冒充历史事实。
     *
     * <p>命中的那份账单会把 {@code monthSpentBefore} 清空：重算无从得知
     * 「结算前」的累计额，留着一个编造的数字会让账单上那句
     * 「结算前本月已消费 ¥X」撒谎。少一句解释，好过多一句错的。
     *
     * @param order 已结算的订单（{@code endTime} 非空）
     * @return 可展示的快照；所有组合都与落库对不上时返回 null
     */
    private OrderBillSnapshot recomputeBill(Order order) {
        try {
            List<Booking> bookings = findCoveringBookings(order, order.getEndTime());
            CardCoverage coverage = queryCardCoverage(order);
            List<FreeRange> freeRanges = queryFreeRanges(order, order.getStartTime(), order.getEndTime());

            // 枚举顺序：优先「都引入」（最接近结算当时的完整输入），逐项撤掉再试。
            // 组合数最多 2×2×2 = 8，全是纯内存计算 —— 查询只在上面的三行各一次
            CardCoverage[] cardOptions = { coverage, null };
            List<List<FreeRange>> activityOptions = List.of(freeRanges, List.of());
            for (BigDecimal monthSpent : List.of(BigDecimal.ZERO, billingService.discountThreshold())) {
                for (CardCoverage cardOption : cardOptions) {
                    for (List<FreeRange> activityOption : activityOptions) {
                        BillingResult bill = calculateBill(order, order.getEndTime(), bookings,
                                monthSpent, cardOption, activityOption);
                        if (billMatches(order, bill)) {
                            bill.setMonthSpentBefore(null);
                            return OrderBillSnapshot.of(bill, !bookings.isEmpty());
                        }
                    }
                }
            }
            log.warn("[订单] 老订单重算与落库金额对不上，详情不展示分段账单 orderNo={} 落库合计={}",
                    order.getOrderNo(), order.getTotalAmount());
            return null;
        } catch (RuntimeException e) {
            // 重算依赖当前库里的活动 / 月卡 / 包场数据，任何一环出意外
            // 都只该让详情页少一块账单，而不是让整个接口 500
            log.warn("[订单] 老订单重算失败，详情不展示分段账单 orderNo={} errmsg={}",
                    order.getOrderNo(), e.getMessage());
            return null;
        }
    }

    /**
     * 重算的账单是否与落库金额一致。
     *
     * <p>四个金额逐一对比较（{@code compareTo} 而非 {@code equals} —— 列是
     * {@code DECIMAL(10,2)}、重算结果是任意标度的 BigDecimal，用 {@code equals}
     * 会栽在标度上）。全等才认这一份：只要有一个对不上，就说明重算的输入
     * （规则、活动、包场、月卡）与结算当时已经不是同一套了。
     *
     * @param order 订单（读取落库的四个金额）
     * @param bill  重算出的账单
     * @return true 表示四个金额全部一致
     */
    private static boolean billMatches(Order order, BillingResult bill) {
        return sameAmount(order.getTotalAmount(), bill.getTotalAmount())
                && sameAmount(order.getDiscountAmount(), bill.getDiscountAmount())
                && sameAmount(order.getCardFreeAmount(), bill.getCardFreeAmount())
                && sameAmount(order.getActivityFreeAmount(), bill.getActivityFreeAmount());
    }

    /**
     * 两个金额是否等值（null 安全）。
     *
     * @param left  落库金额，可为 null
     * @param right 重算金额
     * @return 双方都非空时按 {@code compareTo} 比较；一方为空时只有都为空才算一致
     */
    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
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

        return BizResult.ok(applySettlement(order, endTime, adminId,
                trimToNull(request.getReason()), OrderLeftEvent.Source.ADMIN_ADJUST));
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
        return calculateBill(order, endTime, bookings, queryMonthSpent(order));
    }

    /**
     * 同上，但「结算前当月累计额」由调用方给。
     *
     * <p><b>为什么要把 monthSpent 抽成参数</b>：它平时取自 {@link #queryMonthSpent}
     * （结算与预览都走那条路），但订单详情的「老订单重算」（见 {@link #recomputeBill}）
     * 不能用它 —— 那个查询是「该月已支付订单之和」，重算时【包含本单自身】，
     * 而结算时不含，会把月累计顶过优惠门槛、算出与落库不符的金额。
     * 重算改为把两种优惠状态各算一遍，monthSpent 因此是「必不达标」与「必达标」两个极端值。
     *
     * @param order      订单
     * @param endTime    离场时刻
     * @param bookings   本单期间命中的已付款包场
     * @param monthSpent 结算前当月累计实付额，决定本单走原价还是优惠价
     * @return 计费结果
     */
    private BillingResult calculateBill(Order order, LocalDateTime endTime, List<Booking> bookings,
                                        BigDecimal monthSpent) {
        return calculateBill(order, endTime, bookings, monthSpent,
                queryCardCoverage(order),
                queryFreeRanges(order, order.getStartTime(), endTime));
    }

    /**
     * 同上，但月卡覆盖与免费活动也由调用方给。
     *
     * <p><b>为什么这两个也要能传</b>：结算与预览走上面的四参版（现查现用），
     * 而老订单重算（{@link #recomputeBill}）要在「落库没减过月卡 / 活动」时
     * 传 {@code null} 与空列表，把这两样【后来才有的】可变输入排除出去 ——
     * 否则用户买了卡之后，他买卡之前的全部历史订单都会重算失败。
     *
     * @param coverage   月卡覆盖范围；null 表示不引入月卡
     * @param freeRanges 免费活动区间；空列表表示不引入活动
     */
    private BillingResult calculateBill(Order order, LocalDateTime endTime, List<Booking> bookings,
                                        BigDecimal monthSpent, CardCoverage coverage,
                                        List<FreeRange> freeRanges) {
        List<TimeRange> ranges = billableRanges(order.getStartTime(), endTime, bookings);

        if (ranges.isEmpty()) {
            // 整段被包场覆盖：把计费起止都落在离场时刻，表示「这段没有任何计费」。
            // 若沿用 order.getStartTime()，账单上会显示出一个几小时的「计费起点」，
            // 与「一分钱没收」矛盾；前端也会算不出「确实是包场免掉的」。
            return zeroBill(endTime, endTime, monthSpent);
        }

        // ⚠️ 一个额度累计器贯穿所有区间：封顶是【按半场】算的，而同一个半场
        // 可能被包场剪成好几截 —— 它们共享一份封顶额度（2026-10-10 的规则，
        // 见 BillingService 的类注释）。逐截各自新建累计器的话，
        // 每一截都能把封顶重收一遍
        HalfPeriodUsage usage = new HalfPeriodUsage();
        // 不止本单：同一半场里他此前已经付过的钱也占额度（拆单不能绕过封顶）
        seedHalfPeriodUsage(usage, order, endTime);

        // 起点取【实际计费起点】（第一段的开始），不是订单的开门时刻 ——
        // 包场人提前到店时两者相差几小时，返回开门时刻会让前端展示出
        // 与实际收费不符的账单
        List<BillingResult> parts = new ArrayList<>();
        for (TimeRange range : ranges) {
            parts.add(billingService.calculate(range.from(), range.to(), monthSpent,
                    coverage, freeRanges, usage));
        }
        // 起点取【实际计费起点】（第一段的开始），不是订单的开门时刻 ——
        // 包场人提前到店时两者相差几小时，返回开门时刻会让前端展示出
        // 与实际收费不符的账单
        return mergeBills(parts, ranges.get(0).from(), endTime);
    }

    /**
     * 把「该用户在同一半场内已支付的其它订单」的实收预置进半场额度。
     *
     * <p><b>为什么封顶要跨订单</b>：用户随时可以结算再重新开门（那是正常操作，
     * 不是薅羊毛的手段），若额度只看本单，拆单就能绕过封顶 ——
     * 玩满 4 小时 36 分（日场收 40 元封顶）、结算、再玩 4 小时 36 分，
     * 两单合计 80 元，而这个半场本该最多收 40 元。
     *
     * <p>查询范围 = 本次计费涉及的半场窗口（起点取开门时刻所在半场的起点，
     * 终点取离场时刻所在半场的终点）。命中的历史单一律交给
     * {@link BillingService#seedUsage} 按段归位 —— 跨半场的订单会被拆到
     * 各自半场的桶里，因此范围放宽无害，<b>漏掉才会少收钱</b>。
     *
     * <p>⚠️ <b>没有快照的历史订单不参与累计</b>（快照 2026-10-03 起才有）——
     * 宁可不计（对顾客有利）也不去现算：那要走同一套「枚举可变输入」的重算，
     * 而重算结果本来就只敢用于展示。
     *
     * <p>⚠️ <b>老订单重算（{@link #recomputeBill}）也会走到本方法</b>，
     * 那时查询可能命中「本单之后开的单」—— 多扣额度会让重算与落库对不上，
     * 结果自然回落到「不展示账单」（那条路本来就只在金额全等时才用），
     * 不会把错的账当成历史事实。
     *
     * @param usage   待预置的累计器
     * @param order   本次的订单（重算时是那笔老订单）
     * @param endTime 计费截止时刻
     */
    private void seedHalfPeriodUsage(HalfPeriodUsage usage, Order order, LocalDateTime endTime) {
        LocalDateTime from = billingService.halfPeriodStartAt(order.getStartTime());
        LocalDateTime to = billingService.halfPeriodEndAt(endTime);
        for (Order paid : orderMapper.selectPaidOverlapping(
                order.getUserId(), order.getId(), from, to)) {
            OrderBillSnapshot snapshot = parseSnapshot(paid);
            if (snapshot == null || snapshot.getBill() == null
                    || snapshot.getBill().getSegments() == null) {
                continue;
            }
            billingService.seedUsage(usage, snapshot.getBill().getSegments());
        }
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
     * 查询本单结算前、<b>订单开始那个月</b>的累计实付额。
     *
     * <p><b>月份由订单的 {@code startTime} 推出，不是 {@code now}</b> ——
     * 判定依据必须跟着订单走：用户在零点前看预览、零点后点「停止计时」，
     * 若按 {@code now} 取月份，两次取到的累计不同，页面上的价与实际收的价
     * 就对不上了（预览与结算走的是同一个 {@code calculateBill}，
     * 那是「预览价必然等于实际价」的实现基础）。管理员事后修正时长不会让
     * 历史订单的优惠判定漂移，也是同一个道理。
     *
     * <p>⚠️ <b>这里取的是「开始月」，而被查的累计本身按离场月归集</b>
     * （见 {@code OrderMapper#selectMonthPaidAmount} 的注释）：一笔 8/31 进店、
     * 9/1 离店的夜单因此享 <b>8 月</b>已挣到的优惠资格，却计入 <b>9 月</b>的累计。
     * 「判定跟着订单走、归集跟着离场走」是刻意的分工，改动前先读那一处。
     *
     * @param order 订单
     * @return 订单开始那个月的累计实付额（元）；无记录时为 0
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
     * 查询本单区间内命中的免费活动区间。
     *
     * <p>与 {@link #queryCardCoverage} 同一套口径：解析放在这里由 {@code calculateBill}
     * 统一调用，让「结账预览」与「实际结算」不可能各判一套。
     *
     * <p>传的是<b>完整的计费区间</b>而不是包场剪除后的某一段：活动与订单怎么相交，
     * 由 {@code BillingService} 的切段逻辑去裁 —— 那是边界口径的唯一定义处。
     *
     * @param order 订单
     * @param from  区间起点（通常是订单的开门时刻）
     * @param to    区间终点（离场时刻或预览时刻）
     * @return 免费区间列表；没有活动时返回空列表（不必判 null）
     */
    private List<FreeRange> queryFreeRanges(Order order, LocalDateTime from, LocalDateTime to) {
        return freePeriodService.findRangesFor(order.getStoreId(), from, to);
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
        merged.setActivityFreeAmount(sumOf(parts, BillingResult::getActivityFreeAmount));
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
        bill.setActivityFreeAmount(BigDecimal.ZERO);
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
