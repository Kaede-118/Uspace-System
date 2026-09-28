package com.kaede.uspace.space;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.mapper.BookingMapper;

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
            case "selectByBookingNo" -> selectByBookingNo((String) args[0]);
            case "updateSchedule" -> updateSchedule(args);
            case "updateStatus" -> updateStatus(args);
            case "deleteById" -> deleteById((Long) args[0]);
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
