package com.kaede.uspace.space;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.mapper.BookingMapper;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内存版的 {@link BookingMapper}，让模块 3 的单元测试不依赖数据库。
 *
 * <p><b>本类最要紧的是复刻「两种状态口径」的区别</b>，这正是包场逻辑里
 * 最容易写错的地方：
 * <ul>
 *   <li>重叠统计认 {@code PENDING_PAYMENT + PAID} —— 未付款的包场也占着时段</li>
 *   <li>生效查询只认 {@code PAID} —— 未付款的不产生排他性</li>
 * </ul>
 * 假实现若把两者写成一个口径，测试就会漏掉「排期未付款时散客仍可进店」
 * 这条规则，而那恰恰是设计上刻意保留的行为。
 *
 * <p>其余约定同 {@code FakeStoreMapper}：动态代理、按方法名分发、
 * 模拟逻辑删除过滤。
 */
public class FakeBookingMapper implements InvocationHandler {

    /** 占用时段的状态：待付款与已付款。与真实 SQL 里的 {@code status IN (...)} 对应 */
    private static final Set<String> SLOT_OCCUPYING =
            Set.of(BookingStatus.PENDING_PAYMENT.name(), BookingStatus.PAID.name());

    /** 模拟数据表 */
    private final Map<Long, Booking> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 下一次 {@code markPaid} 是否抛唯一索引冲突。
     *
     * <p>模拟「随机生成的邀请令牌恰好撞上库里已有的那一串」——
     * 真实概率约 2^-256，但重试逻辑若写错了，没有别的方式能测出来。
     */
    private boolean nextMarkPaidDuplicates = false;

    /**
     * 让下一次 {@code markPaid} 抛唯一索引冲突。
     *
     * @return 本对象，便于链式调用
     */
    public FakeBookingMapper failNextMarkPaidWithDuplicateKey() {
        this.nextMarkPaidDuplicates = true;
        return this;
    }

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return BookingMapper 的假实现
     */
    public BookingMapper asMapper() {
        return (BookingMapper) Proxy.newProxyInstance(
                BookingMapper.class.getClassLoader(),
                new Class<?>[]{BookingMapper.class},
                this);
    }

    /**
     * 预置一条包场记录。
     *
     * @param booking 记录，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public Booking seed(Booking booking) {
        if (booking.getId() == null) {
            booking.setId(allocateId());
        }
        if (booking.getDeleted() == null) {
            booking.setDeleted(0);
        }
        rows.put(booking.getId(), booking);
        return booking;
    }

    /**
     * 分配一个未被占用的自增主键。
     *
     * <p><b>必须跳过已占用的 ID</b>：测试里可能用显式 ID 预置数据，
     * 若游标不跳过它，后续不带 ID 的 {@code seed} 会分配到同一个 ID
     * 并把先前的记录悄悄覆盖掉 —— 不报错，但测试结论已经不可信了。
     */
    private long allocateId() {
        while (rows.containsKey(nextId)) {
            nextId++;
        }
        return nextId++;
    }

    /**
     * 按 ID 取出表中的当前状态，供测试断言。
     *
     * @param id 包场 ID
     * @return 记录；不存在时返回 null
     */
    public Booking get(Long id) {
        return rows.get(id);
    }

    /**
     * 取出表中所有未被逻辑删除的包场。
     *
     * <p>供 {@link FakeBookingParticipantMapper} 模拟 JOIN 用：真实的
     * {@code selectPageByUserRole} 与 {@code selectUpcomingByParticipant}
     * 都要连 {@code biz_booking} 这张表，而假实现里没有 SQL，
     * 只能由它自己把两张表连起来。
     *
     * <p>返回的是内部集合的副本（{@code toList()} 产出不可变列表），
     * 调用方无法通过它改动假表 —— 与真实 Mapper 的只读查询语义一致。
     *
     * @return 未删除的包场，顺序不保证
     */
    public List<Booking> allAlive() {
        return rows.values().stream().filter(this::isAlive).toList();
    }

