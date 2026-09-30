package com.kaede.uspace.product;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.product.dto.ProductVo;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品陈列接口（用户端）。
 *
 * <p>只有两个只读接口：看列表、看详情。买走 {@code POST /api/product-orders}，
 * 付款走模块 8 的统一支付入口 —— 与本系统的其他收款一样，
 * 「下单」与「付款」是两步。
 *
 * <p><b>要登录才能看</b>（没有加进 {@code SecurityConfig.PUBLIC_PATHS}）：
 * 与门店名、公告、机台陈列不同，商品是店内售卖的实物，
 * 价格与库存属于经营信息。这与「在店名册需要登录」是同一条边界。
 */
@RestController
@RequestMapping("/api/products")
@Validated
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * 列出全部上架商品。
     *
     * <p>卡片式展示用：封面、名称、价格、是否售罄。已售罄的也在列表里
     * （带上 {@code soldOut = true}）—— 直接消失会让顾客以为这东西下架了。
     *
     * @return 商品列表，按排序值与 ID 升序
     */
    @GetMapping
    public ResponseEntity<ApiResult<List<ProductVo>>> list() {
        return ApiResult.of(productService.listOnSale());
    }

    /**
     * 查一件商品的详情。
     *
     * <p>已下架的商品同样能查到（{@code enabled = false}）——
     * 用户的订单里可能还指着它。下架只影响能不能买，不影响能不能看。
     *
     * @param id 商品 ID
     * @return 商品详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResult<ProductVo>> detail(@PathVariable Long id) {
        return ApiResult.of(productService.detail(id));
    }
}
