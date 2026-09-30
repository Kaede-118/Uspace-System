package com.kaede.uspace.product;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kaede.uspace.product.entity.ProductOrder;
import com.kaede.uspace.product.mapper.ProductOrderMapper;
import com.kaede.uspace.product.mapper.ProductPendingCount;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 内存版的 {@link ProductOrderMapper}，让商品服务的单元测试不依赖数据库。
 *
 * <p><b>会模拟审计字段的自动填充</b>：真表的 {@code created_at} 是
 * {@code NOT NULL} 且无默认值，而「待支付单超过存活时长后不再占库存」
 * 这条规则正是拿它算的 —— 假实现若不填，那条分支在单测里根本走不到。
 *
 * <p><b>{@code countPendingByProduct} 忠实照搬真 SQL 的三个要点</b>：
 * 只算 {@code PENDING_PAYMENT}、只算 {@code created_at > since} 的、
 * 并且<b>没有占用的商品不出现在结果里</b>（{@code GROUP BY} 的本性）。
 * 第三条尤其在造数据时容易踩空 —— 调用方要按「查不到即 0」处理。
 */
public class FakeProductOrderMapper implements InvocationHandler {

    /** 模拟数据表。用 LinkedHashMap 保持插入顺序，便于调试时观察 */
    private final Map<Long, ProductOrder> rows = new LinkedHashMap<>();

    /** 模拟自增主键 */
    private long nextId = 1;

    /**
     * 生成接口代理，交给 Service 使用。
     *
     * @return ProductOrderMapper 的假实现
     */
    public ProductOrderMapper asMapper() {
        return (ProductOrderMapper) Proxy.newProxyInstance(
                ProductOrderMapper.class.getClassLoader(),
                new Class<?>[]{ProductOrderMapper.class},
                this);
    }

    /**
     * 预置一条购买单，模拟「库里已经有这笔单」。
     *
     * <p>测试用它可以造出「40 分钟前发起、至今未付」这类单据，
     * 从而走到「超时单不再占库存」那条分支。
     *
     * @param order 购买单，id 为空时自动分配
     * @return 同一个对象，便于链式构造
     */
    public ProductOrder seed(ProductOrder order) {
        if (order.getId() == null) {
            order.setId(nextId++);
        }
        if (order.getDeleted() == null) {
            order.setDeleted(0);
        }
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
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
    public ProductOrder get(Long id) {
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
            case "insert" -> insert((ProductOrder) args[0]);
            case "selectById" -> selectById((Long) args[0]);
            case "selectByOrderNo" -> selectByOrderNo((String) args[0]);
            case "selectPageBy" -> selectPageBy(args);
            case "markPaid" -> markPaid(args);
            case "closePending" -> closePending((Long) args[0]);
            case "countPendingByProduct" -> countPendingByProduct(args);
            default -> throw new UnsupportedOperationException(
                    "假 Mapper 未实现方法 " + method.getName()
                            + " —— 出现这个错误说明 Service 调用了预期之外的方法，"
                            + "请在 FakeProductOrderMapper 中补上对应实现");
        };
    }

    // ------------------------------------------------------------------
    // 以下是各方法的内存实现
    // ------------------------------------------------------------------