    /**
     * 方法分发。方法名唯一，所以按名字匹配即可。
     *
     * @param proxy  代理对象（未使用）
     * @param method 被调用的方法
     * @param args   调用参数
     * @return 方法返回值
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "insert" -> insert((Booking) args[0]);
            case "selectById" -> selectById((Long) args[0]);
            case "selectPageByStore" -> selectPageByStore(args);
            case "countOverlapping" -> countOverlapping(args);
            case "selectCoveringAt" -> selectCoveringAt(args);
            case "selectAdmissionAt" -> selectAdmissionAt(args);
            case "selectByBookingNo" -> selectByBookingNo((String) args[0]);
            case "updateSchedule" -> updateSchedule(args);
            case "updateStatus" -> updateStatus(args);
            case "deleteById" -> deleteById((Long) args[0]);
            case "selectPageByHost" -> selectPageByHost(args);
            case "selectHostBookingsInRange" -> selectHostBookingsInRange(args);
            case "selectByInviteToken" -> selectByInviteToken((String) args[0]);
            case "markPaid" -> markPaid(args);
            case "markRefunded" -> markRefunded(args);
            case "revertRefund" -> revertRefund((Long) args[0]);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeBookingMapper 中补上对应实现");
        };
    }

    // ==================================================================
    // 各方法的模拟实现
    // ==================================================================

    /**
     * 插入。模拟自增主键与逻辑删除标记的默认值。
     *
     * @param booking 待插入的记录
     * @return 受影响行数，恒为 1
     */
    private int insert(Booking booking) {
        booking.setId(allocateId());
        if (booking.getDeleted() == null) {
            booking.setDeleted(0);
        }
        rows.put(booking.getId(), booking);
        return 1;
    }

    /**
     * 按 ID 查询，过滤已逻辑删除的行。
     *
     * @param id 包场 ID
     * @return 记录；不存在或已删除时返回 null
     */
    private Booking selectById(Long id) {
        Booking booking = rows.get(id);
        return isAlive(booking) ? booking : null;
    }

    /**
     * 分页查询某门店的包场记录，按开始时间倒序。
     *
     * @param args 依次为分页对象、门店 ID
     * @return 分页结果（直接写入传入的 page 对象）
     */
    @SuppressWarnings("unchecked")
    private IPage<Booking> selectPageByStore(Object[] args) {
        IPage<Booking> page = (IPage<Booking>) args[0];
        Long storeId = (Long) args[1];

        List<Booking> all = rows.values().stream()
                .filter(this::isAlive)
                .filter(b -> storeId.equals(b.getStoreId()))
                .sorted(Comparator.comparing(Booking::getStartAt).reversed())
                .toList();

        page.setTotal(all.size());

        int from = (int) Math.min((page.getCurrent() - 1) * page.getSize(), all.size());
        int to = (int) Math.min(from + page.getSize(), all.size());
        page.setRecords(all.subList(from, to));
        return page;
    }

    /**
     * 统计与给定时段重叠、且仍在占用时段的包场数。
     *
     * <p>只认 {@code PENDING_PAYMENT} 与 {@code PAID} —— 已取消、已结束的不占时段。
     *
     * @param args 依次为门店 ID、startAt、endAt、excludeId
     * @return 重叠的记录数
     */
    private int countOverlapping(Object[] args) {
        Long storeId = (Long) args[0];
        LocalDateTime startAt = (LocalDateTime) args[1];
        LocalDateTime endAt = (LocalDateTime) args[2];
        Long excludeId = (Long) args[3];

        return (int) rows.values().stream()
                .filter(this::isAlive)
                .filter(b -> storeId.equals(b.getStoreId()))
                .filter(b -> SLOT_OCCUPYING.contains(b.getStatus()))
                .filter(b -> excludeId == null || !excludeId.equals(b.getId()))
                .filter(b -> b.getStartAt().isBefore(endAt) && b.getEndAt().isAfter(startAt))
                .count();
    }

    /**
     * 查询覆盖给定时刻、且已付款生效的包场。
     *
     * <p><b>只认 {@code PAID}</b>：管理员排了期但对方还没付款的，
     * 不该把散客挡在门外。
     *
     * @param args 依次为门店 ID、时刻
     * @return 覆盖该时刻的已付款包场；没有则返回 null
     */
    private Booking selectCoveringAt(Object[] args) {
        Long storeId = (Long) args[0];
        LocalDateTime time = (LocalDateTime) args[1];

        return rows.values().stream()
                .filter(this::isAlive)
                .filter(b -> storeId.equals(b.getStoreId()))
                .filter(b -> BookingStatus.PAID.name().equals(b.getStatus()))
                .filter(b -> !b.getStartAt().isAfter(time) && b.getEndAt().isAfter(time))
                .max(Comparator.comparing(Booking::getStartAt))
                .orElse(null);
    }

