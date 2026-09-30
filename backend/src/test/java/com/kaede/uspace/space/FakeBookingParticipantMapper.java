package com.kaede.uspace.space;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.space.entity.Booking;
import com.kaede.uspace.space.entity.BookingParticipant;
import com.kaede.uspace.space.mapper.BookingParticipantMapper;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 内存版的 {@link BookingParticipantMapper}，让包场参与者的单元测试不依赖数据库。
 *
 * <p><b>本类最要紧的是真的抛 {@link DuplicateKeyException}</b>：真实库里
 * {@code uk_booking_user} 撞键时抛的就是它，而 {@code BookingService#joinByBooking}
 * 正是靠捕获它把「两人同时点同一条链接」变成正常的「已加入」结果。
 * 假实现若只做一次「存在就跳过」的判断、不抛异常，那条 catch 分支就永远测不到 ——
 * 而它恰恰是并发下唯一正确的那条路径。
 *
 * <p>另外两个方法（{@code selectPageByUserRole} 与 {@code selectUpcomingByParticipant}）
 * 在真实 SQL 里要连 {@code biz_booking}，所以本类持有一个
 * {@link FakeBookingMapper} 把两张表连起来 —— 假实现没有 SQL，
 * 这个连接关系只能自己维护。
 *
 * <p>其余约定同 {@code FakeBookingMapper}：动态代理、按方法名分发、
 * 模拟逻辑删除过滤、未实现的方法直接抛异常而不是静默返回。
 */
public class FakeBookingParticipantMapper implements InvocationHandler {

    /** 模拟数据表 */
    private final Map<Long, BookingParticipant> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /** 关联的包场表，用来模拟 JOIN */
    private final FakeBookingMapper bookingMapper;

    /**
     * 下一次 {@code insert} 是否强制抛唯一键冲突。
     *
     * <p>真实的冲突来自并发：两个人同时点同一条链接，双方都查到「还没加入」、
     * 都去插，后到的那个撞键。而「先查后插」的写法在单线程测试里，
     * 第二次调用会走「查到已有」那条分支，<b>永远碰不到 catch</b> ——
     * 于是用这个开关把那条路径单独逼出来。少了它，
     * 并发下唯一正确的那段代码就没有任何用例看着。
     */
    private boolean nextInsertDuplicates = false;

    /**
     * 让下一次 {@code insert} 抛唯一键冲突（模拟并发下的后到者）。
     *
     * @return 本对象，便于链式调用
     */
    public FakeBookingParticipantMapper failNextInsertWithDuplicateKey() {
        this.nextInsertDuplicates = true;
        return this;
    }

    /**
     * @param bookingMapper 同一个测试里给 BookingService 用的那个假包场 Mapper ——
     *                      两个假实现必须共享同一份数据，否则 JOIN 出来的结果是空的
     */
    public FakeBookingParticipantMapper(FakeBookingMapper bookingMapper) {
        this.bookingMapper = bookingMapper;
    }

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return BookingParticipantMapper 的假实现
     */
    public BookingParticipantMapper asMapper() {
        return (BookingParticipantMapper) Proxy.newProxyInstance(
                BookingParticipantMapper.class.getClassLoader(),
                new Class<?>[]{BookingParticipantMapper.class},
                this);
    }

    /**
     * 预置一条参与者记录（加入时刻取一个固定的过去时刻）。
     *
     * @param bookingId 包场 ID
     * @param userId    用户 ID
     * @param role      角色名，见 {@link BookingParticipantRole}
     * @return 插入后的记录
     */
    public BookingParticipant seed(Long bookingId, Long userId, String role) {
        return seed(bookingId, userId, role, LocalDateTime.of(2026, 9, 30, 12, 0));
    }

    /**
     * 预置一条参与者记录。
     *
     * <p>直接走 {@code insert}，因此同样受唯一键约束 —— 用同一个
     * (bookingId, userId) 预置两次会抛异常，这是刻意的：
     * 真库里那也是插不进去的。
     *
     * @param bookingId 包场 ID
     * @param userId    用户 ID
     * @param role      角色名
     * @param joinedAt  加入时刻
     * @return 插入后的记录
     */
    public BookingParticipant seed(Long bookingId, Long userId, String role, LocalDateTime joinedAt) {
        BookingParticipant row = new BookingParticipant();
        row.setBookingId(bookingId);
        row.setUserId(userId);
        row.setRole(role);
        row.setJoinedAt(joinedAt);
        insert(row);
        return row;
    }

    /**
     * 表中现有的记录条数，供测试断言。
     *
     * @return 记录条数（含已逻辑删除的）
     */
    public int size() {
        return rows.size();
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
            case "insert" -> insert((BookingParticipant) args[0]);
            case "countByBookingAndUser" -> countByBookingAndUser(args);
            case "countByBooking" -> countByBooking((Long) args[0]);
            case "selectByBookingId" -> selectByBookingId((Long) args[0]);
            case "selectPageByUserRole" -> selectPageByUserRole(args);
            case "selectUpcomingByParticipant" -> selectUpcomingByParticipant(args);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeBookingParticipantMapper 中补上对应实现");
        };
    }

    // ==================================================================
    // 各方法的模拟实现
    // ==================================================================

    /**
     * 插入。模拟自增主键、逻辑删除默认值，以及<b>唯一键 {@code uk_booking_user}</b>。
     *
     * @param row 待插入的记录
     * @return 受影响行数，恒为 1
     * @throws DuplicateKeyException 同一场同一人已有记录时
     */
    private int insert(BookingParticipant row) {
        if (nextInsertDuplicates) {
            nextInsertDuplicates = false;
            throw new DuplicateKeyException("模拟：并发下撞唯一索引 uk_booking_user");
        }

        boolean duplicate = rows.values().stream()
                .filter(this::isAlive)
                .anyMatch(existing -> existing.getBookingId().equals(row.getBookingId())
                        && existing.getUserId().equals(row.getUserId()));
        if (duplicate) {
            throw new DuplicateKeyException("模拟：撞唯一索引 uk_booking_user");
        }

        row.setId(allocateId());
        if (row.getDeleted() == null) {
            row.setDeleted(0);
        }
        rows.put(row.getId(), row);
        return 1;
    }

    /**
     * 判定某人在不在这场包场里。
     *
     * @param args 依次为包场 ID、用户 ID
     * @return 命中行数
     */
    private int countByBookingAndUser(Object[] args) {
        Long bookingId = (Long) args[0];
        Long userId = (Long) args[1];
        return (int) rows.values().stream()
                .filter(this::isAlive)
                .filter(row -> bookingId.equals(row.getBookingId())
                        && userId.equals(row.getUserId()))
                .count();
    }

    /**
     * 统计某场的人数。
     *
     * @param bookingId 包场 ID
     * @return 人数
     */
    private int countByBooking(Long bookingId) {
        return (int) rows.values().stream()
                .filter(this::isAlive)
                .filter(row -> bookingId.equals(row.getBookingId()))
                .count();
    }

    /**
     * 查某场的参与者名单。
     *
     * <p>逐条复刻真实 SQL 的排序：<b>发起人优先，其后按加入时刻升序</b>。
     * 排序若只按加入时刻，包场人若比某个被邀请者晚加入（付款在别人点链接之后），
     * 名单上的发起人就会跑到中间去 —— 而邀请页要把他标在最前。
     *
     * @param bookingId 包场 ID
     * @return 参与者列表
     */
    private List<BookingParticipant> selectByBookingId(Long bookingId) {
        return rows.values().stream()
                .filter(this::isAlive)
                .filter(row -> bookingId.equals(row.getBookingId()))
                .sorted(Comparator
                        .comparingInt((BookingParticipant row) ->
                                BookingParticipantRole.isHost(row.getRole()) ? 0 : 1)
                        .thenComparing(BookingParticipant::getJoinedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(BookingParticipant::getId))
                .toList();
    }

    /**
     * 分页查某人以某个角色参与的包场（模拟 JOIN {@code biz_booking}）。
     *
     * @param args 依次为分页对象、用户 ID、角色名
     * @return 分页结果，按开始时间倒序
     */
    @SuppressWarnings("unchecked")
    private IPage<Booking> selectPageByUserRole(Object[] args) {
        IPage<Booking> page = (IPage<Booking>) args[0];
        Long userId = (Long) args[1];
        String role = (String) args[2];

        Set<Long> bookingIds = rows.values().stream()
                .filter(this::isAlive)
                .filter(row -> userId.equals(row.getUserId()) && role.equals(row.getRole()))
                .map(BookingParticipant::getBookingId)
                .collect(Collectors.toSet());

        List<Booking> all = bookingMapper.allAlive().stream()
                .filter(booking -> bookingIds.contains(booking.getId()))
                .sorted(Comparator.comparing(Booking::getStartAt).reversed())
                .toList();

        page.setTotal(all.size());
        int from = (int) Math.min((page.getCurrent() - 1) * page.getSize(), all.size());
        int to = (int) Math.min(from + page.getSize(), all.size());
        page.setRecords(all.subList(from, to));
        return page;
    }

    /**
     * 查某人参与的、尚未结束的已付款包场，取最近的一场（模拟 JOIN {@code biz_booking}）。
     *
     * <p>逐条复刻真实 SQL 的筛选条件：<b>只认 {@code PAID}</b>、
     * 「未结束」用 {@code end_at > now}、按开始时间升序取第一条。
     *
     * @param args 依次为门店 ID、用户 ID、当前时刻
     * @return 最近的一场；没有则返回 null
     */
    private Booking selectUpcomingByParticipant(Object[] args) {
        Long storeId = (Long) args[0];
        Long userId = (Long) args[1];
        LocalDateTime now = (LocalDateTime) args[2];

        Set<Long> bookingIds = rows.values().stream()
                .filter(this::isAlive)
                .filter(row -> userId.equals(row.getUserId()))
                .map(BookingParticipant::getBookingId)
                .collect(Collectors.toSet());

        return bookingMapper.allAlive().stream()
                .filter(booking -> storeId.equals(booking.getStoreId()))
                .filter(booking -> BookingStatus.PAID.name().equals(booking.getStatus()))
                .filter(booking -> bookingIds.contains(booking.getId()))
                .filter(booking -> booking.getEndAt().isAfter(now))
                .min(Comparator.comparing(Booking::getStartAt))
                .orElse(null);
    }

    /**
     * 分配一个未被占用的自增主键。
     *
     * @return 新的主键
     */
    private long allocateId() {
        while (rows.containsKey(nextId)) {
            nextId++;
        }
        return nextId++;
    }

    /**
     * 判断一行是否未被逻辑删除。
     *
     * @param row 记录，可为 null
     * @return 有效返回 true
     */
    private boolean isAlive(BookingParticipant row) {
        return row != null && (row.getDeleted() == null || row.getDeleted() == 0);
    }
}