    /**
     * 插入购买单，模拟自增主键与审计字段的填充。
     *
     * @param order 待插入的购买单
     * @return 受影响行数，恒为 1
     */
    private int insert(ProductOrder order) {
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
    private ProductOrder selectById(Long id) {
        ProductOrder order = rows.get(id);
        return isAlive(order) ? order : null;
    }

    /**
     * 按单号查单据。
     *
     * @param orderNo 购买单号
     * @return 购买单；不存在时返回 null
     */
    private ProductOrder selectByOrderNo(String orderNo) {
        return rows.values().stream()
                .filter(FakeProductOrderMapper::isAlive)
                .filter(o -> Objects.equals(o.getOrderNo(), orderNo))
                .findFirst()
                .orElse(null);
    }

    /**
     * 分页查询购买单，用户端与后台共用。对应真 SQL 的两个可空筛选条件。
     *
     * @param args 依次为分页对象、购买人 ID、状态
     * @return 填好结果的分页对象
     */
    @SuppressWarnings("unchecked")
    private IPage<ProductOrder> selectPageBy(Object[] args) {
        IPage<ProductOrder> page = (IPage<ProductOrder>) args[0];
        Long userId = (Long) args[1];
        String status = (String) args[2];

        List<ProductOrder> matched = rows.values().stream()
                .filter(FakeProductOrderMapper::isAlive)
                .filter(o -> userId == null || Objects.equals(o.getUserId(), userId))
                .filter(o -> status == null || status.isEmpty() || status.equals(o.getStatus()))
                .sorted(Comparator.comparing(ProductOrder::getId, Comparator.reverseOrder()))
                .toList();

        page.setTotal(matched.size());
        int from = (int) ((page.getCurrent() - 1) * page.getSize());
        if (from >= matched.size()) {
            page.setRecords(List.of());
        } else {
            int to = (int) Math.min(from + page.getSize(), matched.size());
            page.setRecords(matched.subList(from, to));
        }
        return page;
    }

    /**
     * 标记为已支付，带状态守卫。
     *
     * @param args 依次为购买单 ID、支付通道、平台交易号、支付时刻
     * @return 受影响行数；0 表示单据不是待支付状态，或记录不存在
     */
    private int markPaid(Object[] args) {
        ProductOrder order = selectById((Long) args[0]);
        if (order == null
                || !ProductOrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
            return 0;
        }
        order.setStatus(ProductOrderStatus.PAID.name());
        order.setPaymentMethod((String) args[1]);
        order.setPaymentNo((String) args[2]);
        order.setPaidAt((LocalDateTime) args[3]);
        return 1;
    }

    /**
     * 关闭待支付单，带状态守卫。
     *
     * <p>守卫不能少：没有它，一笔已经支付成功的单子会被「取消」掉，
     * 而库存已经扣了 —— 于是出现「单子关闭、库存少了」的不一致。
     *
     * @param id 购买单 ID
     * @return 受影响行数；0 表示单据不是待支付状态，或记录不存在
     */
    private int closePending(Long id) {
        ProductOrder order = selectById(id);
        if (order == null
                || !ProductOrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
            return 0;
        }
        order.setStatus(ProductOrderStatus.CLOSED.name());
        return 1;
    }

    /**
     * 统计一批商品各自被未超时的待支付单占掉<b>多少件</b>。
     *
     * <p>真 SQL 是 {@code GROUP BY product_id}，所以<b>没有占用的商品
     * 不出现在结果里</b> —— 这里也照做，让测试与线上跑的是同一套语义。
     *
     * <p><b>累加的是 {@code quantity} 而不是每笔记 1</b>：一笔「买 5 件」的
     * 待支付单占掉的是 5 件库存。真 SQL 用的是 {@code SUM(quantity)}，
     * 假实现若写成计数器，测试就会与线上算出两个不同的可售量。
     *
     * @param args 依次为商品 ID 列表、判定起点
     * @return 各商品的占用量；全部为 0 时返回空列表
     */
    private List<ProductPendingCount> countPendingByProduct(Object[] args) {
        @SuppressWarnings("unchecked")
        List<Long> productIds = (List<Long>) args[0];
        LocalDateTime since = (LocalDateTime) args[1];

        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (ProductOrder order : rows.values()) {
            if (!isAlive(order)
                    || !ProductOrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())
                    || !productIds.contains(order.getProductId())) {
                continue;
            }
            // 超时的不再占位 —— 与真 SQL 的 created_at > since 同一口径。
            // 用 !isAfter 而不是 isBefore，让「恰好等于」也归入超时（SQL 用的是 >）
            if (order.getCreatedAt() == null || !order.getCreatedAt().isAfter(since)) {
                continue;
            }
            quantities.merge(order.getProductId(), order.getQuantity(), Integer::sum);
        }

        List<ProductPendingCount> result = new ArrayList<>();
        quantities.forEach((productId, quantity) -> {
            ProductPendingCount row = new ProductPendingCount();
            row.setProductId(productId);
            row.setPendingQuantity(quantity);
            result.add(row);
        });
        return result;
    }

    /**
     * 判断记录是否存在且未被逻辑删除。
     *
     * @param order 购买单，可为 null
     * @return 有效返回 true
     */
    private static boolean isAlive(ProductOrder order) {
        return order != null && !Integer.valueOf(1).equals(order.getDeleted());
    }
}
