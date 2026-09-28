package com.kaede.uspace.space;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.space.entity.Closure;
import com.kaede.uspace.space.mapper.ClosureMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版的 {@link ClosureMapper}，让模块 3 的单元测试不依赖数据库。
 *
 * <p><b>区间判断的口径必须与真实 SQL 完全一致</b>：闭区间
 * （{@code start_at <= 另一端 end AND end_at >= 另一端 start}）。
 * 差别一个字，边界上的用例（恰好卡在停业开始那一刻）就会得出相反的结论，
 * 而单测却照样通过 —— 那比没有测试更危险。因此这里逐字照抄 SQL 的比较符。
 *
 * <p>其余约定同 {@code FakeStoreMapper}：动态代理、按方法名分发、
 * 模拟逻辑删除过滤。
 */
public class FakeClosureMapper implements InvocationHandler {

    /** 模拟数据表 */
    private final Map<Long, Closure> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return ClosureMapper 的假实现
     */
    public ClosureMapper asMapper() {
        return (ClosureMapper) Proxy.newProxyInstance(
                ClosureMapper.class.getClassLoader(),
                new Class<?>[]{ClosureMapper.class},
                this);
    }

    /**
     * 预置一条停业记录。
     *
     * @param closure 记录，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public Closure seed(Closure closure) {
        if (closure.getId() == null) {
            closure.setId(allocateId());
        }
        if (closure.getDeleted() == null) {
            closure.setDeleted(0);
        }
        rows.put(closure.getId(), closure);
        return closure;
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
     * @param id 记录 ID
     * @return 记录；不存在时返回 null
     */
    public Closure get(Long id) {
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
            case "insert" -> insert((Closure) args[0]);
            case "selectById" -> selectById((Long) args[0]);
            case "selectPageByStore" -> selectPageByStore(args);
            case "countOverlapping" -> countOverlapping(args);
            case "selectCoveringAt" -> selectCoveringAt(args);
            case "updateClosure" -> updateClosure(args);
            case "deleteById" -> deleteById((Long) args[0]);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeClosureMapper 中补上对应实现");
        };
    }

    // ==================================================================
    // 各方法的模拟实现
    // ==================================================================

    /**
     * 插入。模拟自增主键与逻辑删除标记的默认值。
     *
     * @param closure 待插入的记录
     * @return 受影响行数，恒为 1
     */
    private int insert(Closure closure) {
        closure.setId(allocateId());
        if (closure.getDeleted() == null) {
            closure.setDeleted(0);
        }
        rows.put(closure.getId(), closure);
        return 1;
    }

    /**
     * 按 ID 查询，过滤已逻辑删除的行。
     *
     * @param id 记录 ID
     * @return 记录；不存在或已删除时返回 null
     */
    private Closure selectById(Long id) {
        Closure closure = rows.get(id);
        return isAlive(closure) ? closure : null;
    }

    /**
     * 分页查询某门店的停业记录，按开始时间倒序。
     *
     * @param args 依次为分页对象、门店 ID
     * @return 分页结果（直接写入传入的 page 对象）
     */
    @SuppressWarnings("unchecked")
    private IPage<Closure> selectPageByStore(Object[] args) {
        IPage<Closure> page = (IPage<Closure>) args[0];
        Long storeId = (Long) args[1];

        List<Closure> all = rows.values().stream()
                .filter(this::isAlive)
                .filter(c -> storeId.equals(c.getStoreId()))
                .sorted(Comparator.comparing(Closure::getStartAt).reversed())
                .toList();

        page.setTotal(all.size());

        int from = (int) Math.min((page.getCurrent() - 1) * page.getSize(), all.size());
        int to = (int) Math.min(from + page.getSize(), all.size());
        page.setRecords(all.subList(from, to));
        return page;
    }

    /**
     * 统计与给定时段重叠的记录数。
     *
     * <p>比较符与真实 SQL 逐字一致：闭区间重叠。
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
                .filter(c -> storeId.equals(c.getStoreId()))
                .filter(c -> excludeId == null || !excludeId.equals(c.getId()))
                .filter(c -> c.getStartAt().isBefore(endAt) && c.getEndAt().isAfter(startAt))
                .count();
    }

    /**
     * 查询覆盖给定时刻的停业记录。
     *
     * @param args 依次为门店 ID、时刻
     * @return 覆盖该时刻的记录；没有则返回 null
     */
    private Closure selectCoveringAt(Object[] args) {
        Long storeId = (Long) args[0];
        LocalDateTime time = (LocalDateTime) args[1];

        return rows.values().stream()
                .filter(this::isAlive)
                .filter(c -> storeId.equals(c.getStoreId()))
                .filter(c -> !c.getStartAt().isAfter(time) && c.getEndAt().isAfter(time))
                .max(Comparator.comparing(Closure::getStartAt))
                .orElse(null);
    }

    /**
     * 更新停业记录。
     *
     * @param args 依次为 id、startAt、endAt、reason
     * @return 受影响行数；0 表示记录不存在或已删除
     */
    private int updateClosure(Object[] args) {
        Closure closure = selectById((Long) args[0]);
        if (closure == null) {
            return 0;
        }
        closure.setStartAt((LocalDateTime) args[1]);
        closure.setEndAt((LocalDateTime) args[2]);
        closure.setReason((String) args[3]);
        return 1;
    }

    /**
     * 逻辑删除。模拟真实库里 {@code DELETE} 被改写成 {@code UPDATE ... SET deleted = 1}。
     *
     * @param id 记录 ID
     * @return 受影响行数；0 表示记录不存在或已删除
     */
    private int deleteById(Long id) {
        Closure closure = selectById(id);
        if (closure == null) {
            return 0;
        }
        closure.setDeleted(1);
        return 1;
    }

    /**
     * 判断一行是否未被逻辑删除。
     *
     * @param closure 记录，可为 null
     * @return 有效返回 true
     */
    private boolean isAlive(Closure closure) {
        return closure != null && (closure.getDeleted() == null || closure.getDeleted() == 0);
    }
}
