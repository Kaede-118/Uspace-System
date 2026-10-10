package com.kaede.uspace.product;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.common.trade.TradeSource;
import com.kaede.uspace.product.dto.CreateProductOrderRequest;
import com.kaede.uspace.product.dto.ProductOrderVo;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品购买单接口（用户端）。
 *
 * <p><b>这里不提供付款接口</b>：购买单创建出来后，付款走模块 8 的统一入口
 * {@code POST /api/payments}（{@code targetType = PRODUCT}，
 * {@code targetId} 传购买单 ID），微信 / 支付宝三条通道与回调全部复用。
 * 于是「加一类收款」只多了一个处理器实现，支付链路一行未改。
 *
 * <pre>
 *   POST /api/product-orders        →  拿到购买单与单号
 *   POST /api/payments              →  拿到各通道的支付参数
 *   POST /api/payments/mock/pay     →  （模拟模式）完成付款
 *   GET  /api/product-orders/me     →  单子变成已支付，商品库存已扣
 * </pre>
 *
 * <p>路径与 {@code /api/products} 分开：那个是「卖什么」（目录，只读），
 * 这个是「买过什么」（交易）。两者权限相同，但变化的频率与时机完全不同 ——
 * 合成一个 {@code @RequestMapping} 只会让路径失去意义。
 */
@RestController
@RequestMapping("/api/product-orders")
@Validated
public class ProductOrderController {

    private final ProductService productService;

    public ProductOrderController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * 下单买商品。
     *
     * <p>创建一笔待支付的购买单，<b>此时库存还没有扣</b> —— 付款成功后才会扣。
     * 售罄或商品已下架时会被拒绝。
     *
     * @param request 商品 ID 与数量
     * @param me      当前登录用户
     * @return 购买单（含单号与应付金额）
     */
    @PostMapping
    public ResponseEntity<ApiResult<ProductOrderVo>> create(
            @Valid @RequestBody CreateProductOrderRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(productService.createOrder(me.id(), request));
    }

    /**
     * 查我的商品订单，分页。
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param status   状态筛选（PENDING_PAYMENT / PAID / CLOSED），可空
     * @param me       当前登录用户
     * @return 分页结果，最近的在前
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResult<PageResult<ProductOrderVo>>> myOrders(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(productService.myOrders(me.id(), pageNum, pageSize, status));
    }

    /**
     * 取消一笔待支付的购买单。
     *
     * <p><b>这不是退货</b> —— 那笔钱从来没付过，库存也从来没扣过。
     * 取消只是让单子不再占着可售量，用户可以立刻重新下单。
     *
     * @param id 购买单 ID
     * @param me 当前登录用户
     * @return 成功返回空数据
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResult<Void>> cancel(@PathVariable Long id,
                                                  @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(productService.cancelOrder(me.id(), id, TradeSource.WEB));
    }
}
