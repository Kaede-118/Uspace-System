package com.kaede.uspace.space;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.security.TokenGenerator;
import com.kaede.uspace.space.dto.BookingParticipantVo;
import com.kaede.uspace.space.dto.BookingScheduleVo;
import com.kaede.uspace.space.dto.BookingVo;
import com.kaede.uspace.space.dto.CreateBookingRequest;
import com.kaede.uspace.space.dto.JoinResultVo;
import com.kaede.uspace.space.dto.UpdateBookingRequest;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.entity.BookingParticipant;
import com.kaede.uspace.space.event.BookingActivatedEvent;
import com.kaede.uspace.space.mapper.BookingMapper;
import com.kaede.uspace.space.mapper.BookingParticipantMapper;
import com.kaede.uspace.space.mapper.StoreMapper;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 包场服务（模块 3）。
 *
 * <p>职责：<b>排期</b> —— 管理员选时段、指定包场人、定价，以及改期与取消。
 *
 * <p><b>付款不在这里</b>。创建出来的包场处于 {@code PENDING_PAYMENT} 状态，
 * 排期已占位但准入尚未生效；付款成功后转 {@code PAID}、生成邀请令牌，
 * 那一整套流程（下单、回调、验签、幂等）属于模块 8。
 * 这条边界让「排期」与「收钱」各自独立：改期不会碰到支付字段，
 * 付款也不会覆盖排期字段（见 {@code BookingMapper#updateSchedule} 的说明）。
 *
 * <p><b>状态流转的两种口径别混用</b>：
 * <ul>
 *   <li>校验「时段是否已被占用」时，{@code PENDING_PAYMENT} 与 {@code PAID}
 *       都算占用 —— 未付款的包场也已经把时段许出去了</li>
 *   <li>判断「此刻哪场包场生效」时，只有 {@code PAID} 算数 ——
 *       没付款的包场不产生排他性，不该把散客挡在门外</li>
 * </ul>
 */
@Slf4j
@Service
public class BookingService {

    /** 包场单号的时间部分格式 */
    private static final DateTimeFormatter NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 包场单号前缀，便于在日志与对账文件里一眼认出这是包场而非普通订单 */
    private static final String NO_PREFIX = "BK";

    /** 用户端时间表一次最多取几条，防止调用方传一个很大的 limit 把整表拉出来 */
    private static final int MAX_SCHEDULE_LIMIT = 20;

    /** 用户端时间表默认取几条 */
    private static final int DEFAULT_SCHEDULE_LIMIT = 10;

    private final BookingMapper bookingMapper;
    private final StoreMapper storeMapper;
    private final ClosureService closureService;

    /**
     * 用于校验包场人是否存在。
     *
     * <p>这里依赖的是 {@code SysUserMapper} 而不是 {@code UserService}，
     * 是刻意取<b>最松的耦合</b>：只需要「这个用户存不存在」这一个事实，
     * 不需要用户模块的任何业务规则。用 Mapper 意味着用户模块改业务逻辑
     * （如注册流程、昵称规则）不会波及本类。
     */
    private final SysUserMapper userMapper;

    /**
     * 包场参与者：谁在这场包场里。
     *
     * <p>下单准入、包场开始的清场、结算时的包场时段剪切，三处的「谁是参与者」
     * 都以这张表为权威来源。它补上的是「被邀请者」这个过去没有落库的身份 ——
     * 在那之前，被邀请者的唯一凭证是分享出去的令牌链接，而链接进不了 SQL。
     */
    private final BookingParticipantMapper participantMapper;

    /**
     * 发布「包场生效」事件，由 {@code qqbot} 监听后播报到群（见 {@link BookingActivatedEvent}）。
     *
     * <p>本类只在<b>0 元场次</b>那一支发布（建单即 PAID）；收费场次的发布点
     * 在 order 包的 {@code BookingPaymentTargetHandler#markPaid} 里 ——
     * 两处共用同一个事件类，理由见它的类注释。
     */
    private final ApplicationEventPublisher eventPublisher;

    public BookingService(BookingMapper bookingMapper,
                          StoreMapper storeMapper,
                          ClosureService closureService,
                          SysUserMapper userMapper,
                          BookingParticipantMapper participantMapper,
                          ApplicationEventPublisher eventPublisher) {
        this.bookingMapper = bookingMapper;
        this.storeMapper = storeMapper;
        this.closureService = closureService;
        this.userMapper = userMapper;
        this.participantMapper = participantMapper;
        this.eventPublisher = eventPublisher;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 取门店的包场时间表：尚未结束的已付款包场。
     *
     * <p>给用户端首页的「近期包场安排」卡片用。它取代了早先「包场发一条公告」的做法 ——
     * 包场是<b>未来的安排</b>，公告是<b>已发生的事</b>，两者混在一条消息流里，
     * 用户分不清哪条是通知、哪条是日程；而且日程会随改期变动，
     * 而消息流是只增不改的，改期后旧公告就永远对不上了。
     *
     * <p><b>不披露包场人</b>：返回的 {@link BookingScheduleVo} 里根本没有那个字段，
     * 与 {@code StoreStatusVo}「包场时也不披露包场人是谁」是同一条边界。
     *
     * @param limit 最多几条；为 null 或非正时取默认值，超出上限则截到上限
     * @return 时间表，按开始时间升序（从近到远）
     */
    public BizResult<List<BookingScheduleVo>> listSchedule(Integer limit) {
        int size = normalizeScheduleLimit(limit);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        return BizResult.ok(bookingMapper.selectUpcoming(now, size).stream()
                .map(booking -> BookingScheduleVo.from(booking, now))
                .toList());
    }

    /**
     * 分页查询包场记录。
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param scope    视图筛选：{@code active}（已生效 + 待付款）、{@code void}（已取消 + 已退款）、
     *                 null 表示不筛
     * @return 成功时返回分页结果；门店不存在时返回 {@link ErrorCode#STORE_NOT_FOUND}
     */
    public BizResult<PageResult<BookingVo>> listBookings(long pageNum, long pageSize, String scope) {
        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        IPage<Booking> page = bookingMapper.selectPageByStore(
                new Page<>(pageNum, pageSize), storeId, scope);
        return BizResult.ok(PageResult.of(page, BookingVo::from));
    }

    /**
     * 分页查询某人<b>作为包场人</b>的场次（含待付款的）。
     *
     * <p>供模块 8 的用户端使用：包场人要能看到自己名下的场、对未付款的发起支付。
     * <b>被邀请者不在返回范围内</b>，他走 {@link #listJoinedBookings}。
     *
     * <p><b>为什么不改读参与者表</b>：{@code HOST} 行是包场<b>付款成功</b>那一刻
     * 才写入的（与邀请令牌同一事务），改读它就会漏掉所有待付款的场次 ——
     * 而包场人恰恰要靠这个列表找到待付款的场、点进付款入口。
     * 两个列表因此各走各的数据源：本方法按 {@code biz_booking.host_user_id} 查，
     * {@link #listJoinedBookings} 按参与者表的 {@code role} 查。
     *
     * @param userId   包场人用户 ID
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页结果，按开始时间倒序
     */
    public BizResult<PageResult<BookingVo>> listHostBookings(Long userId, long pageNum, long pageSize) {
        IPage<Booking> page = bookingMapper.selectPageByHost(new Page<>(pageNum, pageSize), userId);
        return BizResult.ok(PageResult.of(page, BookingVo::from));
    }

    /**
     * 分页查询某人<b>作为被邀请者</b>参与的场次。
     *
     * <p>与 {@link #listHostBookings} 的区别只有角色一个变量 ——
     * 两个列表都从参与者表出发，将来要支持包场人转让、或多发起人时，
     * 这里不必改查询。
     *
     * <p><b>只查 {@code PARTICIPANT}，不含自己发起的场</b>：
     * 「我参与的」与「我创建的」是两个列表，同一个人可能两边都有，
     * 若这里把 {@code HOST} 也算进来，同一场会在两个列表里各出现一次。
     *
     * @param userId   用户 ID
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页结果，按开始时间倒序
     */
    public BizResult<PageResult<BookingVo>> listJoinedBookings(Long userId, long pageNum, long pageSize) {
        IPage<Booking> page = participantMapper.selectPageByUserRole(
                new Page<>(pageNum, pageSize), userId, BookingParticipantRole.PARTICIPANT.name());
        return BizResult.ok(PageResult.of(page, BookingVo::from));
    }

    /**
     * 判断某人是不是这场包场的参与者（包场人也算）。
     *
     * <p><b>这是「谁是参与者」的唯一判定入口</b>，供 order 包的下单准入使用。
     * 三处判定（准入、清场、计费剪切）里，只有准入必须要问这个问题 ——
     * 另外两处读的是订单上的 {@code bookingId}，而下单时就把它挂好了
     * （见 {@link #findUpcomingBookingForParticipant}），主链路因此一行未改。
     *
     * @param bookingId 包场 ID，可为 null
     * @param userId    用户 ID，可为 null
     * @return 是参与者返回 true；任一参数为 null 时返回 false
     */
    public boolean isParticipant(Long bookingId, Long userId) {
        if (bookingId == null || userId == null) {
            return false;
        }
        return participantMapper.countByBookingAndUser(bookingId, userId) > 0;
    }

    /**
     * 查某人参与的、尚未结束的已付款包场，取最近的一场。
     *
     * <p><b>供下单时决定往订单上挂哪个 {@code bookingId}</b>。参与者可能比准入窗口
     * 更早到店（那时包场还没进窗口，准入判定走「普通」分支，订单本不会挂包场），
     * 若不主动挂上，包场开始时他会被当散客清场、结算时还会被重复计费。
     *
     * <p>挂上一场<b>还没到时间</b>的包场是无害的：计费剪区间时会先把包场区间夹到
     * 订单区间内，夹完为空就整段跳过（见 {@code OrderService#billableRanges}）。
     *
     * @param userId 用户 ID
     * @param now    当前时刻
     * @return 最近的一场；门店未初始化或他没有任何未结束的包场时返回 null
     */
    public Booking findUpcomingBookingForParticipant(Long userId, LocalDateTime now) {
        if (userId == null) {
            return null;
        }
        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return null;
        }
        return participantMapper.selectUpcomingByParticipant(storeId, userId, now);
    }

    /**
     * 查某场包场的参与者名单（含昵称与头像）。
     *
     * <p>昵称与头像<b>批量</b>查一次 {@code sys_user} 补齐，不逐条查 ——
     * 名单通常只有几个人，但这是接口每次刷新都会走的热路径，
     * N+1 在这里没有任何必要。
     *
     * <p>用户已被逻辑删除时，他在名单里仍占一行、只是昵称为空
     * （见 {@link BookingParticipantVo#of}）—— 名单要如实反映「这场有谁」，
     * 不该因为一个人注销了就把整行抹掉。
     *
     * @param bookingId 包场 ID
     * @return 参与者名单，发起人排在最前；没有任何参与者时返回空列表
     */
    public List<BookingParticipantVo> listParticipants(Long bookingId) {
        List<BookingParticipant> rows = participantMapper.selectByBookingId(bookingId);
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> userIds = rows.stream()
                .map(BookingParticipant::getUserId)
                .distinct()
                .toList();
        Map<Long, SysUser> users = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, user -> user));

        return rows.stream()
                .map(row -> BookingParticipantVo.of(row, users.get(row.getUserId())))
                .toList();
    }

    /**
     * 查询某时刻生效中的包场。
     *
     * <p>供营业状态判断与准入控制使用。只有 {@code PAID} 的包场会被返回 ——
     * 管理员排了期但包场人还没付款的，不该把散客挡在门外。
     *
     * @param time 待判断的时刻
     * @return 生效中的包场；没有则返回 null
     */
    public Booking findActiveBookingAt(LocalDateTime time) {
        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return null;
        }
        return bookingMapper.selectCoveringAt(storeId, time);
    }

    /**
     * 查找落在「准入窗口」内的已付款包场。
     *
     * <p>与 {@link #findActiveBookingAt} 的差别只在于<b>窗口往前挪了一段</b>：
     * 本方法还认「即将开始」的包场。下单准入要用这个 —— 包场开始前的提前量内
     * 就不再接待新顾客了；而对外展示的营业状态仍用前者，它只认「正在包场中」。
     *
     * <p>提前量由调用方传入而不是在这里读配置：它属于<b>下单准入</b>的规则，
     * 归 order 包管，放在这里会让 space 包反向依赖 order 包的配置类。
     *
     * @param time 当前时刻
     * @param lead 提前量，从 {@code time} 起往后看多远
     * @return 命中窗口的已付款包场；没有则返回 null
     */
    public Booking findAdmissionBookingAt(LocalDateTime time, Duration lead) {
        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return null;
        }
        return bookingMapper.selectAdmissionAt(storeId, time, time.plus(lead));
    }

    // ==================================================================
    // 写操作
    // ==================================================================

    /**
     * 创建包场（管理员排期）。
     *
     * <p>校验链，按「越基础越先查」的顺序排列，让报错尽早落到真正的原因上：
     * <ol>
     *   <li>时段首尾关系</li>
     *   <li>门店存在</li>
     *   <li>包场人存在</li>
     *   <li>不与既有包场重叠</li>
     *   <li>不与停业时段重叠</li>
     * </ol>
     *
     * <p><b>「开始时刻不能是过去」这条校验于 2026-10-04 放开</b>：排一场
     * 已经开始（或过去）的包场是做测试（清场、准入窗口、群播报）与补录的唯一途径，
     * 而它本身不破坏任何数据。提示交给运营后台的前端 —— 提交时弹一次确认层，
     * 那里是唯一还能拦住手滑的地方。错误码 {@code BOOKING_START_IN_PAST}(40911)
     * 保留但已无调用方。
     *
     * <p>新记录固定为 {@code PENDING_PAYMENT} 状态，等待付款；
     * <b>0 元场次例外</b> —— 它落库时就是终态 {@code PAID}，并当场发布生效事件。
     *
     * @param request 包场时段、包场人与价格
     * @param adminId 安排人（当前登录的管理员 ID）
     * @return 成功时返回新建的包场；失败时返回对应错误码
     */
    @Transactional
    public BizResult<BookingVo> createBooking(CreateBookingRequest request, Long adminId) {
        if (!request.getEndAt().isAfter(request.getStartAt())) {
            return BizResult.fail(ErrorCode.BOOKING_TIME_INVALID);
        }

        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        // 包场人要能登录自己的账号取邀请链接，因此必须是已存在的用户
        SysUser host = userMapper.selectById(request.getHostUserId());
        if (host == null) {
            return BizResult.fail(ErrorCode.USER_NOT_FOUND, "包场人不存在");
        }

        int bookingOverlap = bookingMapper.countOverlapping(
                storeId, request.getStartAt(), request.getEndAt(), null);
        if (bookingOverlap > 0) {
            return BizResult.fail(ErrorCode.BOOKING_OVERLAP);
        }

        if (closureService.overlapsClosure(request.getStartAt(), request.getEndAt())) {
            return BizResult.fail(ErrorCode.BOOKING_CLOSURE_OVERLAP);
        }

        Booking booking = new Booking();
        booking.setBookingNo(generateBookingNo());
        booking.setStoreId(storeId);
        booking.setHostUserId(request.getHostUserId());
        booking.setStartAt(request.getStartAt());
        booking.setEndAt(request.getEndAt());
        booking.setPrice(request.getPrice());
        booking.setRemark(trimToNull(request.getRemark()));
        booking.setCreatedBy(adminId);

        /*
         * 包场费为 0 的场次**即刻生效**，不走支付。
         *
         * 不放行的话它会永远卡在「待付款」：时段占着、邀请链接取不出来（那要 PAID 才给），
         * 而且没有任何办法把它推进去 —— 支付回调会校验金额，0 元的单子根本走不到那一步。
         *
         * 令牌与「结清时刻」在 insert 时一次写完，不必先插一条待付款再 UPDATE：
         * 状态从落下的那一刻就是终态，中间没有可观测的过渡态。
         * payment_method / payment_no 留空 —— 确实没有任何支付方式与流水。
         */
        if (booking.getPrice().compareTo(BigDecimal.ZERO) == 0) {
            booking.setStatus(BookingStatus.PAID.name());
            booking.setInviteToken(TokenGenerator.generate());
            booking.setPaidAt(LocalDateTime.now());
        } else {
            booking.setStatus(BookingStatus.PENDING_PAYMENT.name());
        }

        bookingMapper.insert(booking);

        // 0 元场次在上面那一段就已经置成 PAID —— 它此刻起真的生效了，该让群里知道。
        // 收费场次等付款，那个发布点在 BookingPaymentTargetHandler#markPaid 里
        if (BookingStatus.PAID.name().equals(booking.getStatus())) {
            eventPublisher.publishEvent(new BookingActivatedEvent(
                    booking.getId(), booking.getBookingNo(),
                    booking.getStartAt(), booking.getEndAt()));
        }

        log.info("[空间] 创建包场 {} 包场人={} 时段 {} ~ {} 价格={} 状态={}",
                booking.getBookingNo(), booking.getHostUserId(),
                booking.getStartAt(), booking.getEndAt(), booking.getPrice(),
                booking.getStatus());
        return BizResult.ok(BookingVo.from(booking));
    }

    /**
     * 修改包场排期（改期与改价）。
     *
     * <p><b>只有待付款的包场可以修改</b>：已付款的改期需要同时处理退款，
     * 那属于模块 8 与运营流程，不该由一个「改期」接口顺带完成。
     * 已取消、已结束的更是没有改的意义。
     *
     * <p>不校验「开始时间不能是过去」—— 已排期的包场若已开始，
     * 管理员想把它往后挪是合理诉求；只有创建时才必须是将来的时段。
     *
     * @param id      包场 ID
     * @param request 新的时段与价格
     * @return 成功时 data 为 null；失败时返回对应错误码
     */
    @Transactional
    public BizResult<Void> updateBooking(Long id, UpdateBookingRequest request) {
        if (!request.getEndAt().isAfter(request.getStartAt())) {
            return BizResult.fail(ErrorCode.BOOKING_TIME_INVALID);
        }

        Booking existing = bookingMapper.selectById(id);
        if (existing == null) {
            return BizResult.fail(ErrorCode.BOOKING_NOT_FOUND);
        }
        if (!BookingStatus.PENDING_PAYMENT.name().equals(existing.getStatus())) {
            return BizResult.fail(ErrorCode.BOOKING_NOT_EDITABLE);
        }

        int bookingOverlap = bookingMapper.countOverlapping(
                existing.getStoreId(), request.getStartAt(), request.getEndAt(), id);
        if (bookingOverlap > 0) {
            return BizResult.fail(ErrorCode.BOOKING_OVERLAP);
        }

        if (closureService.overlapsClosure(request.getStartAt(), request.getEndAt())) {
            return BizResult.fail(ErrorCode.BOOKING_CLOSURE_OVERLAP);
        }

        bookingMapper.updateSchedule(id, request.getStartAt(), request.getEndAt(),
                request.getPrice(), trimToNull(request.getRemark()));

        log.info("[空间] 修改包场 id={} 新时段 {} ~ {} 新价格={}",
                id, request.getStartAt(), request.getEndAt(), request.getPrice());
        return BizResult.ok(null);
    }

    /**
     * 取消包场。
     *
     * <p>同样是逻辑删除，且<b>只有待付款的可以取消</b> —— 已付款的涉及退款，
     * 理由同 {@link #updateBooking}。
     *
     * <p>取消与删除在数据上是同一件事（置 {@code deleted = 1}），
     * 但接口语义用「取消」：运营说的是「这场包场不办了」，
     * 而不是「把这条记录删掉」。将来若需要保留取消痕迹，
     * 改为置 {@code CANCELLED} 状态即可，接口不必变。
     *
     * @param id 包场 ID
     * @return 成功时 data 为 null；失败时返回对应错误码
     */
    @Transactional
    public BizResult<Void> cancelBooking(Long id) {
        Booking existing = bookingMapper.selectById(id);
        if (existing == null) {
            return BizResult.fail(ErrorCode.BOOKING_NOT_FOUND);
        }
        if (!BookingStatus.PENDING_PAYMENT.name().equals(existing.getStatus())) {
            return BizResult.fail(ErrorCode.BOOKING_NOT_EDITABLE);
        }

        bookingMapper.deleteById(id);

        log.info("[空间] 取消包场 {} 原时段 {} ~ {}",
                existing.getBookingNo(), existing.getStartAt(), existing.getEndAt());
        return BizResult.ok(null);
    }

    /**
     * 加入包场（被邀请者点开邀请链接时调用）。
     *
     * <p><b>重复加入不是错误</b>：刷新页面、从聊天记录里再点一次、落地页加载后
     * 自动重发，都会走到这里，而用户看到的应当始终是「你在这场的名单里」。
     * 所以两种情形都返回成功，靠 {@link JoinResultVo} 里的两个布尔值区分，
     * 让前端决定是「欢迎」还是「你已经进入过了」。
     *
     * <p><b>唯一键冲突在这里是答案，不是异常</b>：两个人同时点同一条链接时，
     * 双方都会先查到「还没加入」，然后都去插入，后到的那个撞上
     * {@code uk_booking_user}。此时捕获它并当作「已加入」返回即可 ——
     * 若改成靠应用层「先查后插」防重，那点时间差正好是并发窗口，
     * 而唯一键是数据库给的、挡得住。这与 {@code BookingPaymentTargetHandler}
     * 里捕获令牌冲突重试是同一套思路，区别只在撞上之后该重试还是该当成功。
     *
     * <p>这里捕获异常不会破坏事务：MySQL 遇到唯一键冲突只是让这一条语句失败，
     * 事务本身仍可继续；而异常没有传播出本方法，Spring 也就不会标记回滚。
     *
     * @param booking 包场实体，由令牌解析而来（必然是已付款的）
     * @param userId  加入者用户 ID
     * @return 加入结果；包场为空时返回 404
     */
    @Transactional
    public BizResult<JoinResultVo> joinByBooking(Booking booking, Long userId) {
        if (booking == null || booking.getId() == null) {
            return BizResult.fail(ErrorCode.NOT_FOUND, "邀请链接无效或已失效");
        }
        Long bookingId = booking.getId();

        if (participantMapper.countByBookingAndUser(bookingId, userId) > 0) {
            return BizResult.ok(JoinResultVo.alreadyJoined(participantMapper.countByBooking(bookingId)));
        }

        BookingParticipant row = new BookingParticipant();
        row.setBookingId(bookingId);
        row.setUserId(userId);
        row.setRole(BookingParticipantRole.PARTICIPANT.name());
        // 截断到秒：库列是 DATETIME（秒精度），而 MySQL 对亚秒是四舍五入而非截断，
        // 不截断会让写进去的值与内存里的值差最多 1 秒。项目里其他地方同此习惯。
        row.setJoinedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));

        try {
            participantMapper.insert(row);
        } catch (DuplicateKeyException e) {
            log.info("[空间] 重复加入包场（并发撞唯一键）bookingId={} userId={}", bookingId, userId);
            return BizResult.ok(JoinResultVo.alreadyJoined(participantMapper.countByBooking(bookingId)));
        }

        log.info("[空间] 加入包场 bookingId={} userId={}", bookingId, userId);
        return BizResult.ok(JoinResultVo.joined(participantMapper.countByBooking(bookingId)));
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 归一化的时间表条数上限。
     *
     * <p>与公告模块同一套做法：不合法时<b>回落而不是报错</b> ——
     * 传错（负数、超大值）的后果只是多取或少取几条日程，
     * 没有让整个首页接口失败的必要。
     *
     * @param limit 原始值，可为 null
     * @return 1 到 {@value #MAX_SCHEDULE_LIMIT} 之间的条数
     */
    private static int normalizeScheduleLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_SCHEDULE_LIMIT;
        }
        return Math.min(limit, MAX_SCHEDULE_LIMIT);
    }

    /**
     * 生成包场单号。
     *
     * <p>格式 {@code BK + yyyyMMddHHmmss + 4 位随机数}，如
     * {@code BK202609281430251739}，共 20 位。
     *
     * <p>这个单号将来会充当支付接口的<b>商户订单号</b>（{@code out_trade_no}），
     * 回调靠它定位记录并做幂等，因此库里有唯一索引。
     * 时间戳保证递增可读，随机后缀避免同秒并发碰撞；
     * 万一仍然撞上，唯一索引会拦下，不会写出两条同号的记录。
     *
     * @return 新的包场单号
     */
    private static String generateBookingNo() {
        return NO_PREFIX
                + LocalDateTime.now().format(NO_FORMATTER)
                + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    /**
     * 去除首尾空白，空串归一为 null。
     *
     * @param value 原始字符串，可为 null
     * @return 去空白后的字符串；空白串返回 null
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
