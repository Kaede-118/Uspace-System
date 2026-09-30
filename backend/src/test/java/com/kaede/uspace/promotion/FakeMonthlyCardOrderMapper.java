package com.kaede.uspace.promotion;

import com.kaede.uspace.promotion.entity.MonthlyCardOrder;
import com.kaede.uspace.promotion.mapper.MonthlyCardOrderMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 内存版的 {@link MonthlyCardOrderMapper}，让月卡服务的单元测试不依赖数据库。
 *
 * <p><b>会模拟审计字段的自动填充</b>：真实的 MyBatis-Plus 在 insert 时会把
 * {@code createdAt} 填上，而「待支付单超过存活时长后自动关闭」这条规则
 * 正是拿它算的 —— 假实现若不填，那条分支在单测里根本走不到，
 * 于是「超时能自动放行」这件事就成了没人验证过的口头承诺。
 *
 * <p>同理，{@code markPaid} 与 {@code closePending} 都带状态守卫
 * （{@code AND status = 'PENDING_PAYMENT'}），假实现也照做 ——
 * 少了它，「重复回调不会重复发卡」这条就测不出来了。
 */
public class FakeMonthlyCardOrderMapper implements InvocationHandler {

    /** 模拟数据表。用 LinkedHashMap 保持插入顺序，便于调试时观察 */
    private final Map<Long, MonthlyCardOrder> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return MonthlyCardOrderMapper 的假实现
     */
    public MonthlyCardOrderMapper asMapper() {
        return (MonthlyCardOrderMapper) Proxy.newProxyInstance(
                MonthlyCardOrderMapper.class.getClassLoader(),
                new Class<?>[]{MonthlyCardOrderMapper.class},
                this);
    }

    /**
     * 预置一条购买单，模拟「库里已经有这笔单」。
     *
     * <p>测试用它可以造出「40 分钟前发起、至今未付」这类单据，
     * 从而走到超时关闭那条分支。
     *
     * @param order 购买单，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public MonthlyCardOrder seed(MonthlyCardOrder order) {
        if (order.getId() == null) {
            order.setId(nextId++);
        }
        if (order.getDeleted() == null) {
            order.setDeleted(0);
        }
        rows.put(order.getId(), order);
        return order;
    }

    /**
     * 按 ID 取出表中的当前状态，供测试断言。
     *
     * @param id 购买单 ID
     * @return 购买单；不存在时返回 null
     */
    public MonthlyCardOrder get(Long id) {
        return rows.get(id);
    }

