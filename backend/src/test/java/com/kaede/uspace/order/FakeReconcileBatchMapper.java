package com.kaede.uspace.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.order.entity.ReconcileBatch;
import com.kaede.uspace.order.mapper.ReconcileBatchMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版的 {@link ReconcileBatchMapper}，让对账的单元测试不依赖数据库。
 *
 * <p>实现方式与 {@code FakePaymentProofMapper} 一致：动态代理 + 按方法名分发，
 * 只处理被真正调用的方法，其余直接抛异常并提示补哪个。
 *
 * <p><b>最要紧的一处是 {@code insert} 必须回填主键</b> —— 它模拟的是
 * MyBatis-Plus 自带 {@code insert} 的 {@code useGeneratedKeys}。
 * 假实现不回填的话，批次 ID 恒为 null，后面「把凭证认领到这一批」整条链断掉，
 * 而报错的地方离原因很远。
 *
 * <p>SQL 是否正确、{@code DECIMAL} 精度、真库上的排序，由集成测试负责。
 */
public class FakeReconcileBatchMapper implements InvocationHandler {

    /** 模拟数据表，保持插入顺序便于调试时观察 */
    private final Map<Long, ReconcileBatch> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return ReconcileBatchMapper 的假实现
     */
    public ReconcileBatchMapper asMapper() {
        return (ReconcileBatchMapper) Proxy.newProxyInstance(
                ReconcileBatchMapper.class.getClassLoader(),
                new Class<?>[]{ReconcileBatchMapper.class},
                this);
    }

    /**
     * 按 ID 取出表中的当前状态，供测试断言。
     *
     * @param id 批次 ID
     * @return 批次；不存在时返回 null
     */
    public ReconcileBatch get(Long id) {
        return rows.get(id);
    }

    /** 取全部批次，按 ID 升序 */
    public List<ReconcileBatch> all() {
        return new ArrayList<>(rows.values());
    }

    /** 取当前表里的记录条数 */
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
            case "insert" -> insert((ReconcileBatch) args[0]);
            case "selectById" -> rows.get((Long) args[0]);
            case "selectPageForAdmin" -> selectPageForAdmin(args);
            case "updateMatchedCount" -> updateMatchedCount((Long) args[0], (Integer) args[1]);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeReconcileBatchMapper 中补上对应实现");
        };
    }

    /**
     * 插入，并<b>回填主键</b>。
     *
     * <p>主键按「未占用」分配，而不是无脑自增：测试里可能用显式 ID 预置数据，
     * 游标不跳过它的话会悄悄覆盖掉先前的记录（与 {@code FakePaymentProofMapper} 同一套做法）。
     *
     * @param batch 批次
     * @return 受影响行数，恒为 1
     */
    private int insert(ReconcileBatch batch) {
        if (batch.getId() == null) {
            while (rows.containsKey(nextId)) {
                nextId++;
            }
            batch.setId(nextId++);
        }
        if (batch.getCreatedAt() == null) {
            batch.setCreatedAt(LocalDateTime.now());
        }
        rows.put(batch.getId(), batch);
        return 1;
    }

    /**
     * 后台分页查询：按门店过滤，新的在前。
     *
     * @param args 反射参数：分页对象与门店 ID
     * @return 填好 total 与 records 的分页对象
     */
    private IPage<ReconcileBatch> selectPageForAdmin(Object[] args) {
        IPage<ReconcileBatch> page = asPage(args[0]);
        Long storeId = (Long) args[1];

        List<ReconcileBatch> filtered = rows.values().stream()
                .filter(batch -> storeId.equals(batch.getStoreId()))
                .sorted(Comparator.comparing(ReconcileBatch::getCreatedAt)
                        .thenComparing(ReconcileBatch::getId)
                        .reversed())
                .toList();
        return fillPage(page, filtered);
    }

    /**
     * 修正匹配笔数，只改那一列。
     *
     * @param id           批次 ID
     * @param matchedCount 实际笔数
     * @return 受影响行数；批次不存在时为 0
     */
    private int updateMatchedCount(Long id, Integer matchedCount) {
        ReconcileBatch batch = rows.get(id);
        if (batch == null) {
            return 0;
        }
        batch.setMatchedCount(matchedCount);
        return 1;
    }

    /**
     * 把反射拿到的分页参数转成有类型的对象。
     *
     * @param arg 第一个参数
     * @return 分页对象
     */
    @SuppressWarnings("unchecked")
    private static IPage<ReconcileBatch> asPage(Object arg) {
        return (IPage<ReconcileBatch>) arg;
    }

    /**
     * 截取一页，模拟 MyBatis-Plus 分页插件的行为。
     *
     * @param page 分页参数
     * @param all  过滤排序后的全量数据
     * @return 填好 total 与 records 的分页对象
     */
    private static IPage<ReconcileBatch> fillPage(IPage<ReconcileBatch> page, List<ReconcileBatch> all) {
        page.setTotal(all.size());
        int offset = (int) Math.max(Math.min((page.getCurrent() - 1) * page.getSize(), all.size()), 0);
        int end = (int) Math.min(offset + page.getSize(), all.size());
        page.setRecords(new ArrayList<>(all.subList(offset, end)));
        return page;
    }
}
