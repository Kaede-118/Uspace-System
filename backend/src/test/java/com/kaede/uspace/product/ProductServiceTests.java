package com.kaede.uspace.product;

import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.trade.TradeSource;
import com.kaede.uspace.product.event.ProductOrderCancelledEvent;
import com.kaede.uspace.product.dto.CreateProductOrderRequest;
import com.kaede.uspace.product.dto.ProductOrderVo;
import com.kaede.uspace.product.dto.ProductSaveRequest;
import com.kaede.uspace.product.dto.ProductVo;
import com.kaede.uspace.product.entity.Product;
import com.kaede.uspace.product.entity.ProductOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProductService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring、不连数据库。</b>两张表的数据访问由
 * {@link FakeProductMapper} 与 {@link FakeProductOrderMapper} 顶替。
 *
 * <p>覆盖重点全在「写错了不报错、只会静默算错」的地方：
 * <ul>
 *   <li><b>可售量的口径</b> —— 库存减去「未支付的待支付单数」。
 *       少减了会让两人同时买最后一件都下单成功；多减了（比如把超时的单也算上）
 *       会让一件商品被一笔无人认领的旧单永久占住，而界面上看不出任何异常</li>
 *   <li><b>超时的待支付单不再占库存</b> —— 商品单不会像月卡那样被自动关闭，
 *       所以这条过滤是「旧单不再挡路」的唯一出口</li>
 *   <li><b>价格与名称的快照</b> —— 商品调价改名之后，历史订单必须还是当时的数字。
 *       这一条错了，用户会看到一个自己从没同意过的金额</li>
 *   <li><b>{@code enabled} 是全量替换语义的唯一例外</b> —— 传 null 时保持原值。
 *       改了这条，管理员改个商品名就会顺手把商品下架，而他毫无察觉</li>
 *   <li><b>下单互斥</b> —— 一笔没了结（待支付 / 凭证未通过）就不给下新的，
 *       而超时的旧单照样挡着。这一条松了，用户能挂起一串没人管的待支付单</li>
 * </ul>
 *
 * <p>另有一条钉住下单接口的价格<b>只认服务端</b>：
 * 前端传不了价格，改请求体也换不来一件 1 分钱的商品。
 */
class ProductServiceTests {

    private static final Long USER_ID = 1001L;
    private static final Long OTHER_USER_ID = 1002L;

    private final FakeProductMapper productMapper = new FakeProductMapper();
    private final FakeProductOrderMapper orderMapper = new FakeProductOrderMapper();
    private final ProductProperties properties = new ProductProperties();

    /** 被测服务，每个用例前重建 */
    private ProductService service;