    /**
     * 准入窗口查询：与 {@link #selectCoveringAt} 同一套语义，只是窗口往前挪了一段。
     *
     * <p><b>三个条件必须与真实 SQL 逐字一致</b>：只认 {@code PAID}、开始时刻不晚于
     * 窗口右端、结束时刻晚于当前时刻。写反任何一条，准入用例得出的都是一份
     * 「看着合理」的假结论 —— 尤其是把「开始时刻 ≤ 窗口右端」写成「≤ 当前时刻」，
     * 会让预备期永远命不中，而所有既有用例照样全绿。
     *
     * @param args 依次为门店 ID、当前时刻、窗口右端
     * @return 命中窗口的已付款包场；没有则返回 null
     */
    private Booking selectAdmissionAt(Object[] args) {
        Long storeId = (Long) args[0];
        LocalDateTime time = (LocalDateTime) args[1];
        LocalDateTime windowEnd = (LocalDateTime) args[2];

        return rows.values().stream()
                .filter(this::isAlive)
                .filter(b -> storeId.equals(b.getStoreId()))
                .filter(b -> BookingStatus.PAID.name().equals(b.getStatus()))
                .filter(b -> !b.getStartAt().isAfter(windowEnd) && b.getEndAt().isAfter(time))
                .max(Comparator.comparing(Booking::getStartAt))
                .orElse(null);
    }

