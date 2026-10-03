package com.kaede.uspace.order;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.order.dto.PaymentTarget;
import com.kaede.uspace.product.FakeProductMapper;
import com.kaede.uspace.product.FakeProductOrderMapper;
import com.kaede.uspace.product.ProductNo;
import com.kaede.uspace.product.ProductOrderStatus;
import com.kaede.uspace.product.entity.Product;
import com.kaede.uspace.product.entity.ProductOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProductPaymentTargetHandler} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库。</b>两张表由
 * {@link FakeProductMapper} 与 {@link FakeProductOrderMapper} 顶替。
 *
 * <p>这个类守的是商品收款链路上最容易出事的两处：
 * <ul>
 *   <li><b>幂等</b> —— 支付平台会重推通知。第二道防线是
 *       {@code markPaid} 的状态守卫：它返回 false 时<b>绝不能再扣一次库存</b>，
 *       否则用户付一次钱扣两件货，而账面上看不出任何异常</li>
 *   <li><b>库存扣不动时不能抛异常</b> —— 抛了会让支付平台不断重推一笔
 *       永远处理不了的通知。这个分支在真实环境里是必然会走到的
 *       （两人同时买最后一件），而它错了的表现是「支付回调被无限重推」，
 *       排查起来会绕很大一圈</li>
 * </ul>
 */
class ProductPaymentTargetHandlerTests {

    private static final Long USER_ID = 1001L;
    private static final Long OTHER_USER_ID = 1002L;

    private final FakeProductMapper productMapper = new FakeProductMapper();
    private final FakeProductOrderMapper orderMapper = new FakeProductOrderMapper();

