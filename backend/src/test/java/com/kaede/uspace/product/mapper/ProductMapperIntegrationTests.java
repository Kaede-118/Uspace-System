package com.kaede.uspace.product.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kaede.uspace.product.ProductNo;
import com.kaede.uspace.product.ProductOrderStatus;
import com.kaede.uspace.product.entity.Product;
import com.kaede.uspace.product.entity.ProductOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商品两张表的集成测试。
 *
 * <p><b>连真库、每个用例结束后自动回滚。</b>未配置 {@code MYSQL_PASSWORD}
 * 时整体跳过，不让没配库的机器测试失败。
 *
 * <p>本类钉住的是<b>只有真 SQL 才能验证</b>的那几处，
 * 单测里的假 Mapper 顶替不了它们：
 * <ul>
 *   <li><b>{@code countPendingByProduct} 用的是 {@code SUM(quantity)} 而不是
 *       {@code COUNT(*)}</b> —— 一笔买 5 件的待支付单占的是 5 件库存。
 *       写成后者不报任何错，只让可售量虚高一大截</li>
 *   <li><b>{@code deductStock} 的库存条件</b> —— 它是这套库存方案唯一的硬防线。
 *       少了 {@code AND stock >= ?}，两人同时买最后一件会把库存扣成负数</li>
 *   <li><b>{@code markPaid} / {@code closePending} 的状态守卫</b> ——
 *       支付平台会重推通知，守卫少了会重复扣库存</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class ProductMapperIntegrationTests {

    /** 测试用的用户 ID。取大数以免与真实数据混淆 */
    private static final Long USER_ID = 999921L;
    private static final Long OTHER_USER_ID = 999922L;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductOrderMapper orderMapper;

    // ==================================================================
    // 商品表
    // ==================================================================

    @Test
    @DisplayName("商品：主键回填、审计字段自动填充、可按 ID 查回")
    void insertProduct_fillsAuditFields() {
        Product product = newProduct("集成测试用品", "9.90", 10, 1, 0);
        productMapper.insert(product);

        assertNotNull(product.getId(), "自增主键要回填");
        assertNotNull(product.getCreatedAt(), "created_at 由框架自动填充");
        assertNotNull(product.getUpdatedAt());

        Product loaded = productMapper.selectById(product.getId());
        assertNotNull(loaded);
        assertEquals(0, new BigDecimal("9.90").compareTo(loaded.getPrice()));
        assertEquals(10, loaded.getStock());
        assertEquals(1, loaded.getEnabled());
    }

    @Test
    @DisplayName("商品：上架列表只含上架的，排序值小的在前")
    void selectOnSaleList_filtersAndOrders() {
        Product late = newProduct("排在后面", "1.00", 5, 1, 900);
        Product early = newProduct("排在前面", "1.00", 5, 1, -900);
        Product disabled = newProduct("已下架", "1.00", 5, 0, -999);
        productMapper.insert(late);
        productMapper.insert(early);
        productMapper.insert(disabled);

        List<Product> onSale = productMapper.selectOnSaleList();

        assertTrue(onSale.stream().anyMatch(p -> p.getId().equals(early.getId())));
        assertTrue(onSale.stream().anyMatch(p -> p.getId().equals(late.getId())));
        assertTrue(onSale.stream().noneMatch(p -> p.getId().equals(disabled.getId())),
                "下架的不能出现在用户端列表里");

        int earlyIndex = indexOf(onSale, early.getId());
        int lateIndex = indexOf(onSale, late.getId());
        assertTrue(earlyIndex < lateIndex, "排序值小的排在前面");
    }

    @Test
    @DisplayName("商品：后台分页带关键词与上架状态筛选")
    void selectPageBy_filtersByKeywordAndEnabled() {
        String marker = "筛选标记" + System.nanoTime();
        Product onSale = newProduct(marker + "甲", "1.00", 5, 1, 0);
        Product offSale = newProduct(marker + "乙", "1.00", 5, 0, 0);
        productMapper.insert(onSale);
        productMapper.insert(offSale);

        Page<Product> all = (Page<Product>) productMapper.selectPageBy(
                new Page<>(1, 10), marker, null, false);
        assertEquals(2, all.getTotal(), "关键词应当同时命中上架与下架的");

        Page<Product> enabledOnly = (Page<Product>) productMapper.selectPageBy(
                new Page<>(1, 10), marker, 1, false);
        assertEquals(1, enabledOnly.getTotal(), "只看上架的");
        assertEquals(onSale.getId(), enabledOnly.getRecords().get(0).getId());

        Page<Product> disabledOnly = (Page<Product>) productMapper.selectPageBy(
                new Page<>(1, 10), marker, 0, false);
        assertEquals(offSale.getId(), disabledOnly.getRecords().get(0).getId());
    }

    @Test
    @DisplayName("分页查询：两种排序各自生效（默认按展示序、可按库存从少到多）")
    void selectPageBy_switchesOrderBy() {
        String marker = "排序标记" + System.nanoTime();
        // 故意让 sort_no 与 stock 的顺序相反，两种排序的结果才区分得开
        productMapper.insert(newProduct(marker + "甲", "1.00", 80, 1, 10));
        productMapper.insert(newProduct(marker + "乙", "1.00", 5, 1, 20));
        productMapper.insert(newProduct(marker + "丙", "1.00", 40, 1, 30));

        Page<Product> bySortNo = (Page<Product>) productMapper.selectPageBy(
                new Page<>(1, 10), marker, null, false);
        assertEquals(List.of(marker + "甲", marker + "乙", marker + "丙"),
                bySortNo.getRecords().stream().map(Product::getName).toList(),
                "默认按 sort_no 升序");

        Page<Product> byStock = (Page<Product>) productMapper.selectPageBy(
                new Page<>(1, 10), marker, null, true);
        assertEquals(List.of(marker + "乙", marker + "丙", marker + "甲"),
                byStock.getRecords().stream().map(Product::getName).toList(),
                "按库存从少到多 —— 排序必须落在 SQL 的 ORDER BY 上，"
                        + "否则分页之后『库存最少的』根本不在这一页里");
    }

    @Test
    @DisplayName("全量更新：传 null 的字段真的被清空，而不是被跳过")
    void updateProduct_writesNullsInsteadOfSkipping() {
        Product product = newProduct("全量更新测试品", "9.90", 10, 1, 0);
        product.setCover("/uploads/product/a.jpg");
        product.setDescription("原来的描述");
        productMapper.insert(product);

        // 模拟后台改一次：撤掉封面、清空描述（PUT 的全量替换语义）
        product.setCover(null);
        product.setDescription(null);
        product.setName("改过名的");
        product.setPrice(new BigDecimal("19.90"));

        assertEquals(1, productMapper.updateProduct(product));

        Product loaded = productMapper.selectById(product.getId());
        assertNull(loaded.getCover(),
                "⚠️ 若这里走 updateById，封面会仍然是那旧图 —— MyBatis-Plus 的默认字段策略"
                        + "跳过 null，接口返回 200、前端刷新后旧值还在，全程不报错。"
                        + "本项目的模块 1 / 3 / 4 与公告包都踩过同一个坑");
        assertNull(loaded.getDescription());
        assertEquals("改过名的", loaded.getName());
        assertEquals(0, new BigDecimal("19.90").compareTo(loaded.getPrice()));
    }

    @Test
    @DisplayName("扣库存：库存够才扣得动，不够返回 0 且不会扣成负数")
    void deductStock_isGuardedByStock() {
        Product product = newProduct("扣减测试品", "1.00", 3, 1, 0);
        productMapper.insert(product);

        assertEquals(1, productMapper.deductStock(product.getId(), 2), "库存 3 扣 2，扣得动");
        assertEquals(1, productMapper.selectById(product.getId()).getStock());

        assertEquals(0, productMapper.deductStock(product.getId(), 2),
                "只剩 1 件却要扣 2 —— 这是「两人同时买最后一件」那条路径，"
                        + "必须返回 0，绝不能扣成负数");
        assertEquals(1, productMapper.selectById(product.getId()).getStock(),
                "扣不动的这次不能改动任何数字");

        assertEquals(1, productMapper.deductStock(product.getId(), 1), "刚好扣完");
        assertEquals(0, productMapper.selectById(product.getId()).getStock());
    }

    @Test
    @DisplayName("扣库存：已删除的商品扣不动")
    void deductStock_ignoresDeletedProduct() {
        Product product = newProduct("待删除品", "1.00", 10, 1, 0);
        productMapper.insert(product);
        productMapper.deleteById(product.getId());

        assertEquals(0, productMapper.deductStock(product.getId(), 1),
                "逻辑删除的商品不该再被扣库存 —— 它已经从陈列里消失了");
    }

    // ==================================================================
    // 购买单表
    // ==================================================================

    @Test
    @DisplayName("购买单：按单号反查得到，重号被唯一索引拦下")
    void insertOrder_andSelectByOrderNo() {
        Product product = newProduct("测试品", "2.00", 10, 1, 0);
        productMapper.insert(product);

        ProductOrder order = newOrder(USER_ID, product, 2);
        orderMapper.insert(order);
        assertNotNull(order.getId());

        ProductOrder loaded = orderMapper.selectByOrderNo(order.getOrderNo());
        assertNotNull(loaded, "支付回调就是这么找目标的");
        assertEquals(2, loaded.getQuantity());
        assertEquals(0, new BigDecimal("4.00").compareTo(loaded.getAmount()));

        ProductOrder duplicate = newOrder(OTHER_USER_ID, product, 1);
        duplicate.setOrderNo(order.getOrderNo());
        assertThrows(DuplicateKeyException.class, () -> orderMapper.insert(duplicate),
                "单号是支付回调的幂等键，重号必须由数据库拦下 —— 应用层查重挡不住并发");
    }

    @Test
    @DisplayName("标记已支付：带状态守卫，重复调用第二次返回 0")
    void markPaid_isGuardedByStatus() {
        Product product = newProduct("测试品", "2.00", 10, 1, 0);
        productMapper.insert(product);
        ProductOrder order = newOrder(USER_ID, product, 1);
        orderMapper.insert(order);

        LocalDateTime paidAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        assertEquals(1, orderMapper.markPaid(order.getId(), "WXPAY_JSAPI", "TXN-1", paidAt));
        assertEquals(0, orderMapper.markPaid(order.getId(), "WXPAY_JSAPI", "TXN-2",
                        paidAt.plusMinutes(1)),
                "支付平台会重推通知，第二次必须返回 0 —— 调用方据此跳过扣库存");

        ProductOrder loaded = orderMapper.selectByOrderNo(order.getOrderNo());
        assertEquals(ProductOrderStatus.PAID.name(), loaded.getStatus());
        assertEquals("TXN-1", loaded.getPaymentNo(), "第二次回调不该覆盖首次写入的交易号");
        assertEquals(paidAt, loaded.getPaidAt());
    }

    @Test
    @DisplayName("关闭：只关得动待支付单，已支付的关不掉")
    void closePending_isGuardedByStatus() {
        Product product = newProduct("测试品", "2.00", 10, 1, 0);
        productMapper.insert(product);

        ProductOrder pending = newOrder(USER_ID, product, 1);
        ProductOrder paid = newOrder(USER_ID, product, 1);
        orderMapper.insert(pending);
        orderMapper.insert(paid);
        orderMapper.markPaid(paid.getId(), "ALIPAY_WAP", "TXN-3",
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));

        assertEquals(1, orderMapper.closePending(pending.getId()));
        assertEquals(0, orderMapper.closePending(paid.getId()),
                "已支付的单关不掉 —— 关得掉的话会出现「单子关闭、库存少了」");

        assertEquals(ProductOrderStatus.CLOSED.name(),
                orderMapper.selectByOrderNo(pending.getOrderNo()).getStatus());
        assertEquals(ProductOrderStatus.PAID.name(),
                orderMapper.selectByOrderNo(paid.getOrderNo()).getStatus());
    }

    @Test
    @DisplayName("占用量统计：按件数求和，不是按单据笔数")
    void countPendingByProduct_sumsQuantity() {
        Product product = newProduct("占用量测试品", "2.00", 100, 1, 0);
        productMapper.insert(product);

        // 两笔单：一笔 2 件、一笔 3 件
        orderMapper.insert(newOrder(USER_ID, product, 2));
        orderMapper.insert(newOrder(OTHER_USER_ID, product, 3));
        // 一笔已支付的，不该计入
        ProductOrder paid = newOrder(USER_ID, product, 7);
        orderMapper.insert(paid);
        orderMapper.markPaid(paid.getId(), "WXPAY_JSAPI", "TXN-4",
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        // 一笔已关闭的，同样不计入
        ProductOrder closed = newOrder(USER_ID, product, 9);
        orderMapper.insert(closed);
        orderMapper.closePending(closed.getId());

        LocalDateTime since = LocalDateTime.now().minusMinutes(30);
        List<ProductPendingCount> counts =
                orderMapper.countPendingByProduct(List.of(product.getId()), since);

        assertEquals(1, counts.size(), "只有这一件商品有占用");
        assertEquals(5, counts.get(0).getPendingQuantity(),
                "⚠️ 是 SUM(quantity) = 2 + 3 = 5，不是 COUNT(*) = 2 笔。"
                        + "写成后者不报任何错，只让可售量虚高 —— "
                        + "表现为「明明下单成功了，付款后却说没货」");
    }

    @Test
    @DisplayName("占用量统计：超时的单不再占位，没有占用的商品不出现在结果里")
    void countPendingByProduct_filtersByTimeAndOmitsEmpty() {
        Product occupied = newProduct("有占用的", "2.00", 100, 1, 0);
        Product idle = newProduct("没人占的", "2.00", 100, 1, 0);
        productMapper.insert(occupied);
        productMapper.insert(idle);
        orderMapper.insert(newOrder(USER_ID, occupied, 3));

        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = List.of(occupied.getId(), idle.getId());

        List<ProductPendingCount> fresh = orderMapper.countPendingByProduct(ids,
                now.minusMinutes(30));
        assertEquals(1, fresh.size(), "刚下的单还在存活时长内，算占用");
        assertEquals(3, fresh.get(0).getPendingQuantity());

        List<ProductPendingCount> expired = orderMapper.countPendingByProduct(ids,
                now.plusMinutes(1));
        assertTrue(expired.isEmpty(),
                "把判定起点推到了单子创建之后 —— 相当于这笔单已经超时。"
                        + "超时不占位是「用户下单后不付款」不再永久占住商品的唯一出口"
                        + "（商品单不像月卡那样会被自动关闭）");
    }

    @Test
    @DisplayName("购买单分页：按用户与状态筛选，最新的在前")
    void selectPageBy_filtersByUserAndStatus() {
        Product product = newProduct("分页测试品", "2.00", 100, 1, 0);
        productMapper.insert(product);

        ProductOrder first = newOrder(USER_ID, product, 1);
        orderMapper.insert(first);
        ProductOrder second = newOrder(USER_ID, product, 1);
        orderMapper.insert(second);
        orderMapper.insert(newOrder(OTHER_USER_ID, product, 1));

        Page<ProductOrder> mine = (Page<ProductOrder>) orderMapper.selectPageBy(
                new Page<>(1, 10), USER_ID, null);
        assertEquals(2, mine.getTotal(), "只返回自己的");
        assertEquals(second.getId(), mine.getRecords().get(0).getId(), "最新的在前");

        Page<ProductOrder> pendingOnly = (Page<ProductOrder>) orderMapper.selectPageBy(
                new Page<>(1, 10), USER_ID, ProductOrderStatus.PENDING_PAYMENT.name());
        assertEquals(2, pendingOnly.getTotal());

        orderMapper.closePending(first.getId());
        Page<ProductOrder> closedOnly = (Page<ProductOrder>) orderMapper.selectPageBy(
                new Page<>(1, 10), USER_ID, ProductOrderStatus.CLOSED.name());
        assertEquals(first.getId(), closedOnly.getRecords().get(0).getId());
    }

    @Test
    @DisplayName("购买单：按 ID 查不到已逻辑删除的记录")
    void selectById_hidesDeletedOrder() {
        Product product = newProduct("测试品", "2.00", 100, 1, 0);
        productMapper.insert(product);
        ProductOrder order = newOrder(USER_ID, product, 1);
        orderMapper.insert(order);

        orderMapper.deleteById(order.getId());

        assertNull(orderMapper.selectById(order.getId()),
                "逻辑删除后查不到 —— 手写 SQL 里的 deleted = 0 就是干这个的");
        assertNull(orderMapper.selectByOrderNo(order.getOrderNo()));
    }

    // ==================================================================
    // 构造辅助
    // ==================================================================

    /**
     * 造一件未落库的商品。名称带时间戳前缀由调用方给，避免与演示数据撞名。
     *
     * @param name    名称
     * @param price   售价（元）
     * @param stock   库存
     * @param enabled 是否上架
     * @param sortNo  排序值
     * @return 商品实体
     */
    private static Product newProduct(String name, String price, int stock,
                                      int enabled, int sortNo) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
        product.setEnabled(enabled);
        product.setSortNo(sortNo);
        return product;
    }

    /**
     * 造一条未落库的购买单。
     *
     * @param userId  购买人
     * @param product 商品
     * @param quantity 数量
     * @return 购买单实体
     */
    private static ProductOrder newOrder(Long userId, Product product, int quantity) {
        ProductOrder order = new ProductOrder();
        order.setOrderNo(ProductNo.generate());
        order.setUserId(userId);
        order.setProductId(product.getId());
        order.setProductName(product.getName());
        order.setUnitPrice(product.getPrice());
        order.setQuantity(quantity);
        order.setAmount(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
        order.setStatus(ProductOrderStatus.PENDING_PAYMENT.name());
        return order;
    }

    /**
     * 在列表里找某个 ID 的下标。
     *
     * @param products 商品列表
     * @param id       目标 ID
     * @return 下标；找不到时返回 -1
     */
    private static int indexOf(List<Product> products, Long id) {
        for (int i = 0; i < products.size(); i++) {
            if (products.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
