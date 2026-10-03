package com.kaede.uspace.product;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.product.dto.ProductCoverVo;
import com.kaede.uspace.product.dto.ProductOrderVo;
import com.kaede.uspace.product.dto.ProductSaveRequest;
import com.kaede.uspace.product.dto.ProductVo;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 商品管理接口（运营后台）。
 *
 * <p>两件事：<b>维护商品</b>（增删改查）与<b>看订单</b>
 * （谁买了什么、付了没）。少了后者，后台的「商品」页就只是半个功能 ——
 * 管理员摆好了货却看不到任何一笔交易。
 *
 * <p>整个类的接口都要求管理员身份 —— 注解标在类上而不是逐个方法上，
 * 少写一个就是接口裸奔，而那种疏漏不会有任何报错。
 *
 * <p><b>没有核销接口</b>：无人值守店里没有店员，付了钱自己取。
 * 这是明知的取舍，见 {@code package-info}。
 */
@RestController
@RequestMapping("/api/admin/products")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminProductController {

    private final ProductService productService;

    private final ProductCoverService productCoverService;

    public AdminProductController(ProductService productService,
                                  ProductCoverService productCoverService) {
        this.productService = productService;
        this.productCoverService = productCoverService;
    }

    /**
     * 分页查询商品，含已下架的。
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param keyword  名称关键词，可空
     * @param enabled  上架状态筛选（1=上架 0=下架），可空
     * @param stockAsc 是否按「库存从少到多」排，默认否。
     *                 补货优先用的排序 —— <b>必须由后端排</b>：
     *                 结果是分页的，前端只能排当前这一页，
     *                 第二页可能藏着比本页更少的库存，而运营看的是「最上面那条最少」
     * @return 分页结果
     */
    @GetMapping
    public ResponseEntity<ApiResult<PageResult<ProductVo>>> list(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(defaultValue = "false") boolean stockAsc) {
        return ApiResult.of(productService.listAll(pageNum, pageSize, keyword, enabled, stockAsc));
    }

    /**
     * 新增一件商品。
     *
     * @param request 商品内容
     * @return 新建的商品
     */
    @PostMapping
    public ResponseEntity<ApiResult<ProductVo>> create(
            @Valid @RequestBody ProductSaveRequest request) {
        return ApiResult.of(productService.create(request));
    }

    /**
     * 修改一件商品。
     *
     * <p>全量替换语义，唯一例外是 {@code enabled}：不传则保持原值。
     * 调价不影响已售出的订单（那些单子上存的是下单时的价格快照）。
     *
     * @param id      商品 ID
     * @param request 商品内容
     * @return 修改后的商品
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResult<ProductVo>> update(
            @PathVariable Long id,
            @Valid @RequestBody ProductSaveRequest request) {
        return ApiResult.of(productService.update(id, request));
    }

    /**
     * 删除一件商品（逻辑删除）。
     *
     * <p>只是想「暂时不卖」的话改用 {@code PUT} 把 {@code enabled} 置 0 ——
     * 那样还能重新上架。删除之后商品从两个列表里双双消失。
     * 无论哪种，历史订单都不受影响。
     *
     * @param id 商品 ID
     * @return 成功返回空数据
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResult<Void>> delete(@PathVariable Long id) {
        return ApiResult.of(productService.delete(id));
    }

    /**
     * 上传一张商品封面图。
     *
     * <p><b>只返回路径，不写任何数据库</b> —— 前端把路径填进表单，
     * 随新增 / 修改商品一起提交。三条理由见 {@link ProductCoverService}。
     *
     * <p>⚠️ <b>本方法必须留在本类里</b>：{@code @PreAuthorize} 标在类上，
     * 一旦挪去别的 Controller（或写成 {@code /api/products/cover}），
     * 它就变成任何登录用户都能调 —— 而且不会有任何报错。
     *
     * <p>⚠️ {@code required = false} 是刻意的：声明为必填时 Spring 抛的
     * {@code MissingServletRequestPartException} 不在全局异常处理器名单里，
     * 会落到兜底分支返回 500 —— 而它明明是「你忘了传文件」，该给 400。
     *
     * <p>路径 {@code /cover} 与 {@code /{id}} 不冲突（Spring 精确匹配优先），
     * 与既有的 {@code /orders} 同一个模式。
     *
     * @param file 上传的图片，表单字段名固定为 {@code file}
     * @return 封面图的站内相对路径
     */
    @PostMapping(value = "/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResult<ProductCoverVo>> uploadCover(
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return ApiResult.of(productCoverService.uploadCover(file));
    }

    /**
     * 分页查询商品订单。
     *
     * <p>路径挂在 {@code /orders} 下而不是另开一个 {@code /api/admin/product-orders}：
     * 「商品的订单」属于商品这一块，后台的一个页面就能把两件事都办了。
     * 字面路径 {@code /orders} 与 {@code /{id}} 不会冲突 ——
     * Spring 的路径匹配优先精确匹配。
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param userId   购买人筛选，可空
     * @param status   状态筛选（PENDING_PAYMENT / PAID / CLOSED），可空
     * @return 分页结果，最近的在前
     */
    @GetMapping("/orders")
    public ResponseEntity<ApiResult<PageResult<ProductOrderVo>>> listOrders(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status) {
        return ApiResult.of(productService.listOrders(pageNum, pageSize, userId, status));
    }
}