    /** 收集被测代码发布的事件（取消购买单时发一条，由 order 包记进交易流水） */
    private final List<Object> publishedEvents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        publishedEvents.clear();
        service = new ProductService(productMapper.asMapper(), orderMapper.asMapper(),
                properties, publishedEvents::add);
    }

    // ==================================================================
    // 造数据的小工具
    // ==================================================================

    /**
     * 造一件上架商品并放进假表。
     *
     * @param name  名称
     * @param price 售价（元）
     * @param stock 库存
     * @return 带自增 ID 的商品
     */
    private Product seedProduct(String name, String price, int stock) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
        product.setEnabled(1);
        product.setSortNo(0);
        return productMapper.seed(product);
    }

    /**
     * 造一笔待支付单并放进假表。
     *
     * @param userId    下单人
     * @param productId 商品 ID
     * @param quantity  数量
     * @param createdAt 下单时刻，用来构造「已超时」的单据
     * @return 带自增 ID 的购买单
     */
    private ProductOrder seedPendingOrder(Long userId, Long productId, int quantity,
                                          LocalDateTime createdAt) {
        ProductOrder order = new ProductOrder();
        order.setOrderNo(ProductNo.generate());
        order.setUserId(userId);
        order.setProductId(productId);
        order.setProductName("占位商品");
        order.setUnitPrice(new BigDecimal("2.00"));
        order.setQuantity(quantity);
        order.setAmount(new BigDecimal("2.00").multiply(BigDecimal.valueOf(quantity)));
        order.setStatus(ProductOrderStatus.PENDING_PAYMENT.name());
        order.setCreatedAt(createdAt);
        return orderMapper.seed(order);
    }

    /**
     * 构造一个下单请求。
     *
     * @param productId 商品 ID
     * @param quantity  数量
     * @return 请求对象
     */
    private static CreateProductOrderRequest request(Long productId, int quantity) {
        CreateProductOrderRequest request = new CreateProductOrderRequest();
        request.setProductId(productId);
        request.setQuantity(quantity);
        return request;
    }

    // ==================================================================
    // 下单
    // ==================================================================

    @Test
    @DisplayName("下单：落一条待支付单，金额 = 单价 × 数量，此时库存未扣")
    void createOrder_snapshotsPriceAndKeepsStock() {
        Product product = seedProduct("矿泉水", "2.00", 10);

        BizResult<ProductOrderVo> result = service.createOrder(USER_ID, request(product.getId(), 3));

        assertTrue(result.isSuccess(), "有货就该下单成功");
        ProductOrderVo vo = result.getData();
        assertNotNull(vo.getOrderNo(), "要返回单号 —— 付款时它充当商户订单号");
        assertTrue(vo.getOrderNo().startsWith(ProductNo.PREFIX),
                "单号前缀必须对，它是支付回调路由的依据");
        assertEquals("2.00", vo.getUnitPrice().toPlainString());
        assertEquals(3, vo.getQuantity());
        assertEquals("6.00", vo.getAmount().toPlainString(), "金额用 BigDecimal 算，不能有浮点误差");
        assertEquals(ProductOrderStatus.PENDING_PAYMENT.name(), vo.getStatus());

        assertEquals(10, productMapper.get(product.getId()).getStock(),
                "库存是【支付成功时】才扣的，下单这一刻不能动它");
    }

    @Test
    @DisplayName("下单：库存为 0 时拒绝，且不留下任何单据")
    void createOrder_rejectsSoldOut() {
        Product product = seedProduct("已售罄", "15.00", 0);

        BizResult<ProductOrderVo> result = service.createOrder(USER_ID, request(product.getId(), 1));

        assertEquals(ErrorCode.PRODUCT_SOLD_OUT, result.getError());
        assertEquals(0, orderMapper.size(), "被拒绝的请求不该留下购买单");
    }

    @Test
    @DisplayName("下单：数量超过可售量时拒绝，提示里带上剩余件数")
    void createOrder_rejectsWhenQuantityExceedsAvailable() {
        Product product = seedProduct("能量饮料", "6.50", 2);

        BizResult<ProductOrderVo> result = service.createOrder(USER_ID, request(product.getId(), 3));

        assertEquals(ErrorCode.PRODUCT_SOLD_OUT, result.getError());
        assertTrue(result.resolveMessage().contains("2"),
                "要告诉用户还剩几件 —— 只说「售罄」的话他会以为一件都没有了");
        assertEquals(0, orderMapper.size());
    }

    @Test
    @DisplayName("下单：别人未支付的单子也算占着库存")
    void createOrder_countsOthersPendingOrders() {
        Product product = seedProduct("能量饮料", "6.50", 2);
        // 别人下了一单还没付款，占掉 1 件
        seedPendingOrder(OTHER_USER_ID, product.getId(), 1, LocalDateTime.now());

        // 表面上库存还有 2 件，但可售量只有 1 件
        BizResult<ProductOrderVo> one = service.createOrder(USER_ID, request(product.getId(), 1));
        assertTrue(one.isSuccess(), "可售量还剩 1 件，买 1 件应当成功");

        BizResult<ProductOrderVo> two = service.createOrder(USER_ID, request(product.getId(), 2));
        assertEquals(ErrorCode.PRODUCT_SOLD_OUT, two.getError(),
                "守住「两人同时买最后一件都能成功」那道时间差");
    }

    @Test
    @DisplayName("占用按件数算：一笔买 5 件的单占 5 件，不是 1 件")
    void pendingQuantity_isSummedNotCounted() {
        // ⚠️ 这条用例钉住一个「写错了不报错」的地方：统计占用量若用
        // COUNT(*) 而不是 SUM(quantity)，一笔买 5 件的单只会占掉 1 件，
        // 可售量因此虚高 4 件 —— 表现为「明明下单成功了，付款后却说没货」。
        // 它是在写这个模块时被一条更宽的用例顺带发现的，留一条专测守着
        Product product = seedProduct("能量饮料", "6.50", 10);
        seedPendingOrder(OTHER_USER_ID, product.getId(), 5, LocalDateTime.now());

        ProductVo vo = service.listOnSale().getData().get(0);

        assertEquals(10, vo.getStock());
        assertEquals(5, vo.getAvailableStock(), "10 件被占掉 5 件，还剩 5 件");
    }

    @Test
    @DisplayName("下单：超时的待支付单不再占库存")
    void createOrder_ignoresTimedOutPendingOrders() {
        Product product = seedProduct("限量周边", "15.00", 1);
        // 40 分钟前的未付单 —— 超过默认的 30 分钟存活时长。
        // 商品单不像月卡那样会被自动关闭，所以这条过滤是「旧单不再挡路」的唯一出口
        LocalDateTime longAgo = LocalDateTime.now().minusMinutes(40);
        seedPendingOrder(OTHER_USER_ID, product.getId(), 1, longAgo);

        BizResult<ProductOrderVo> result = service.createOrder(USER_ID, request(product.getId(), 1));

        assertTrue(result.isSuccess(),
                "超时的单子不再占位 —— 否则一笔无人认领的旧单会把商品永久占住，"
                        + "而且界面上看不出任何异常");
    }

    // ------------------------------------------------------------------
    // 下单互斥（2026-10-10 加）：一笔没了结就不给下新的
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⚠️ 守门：名下有未付款的商品单时不给下新的，提示里带单号")
    void createOrder_rejectsWhenUnpaidOrderExists() {
        Product product = seedProduct("可乐", "3.50", 10);
        ProductOrder unpaid = seedPendingOrder(USER_ID, product.getId(), 1, LocalDateTime.now());

        BizResult<ProductOrderVo> result = service.createOrder(USER_ID, request(product.getId(), 1));

        assertEquals(ErrorCode.PRODUCT_UNPAID_EXISTS, result.getError());
        assertTrue(result.resolveMessage().contains(unpaid.getOrderNo()),
                "单号要报出来 —— 用户得照着它去付款或取消：" + result.resolveMessage());
        assertEquals(1, orderMapper.size(), "被拒绝的请求不该留下第二条购买单");
    }

    @Test
    @DisplayName("守门：凭证被驳回的商品单同样挡着，给的是「重新上传」的码")
    void createOrder_rejectsWhenProofRejected() {
        Product product = seedProduct("可乐", "3.50", 10);
        ProductOrder rejected = seedPendingOrder(USER_ID, product.getId(), 1, LocalDateTime.now());
        rejected.setStatus(ProductOrderStatus.REJECTED.name());

        BizResult<ProductOrderVo> result = service.createOrder(USER_ID, request(product.getId(), 1));

        assertEquals(ErrorCode.PRODUCT_PROOF_REJECTED, result.getError(),
                "「还没付钱」与「付了但凭证没通过」的处置动作不同 —— "
                        + "合并成一个码，用户看到「待支付」会再付一次钱");
    }

    @Test
    @DisplayName("守门：超时未付的旧单照样挡着（与「超时不占库存」是两个口径）")
    void createOrder_unpaidBlockIgnoresTimeout() {
        Product product = seedProduct("可乐", "3.50", 10);
        seedPendingOrder(USER_ID, product.getId(), 1, LocalDateTime.now().minusMinutes(40));

        BizResult<ProductOrderVo> result = service.createOrder(USER_ID, request(product.getId(), 1));

        assertEquals(ErrorCode.PRODUCT_UNPAID_EXISTS, result.getError(),
                "⚠️ 时间窗只影响「还占不占库存」—— 两天前没付的单子今天照样挡人，"
                        + "要么付掉、要么取消；否则「欠着费接着买」就从这道缝里漏过去");
    }

    @Test
    @DisplayName("守门：取消掉那笔未付款单之后就能再下单")
    void createOrder_allowedAfterCancel() {
        Product product = seedProduct("可乐", "3.50", 10);
        ProductOrder unpaid = seedPendingOrder(USER_ID, product.getId(), 1, LocalDateTime.now());

        assertEquals(ErrorCode.PRODUCT_UNPAID_EXISTS,
                service.createOrder(USER_ID, request(product.getId(), 1)).getError(),
                "先确认它确实挡着");

        service.cancelOrder(USER_ID, unpaid.getId(), TradeSource.WEB);
        BizResult<ProductOrderVo> after = service.createOrder(USER_ID, request(product.getId(), 1));

        assertTrue(after.isSuccess(), "取消之后路要通 —— 否则这道拦截就是个死胡同");
    }

    @Test
    @DisplayName("守门：别人的未付款单挡不住我（互斥只认本人）")
    void createOrder_otherUsersUnpaidOrderDoesNotBlock() {
        Product product = seedProduct("可乐", "3.50", 10);
        seedPendingOrder(OTHER_USER_ID, product.getId(), 1, LocalDateTime.now());

        BizResult<ProductOrderVo> result = service.createOrder(USER_ID, request(product.getId(), 1));

        assertTrue(result.isSuccess(),
                "挡的是「自己欠着又买新的」，不是替别人挡 —— "
                        + "按商品全局挡的话，一个人不付款全店都买不了");
    }

    @Test
    @DisplayName("取消：发一条「购买单被取消」事件 —— 交易流水靠它留档")
    void cancelOrder_publishesCancelledEvent() {
        Product product = seedProduct("可乐", "3.50", 10);
        ProductOrder order = seedPendingOrder(USER_ID, product.getId(), 2, LocalDateTime.now());

        service.cancelOrder(USER_ID, order.getId(), TradeSource.WEB);

        assertEquals(1, publishedEvents.size(), "成功取消要恰好发一条事件，不能多也不能少");
        ProductOrderCancelledEvent event = (ProductOrderCancelledEvent) publishedEvents.get(0);
        assertEquals(order.getOrderNo(), event.orderNo(),
                "流水靠单号与业务单据对齐 —— 记错了这个字段，那笔账就找不回原单");
        assertEquals(USER_ID, event.userId());
        assertEquals(0, order.getAmount().compareTo(event.amount()), "金额要原样带出去");
        assertEquals(TradeSource.WEB, event.source(), "网页端取消要记成 WEB");
    }

    @Test
    @DisplayName("下单：商品已下架时拒绝")
    void createOrder_rejectsDisabledProduct() {
        Product product = seedProduct("下架货", "9.90", 10);
        product.setEnabled(0);

        BizResult<ProductOrderVo> result = service.createOrder(USER_ID, request(product.getId(), 1));

        assertEquals(ErrorCode.PRODUCT_STATUS_INVALID, result.getError(),
                "下架是「暂时不卖」，与不存在要分开 —— 用户该做的是换个东西买");
        assertEquals(0, orderMapper.size());
    }

    @Test
    @DisplayName("下单：商品不存在")
    void createOrder_rejectsUnknownProduct() {
        BizResult<ProductOrderVo> result = service.createOrder(USER_ID, request(9999L, 1));

        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, result.getError());
    }

    // ==================================================================
    // 陈列与详情
    // ==================================================================

    @Test
    @DisplayName("列表：不含下架商品，但含已售罄的")
    void listOnSale_excludesDisabledButKeepsSoldOut() {
        seedProduct("在售", "2.00", 10);
        seedProduct("已售罄", "15.00", 0);
        Product disabled = seedProduct("下架了", "5.00", 10);
        disabled.setEnabled(0);

        BizResult<List<ProductVo>> result = service.listOnSale();

        List<String> names = result.getData().stream().map(ProductVo::getName).toList();
        assertEquals(List.of("在售", "已售罄"), names,
                "下架的要从列表里消失；售罄的要留着 —— 直接消失会让顾客以为这东西不卖了");
        assertTrue(result.getData().get(1).getSoldOut(), "售罄的要带上标记，前端据此把卡片置灰");
        assertFalse(result.getData().get(0).getSoldOut());
    }

    @Test
    @DisplayName("列表：可售量扣掉未支付的占用，与下单校验同一个口径")
    void listOnSale_availableStockMatchesOrderCheck() {
        Product product = seedProduct("能量饮料", "6.50", 3);
        seedPendingOrder(OTHER_USER_ID, product.getId(), 2, LocalDateTime.now());

        ProductVo vo = service.listOnSale().getData().get(0);

        assertEquals(3, vo.getStock(), "实际库存照实给");
        assertEquals(1, vo.getAvailableStock(), "可售量要扣掉别人占着的 2 件");
        assertFalse(vo.getSoldOut(), "还剩 1 件，不算售罄");

        // 口径一致的验证：说剩 1 件，就真的只能买 1 件
        assertTrue(service.createOrder(USER_ID, request(product.getId(), 1)).isSuccess());
        assertEquals(ErrorCode.PRODUCT_SOLD_OUT,
                service.createOrder(USER_ID, request(product.getId(), 1)).getError(),
                "页面说还剩几件，下单就得能买几件 —— 两处口径不一致会让人以为系统坏了");
    }

    @Test
    @DisplayName("详情：已下架的商品仍能查到")
    void detail_returnsDisabledProduct() {
        Product product = seedProduct("下架了", "5.00", 10);
        product.setEnabled(0);

        BizResult<ProductVo> result = service.detail(product.getId());

        assertTrue(result.isSuccess(),
                "用户的订单里可能还指着它，点进去看不了会很莫名其妙");
        assertFalse(result.getData().getEnabled());
    }

    @Test
    @DisplayName("详情：商品不存在")
    void detail_rejectsUnknownProduct() {
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, service.detail(9999L).getError());
    }

    // ==================================================================
    // 取消
    // ==================================================================

    @Test
    @DisplayName("取消：待支付单转已关闭，释放它占着的可售量")
    void cancelOrder_releasesAvailability() {
        Product product = seedProduct("限量周边", "15.00", 1);
        ProductOrder order = seedPendingOrder(USER_ID, product.getId(), 1, LocalDateTime.now());
        assertEquals(ErrorCode.PRODUCT_SOLD_OUT,
                service.createOrder(OTHER_USER_ID, request(product.getId(), 1)).getError(),
                "先确认这一件确实被占着");

        assertTrue(service.cancelOrder(USER_ID, order.getId(), TradeSource.WEB).isSuccess());

        assertEquals(ProductOrderStatus.CLOSED.name(), orderMapper.get(order.getId()).getStatus());
        assertTrue(service.createOrder(OTHER_USER_ID, request(product.getId(), 1)).isSuccess(),
                "取消之后可售量要还回来 —— 这正是「取消」这个动作的意义");
    }

    @Test
    @DisplayName("取消：已支付的单不能取消")
    void cancelOrder_rejectsPaidOrder() {
        Product product = seedProduct("矿泉水", "2.00", 10);
        ProductOrder order = seedPendingOrder(USER_ID, product.getId(), 1, LocalDateTime.now());
        order.setStatus(ProductOrderStatus.PAID.name());

        BizResult<Void> result = service.cancelOrder(USER_ID, order.getId(), TradeSource.WEB);

        assertEquals(ErrorCode.PRODUCT_STATUS_INVALID, result.getError(),
                "关掉一笔付过款的单会造成「单子关闭、库存少了」的不一致");
        assertEquals(ProductOrderStatus.PAID.name(), orderMapper.get(order.getId()).getStatus(),
                "被拒绝的取消不该改动任何状态");
    }

    @Test
    @DisplayName("取消：别人的单按「不存在」处理，不用 403")
    void cancelOrder_treatsOthersOrderAsNotFound() {
        Product product = seedProduct("矿泉水", "2.00", 10);
        ProductOrder order = seedPendingOrder(USER_ID, product.getId(), 1, LocalDateTime.now());

        BizResult<Void> result = service.cancelOrder(OTHER_USER_ID, order.getId(), TradeSource.WEB);

        assertEquals(ErrorCode.PRODUCT_ORDER_NOT_FOUND, result.getError(),
                "403 等于承认「这个单子存在，只是不归你」，可以被用来枚举单号");
        assertEquals(ProductOrderStatus.PENDING_PAYMENT.name(),
                orderMapper.get(order.getId()).getStatus());
    }

    // ==================================================================
    // 我的订单
    // ==================================================================

    @Test
    @DisplayName("我的订单：只返回自己的，且按最新在前")
    void myOrders_returnsOnlyOwnOrders() {
        Product product = seedProduct("矿泉水", "2.00", 100);
        ProductOrder mine = seedPendingOrder(USER_ID, product.getId(), 1, LocalDateTime.now());
        seedPendingOrder(OTHER_USER_ID, product.getId(), 2, LocalDateTime.now());

        BizResult<PageResult<ProductOrderVo>> result =
                service.myOrders(USER_ID, 1, 10, null);

        assertEquals(1, result.getData().getTotal(), "别人的单不该出现在我的列表里");
        assertEquals(mine.getOrderNo(), result.getData().getRecords().get(0).getOrderNo());
    }

    @Test
    @DisplayName("我的订单：状态筛选传了非法值时返回参数错误，而不是静默返回全部")
    void myOrders_rejectsInvalidStatus() {
        BizResult<PageResult<ProductOrderVo>> result =
                service.myOrders(USER_ID, 1, 10, "NOT_A_STATUS");

        assertEquals(ErrorCode.PARAM_INVALID, result.getError(),
                "静默忽略非法筛选值会让前端以为自己筛对了，实际问题被藏起来");
    }

    // ==================================================================
    // 后台维护
    // ==================================================================

    @Test
    @DisplayName("后台改商品：不传 enabled 时保持原值")
    void update_keepsEnabledWhenNotProvided() {
        Product product = seedProduct("在售", "2.00", 10);
        product.setEnabled(0);

        ProductSaveRequest request = new ProductSaveRequest();
        request.setName("改了个名字");
        request.setPrice(new BigDecimal("3.00"));
        request.setStock(20);
        // enabled 不传

        assertTrue(service.update(product.getId(), request).isSuccess());

        assertEquals(0, productMapper.get(product.getId()).getEnabled(),
                "全量替换语义的唯一例外：改个名字顺手把商品下架了、管理员却毫无察觉，"
                        + "比「多写一个字段」严重得多");
        assertEquals("改了个名字", productMapper.get(product.getId()).getName());
        assertEquals(20, productMapper.get(product.getId()).getStock());
    }

    @Test
    @DisplayName("后台改商品：传了 enabled 就按传的来")
    void update_appliesEnabledWhenProvided() {
        Product product = seedProduct("在售", "2.00", 10);

        ProductSaveRequest request = new ProductSaveRequest();
        request.setName("在售");
        request.setPrice(new BigDecimal("2.00"));
        request.setStock(10);
        request.setEnabled(0);

        service.update(product.getId(), request);

        assertEquals(0, productMapper.get(product.getId()).getEnabled());
    }

    @Test
    @DisplayName("后台改商品：没传的可选字段按清空处理（全量替换）")
    void update_clearsOptionalFieldsWhenMissing() {
        Product product = seedProduct("在售", "2.00", 10);
        product.setCover("/uploads/product/a.jpg");
        product.setDescription("原来的描述");

        ProductSaveRequest request = new ProductSaveRequest();
        request.setName("在售");
        request.setPrice(new BigDecimal("2.00"));
        request.setStock(10);

        service.update(product.getId(), request);

        assertNull(productMapper.get(product.getId()).getCover(),
                "PUT 是全量替换：没传就是清掉，而不是保持原值");
        assertNull(productMapper.get(product.getId()).getDescription());
    }

    @Test
    @DisplayName("后台改商品：封面传空串也按清空处理（「移除封面」按钮走的就是这条）")
    void update_withEmptyCover_clearsIt() {
        Product product = seedProduct("在售", "2.00", 10);
        product.setCover("/uploads/product/a.jpg");

        ProductSaveRequest request = new ProductSaveRequest();
        request.setName("在售");
        request.setPrice(new BigDecimal("2.00"));
        request.setStock(10);
        // 后台的「移除封面」就是把这个字段置空，提交上来正是这个形态
        request.setCover("");

        service.update(product.getId(), request);

        // 空串必须被归一成 null：留一个空字符串在库里的话，它既不是「有封面」
        // 也不是「没封面」，展示与查询两边都得为它写特例
        assertNull(productMapper.get(product.getId()).getCover(),
                "空串应当被归一为 null，而不是原样存进去");
    }

    // ==================================================================
    // 调整库存（后台「只改库存」接口与群里的库存指令共用这一条）
    // ==================================================================

    @Test
    @DisplayName("改库存：传的是新的库存值，不是增减量")
    void updateStock_setsValueInsteadOfAdding() {
        Product cola = seedProduct("可乐", "3.50", 20);

        BizResult<ProductVo> result = service.updateStock(cola.getId(), 5);

        assertTrue(result.isSuccess(), result.resolveMessage());
        assertEquals(5, productMapper.get(cola.getId()).getStock(),
                "⚠️ 传的是【新的库存值】——写成按量扣减的话，「补货到 5」会变成「再进 5 件」，"
                        + "而两者在库存数上只差一点，很难被发现");
        assertEquals(5, result.getData().getStock(), "返回值里也要是新值");
        assertEquals(5, result.getData().getAvailableStock(), "没有待支付单时，可售量就是库存");
    }

    @Test
    @DisplayName("改库存：改成同一个数仍算成功（SET 语义的指纹）")
    void updateStock_sameValueStillSucceeds() {
        Product cola = seedProduct("可乐", "3.50", 5);

        assertTrue(service.updateStock(cola.getId(), 5).isSuccess(),
                "⚠️ 这条钉住「匹配行数」语义：若把条件写成 stock <> ?（或驱动改成 useAffectedRows），"
                        + "「改成同一个数」会被误报成「商品不存在」");
    }

    @Test
    @DisplayName("改库存：可售量扣掉未付款订单占用的部分")
    void updateStock_returnsAvailabilityAfterPendingOrders() {
        Product cola = seedProduct("可乐", "3.50", 1);
        seedPendingOrder(USER_ID, cola.getId(), 2, LocalDateTime.now());

        BizResult<ProductVo> result = service.updateStock(cola.getId(), 10);

        assertEquals(10, result.getData().getStock());
        assertEquals(8, result.getData().getAvailableStock(),
                "可售量 = 库存 − 待支付单占用 —— 与列表、下单校验同一口径");
    }

    @Test
    @DisplayName("改库存：负数被挡下且不动数据（群里绕过 Bean Validation）")
    void updateStock_rejectsNegative() {
        Product cola = seedProduct("可乐", "3.50", 5);

        assertEquals(ErrorCode.PARAM_INVALID, service.updateStock(cola.getId(), -1).getError(),
                "群里的指令直接调本方法，@Min(0) 管不到它");
        assertEquals(5, productMapper.get(cola.getId()).getStock(), "被拒时一个数都不该动");
    }

    @Test
    @DisplayName("改库存：商品不存在时回 NOT_FOUND")
    void updateStock_rejectsUnknownProduct() {
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, service.updateStock(999L, 3).getError());
    }

    @Test
    @DisplayName("后台新增：不传 enabled 时默认上架，不传 sortNo 按 0")
    void create_defaultsEnabledAndSortNo() {
        ProductSaveRequest request = new ProductSaveRequest();
        request.setName("新品");
        request.setPrice(new BigDecimal("9.90"));
        request.setStock(5);

        BizResult<ProductVo> result = service.create(request);

        assertTrue(result.isSuccess());
        Product saved = productMapper.get(result.getData().getId());
        assertEquals(1, saved.getEnabled(), "新商品默认上架");
        assertEquals(0, saved.getSortNo());
    }

    @Test
    @DisplayName("后台列表：含已下架商品，且可按状态筛选")
    void listAll_includesDisabled() {
        seedProduct("在售", "2.00", 10);
        Product disabled = seedProduct("下架了", "5.00", 10);
        disabled.setEnabled(0);

        assertEquals(2, service.listAll(1, 10, null, null, false).getData().getTotal(),
                "后台要能看到下架的商品，否则没法重新上架");
        assertEquals(1, service.listAll(1, 10, null, 1, false).getData().getTotal(), "只看上架的");
        assertEquals(1, service.listAll(1, 10, null, 0, false).getData().getTotal(), "只看下架的");
    }

    @Test
    @DisplayName("后台列表：按库存从少到多排，补货优先")
    void listAll_sortsByStockAscending() {
        seedProduct("库存多", "2.00", 90);
        seedProduct("库存少", "2.00", 3);
        seedProduct("库存中等", "2.00", 20);

        List<String> names = service.listAll(1, 10, null, null, true).getData()
                .getRecords().stream().map(ProductVo::getName).toList();

        assertEquals(List.of("库存少", "库存中等", "库存多"), names,
                "库存少的排在前面 —— 这条排序必须做在 SQL 里，前端排只能排当前页");
    }

    @Test
    @DisplayName("后台删除：商品从两个列表里都消失，但历史订单不受影响")
    void delete_hidesProductButKeepsOrderSnapshot() {
        Product product = seedProduct("矿泉水", "2.00", 10);
        ProductOrder order = seedPendingOrder(USER_ID, product.getId(), 2, LocalDateTime.now());

        assertTrue(service.delete(product.getId()).isSuccess());

        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, service.detail(product.getId()).getError());
        assertTrue(service.listOnSale().getData().isEmpty());
        assertEquals(0, service.listAll(1, 10, null, null, false).getData().getTotal());

        // 订单上存的是下单时的名称与价格快照，不依赖商品记录还在不在
        BizResult<PageResult<ProductOrderVo>> orders = service.myOrders(USER_ID, 1, 10, null);
        assertEquals(1, orders.getData().getTotal(), "历史订单不因商品被删而消失");
        assertEquals("占位商品", orders.getData().getRecords().get(0).getProductName());
    }

    @Test
    @DisplayName("后台删除：不存在的商品返回 404")
    void delete_rejectsUnknownProduct() {
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, service.delete(9999L).getError());
    }

    // ==================================================================
    // 下单数量：群里那条路绕过了 Bean Validation
    // ==================================================================

    @Test
    @DisplayName("⚠️ 数量范围由 Service 兜底 —— Bean Validation 只管得到 Controller 入参")
    void createOrder_validatesQuantityRange() {
        Product product = seedProduct("可乐", "3.50", 100);

        assertEquals(ErrorCode.PARAM_INVALID,
                service.createOrder(USER_ID, request(product.getId(), 0)).getError(),
                "0 件会建出一笔 0 元的单");
        assertEquals(ErrorCode.PARAM_INVALID,
                service.createOrder(USER_ID, request(product.getId(), 100)).getError(),
                "上限是 99，与网页端的 @Max 同一个数（两处共用一个常量）");
        assertEquals(ErrorCode.PARAM_INVALID,
                service.createOrder(USER_ID, request(product.getId(), -5)).getError(),
                "⚠️ 负数量会算出一笔【负金额】的订单，而且不报任何错");

        assertTrue(service.createOrder(USER_ID, request(product.getId(), 99)).isSuccess(),
                "上限之内照常下单");
    }

    // ==================================================================
    // 商品名唯一：群里的下单指令按名字找商品，全靠它才不会下错单
    // ==================================================================

    @Test
    @DisplayName("按名字查：上架的下架的都找得到，找不到返回 404")
    void findLiveByName_returnsEvenDisabled() {
        seedProduct("可乐 500ml", "3.50", 10);
        Product disabled = seedProduct("王老吉 500ml", "4.00", 10);
        disabled.setEnabled(0);

        assertEquals("可乐 500ml", service.findLiveByName("可乐 500ml").getData().getName());
        assertEquals("王老吉 500ml", service.findLiveByName("王老吉 500ml").getData().getName(),
                "⚠️ 下架的也要找得到 —— 调用方要能区分「没有这件商品」与「这件已下架」，"
                        + "前者指去 fw菜单，后者让他等上架，两句话不一样");
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, service.findLiveByName("不存在").getError());
    }

    @Test
    @DisplayName("按名字查：去掉首尾空白，但中间的空格一个字都不能少")
    void findLiveByName_trimsButKeepsInnerSpaces() {
        seedProduct("王老吉 250ml（绿）", "2.00", 10);

        assertTrue(service.findLiveByName("  王老吉 250ml（绿） ").isSuccess(),
                "从群里复制名字时容易带上首尾空格");
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND,
                service.findLiveByName("王老吉250ml（绿）").getError(),
                "⚠️ 中间那个空格不能忽略：一旦做模糊匹配，就要面对「同时匹配到两件」，"
                        + "而唯一键管不住模糊等价");
    }

    @Test
    @DisplayName("新增：同名被拒（靠它保证群里的名字不会指向两件商品）")
    void create_rejectsDuplicateName() {
        seedProduct("可乐", "3.50", 10);

        ProductSaveRequest request = new ProductSaveRequest();
        request.setName("可乐");
        request.setPrice(new BigDecimal("3.00"));
        request.setStock(5);

        assertEquals(ErrorCode.PRODUCT_NAME_EXISTS, service.create(request).getError());
    }

    @Test
    @DisplayName("⚠️ 已删除的商品仍占着名字 —— 查重口径必须与那条唯一键严格一致")
    void create_rejectsNameTakenByDeletedProduct() {
        Product deleted = seedProduct("可乐", "3.50", 10);
        service.delete(deleted.getId());

        ProductSaveRequest request = new ProductSaveRequest();
        request.setName("可乐");
        request.setPrice(new BigDecimal("3.00"));
        request.setStock(5);

        assertEquals(ErrorCode.PRODUCT_NAME_EXISTS, service.create(request).getError(),
                "数据库那条 uk_name 不含 deleted，已删的「可乐」仍然占着这个名字。"
                        + "查重这边若顺手筛掉 deleted（照 DeviceMapper 写就会这样），"
                        + "接口会说「名字可用」而保存时撞唯一键，用户拿到一句 500");
    }

    @Test
    @DisplayName("修改：改成别人已用的名字被拒；不改名时不能把自己挡住")
    void update_rejectsDuplicateNameButIgnoresSelf() {
        seedProduct("可乐", "3.50", 10);
        Product water = seedProduct("矿泉水", "2.00", 10);

        ProductSaveRequest rename = new ProductSaveRequest();
        rename.setName("可乐");
        rename.setPrice(new BigDecimal("2.00"));
        rename.setStock(10);
        assertEquals(ErrorCode.PRODUCT_NAME_EXISTS,
                service.update(water.getId(), rename).getError());

        ProductSaveRequest keepName = new ProductSaveRequest();
        keepName.setName("矿泉水");
        keepName.setPrice(new BigDecimal("2.50"));
        keepName.setStock(10);
        assertTrue(service.update(water.getId(), keepName).isSuccess(),
                "⚠️ 不排开自己的话，每一次普通的保存都会被自己挡住");
    }
}
