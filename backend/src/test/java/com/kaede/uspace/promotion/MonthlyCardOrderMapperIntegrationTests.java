package com.kaede.uspace.promotion;

import com.kaede.uspace.promotion.entity.MonthlyCardOrder;
import com.kaede.uspace.promotion.mapper.MonthlyCardOrderMapper;
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

/**
 * {@link MonthlyCardOrderMapper} 的集成测试。
 *
 * <p><b>连真库、每个用例结束后自动回滚</b>，理由与
 * {@link MonthlyCardMapperIntegrationTests} 相同。
 *
 * <p>本类钉住的是支付链路上两处「写错不报错」的地方：
 * <ul>
 *   <li><b>{@code markPaid} 的状态守卫</b> —— 支付平台会重推通知，
 *       少了守卫，第二次回调会再发一张卡，用户付一次钱拿到两张</li>
 *   <li><b>{@code closePending} 的状态守卫</b> —— 少了它，
 *       一笔已支付成功的单子会被「取消」掉，而卡已经发出去了</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class MonthlyCardOrderMapperIntegrationTests {

    /** 测试用的用户 ID。取大数以免与真实数据混淆 */
    private static final Long USER_ID = 999911L;
    private static final Long OTHER_USER_ID = 999912L;

    @Autowired
    private MonthlyCardOrderMapper orderMapper;

    @Test
    @DisplayName("插入：主键回填，审计字段自动填充")
    void insert_fillsAuditFields() {
        MonthlyCardOrder order = newOrder(USER_ID, CardOrderStatus.PENDING_PAYMENT);
        orderMapper.insert(order);

        assertNotNull(order.getId(), "自增主键要回填");
        assertNotNull(order.getCreatedAt(), "created_at 由框架自动填充 —— "
                + "「待支付单超过存活时长后自动关闭」这条规则正是拿它算的");

        MonthlyCardOrder loaded = orderMapper.selectByOrderNo(order.getOrderNo());
        assertNotNull(loaded, "要能按单号反查 —— 支付回调就是这么找目标的");
        assertEquals(0, new BigDecimal("600").compareTo(loaded.getPrice()));
        assertEquals(CardOrderStatus.PENDING_PAYMENT.name(), loaded.getStatus());
    }

    @Test
    @DisplayName("插入：单号唯一索引挡得住重号")
    void insert_rejectsDuplicateOrderNo() {
        MonthlyCardOrder first = newOrder(USER_ID, CardOrderStatus.PENDING_PAYMENT);
        orderMapper.insert(first);

        MonthlyCardOrder duplicate = newOrder(OTHER_USER_ID, CardOrderStatus.PENDING_PAYMENT);
        duplicate.setOrderNo(first.getOrderNo());

        assertThrows(DuplicateKeyException.class, () -> orderMapper.insert(duplicate),
                "单号是支付回调的幂等键，重号必须由数据库拦下 —— "
                        + "应用层查重挡不住并发");
    }

    @Test
    @DisplayName("标记已支付：带状态守卫，重复调用第二次返回 0")
    void markPaid_isGuardedByStatus() {
        MonthlyCardOrder order = newOrder(USER_ID, CardOrderStatus.PENDING_PAYMENT);
        orderMapper.insert(order);

        LocalDateTime paidAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        int first = orderMapper.markPaid(order.getId(), "WXPAY_H5", "TXN-001", paidAt);
        int second = orderMapper.markPaid(order.getId(), "WXPAY_H5", "TXN-002",
                paidAt.plusMinutes(1));

        assertEquals(1, first, "首次标记应当成功");
        assertEquals(0, second,
                "支付平台会重推通知，第二次必须返回 0 —— 调用方据此跳过发卡与累加卡费");

        MonthlyCardOrder loaded = orderMapper.selectByOrderNo(order.getOrderNo());
        assertEquals(CardOrderStatus.PAID.name(), loaded.getStatus());
        assertEquals("TXN-001", loaded.getPaymentNo(),
                "第二次回调不该覆盖首次写入的交易号");
        assertEquals(paidAt, loaded.getPaidAt(), "支付时刻同样不该被改写");
    }

    @Test
    @DisplayName("关闭：只关得动待支付单，已支付的关不掉")
    void closePending_isGuardedByStatus() {
        MonthlyCardOrder pending = newOrder(USER_ID, CardOrderStatus.PENDING_PAYMENT);
        orderMapper.insert(pending);
        MonthlyCardOrder paid = newOrder(OTHER_USER_ID, CardOrderStatus.PENDING_PAYMENT);
        orderMapper.insert(paid);
        orderMapper.markPaid(paid.getId(), "WXPAY_JSAPI", "TXN-003",
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));

        assertEquals(1, orderMapper.closePending(pending.getId()), "待支付单可以关闭");
        assertEquals(0, orderMapper.closePending(paid.getId()),
                "已支付的单关不掉 —— 关得掉的话会出现「单子关闭、卡还生效」");

        assertEquals(CardOrderStatus.CLOSED.name(),
                orderMapper.selectByOrderNo(pending.getOrderNo()).getStatus());
        assertEquals(CardOrderStatus.PAID.name(),
                orderMapper.selectByOrderNo(paid.getOrderNo()).getStatus());
    }

    @Test
    @DisplayName("待支付查询：只返回自己的、且只返回待支付的")
    void selectPendingByUser_filtersByUserAndStatus() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        MonthlyCardOrder mine = newOrder(USER_ID, CardOrderStatus.PENDING_PAYMENT);
        orderMapper.insert(mine);
        MonthlyCardOrder others = newOrder(OTHER_USER_ID, CardOrderStatus.PENDING_PAYMENT);
        orderMapper.insert(others);
        MonthlyCardOrder closed = newOrder(USER_ID, CardOrderStatus.PENDING_PAYMENT);
        orderMapper.insert(closed);
        orderMapper.closePending(closed.getId());

        List<MonthlyCardOrder> pending = orderMapper.selectPendingForUpdate(USER_ID);

        assertEquals(1, pending.size(), "自己的、且仍在待支付的那一条");
        assertEquals(mine.getId(), pending.get(0).getId());
        assertEquals(mine.getId(), orderMapper.selectPendingByUser(USER_ID).getId(),
                "取最近一张待支付单，供卡包页显示「去支付」");
        assertNull(orderMapper.selectPendingByUser(OTHER_USER_ID + 1000L), "查无此人时返回 null");

        // 构造前提：确实还留着一条已关闭的单，它不该出现在结果里
        assertNotNull(orderMapper.selectLatestByUser(USER_ID), "最近一张单始终查得到");
        assertEquals(now.toLocalDate(), orderMapper.selectLatestByUser(USER_ID)
                .getCreatedAt().toLocalDate(), "审计字段填的是真实时刻");
    }

    // ==================================================================
    // 构造辅助
    // ==================================================================

    /**
     * 造一条未落库的购买单。
     *
     * @param userId 购买人
     * @param status 状态
     * @return 购买单实体
     */
    private static MonthlyCardOrder newOrder(Long userId, CardOrderStatus status) {
        MonthlyCardOrder order = new MonthlyCardOrder();
        order.setOrderNo(MonthlyCardNo.generate());
        order.setUserId(userId);
        order.setCardType(MonthlyCardType.ALL_DAY.name());
        order.setPrice(new BigDecimal("600"));
        order.setStatus(status.name());
        return order;
    }
}