    /** 被测处理器，每个用例前重建 */
    private ProductPaymentTargetHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ProductPaymentTargetHandler(orderMapper.asMapper(), productMapper.asMapper());
    }

    // ==================================================================
    // 造数据的小工具
    // ==================================================================

    /**
     * 造一件商品并放进假表。
     *
     * @param stock 库存
     * @return 带自增 ID 的商品
     */
    private Product seedProduct(int stock) {
        Product product = new Product();
        product.setName("矿泉水");
        product.setPrice(new BigDecimal("2.00"));
        product.setStock(stock);
        product.setEnabled(1);
        product.setSortNo(0);
        return productMapper.seed(product);
    }

    /**
     * 造一笔待支付购买单并放进假表。
     *
     * @param userId  下单人
     * @param product 商品
     * @param quantity 数量
     * @return 带自增 ID 的购买单
     */
    private ProductOrder seedOrder(Long userId, Product product, int quantity) {
        ProductOrder order = new ProductOrder();
        order.setOrderNo(ProductNo.generate());
        order.setUserId(userId);
        order.setProductId(product.getId());
        order.setProductName(product.getName());
        order.setUnitPrice(product.getPrice());
        order.setQuantity(quantity);
        order.setAmount(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
        order.setStatus(ProductOrderStatus.PENDING_PAYMENT.name());
        return orderMapper.seed(order);
    }

    /**
     * 走一遍「发起支付前的载入」，拿到统一结构的支付目标。
     *
     * @param orderId 购买单 ID
     * @param userId  发起人
     * @return 支付目标
     */
    private PaymentTarget loadTarget(Long orderId, Long userId) {
        BizResult<PaymentTarget> loaded = handler.loadForPay(orderId, userId);
        assertTrue(loaded.isSuccess(), "载入应当成功");
        return loaded.getData();
    }

    // ==================================================================
    // 目标类型与归属
    // ==================================================================

    @Test
    @DisplayName("目标类型与前缀：PRODUCT / PD，回调靠它路由")
    void typeAndPrefix() {
        assertEquals(PaymentTargetType.PRODUCT, handler.type());
        assertEquals("PD", PaymentTargetType.PRODUCT.getOrderNoPrefix());
        assertEquals(PaymentTargetType.PRODUCT, PaymentTargetType.fromOrderNo("PD202609301200001234"),
                "回调只带得回一个商户订单号，认错前缀就会静默地找不到目标");
    }

    @Test
    @DisplayName("商品的消费记进 order_paid，与房间使用费同类")
    void paidCategoryIsOrder() {
        assertEquals(PaidCategory.ORDER, handler.paidCategory(),
                "记成 CARD 的话，商品消费会混进「月卡充值」那个口径，"
                        + "两个累计数字都错而不报任何错");
    }

    @Test
    @DisplayName("载入：不存在与不属于本人返回同一个码")
    void loadForPay_rejectsForeignOrder() {
        Product product = seedProduct(10);
        ProductOrder order = seedOrder(USER_ID, product, 1);

        assertEquals(ErrorCode.PRODUCT_ORDER_NOT_FOUND,
                handler.loadForPay(order.getId(), OTHER_USER_ID).getError(),
                "区分「不存在」与「不是你的」等于让人能靠错误码枚举出别人的单号");
        assertEquals(ErrorCode.PRODUCT_ORDER_NOT_FOUND,
                handler.loadForPay(9999L, USER_ID).getError());
    }

    @Test
    @DisplayName("载入：已支付的单不需要再支付")
    void loadForPay_rejectsPaidOrder() {
        Product product = seedProduct(10);
        ProductOrder order = seedOrder(USER_ID, product, 1);
        order.setStatus(ProductOrderStatus.PAID.name());

        assertEquals(ErrorCode.PRODUCT_STATUS_INVALID,
                handler.loadForPay(order.getId(), USER_ID).getError());
    }

    @Test
    @DisplayName("通道：商品受理全部通道，包括扫码转账")
    void supportsChannel_acceptsAll() {
        for (PaymentChannel channel : PaymentChannel.values()) {
            assertTrue(handler.supportsChannel(channel),
                    "2026-09-30 起四类收款都受理扫码转账 —— 付款凭证统一成 "
                            + "biz_payment_proof 之后，不再有「人工核销接口是订单专用的」"
                            + "那条限制，本处理器也就不需要覆写 supportsChannel 了");
        }
        assertTrue(handler.supportsChannel(null),
                "传 null 不抛异常即可（默认实现与通道无关，直接放行；"
                        + "真实调用路径上 null 在更早一步就被挡掉了）");
    }

    // ==================================================================
    // 付款成功：改状态 + 扣库存
    // ==================================================================

    @Test
    @DisplayName("付款成功：单子转已支付，库存按数量扣减")
    void markPaid_deductsStock() {
        Product product = seedProduct(10);
        ProductOrder order = seedOrder(USER_ID, product, 3);
        PaymentTarget target = loadTarget(order.getId(), USER_ID);

        LocalDateTime paidAt = LocalDateTime.now();
        assertTrue(handler.markPaid(target, PaymentChannel.WXPAY_JSAPI, "4200001234", paidAt, null));

        ProductOrder saved = orderMapper.get(order.getId());
        assertEquals(ProductOrderStatus.PAID.name(), saved.getStatus());
        assertEquals("WXPAY_JSAPI", saved.getPaymentMethod());
        assertEquals("4200001234", saved.getPaymentNo());
        assertEquals(paidAt, saved.getPaidAt());

        assertEquals(7, productMapper.get(product.getId()).getStock(), "10 件卖了 3 件，还剩 7 件");
    }

    @Test
    @DisplayName("付款成功：金额取的是下单时的快照，改了价也不影响这一单")
    void markPaid_usesSnapshottedAmount() {
        Product product = seedProduct(10);
        ProductOrder order = seedOrder(USER_ID, product, 1);
        String outTradeNo = order.getOrderNo();
        // 发起支付之后管理员调了价
        product.setPrice(new BigDecimal("99.00"));

        PaymentTarget target = handler.loadByOutTradeNo(outTradeNo);

        assertEquals(0, new BigDecimal("2.00").compareTo(target.getAmount()),
                "用户看到的是下单时的价，付款过程中恰好调价也不该改变这一单的金额");
    }

    @Test
    @DisplayName("重复回调：第二次不再扣库存")
    void markPaid_isIdempotent() {
        Product product = seedProduct(10);
        ProductOrder order = seedOrder(USER_ID, product, 3);
        PaymentTarget target = loadTarget(order.getId(), USER_ID);

        assertTrue(handler.markPaid(target, PaymentChannel.WXPAY_JSAPI, "t1", LocalDateTime.now(), null));
        // 支付平台重推同一条通知
        assertFalse(handler.markPaid(target, PaymentChannel.WXPAY_JSAPI, "t1", LocalDateTime.now(), null),
                "状态守卫拿到 0 行受影响，说明这一笔已经被处理过");

        assertEquals(7, productMapper.get(product.getId()).getStock(),
                "用户付一次钱只能扣一次货 —— 多扣一次账面上看不出任何异常");
    }

    @Test
    @DisplayName("库存不足：不抛异常、不改状态，留 error 日志转人工处理")
    void markPaid_survivesStockShortage() {
        Product product = seedProduct(2);
        ProductOrder order = seedOrder(USER_ID, product, 3);
        PaymentTarget target = loadTarget(order.getId(), USER_ID);

        // 抛异常会让支付平台不断重推一笔永远处理不了的通知 ——
        // 这个分支在真实环境里必然会走到（两人同时买最后一件）
        assertTrue(handler.markPaid(target, PaymentChannel.WXPAY_JSAPI, "t1", LocalDateTime.now(), null),
                "钱已经收了，这单不能因为没货就从账上消失");

        assertEquals(ProductOrderStatus.PAID.name(), orderMapper.get(order.getId()).getStatus(),
                "状态仍要转已支付 —— 退款或补货是人的决定，不是代码的");
        assertEquals(2, productMapper.get(product.getId()).getStock(),
                "扣不动就一件都不扣，绝不能扣成负数");
    }

    @Test
    @DisplayName("商品已被删除：同样不抛异常")
    void markPaid_survivesDeletedProduct() {
        Product product = seedProduct(10);
        ProductOrder order = seedOrder(USER_ID, product, 1);
        PaymentTarget target = loadTarget(order.getId(), USER_ID);
        product.setDeleted(1);

        assertTrue(handler.markPaid(target, PaymentChannel.ALIPAY_WAP, "t1", LocalDateTime.now(), null));

        assertEquals(ProductOrderStatus.PAID.name(), orderMapper.get(order.getId()).getStatus(),
                "商品没了是运营的问题，不能把已经收到的钱卡在待支付状态");
    }

    @Test
    @DisplayName("按单号载入：不存在时返回 null 而不是抛异常")
    void loadByOutTradeNo_returnsNullForUnknown() {
        assertNull(handler.loadByOutTradeNo("PD000000000000000000"));
        assertNotNull(handler.loadByOutTradeNo(
                seedOrder(USER_ID, seedProduct(1), 1).getOrderNo()));
    }
}
