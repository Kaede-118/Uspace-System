package com.kaede.uspace.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.order.entity.ReconcileDiff;
import com.kaede.uspace.order.mapper.ReconcileBatchDiffCount;
import com.kaede.uspace.order.mapper.ReconcileDiffMapper;
import com.kaede.uspace.order.mapper.ReconcileDiffTypeCount;
import com.kaede.uspace.order.reconcile.ReconcileDiffType;

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
 * 内存版的 {@link ReconcileDiffMapper}，让对账的单元测试不依赖数据库。
 *
 * <p>实现方式与 {@code FakePaymentProofMapper} 一致：动态代理 + 按方法名分发，
 * 未实现的方法直接抛异常并提示补哪个。
 *
 * <p><b>三处必须与真 SQL 逐字一致</b>，否则单测会给出与真库相反的结论：
 * <ol>
 *   <li><b>{@code handle} 的状态守卫</b> —— {@code WHERE handled = 0}
 *       是并发处理的唯一防线</li>
 *   <li><b>列表排序</b> —— 待处理优先、同状态按类型优先级、最后按 ID 兜底。
 *       少了它，「最紧急的差异排最前」这条设计在单测里根本测不到</li>
 *   <li><b>两条「取未处理差异」的筛选</b> —— 只看 {@code handled = 0}，
 *       给差异去重用。写成「全取」的话，去重逻辑测起来是全绿、
 *       而线上会把处理过的老差异当成新的一直不重复报</li>
 * </ol>
 */
public class FakeReconcileDiffMapper implements InvocationHandler {

    /** 模拟数据表，保持插入顺序便于调试时观察 */
    private final Map<Long, ReconcileDiff> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return ReconcileDiffMapper 的假实现
     */
    public ReconcileDiffMapper asMapper() {
        return (ReconcileDiffMapper) Proxy.newProxyInstance(
                ReconcileDiffMapper.class.getClassLoader(),
                new Class<?>[]{ReconcileDiffMapper.class},
                this);
    }

    /**
     * 预置一条差异。
     *
     * @param diff 差异，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public ReconcileDiff seed(ReconcileDiff diff) {
        insert(diff);
        return diff;
    }

    /**
     * 按 ID 取出表中的当前状态，供测试断言。
     *
     * @param id 差异 ID
     * @return 差异；不存在时返回 null
     */
    public ReconcileDiff get(Long id) {
        return rows.get(id);
    }

    /** 取全部差异，按 ID 升序 */
    public List<ReconcileDiff> all() {
        return new ArrayList<>(rows.values());
    }

