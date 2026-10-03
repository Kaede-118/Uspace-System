package com.kaede.uspace.order;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.order.dto.AdminPayQrVo;
import com.kaede.uspace.order.dto.PayQrImageVo;
import com.kaede.uspace.order.dto.PayQrSaveRequest;
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

import java.util.List;

/**
 * 收款码管理（模块 8 的支付能力，仅 ADMIN）。
 *
 * <p>收款码是 12 月投产时唯一的收款方式，所以这张表是运营必须能自助维护的 ——
 * 换了收款账号、被限额了、临时停用一张，都不该去找开发改数据库。
 *
 * <p><b>图片走独立的上传端点</b>（{@code /image}），与商品封面的
 * {@code /api/admin/products/cover} 是同一套语义：上传只落盘、只返回路径，
 * 随新增 / 修改一起提交。理由见 {@code PayQrImageService} 的类注释。
 *
 * <p><b>权限靠类上的 {@code @PreAuthorize}</b>，不在方法上逐个写 ——
 * 逐个写的话，将来新增一个方法忘了标，就是一个「谁都能删收款码」的缺口，
 * 而且不会有任何报错。这也是 {@code AdminOrderController} 的做法。
 */
@RestController
@RequestMapping("/api/admin/pay-qrs")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminPayQrController {

    private final PayQrService payQrService;
    private final PayQrImageService payQrImageService;

    /**
     * 构造器注入。
     *
     * @param payQrService      收款码的查询与维护
     * @param payQrImageService 收款码图片的上传
     */
    public AdminPayQrController(PayQrService payQrService,
                                PayQrImageService payQrImageService) {
        this.payQrService = payQrService;
        this.payQrImageService = payQrImageService;
    }

    /**
     * 列出全部收款码（含停用的）。
     *
     * <p><b>含停用的是一个刻意的选择</b>：只列启用中的话，管理员停用一张码之后
     * 就再也找不回它了，而「临时停用、过阵子再开」正是最常见的用法。
     *
     * @return 收款码列表，按 sort 升序
     */
    @GetMapping
    public ResponseEntity<ApiResult<List<AdminPayQrVo>>> list() {
        return ResponseEntity.ok(ApiResult.ok(payQrService.listAll()));
    }

    /**
     * 新增一张收款码。
     *
     * <p>新增出来默认是可用的（{@code enabled} 由请求体给），不像包场那样
     * 还有个「待付款才生效」的中间态 —— 收款码一旦配好就是立刻生效的。
     *
     * @param request 收款码内容
     * @return 新增后的收款码
     */
    @PostMapping
    public ResponseEntity<ApiResult<AdminPayQrVo>> create(@Valid @RequestBody PayQrSaveRequest request) {
        return ApiResult.of(payQrService.create(request));
    }

    /**
     * 修改一张收款码（全量替换）。
     *
     * <p>没传的字段就是清空，唯一的例外是 {@code sort}（不传按 0 处理）。
     * 详见 {@code PayQrSaveRequest} 的类注释。
     *
     * @param id      收款码 ID
     * @param request 新的内容
     * @return 修改后的收款码
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResult<AdminPayQrVo>> update(@PathVariable Long id,
                                                          @Valid @RequestBody PayQrSaveRequest request) {
        return ApiResult.of(payQrService.update(id, request));
    }

    /**
     * 删除一张收款码（逻辑删除）。
     *
     * @param id 收款码 ID
     * @return 空数据
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResult<Void>> delete(@PathVariable Long id) {
        return ApiResult.of(payQrService.delete(id));
    }

    /**
     * 上传收款码图片。
     *
     * <p><b>只返回路径，不写数据库</b>：管理员拿到 {@code imageUrl} 填进表单，
     * 随新增 / 修改一起提交。三条理由（新增时还没有 ID、表单可以中途取消、
     * 传错能反复换）见 {@code PayQrImageService}。
     *
     * <p>{@code required = false} 是刻意的：请求里没带 file 部分时，
     * 让它落到 Service 的校验里变成一个 400，而不是被 Spring 抛成异常。
     * 这条经验来自图片上传那一轮 —— 当时接口声明了
     * {@code consumes = MULTIPART_FORM_DATA_VALUE}，一个不带请求体的 POST
     * 在真实服务上返回 500，而 MockMvc 的 {@code multipart()} 构造器
     * 永远造出合法请求，集成测试因此是全绿的。
     *
     * @param file 上传的图片
     * @return 图片的站内路径
     */
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResult<PayQrImageVo>> uploadImage(
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return ApiResult.of(payQrImageService.upload(file));
    }
}
