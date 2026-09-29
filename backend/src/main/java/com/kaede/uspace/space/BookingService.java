package com.kaede.uspace.space;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.space.dto.BookingVo;
import com.kaede.uspace.space.dto.CreateBookingRequest;
import com.kaede.uspace.space.dto.UpdateBookingRequest;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.mapper.BookingMapper;
import com.kaede.uspace.space.mapper.StoreMapper;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

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

    public BookingService(BookingMapper bookingMapper,
                          StoreMapper storeMapper,
                          ClosureService closureService,
                          SysUserMapper userMapper) {
        this.bookingMapper = bookingMapper;
        this.storeMapper = storeMapper;
        this.closureService = closureService;
        this.userMapper = userMapper;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 分页查询包场记录。
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @return 成功时返回分页结果；门店不存在时返回 {@link ErrorCode#STORE_NOT_FOUND}
     */
    public BizResult<PageResult<BookingVo>> listBookings(long pageNum, long pageSize) {
        Long storeId = storeMapper.selectCurrentId();
        if (storeId == null) {
            return BizResult.fail(ErrorCode.STORE_NOT_FOUND);
        }

        IPage<Booking> page = bookingMapper.selectPageByStore(new Page<>(pageNum, pageSize), storeId);
        return BizResult.ok(PageResult.of(page, BookingVo::from));
    }

    /**
     * 分页查询某人作为包场人的场次（含待付款的）。
     *
     * <p>供模块 8 的用户端使用：包场人要能看到自己名下的场、对未付款的发起支付。
     * <b>被邀请者不在返回范围内</b> —— 他不记在 {@code host_user_id} 上，
     * 只能凭邀请链接查看那一场（见模块 8 的邀请令牌入口）。
     *
     * @param userId   包场人用户 ID
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页结果，按开始时间倒序
     */
    public BizResult<PageResult<BookingVo>> listMyBookings(Long userId, long pageNum, long pageSize) {
        IPage<Booking> page = bookingMapper.selectPageByHost(new Page<>(pageNum, pageSize), userId);
        return BizResult.ok(PageResult.of(page, BookingVo::from));
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
     *   <li>时段不能是过去</li>
     *   <li>门店存在</li>
     *   <li>包场人存在</li>
     *   <li>不与既有包场重叠</li>
     *   <li>不与停业时段重叠</li>
     * </ol>
     *
     * <p>新记录固定为 {@code PENDING_PAYMENT} 状态，等待付款。
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
        if (request.getStartAt().isBefore(LocalDateTime.now())) {
            return BizResult.fail(ErrorCode.BOOKING_START_IN_PAST);
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
        booking.setStatus(BookingStatus.PENDING_PAYMENT.name());
        booking.setRemark(trimToNull(request.getRemark()));
        booking.setCreatedBy(adminId);
        bookingMapper.insert(booking);

        log.info("[空间] 创建包场 {} 包场人={} 时段 {} ~ {} 价格={}",
                booking.getBookingNo(), booking.getHostUserId(),
                booking.getStartAt(), booking.getEndAt(), booking.getPrice());
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

    // ==================================================================
    // 内部工具
    // ==================================================================

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
