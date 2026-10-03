package com.kaede.uspace.billing;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.billing.entity.FreePeriod;
import com.kaede.uspace.billing.mapper.FreePeriodMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版的 {@link FreePeriodMapper}，让免费时段（模块 7）的单元测试不依赖数据库。
 *
 * <p><b>区间判断的口径必须与真实 SQL 完全一致</b>：半开区间
 * （{@code start_at < 另一端 end AND end_at > 另一端 start}），
 * 因此首尾相接的两场活动（18:00–20:00 与 20:00–22:00）<b>不算重叠</b>。
 * 差别一个字，边界上的用例（活动 20:00 开始那一刻进店的订单）就会得出相反的结论，
 * 而单测却照样通过 —— 那比没有测试更危险。所以这里逐字照抄 SQL 的比较符。
 *
 * <p>其余约定同 {@code FakeClosureMapper}：动态代理、按方法名分发、
 * 模拟逻辑删除过滤。
 */
public class FakeFreePeriodMapper implements InvocationHandler {

    /** 模拟数据表 */
    private final Map<Long, FreePeriod> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return FreePeriodMapper 的假实现
     */
    public FreePeriodMapper asMapper() {
        return (FreePeriodMapper) Proxy.newProxyInstance(
                FreePeriodMapper.class.getClassLoader(),
                new Class<?>[]{FreePeriodMapper.class},
                this);
    }

    /**
     * 预置一条活动记录。
     *
     * @param period 记录，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public FreePeriod seed(FreePeriod period) {
        if (period.getId() == null) {
            period.setId(allocateId());
        }
        if (period.getDeleted() == null) {
            period.setDeleted(0);
        }
        rows.put(period.getId(), period);
        return period;
    }

    /**
     * 按 ID 取出表中的当前状态，供测试断言。
     *
     * @param id 记录 ID
     * @return 记录；不存在时返回 null
     */
    public FreePeriod get(Long id) {
        return rows.get(id);
    }

