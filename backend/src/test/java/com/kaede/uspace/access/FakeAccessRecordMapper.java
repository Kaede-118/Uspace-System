package com.kaede.uspace.access;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.access.entity.AccessRecord;
import com.kaede.uspace.access.mapper.AccessRecordMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版的 {@link AccessRecordMapper}，让模块 6 的单元测试不依赖数据库。
 *
 * <p>实现方式与 {@code space/FakeBookingMapper} 一致：动态代理 + 按方法名分发，
 * 只处理被真正调用的方法，其余直接抛异常并提示补哪个。
 *
 * <p><b>本表没有逻辑删除</b>（{@code biz_access_record} 是 append-only 的事件日志），
 * 所以这里没有 {@code isAlive} 那套过滤 —— 与其它假 Mapper 的区别就在这一处。
 *
 * <p><b>半开区间的比较符必须与 SQL 逐字一致</b>（{@code >= from && < to}）。
 * 差一个字符，边界上的用例（恰好落在终点的那条）就会得出相反结论，
 * 而单测却照样通过 —— 那比没有测试更危险。
 */
public class FakeAccessRecordMapper implements InvocationHandler {

    /** 模拟数据表。用 LinkedHashMap 保持插入顺序，便于调试时观察 */
    private final Map<Long, AccessRecord> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return AccessRecordMapper 的假实现
     */
    public AccessRecordMapper asMapper() {
        return (AccessRecordMapper) Proxy.newProxyInstance(
                AccessRecordMapper.class.getClassLoader(),
                new Class<?>[]{AccessRecordMapper.class},
                this);
    }

    /**
     * 预置一条开门记录。
     *
     * @param record 记录，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public AccessRecord seed(AccessRecord record) {
        if (record.getId() == null) {
            record.setId(allocateId());
        }
        rows.put(record.getId(), record);
        return record;
    }

    /**
     * 按 ID 取出表中的当前状态，供测试断言。
     *
     * @param id 记录 ID
     * @return 记录；不存在时返回 null
     */
    public AccessRecord get(Long id) {
        return rows.get(id);
    }

    /**
     * 取当前表里的记录条数。
     *
     * <p>断言「失败时库里一条都没多」用它比逐个查 ID 更直接。
     *
     * @return 记录条数
     */
    public int size() {
        return rows.size();
    }

    /**
     * 分配一个未被占用的自增主键。
     *
     * <p><b>必须跳过已占用的 ID</b>：测试里常用显式 ID 预置数据，
     * 若游标不跳过它，后续不带 ID 的 {@code seed} 会分配到同一个 ID
     * 并把先前的记录悄悄覆盖掉 —— 表现为「明明预置了两条却只剩一条」，
     * 而且不会报任何错。
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
            case "insert" -> insert((AccessRecord) args[0]);
            case "selectPageByStore" -> selectPageByStore(args);
            case "selectPageByUser" -> selectPageByUser(args);
            case "selectInWindow" -> selectInWindow(args);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeAccessRecordMapper 中补上对应实现");
        };
    }

    // ==================================================================
    // 各方法的模拟实现
    // ==================================================================

    /**
     * 插入。模拟自增主键与审计字段填充。
     *
     * @param record 待插入的记录
     * @return 受影响行数，恒为 1
     */
    private int insert(AccessRecord record) {
        record.setId(allocateId());
        if (record.getCreatedAt() == null) {
            // 真库里由 AuditMetaObjectHandler 填充；这里模拟同一行为，
            // 让「落库后 createdAt 有值」这条断言在单测里也成立
            record.setCreatedAt(LocalDateTime.now());
        }
        rows.put(record.getId(), record);
        return 1;
    }

    /**
     * 分页查询某门店的记录，可选择性按用户与时间过滤。
     *
     * @param args 依次为分页对象、storeId、userId、from、to
     * @return 分页结果（直接写入传入的 page 对象）
     */
    private IPage<AccessRecord> selectPageByStore(Object[] args) {
        IPage<AccessRecord> page = asPage(args[0]);
        Long storeId = (Long) args[1];
        Long userId = (Long) args[2];
        LocalDateTime from = (LocalDateTime) args[3];
        LocalDateTime to = (LocalDateTime) args[4];

        List<AccessRecord> all = rows.values().stream()
                .filter(r -> storeId.equals(r.getStoreId()))
                .filter(r -> userId == null || userId.equals(r.getUserId()))
                .filter(r -> from == null || !r.getOpenTime().isBefore(from))
                .filter(r -> to == null || r.getOpenTime().isBefore(to))
                .sorted(descending())
                .toList();

        return fillPage(page, all);
    }

    /**
     * 分页查询某用户的记录。
     *
     * <p>{@code userId} 是必填的 —— 与真实 SQL 一致，这个方法不存在「传空查全表」的语义。
     *
     * @param args 依次为分页对象、userId
     * @return 分页结果
     */
    private IPage<AccessRecord> selectPageByUser(Object[] args) {
        IPage<AccessRecord> page = asPage(args[0]);
        Long userId = (Long) args[1];

        List<AccessRecord> all = rows.values().stream()
                .filter(r -> userId.equals(r.getUserId()))
                .sorted(descending())
                .toList();

        return fillPage(page, all);
    }

    /**
     * 查询某把锁在窗口内的记录，按开门时刻升序。
     *
     * <p><b>区间是半开 {@code [from, to)}</b> —— 与 {@code AccessRecordMapper#selectInWindow}
     * 的 SQL 比较符逐字对应。改动此处前先改那边，反之亦然。
     *
     * @param args 依次为 lockId、from、to
     * @return 窗口内的记录
     */
    private List<AccessRecord> selectInWindow(Object[] args) {
        Long lockId = (Long) args[0];
        LocalDateTime from = (LocalDateTime) args[1];
        LocalDateTime to = (LocalDateTime) args[2];

        return rows.values().stream()
                .filter(r -> lockId.equals(r.getLockId()))
                .filter(r -> !r.getOpenTime().isBefore(from) && r.getOpenTime().isBefore(to))
                .sorted(Comparator.comparing(AccessRecord::getOpenTime)
                        .thenComparing(AccessRecord::getId))
                .toList();
    }

    // ==================================================================
    // 分页工具
    // ==================================================================

    /**
     * 把参数里的分页对象取出来。
     *
     * @param arg 方法第一个参数
     * @return 分页对象
     */
    @SuppressWarnings("unchecked")
    private static IPage<AccessRecord> asPage(Object arg) {
        return (IPage<AccessRecord>) arg;
    }

    /**
     * 列表排序：开门时刻倒序，同一秒再按 ID 倒序。
     *
     * <p>与 SQL 的 {@code ORDER BY open_time DESC, id DESC} 一致 ——
     * 第二排序键不能省，否则同一秒的多条记录在翻页边界会抖动。
     *
     * @return 比较器
     */
    private static Comparator<AccessRecord> descending() {
        return Comparator.comparing(AccessRecord::getOpenTime).reversed()
                .thenComparing(Comparator.comparing(AccessRecord::getId).reversed());
    }

    /**
     * 把全量结果按分页参数切一段写回分页对象。
     *
     * @param page 分页对象
     * @param all  满足条件的全部记录
     * @return 同一个分页对象
     */
    private static IPage<AccessRecord> fillPage(IPage<AccessRecord> page, List<AccessRecord> all) {
        page.setTotal(all.size());

        int offset = (int) Math.min((page.getCurrent() - 1) * page.getSize(), all.size());
        offset = Math.max(offset, 0);
        int end = (int) Math.min(offset + page.getSize(), all.size());
        page.setRecords(all.subList(offset, end));
        return page;
    }
}
