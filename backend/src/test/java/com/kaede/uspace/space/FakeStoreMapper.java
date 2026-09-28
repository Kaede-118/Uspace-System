package com.kaede.uspace.space;

import com.kaede.uspace.space.entity.Store;
import com.kaede.uspace.space.mapper.StoreMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内存版的 {@link StoreMapper}，让模块 3 的单元测试不依赖数据库。
 *
 * <p>实现方式与 {@code user/FakeSysUserMapper} 一致：动态代理 + 按方法名分发，
 * 只处理被真正调用的方法，其余直接抛异常并提示补哪个 ——
 * Service 一旦用了预期之外的方法，测试会立刻告诉你。
 *
 * <p><b>模拟的是数据库行为的语义而非实现</b>：逻辑删除过滤、
 * 唯一约束对应的查重。这些是 Service 层判断的依据，假实现必须与真库一致，
 * 否则单测通过的结论就不可信。SQL 是否正确、列名映射是否对得上，
 * 由集成测试负责。
 */
public class FakeStoreMapper implements InvocationHandler {

    /** 模拟数据表。用 LinkedHashMap 保持插入顺序，便于调试时观察 */
    private final Map<Long, Store> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return StoreMapper 的假实现
     */
    public StoreMapper asMapper() {
        return (StoreMapper) Proxy.newProxyInstance(
                StoreMapper.class.getClassLoader(),
                new Class<?>[]{StoreMapper.class},
                this);
    }

    /**
     * 预置一条门店数据。
     *
     * @param store 门店，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public Store seed(Store store) {
        if (store.getId() == null) {
            store.setId(allocateId());
        }
        if (store.getDeleted() == null) {
            store.setDeleted(0);
        }
        rows.put(store.getId(), store);
        return store;
    }

    /**
     * 分配一个未被占用的自增主键。
     *
     * <p><b>必须跳过已占用的 ID</b>：测试里常用显式 ID（如固定为 1）预置数据，
     * 若游标不跳过它，后续不带 ID 的 {@code seed} 会分配到同一个 ID
     * 并把先前的记录悄悄覆盖掉 —— 表现为「明明预置了两条数据却只剩一条」，
     * 而且不会报任何错，排查起来极其费劲。
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
     * @param id 门店 ID
     * @return 门店；不存在时返回 null
     */
    public Store get(Long id) {
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
            case "insert" -> insert((Store) args[0]);
            case "selectById" -> selectById((Long) args[0]);
            case "selectCurrent" -> selectCurrent();
            case "selectCurrentId" -> selectCurrentId();
            case "countByName" -> countByName((String) args[0], (Long) args[1]);
            case "updateStore" -> updateStore(args);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeStoreMapper 中补上对应实现");
        };
    }

    // ==================================================================
    // 各方法的模拟实现
    // ==================================================================

    /**
     * 插入。模拟自增主键与逻辑删除标记的默认值。
     *
     * @param store 待插入的门店
     * @return 受影响行数，恒为 1
     */
    private int insert(Store store) {
        store.setId(allocateId());
        if (store.getDeleted() == null) {
            store.setDeleted(0);
        }
        rows.put(store.getId(), store);
        return 1;
    }

    /**
     * 按 ID 查询，自动过滤已逻辑删除的行 —— 真实库里全局配置就是这么做的。
     *
     * @param id 门店 ID
     * @return 门店；不存在或已删除时返回 null
     */
    private Store selectById(Long id) {
        Store store = rows.get(id);
        return isAlive(store) ? store : null;
    }

    /**
     * 取第一条未删除的门店，模拟 {@code ORDER BY id LIMIT 1}。
     *
     * @return 当前门店；表为空时返回 null
     */
    private Store selectCurrent() {
        return rows.values().stream()
                .filter(this::isAlive)
                .min(Comparator.comparing(Store::getId))
                .orElse(null);
    }

    /**
     * 取第一条未删除门店的 ID。
     *
     * @return 当前门店 ID；表为空时返回 null
     */
    private Long selectCurrentId() {
        Store current = selectCurrent();
        return current == null ? null : current.getId();
    }

    /**
     * 统计同名门店数，并排除指定 ID（改回原名时不该算冲突）。
     *
     * @param name      名称
     * @param excludeId 要排除的 ID，可为 null
     * @return 同名且未删除的门店数
     */
    private int countByName(String name, Long excludeId) {
        return (int) rows.values().stream()
                .filter(this::isAlive)
                .filter(s -> s.getName().equals(name))
                .filter(s -> excludeId == null || !excludeId.equals(s.getId()))
                .count();
    }

    /**
     * 更新门店信息。传 null 即清空该字段 —— 与真实 SQL 的全量替换语义一致。
     *
     * @param args 依次为 id、name、address、description
     * @return 受影响行数；0 表示门店不存在或已删除
     */
    private int updateStore(Object[] args) {
        Store store = selectById((Long) args[0]);
        if (store == null) {
            return 0;
        }
        store.setName((String) args[1]);
        store.setAddress((String) args[2]);
        store.setDescription((String) args[3]);
        return 1;
    }

    /**
     * 判断一行是否未被逻辑删除。
     *
     * @param store 门店，可为 null
     * @return 有效返回 true
     */
    private boolean isAlive(Store store) {
        return store != null && (store.getDeleted() == null || store.getDeleted() == 0);
    }
}