    /**
     * 表中当前记录数（含已逻辑删除的）。
     *
     * @return 记录数
     */
    public int size() {
        return rows.size();
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
            case "insert" -> insert((FreePeriod) args[0]);
            case "selectById" -> selectById((Long) args[0]);
            case "selectPageByStore" -> selectPageByStore(args);
            case "countOverlapping" -> countOverlapping(args);
            case "selectCoveringAt" -> selectCoveringAt(args);
            case "selectOverlapping" -> selectOverlapping(args);
            case "selectUpcoming" -> selectUpcoming(args);
            case "updatePeriod" -> updatePeriod(args);
            case "deleteById" -> deleteById((Long) args[0]);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeFreePeriodMapper 中补上对应实现");
        };
    }

    // ==================================================================
    // 各方法的模拟实现
    // ==================================================================

    /**
     * 插入。模拟自增主键与逻辑删除标记的默认值。
     *
     * @param period 待插入的记录
     * @return 受影响行数，恒为 1
     */
    private int insert(FreePeriod period) {
        period.setId(allocateId());
        if (period.getDeleted() == null) {
            period.setDeleted(0);
        }
        rows.put(period.getId(), period);
        return 1;
    }

    /**
     * 按 ID 查询，过滤已逻辑删除的行。
     *
     * @param id 记录 ID
     * @return 记录；不存在或已删除时返回 null
     */
    private FreePeriod selectById(Long id) {
        FreePeriod period = rows.get(id);
        return isAlive(period) ? period : null;
    }

    /**
     * 分页查询某门店的活动，按开始时间倒序。
     *
     * @param args 依次为分页对象、门店 ID
     * @return 分页结果（直接写入传入的 page 对象）
     */
    @SuppressWarnings("unchecked")
    private IPage<FreePeriod> selectPageByStore(Object[] args) {
        IPage<FreePeriod> page = (IPage<FreePeriod>) args[0];
        Long storeId = (Long) args[1];

        List<FreePeriod> all = rows.values().stream()
                .filter(this::isAlive)
                .filter(p -> storeId.equals(p.getStoreId()))
                .sorted(Comparator.comparing(FreePeriod::getStartAt).reversed())
                .toList();

        page.setTotal(all.size());

        int from = (int) Math.min((page.getCurrent() - 1) * page.getSize(), all.size());
        int to = (int) Math.min(from + page.getSize(), all.size());
        page.setRecords(all.subList(from, to));
        return page;
    }

    /**
     * 统计与给定时段有交集的活动数。
     *
     * <p>比较符与真实 SQL 逐字一致：半开区间重叠。
     *
     * @param args 依次为门店 ID、startAt、endAt、excludeId
     * @return 重叠的活动数
     */
    private int countOverlapping(Object[] args) {
        Long storeId = (Long) args[0];
        LocalDateTime startAt = (LocalDateTime) args[1];
        LocalDateTime endAt = (LocalDateTime) args[2];
        Long excludeId = (Long) args[3];

        return (int) rows.values().stream()
                .filter(this::isAlive)
                .filter(p -> storeId.equals(p.getStoreId()))
                .filter(p -> excludeId == null || !excludeId.equals(p.getId()))
                .filter(p -> p.getStartAt().isBefore(endAt) && p.getEndAt().isAfter(startAt))
                .count();
    }

    /**
     * 查询覆盖给定时刻的活动。
     *
     * @param args 依次为门店 ID、时刻
     * @return 覆盖该时刻的活动；没有则返回 null
     */
    private FreePeriod selectCoveringAt(Object[] args) {
        Long storeId = (Long) args[0];
        LocalDateTime time = (LocalDateTime) args[1];

        return rows.values().stream()
                .filter(this::isAlive)
                .filter(p -> storeId.equals(p.getStoreId()))
                .filter(p -> !p.getStartAt().isAfter(time) && p.getEndAt().isAfter(time))
                .max(Comparator.comparing(FreePeriod::getStartAt))
                .orElse(null);
    }

    /**
     * 查询与给定区间有交集的活动，按开始时间升序。
     *
     * @param args 依次为门店 ID、from、to
     * @return 有交集的活动
     */
    private List<FreePeriod> selectOverlapping(Object[] args) {
        Long storeId = (Long) args[0];
        LocalDateTime from = (LocalDateTime) args[1];
        LocalDateTime to = (LocalDateTime) args[2];

        return rows.values().stream()
                .filter(this::isAlive)
                .filter(p -> storeId.equals(p.getStoreId()))
                .filter(p -> p.getStartAt().isBefore(to) && p.getEndAt().isAfter(from))
                .sorted(Comparator.comparing(FreePeriod::getStartAt))
                .toList();
    }

    /**
     * 查询尚未结束的活动，按开始时间升序，最多 {@code limit} 条。
     *
     * @param args 依次为门店 ID、起始时刻、条数上限
     * @return 尚未结束的活动
     */
    private List<FreePeriod> selectUpcoming(Object[] args) {
        Long storeId = (Long) args[0];
        LocalDateTime from = (LocalDateTime) args[1];
        int limit = (Integer) args[2];

        return rows.values().stream()
                .filter(this::isAlive)
                .filter(p -> storeId.equals(p.getStoreId()))
                .filter(p -> p.getEndAt().isAfter(from))
                .sorted(Comparator.comparing(FreePeriod::getStartAt))
                .limit(limit)
                .toList();
    }

    /**
     * 更新活动的时段与名称。
     *
     * <p><b>它是显式 SQL 而不是 {@code updateById}</b>：后者的「跳过 null 字段」
     * 语义会让「把活动名称清空」这件事做不到，且不报任何错
     * （接口 200、刷新后旧名称还在）—— 项目里已有多处踩过同一个坑。
     *
     * @param args 依次为 id、startAt、endAt、reason
     * @return 受影响行数；0 表示记录不存在或已删除
     */
    private int updatePeriod(Object[] args) {
        FreePeriod period = selectById((Long) args[0]);
        if (period == null) {
            return 0;
        }
        period.setStartAt((LocalDateTime) args[1]);
        period.setEndAt((LocalDateTime) args[2]);
        period.setReason((String) args[3]);
        return 1;
    }

    /**
     * 逻辑删除。模拟真实库里 {@code DELETE} 被改写成 {@code UPDATE ... SET deleted = 1}。
     *
     * @param id 记录 ID
     * @return 受影响行数；0 表示记录不存在或已删除
     */
    private int deleteById(Long id) {
        FreePeriod period = selectById(id);
        if (period == null) {
            return 0;
        }
        period.setDeleted(1);
        return 1;
    }

    /**
     * 判断一行是否未被逻辑删除。
     *
     * @param period 记录，可为 null
     * @return 有效返回 true
     */
    private boolean isAlive(FreePeriod period) {
        return period != null && (period.getDeleted() == null || period.getDeleted() == 0);
    }
}