    /**
     * 表中当前的单据数，供测试断言「有没有多出记录」。
     *
     * @return 记录数
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
            case "insert" -> insert((MonthlyCardOrder) args[0]);
            case "selectById" -> selectById((Long) args[0]);
            case "selectByOrderNo" -> selectByOrderNo((String) args[0]);
            case "selectPendingForUpdate" -> selectPendingForUpdate((Long) args[0]);
            case "selectPendingByUser" -> selectPendingByUser((Long) args[0]);
            case "selectLatestByUser" -> selectLatestByUser((Long) args[0]);
            case "markPaid" -> markPaid(args);
            case "closePending" -> closePending((Long) args[0]);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeMonthlyCardOrderMapper 中补上对应实现");
        };
    }

    // ------------------------------------------------------------------
    // 以下是各方法的内存实现
    // ------------------------------------------------------------------

    /**
     * 插入购买单，模拟自增主键与审计字段的填充。
     *
     * <p>内存实现里 {@code FOR UPDATE} 没有对应物 —— 单测不跑并发，
     * 加锁行为由 Service 上的 {@code @Transactional} 保证，
     * 那属于「评审时对着代码看」的事，不是单测能覆盖的。
     *
     * @param order 待插入的购买单
     * @return 受影响行数，恒为 1
     */
    private int insert(MonthlyCardOrder order) {
        if (order.getId() == null) {
            order.setId(nextId++);
        }
        if (order.getDeleted() == null) {
            order.setDeleted(0);
        }
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        }
        if (order.getUpdatedAt() == null) {
            order.setUpdatedAt(order.getCreatedAt());
        }
        rows.put(order.getId(), order);
        return 1;
    }

    /**
     * 按 ID 查未删除的单据。
     *
     * @param id 购买单 ID
     * @return 购买单；不存在或已删除时返回 null
     */
    private MonthlyCardOrder selectById(Long id) {
        MonthlyCardOrder order = rows.get(id);
        return isAlive(order) ? order : null;
    }

    /**
     * 按单号查单据。
     *
     * @param orderNo 购买单号
     * @return 购买单；不存在时返回 null
     */
    private MonthlyCardOrder selectByOrderNo(String orderNo) {
        return rows.values().stream()
                .filter(FakeMonthlyCardOrderMapper::isAlive)
                .filter(o -> Objects.equals(o.getOrderNo(), orderNo))
                .findFirst()
                .orElse(null);
    }

    /**
     * 查某用户的全部待支付单，按 ID 升序。对应真 SQL 的加锁读。
     *
     * @param userId 用户 ID
     * @return 待支付单列表
     */
    private List<MonthlyCardOrder> selectPendingForUpdate(Long userId) {
        return rows.values().stream()
                .filter(FakeMonthlyCardOrderMapper::isAlive)
                .filter(o -> Objects.equals(o.getUserId(), userId))
                .filter(o -> CardOrderStatus.PENDING_PAYMENT.name().equals(o.getStatus()))
                .sorted(Comparator.comparing(MonthlyCardOrder::getId))
                .toList();
    }

    /**
     * 查某用户最近的一张待支付单。
     *
     * <p>对应真 SQL 的 {@code ORDER BY id DESC LIMIT 1} —— 注意它返回的是
     * <b>单条</b>而不是列表，与加锁读的那个方法不是一回事。
     *
     * @param userId 用户 ID
     * @return 待支付单；没有则返回 null
     */
    private MonthlyCardOrder selectPendingByUser(Long userId) {
        return selectPendingForUpdate(userId).stream()
                .max(Comparator.comparing(MonthlyCardOrder::getId))
                .orElse(null);
    }

    /**
     * 查某用户最近的一张单据，不限状态。
     *
     * @param userId 用户 ID
     * @return 最近的单据；没有则返回 null
     */
    private MonthlyCardOrder selectLatestByUser(Long userId) {
        return rows.values().stream()
                .filter(FakeMonthlyCardOrderMapper::isAlive)
                .filter(o -> Objects.equals(o.getUserId(), userId))
                .max(Comparator.comparing(MonthlyCardOrder::getId))
                .orElse(null);
    }

    /**
     * 标记为已支付，带状态守卫。
     *
     * @param args 依次为购买单 ID、支付通道、平台交易号、支付时刻
     * @return 受影响行数；0 表示单据不是待支付状态，或记录不存在
     */
    private int markPaid(Object[] args) {
        MonthlyCardOrder order = selectById((Long) args[0]);
        if (order == null || !CardOrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
            return 0;
        }
        order.setStatus(CardOrderStatus.PAID.name());
        order.setPaymentMethod((String) args[1]);
        order.setPaymentNo((String) args[2]);
        order.setPaidAt((LocalDateTime) args[3]);
        return 1;
    }

    /**
     * 关闭待支付单，带状态守卫。
     *
     * <p>守卫不能少：没有它，一笔已经支付成功的单子会被「取消」掉，
     * 而卡已经发出去了 —— 于是出现「单子关闭、卡还在生效」的不一致。
     *
     * @param id 购买单 ID
     * @return 受影响行数；0 表示单据不是待支付状态，或记录不存在
     */
    private int closePending(Long id) {
        MonthlyCardOrder order = selectById(id);
        if (order == null || !CardOrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
            return 0;
        }
        order.setStatus(CardOrderStatus.CLOSED.name());
        return 1;
    }

    /**
     * 判断记录是否存在且未被逻辑删除。
     *
     * @param order 购买单，可为 null
     * @return 有效返回 true
     */
    private static boolean isAlive(MonthlyCardOrder order) {
        return order != null && !Integer.valueOf(1).equals(order.getDeleted());
    }
}
