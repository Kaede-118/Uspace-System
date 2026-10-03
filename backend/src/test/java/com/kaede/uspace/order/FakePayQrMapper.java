package com.kaede.uspace.order;

import com.kaede.uspace.order.entity.PayQr;
import com.kaede.uspace.order.mapper.PayQrMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 内存版的 {@link PayQrMapper}，让收款码的单元测试不依赖数据库。
 *
 * <p>实现方式与 {@code FakeStoreMapper}、{@code FakeNoticeMapper} 一致：
 * 动态代理 + 按方法名分发，只处理被真正调用的方法，其余直接抛异常并提示补哪个 ——
 * Service 一旦用了预期之外的方法，测试会立刻告诉你，而不是静默返回 null。
 *
 * <p><b>模拟的是数据库行为的语义而非实现</b>：逻辑删除过滤、两个查询口径的差异
 *（{@code selectEnabledByStore} 只给启用的、{@code selectAllByStore} 含停用的）、
 * 排序键。这些正是 Service 层判断的依据，假实现必须与真库一致，
 * 否则单测通过的结论不可信。SQL 是否正确、列名映射对不对，由集成测试负责。
 */
public class FakePayQrMapper implements InvocationHandler {

    /** 模拟数据表。用 LinkedHashMap 保持插入顺序，便于调试时观察 */
    private final Map<Long, PayQr> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return PayQrMapper 的假实现
     */
    public PayQrMapper asMapper() {
        return (PayQrMapper) Proxy.newProxyInstance(
                PayQrMapper.class.getClassLoader(),
                new Class<?>[]{PayQrMapper.class},
                this);
    }

    /**
     * 预置一条收款码数据。
     *
     * @param qr 收款码，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public PayQr seed(PayQr qr) {
        if (qr.getId() == null) {
            qr.setId(allocateId());
        }
        if (qr.getDeleted() == null) {
            qr.setDeleted(0);
        }
        rows.put(qr.getId(), qr);
        return qr;
    }

    /**
     * 按 ID 取出表中的当前状态，供测试断言。
     *
     * @param id 收款码 ID
     * @return 收款码；不存在时返回 null
     */
    public PayQr get(Long id) {
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
            case "insert" -> insert((PayQr) args[0]);
            case "selectById" -> selectById((Long) args[0]);
            case "selectBatchIds" -> selectBatchIds((Collection<Long>) args[0]);
            case "selectEnabledByStore" -> selectByStore((Long) args[0], true);
            case "selectAllByStore" -> selectByStore((Long) args[0], false);
            case "updateFields" -> updateFields(args);
            case "deleteById" -> deleteById((Long) args[0]);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakePayQrMapper 中补上对应实现");
        };
    }

    // ==================================================================
    // 各方法的模拟实现
    // ==================================================================

    /**
     * 插入。模拟自增主键与逻辑删除标记的默认值。
     *
     * @param qr 待插入的收款码
     * @return 受影响行数，恒为 1
     */
    private int insert(PayQr qr) {
        qr.setId(allocateId());
        if (qr.getDeleted() == null) {
            qr.setDeleted(0);
        }
        rows.put(qr.getId(), qr);
        return 1;
    }

    /**
     * 按 ID 查询，自动过滤已逻辑删除的行 —— 真实库里全局配置就是这么做的。
     *
     * @param id 收款码 ID
     * @return 收款码；不存在或已删除时返回 null
     */
    private PayQr selectById(Long id) {
        PayQr qr = rows.get(id);
        return isAlive(qr) ? qr : null;
    }

    /**
     * 按一批 ID 查询，同样过滤已逻辑删除的行。
     *
     * <p>供付款凭证的后台列表使用：它要把每条凭证上的 {@code pay_qr_id}
     * 批量换成显示名，逐条查会变成 N+1。
     *
     * @param ids 一批收款码 ID
     * @return 命中的收款码；不存在的 ID 被跳过（真库也是这个行为）
     */
    private List<PayQr> selectBatchIds(Collection<Long> ids) {
        return ids.stream().map(this::selectById).filter(Objects::nonNull).toList();
    }

    /**
     * 按门店取收款码。
     *
     * <p><b>两个口径由 {@code enabledOnly} 一个参数切换</b>，
     * 而不是写成两个几乎相同的私有方法 —— 那样改排序规则时要改两处，
     * 而其中一处漏改不会有任何报错，只表现为「两个接口的排序不一样」。
     *
     * <p>排序与真 SQL 逐字一致（{@code sort ASC, id ASC}）：
     * 少了第二个键，同 sort 的记录顺序就不确定，测试会时好时坏。
     *
     * @param storeId     门店 ID
     * @param enabledOnly 是否只取启用中的
     * @return 收款码列表，按 sort 升序
     */
    private List<PayQr> selectByStore(Long storeId, boolean enabledOnly) {
        return rows.values().stream()
                .filter(this::isAlive)
                .filter(qr -> storeId.equals(qr.getStoreId()))
                .filter(qr -> !enabledOnly || Integer.valueOf(1).equals(qr.getEnabled()))
                .sorted(Comparator.comparing(PayQr::getSort, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(PayQr::getId))
                .toList();
    }

    /**
     * 全量替换可编辑字段。传 null 即写入 null —— 与真实 SQL 的全量替换语义一致。
     *
     * @param args 依次为 id、channel、name、imageUrl、enabled、sort
     * @return 受影响行数；0 表示收款码不存在或已删除
     */
    private int updateFields(Object[] args) {
        PayQr qr = selectById((Long) args[0]);
        if (qr == null) {
            return 0;
        }
        qr.setChannel((String) args[1]);
        qr.setName((String) args[2]);
        qr.setImageUrl((String) args[3]);
        qr.setEnabled((Integer) args[4]);
        qr.setSort((Integer) args[5]);
        return 1;
    }

    /**
     * 逻辑删除。真实库里框架把 {@code deleteById} 改写成
     * {@code UPDATE ... SET deleted = 1}，这里用同一套语义。
     *
     * @param id 收款码 ID
     * @return 受影响行数；0 表示收款码不存在或已删除
     */
    private int deleteById(Long id) {
        PayQr qr = selectById(id);
        if (qr == null) {
            return 0;
        }
        qr.setDeleted(1);
        return 1;
    }

    /**
     * 分配一个未被占用的自增主键。
     *
     * <p><b>必须跳过已占用的 ID</b>：测试里常用显式 ID 预置数据，
     * 若游标不跳过它，后续不带 ID 的 {@code seed} 会分配到同一个 ID
     * 并把先前的记录悄悄覆盖掉 —— 表现为「明明预置了两条却只剩一条」，
     * 且不会报错。
     *
     * @return 可用的主键
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
     * @param qr 收款码，可为 null
     * @return 有效返回 true
     */
    private boolean isAlive(PayQr qr) {
        return qr != null && (qr.getDeleted() == null || qr.getDeleted() == 0);
    }
}