    /**
     * 取某个批次的全部差异，供测试断言。
     *
     * @param batchId 批次 ID
     * @return 该批次的差异
     */
    public List<ReconcileDiff> findByBatch(Long batchId) {
        return rows.values().stream().filter(d -> batchId.equals(d.getBatchId())).toList();
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
            case "insert" -> insert((ReconcileDiff) args[0]);
            case "selectById" -> rows.get((Long) args[0]);
            case "selectPageForAdmin" -> selectPageForAdmin(args);
            case "handle" -> handle((Long) args[0], (Long) args[1], (String) args[2]);
            case "countByBatchGroupByType" -> countByBatchGroupByType((Long) args[0]);
            case "countUnhandledByBatchIds" -> countUnhandledByBatchIds(asLongList(args[0]));
            case "selectUnhandledByProofIds" -> selectUnhandledByProofIds(asLongList(args[0]));
            case "selectUnhandledByPaymentNos" -> selectUnhandledByPaymentNos(asStringList(args[0]));
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeReconcileDiffMapper 中补上对应实现");
        };
    }

    /**
     * 插入，并<b>回填主键</b>（模拟 MyBatis-Plus 自带 insert 的 useGeneratedKeys）。
     *
     * @param diff 差异
     * @return 受影响行数，恒为 1
     */
    private int insert(ReconcileDiff diff) {
        if (diff.getId() == null) {
            while (rows.containsKey(nextId)) {
                nextId++;
            }
            diff.setId(nextId++);
        }
        if (diff.getCreatedAt() == null) {
            diff.setCreatedAt(LocalDateTime.now());
        }
        if (diff.getHandled() == null) {
            diff.setHandled(0);
        }
        rows.put(diff.getId(), diff);
        return 1;
    }

    /**
     * 后台分页查询某个批次的差异。
     *
     * <p>排序与真 SQL 一致：{@code handled ASC}, 类型优先级, {@code id ASC}。
     * 类型优先级用枚举的 {@code ordinal()} —— 真 SQL 里那段
     * {@code FIELD(...)} 字面量与声明顺序由 {@code ReconcileDiffTypeTests} 钉着，
     * 所以两者等价。
     *
     * @param args 反射参数：分页对象、批次 ID、类型筛选、处理状态筛选
     * @return 填好 total 与 records 的分页对象
     */
    private IPage<ReconcileDiff> selectPageForAdmin(Object[] args) {
        IPage<ReconcileDiff> page = asPage(args[0]);
        Long batchId = (Long) args[1];
        String diffType = (String) args[2];
        Integer handled = (Integer) args[3];

        List<ReconcileDiff> filtered = rows.values().stream()
                .filter(diff -> batchId.equals(diff.getBatchId()))
                .filter(diff -> diffType == null || diffType.isEmpty() || diffType.equals(diff.getDiffType()))
                .filter(diff -> handled == null || handled.equals(diff.getHandled()))
                .sorted(Comparator.comparing(ReconcileDiff::getHandled)
                        .thenComparing(diff -> typeOrder(diff.getDiffType()))
                        .thenComparing(ReconcileDiff::getId))
                .toList();
        return fillPage(page, filtered);
    }

    /**
     * 类型优先级。
     *
     * <p><b>认不出的类型返回 0（排最前）</b>，这是刻意的：MySQL 的
     * {@code FIELD(x, ...)} 在 x 不在列表里时正是返回 0。
     * 假实现返回一个「排最后」的值的话，两边行为不一致 ——
     * 而真库里本来不该出现认不出的类型，所以这种不一致不会被任何用例发现。
     *
     * @param diffType 类型名
     * @return 优先级，越小越靠前
     */
    private static int typeOrder(String diffType) {
        for (ReconcileDiffType type : ReconcileDiffType.values()) {
            if (type.name().equals(diffType)) {
                return type.ordinal();
            }
        }
        return 0;
    }

    /**
     * 标记已处理，带状态守卫。
     *
     * @param id      差异 ID
     * @param adminId 处理人
     * @param note    备注
     * @return 受影响行数；已被处理过或不存在时为 0
     */
    private int handle(Long id, Long adminId, String note) {
        ReconcileDiff diff = rows.get(id);
        if (diff == null || !Integer.valueOf(0).equals(diff.getHandled())) {
            return 0;
        }
        diff.setHandled(1);
        diff.setHandleNote(note);
        diff.setHandledBy(adminId);
        diff.setHandledAt(LocalDateTime.now());
        return 1;
    }

    /**
     * 按批次分组数差异条数。
     *
     * @param batchId 批次 ID
     * @return 各类型的条数；没有差异的类型不出现
     */
    private List<ReconcileDiffTypeCount> countByBatchGroupByType(Long batchId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ReconcileDiff diff : rows.values()) {
            if (batchId.equals(diff.getBatchId())) {
                counts.merge(diff.getDiffType(), 1, Integer::sum);
            }
        }
        List<ReconcileDiffTypeCount> result = new ArrayList<>();
        counts.forEach((type, cnt) -> {
            ReconcileDiffTypeCount row = new ReconcileDiffTypeCount();
            row.setDiffType(type);
            row.setCnt(cnt);
            result.add(row);
        });
        return result;
    }

    /**
     * 按批次分组数未处理的差异条数。
     *
     * @param batchIds 批次 ID 列表
     * @return 各批次的未处理条数；全处理完的批次不出现
     */
    private List<ReconcileBatchDiffCount> countUnhandledByBatchIds(List<Long> batchIds) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (ReconcileDiff diff : rows.values()) {
            if (Integer.valueOf(0).equals(diff.getHandled()) && batchIds.contains(diff.getBatchId())) {
                counts.merge(diff.getBatchId(), 1, Integer::sum);
            }
        }
        List<ReconcileBatchDiffCount> result = new ArrayList<>();
        counts.forEach((batchId, cnt) -> {
            ReconcileBatchDiffCount row = new ReconcileBatchDiffCount();
            row.setBatchId(batchId);
            row.setCnt(cnt);
            result.add(row);
        });
        return result;
    }

    /**
     * 取这些凭证身上未处理的差异（给去重用）。
     *
     * @param proofIds 凭证 ID 列表
     * @return 未处理的差异，只带判重需要的列
     */
    private List<ReconcileDiff> selectUnhandledByProofIds(List<Long> proofIds) {
        return rows.values().stream()
                .filter(diff -> Integer.valueOf(0).equals(diff.getHandled()))
                .filter(diff -> diff.getProofId() != null && proofIds.contains(diff.getProofId()))
                .toList();
    }

    /**
     * 取这些流水号身上未处理的差异（给去重用）。
     *
     * @param paymentNos 归一化后的流水号列表
     * @return 未处理的差异，只带判重需要的列
     */
    private List<ReconcileDiff> selectUnhandledByPaymentNos(List<String> paymentNos) {
        return rows.values().stream()
                .filter(diff -> Integer.valueOf(0).equals(diff.getHandled()))
                .filter(diff -> diff.getPaymentNo() != null && paymentNos.contains(diff.getPaymentNo()))
                .toList();
    }

    /**
     * 把反射拿到的参数转成 ID 列表。
     *
     * @param arg 参数
     * @return ID 列表
     */
    @SuppressWarnings("unchecked")
    private static List<Long> asLongList(Object arg) {
        return (List<Long>) arg;
    }

    /**
     * 把反射拿到的参数转成字符串列表。
     *
     * @param arg 参数
     * @return 字符串列表
     */
    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object arg) {
        return (List<String>) arg;
    }

    /**
     * 把反射拿到的分页参数转成有类型的对象。
     *
     * @param arg 第一个参数
     * @return 分页对象
     */
    @SuppressWarnings("unchecked")
    private static IPage<ReconcileDiff> asPage(Object arg) {
        return (IPage<ReconcileDiff>) arg;
    }

    /**
     * 截取一页，模拟 MyBatis-Plus 分页插件的行为。
     *
     * @param page 分页参数
     * @param all  过滤排序后的全量数据
     * @return 填好 total 与 records 的分页对象
     */
    private static IPage<ReconcileDiff> fillPage(IPage<ReconcileDiff> page, List<ReconcileDiff> all) {
        page.setTotal(all.size());
        int offset = (int) Math.max(Math.min((page.getCurrent() - 1) * page.getSize(), all.size()), 0);
        int end = (int) Math.min(offset + page.getSize(), all.size());
        page.setRecords(new ArrayList<>(all.subList(offset, end)));
        return page;
    }
}