    /**
     * 按包场单号查询。
     *
     * @param bookingNo 包场单号
     * @return 记录；不存在时返回 null
     */
    private Booking selectByBookingNo(String bookingNo) {
        return rows.values().stream()
                .filter(this::isAlive)
                .filter(b -> bookingNo.equals(b.getBookingNo()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 更新排期（改期与改价）。
     *
     * <p><b>刻意不碰状态与支付字段</b> —— 与真实 SQL 一致。
     * 这条边界让「改期」与「付款」不会互相覆盖。
     *
     * @param args 依次为 id、startAt、endAt、price、remark
     * @return 受影响行数；0 表示记录不存在或已删除
     */
    private int updateSchedule(Object[] args) {
        Booking booking = selectById((Long) args[0]);
        if (booking == null) {
            return 0;
        }
        booking.setStartAt((LocalDateTime) args[1]);
        booking.setEndAt((LocalDateTime) args[2]);
        booking.setPrice((BigDecimal) args[3]);
        booking.setRemark((String) args[4]);
        return 1;
    }

    /**
     * 更新状态。
     *
     * @param args 依次为 id、目标状态
     * @return 受影响行数；0 表示记录不存在或已删除
     */
    private int updateStatus(Object[] args) {
        Booking booking = selectById((Long) args[0]);
        if (booking == null) {
            return 0;
        }
        booking.setStatus((String) args[1]);
        return 1;
    }

    // ==================================================================
    // 以下供模块 8 使用（订单结算剪切包场、包场付款、邀请令牌）
    // ==================================================================

    /**
     * 分页查询某人作为包场人的场次。
     *
     * @param args 依次为分页对象、包场人用户 ID
     * @return 分页结果，按开始时间倒序
     */
    @SuppressWarnings("unchecked")
    private IPage<Booking> selectPageByHost(Object[] args) {
        IPage<Booking> page = (IPage<Booking>) args[0];
        Long hostUserId = (Long) args[1];

        List<Booking> all = rows.values().stream()
                .filter(this::isAlive)
                .filter(b -> hostUserId.equals(b.getHostUserId()))
                .sorted(Comparator.comparing(Booking::getStartAt).reversed())
                .toList();

        page.setTotal(all.size());
        int from = (int) Math.min((page.getCurrent() - 1) * page.getSize(), all.size());
        int to = (int) Math.min(from + page.getSize(), all.size());
        page.setRecords(all.subList(from, to));
        return page;
    }

    /**
     * 查询某人在给定区间内作为包场人的、已付款生效的包场。
     *
     * <p>逐条复刻真实 SQL 的筛选条件：<b>只认 {@code PAID}</b>、
     * 区间相交用半开判断、按开始时间升序。
     * 半开判断若写成闭区间，「包场恰好从订单结束时刻开始」这种边界
     * 会被误判为相交，而那条边界正是「相邻不算重叠」规则的体现。
     *
     * @param args 依次为门店 ID、包场人用户 ID、from、to
     * @return 相交的包场列表
     */
    private List<Booking> selectHostBookingsInRange(Object[] args) {
        Long storeId = (Long) args[0];
        Long hostUserId = (Long) args[1];
        LocalDateTime from = (LocalDateTime) args[2];
        LocalDateTime to = (LocalDateTime) args[3];

        return rows.values().stream()
                .filter(this::isAlive)
                .filter(b -> storeId.equals(b.getStoreId()))
                .filter(b -> hostUserId.equals(b.getHostUserId()))
                .filter(b -> BookingStatus.PAID.name().equals(b.getStatus()))
                .filter(b -> b.getStartAt().isBefore(to) && b.getEndAt().isAfter(from))
                .sorted(Comparator.comparing(Booking::getStartAt))
                .toList();
    }

    /**
     * 按邀请令牌查询。
     *
     * @param inviteToken 邀请令牌
     * @return 记录；不存在时返回 null
     */
    private Booking selectByInviteToken(String inviteToken) {
        return rows.values().stream()
                .filter(this::isAlive)
                .filter(b -> inviteToken.equals(b.getInviteToken()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 包场付款成功：转已付款并写入全部支付字段与邀请令牌。
     *
     * <p><b>带「待付款」状态守卫</b> —— 与真实 SQL 的
     * {@code AND status = 'PENDING_PAYMENT'} 一致。这是支付回调幂等的关键一道：
     * 重复回调时第二次会拿到 0 行受影响，调用方据此跳过累加用户消费额那一步。
     *
     * @param args 依次为 id、paymentMethod、paymentNo、paidAt、inviteToken
     * @return 受影响行数
     */
    private int markPaid(Object[] args) {
        if (nextMarkPaidDuplicates) {
            nextMarkPaidDuplicates = false;
            throw new DuplicateKeyException("模拟：邀请令牌撞唯一索引 uk_invite_token");
        }

        Booking booking = selectById((Long) args[0]);
        if (booking == null
                || !BookingStatus.PENDING_PAYMENT.name().equals(booking.getStatus())) {
            return 0;
        }
        booking.setStatus(BookingStatus.PAID.name());
        booking.setPaymentMethod((String) args[1]);
        booking.setPaymentNo((String) args[2]);
        booking.setPaidAt((LocalDateTime) args[3]);
        booking.setInviteToken((String) args[4]);
        return 1;
    }

    /**
     * 撤销并记退款。对应真 SQL 的 {@code WHERE status = 'PAID'} 守卫。
     *
     * <p>⚠️ 真 SQL 里状态与退款字段是同一条 UPDATE 写下去的，
     * 这里也必须一起改 —— 分成两步的话，假 Mapper 上跑得通、
     * 真库上却可能出现「状态已退款、退款字段还是空」的中间态。
     *
     * @param args 依次为包场 ID、退款方式、退款金额、退款时刻、操作人、退款单号
     * @return 受影响行数；0 表示该场不是已付款状态（含已被撤销过）
     */
    private int markRefunded(Object[] args) {
        Booking booking = selectById((Long) args[0]);
        if (booking == null || !BookingStatus.PAID.name().equals(booking.getStatus())) {
            return 0;
        }
        booking.setStatus(BookingStatus.REFUNDED.name());
        booking.setRefundMode((String) args[1]);
        booking.setRefundAmount((BigDecimal) args[2]);
        booking.setRefundedAt((LocalDateTime) args[3]);
        booking.setRefundedBy((Long) args[4]);
        booking.setRefundNo((String) args[5]);
        return 1;
    }

    /**
     * 退款失败时把状态退回已付款。对应真 SQL 的 {@code WHERE status = 'REFUNDED'} 守卫。
     *
     * @param id 包场 ID
     * @return 受影响行数；0 表示状态已被改动过
     */
    private int revertRefund(Long id) {
        Booking booking = selectById(id);
        if (booking == null || !BookingStatus.REFUNDED.name().equals(booking.getStatus())) {
            return 0;
        }
        booking.setStatus(BookingStatus.PAID.name());
        booking.setRefundMode(null);
        booking.setRefundAmount(null);
        booking.setRefundedAt(null);
        booking.setRefundedBy(null);
        booking.setRefundNo(null);
        return 1;
    }

    /**
     * 逻辑删除。模拟真实库里 {@code DELETE} 被改写成 {@code UPDATE ... SET deleted = 1}。
     *
     * @param id 包场 ID
     * @return 受影响行数；0 表示记录不存在或已删除
     */
    private int deleteById(Long id) {
        Booking booking = selectById(id);
        if (booking == null) {
            return 0;
        }
        booking.setDeleted(1);
        return 1;
    }

    /**
     * 判断一行是否未被逻辑删除。
     *
     * @param booking 记录，可为 null
     * @return 有效返回 true
     */
    private boolean isAlive(Booking booking) {
        return booking != null && (booking.getDeleted() == null || booking.getDeleted() == 0);
    }
}
