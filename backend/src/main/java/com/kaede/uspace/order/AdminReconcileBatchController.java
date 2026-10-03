package com.kaede.uspace.order;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.order.dto.BillFileVo;
import com.kaede.uspace.order.dto.ReconcileBatchVo;
import com.kaede.uspace.order.dto.ReconcileDiffVo;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * 后台对账批次接口（模块 8 的支付能力，仅 ADMIN）。
 *
 * <p>管理员在这里<b>上传收款账号导出的账单</b>，系统把它与系统内的付款凭证勾稽，
 * 输出差异。设计目标是把他从「逐条看 100%」降到「只看差异」——
 * {@code AdminPaymentProofController} 完成单笔的核对，本控制器完成一整天的对账。
 *
 * <p><b>一次上传就是一个批次，没有「先预览再确认」</b>：对账不改任何业务状态
 *（不碰订单、不碰 {@code verify_status}），误传一份已经对过的账单会走
 * 「已被认领」那一支、零差异。为一个小概率的误操作引入「待确认」中间态、
 * 临时文件清理、防重复确认，不值得。
 *
 * <p><b>不接收 {@code channel} 参数</b> —— 微信还是支付宝由表头认出来。
 * 让管理员多选一个东西就多一个选错的机会，而选错的后果是数据错且不会报错。
 *
 * <p><b>分页参数用 {@code page} + {@code size}</b>（月卡与商品那两个模块用的是
 * {@code pageNum} + {@code pageSize}）。传错<b>不会报错</b>，只会永远返回第一页。
 *
 * <p>权限靠类上的 {@code @PreAuthorize}，不在方法上逐个写。
 */
@RestController
@RequestMapping("/api/admin/reconcile-batches")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminReconcileBatchController {

    private final ReconcileService reconcileService;

    /**
     * 构造器注入。
     *
     * @param reconcileService 对账的编排
     */
    public AdminReconcileBatchController(ReconcileService reconcileService) {
        this.reconcileService = reconcileService;
    }

    /**
     * 上传账单并执行对账。
     *
     * <p>{@code file} 参数<b>刻意声明成非必需</b>：没带文件部分时让请求落到
     * Service 的校验上返回 400，而不是被 Spring 抛成 500 ——
     * 「没选文件」是用户操作，不是服务端故障（{@code AdminPayQrController} 踩过这个坑）。
     *
     * <p>响应里直接带上解析结果（账单多少笔、合计多少、区间是什么），
     * 管理员据此就能看出自己有没有传错文件 —— 这也是不做「预览」那一步的替代。
     *
     * @param file 账单文件（微信 xlsx / 支付宝 CSV / 标准模板 CSV）
     * @param me   当前登录的管理员
     * @return 新批次的视图
     */
    @PostMapping
    public ResponseEntity<ApiResult<ReconcileBatchVo>> upload(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(reconcileService.reconcile(file, me.id()));
    }

    /**
     * 分页查询对账批次。
     *
     * <p>排序固定为「新的在前」。每一项带 {@code unhandledCount}（还有几条差异没处理）——
     * 传错文件产生的那种批次一眼就能看出来（一屏差异、一条都没处理）。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，最多 100
     * @return 分页结果
     */
    @GetMapping
    public ResponseEntity<ApiResult<PageResult<ReconcileBatchVo>>> list(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size) {
        return ResponseEntity.ok(ApiResult.ok(reconcileService.listBatches((int) page, (int) size)));
    }

    /**
     * 取一个批次的详情。
     *
     * <p>比列表多一个 {@code diffTypeCounts}（各类差异各多少条），供类型筛选按钮显示角标。
     * <b>某个类型一条都没有时那个键不存在</b>，前端要按 {@code ?? 0} 取值。
     *
     * @param id 批次 ID
     * @return 批次详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResult<ReconcileBatchVo>> detail(@PathVariable Long id) {
        return ApiResult.of(reconcileService.getBatch(id));
    }

    /**
     * 分页查询某个批次的差异明细。
     *
     * <p>排序是「待处理的 → 类型优先级（最紧急的在前）→ 新的在前」。
     *
     * @param id       批次 ID
     * @param page     页码，从 1 开始
     * @param size     每页条数，最多 100
     * @param diffType 类型筛选，可空
     * @param handled  处理状态筛选（0 / 1），可空（不过滤）
     * @return 分页结果
     */
    @GetMapping("/{id}/diffs")
    public ResponseEntity<ApiResult<PageResult<ReconcileDiffVo>>> diffs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size,
            @RequestParam(required = false) String diffType,
            @RequestParam(required = false) Integer handled) {
        return ResponseEntity.ok(ApiResult.ok(
                reconcileService.listDiffs(id, (int) page, (int) size, diffType, handled)));
    }

    /**
     * 下载账单原文件。
     *
     * <p><b>这是本模块唯一一处安全敏感点。</b>账单里含全部交易对手、备注、金额与时间 ——
     * 所以它不落在公开的 {@code /uploads/} 目录下（那里的 {@code /uploads/**}
     * 在 {@code SecurityConfig.PUBLIC_PATHS} 里是匿名可访问的），
     * 只能通过这个带鉴权的接口取。
     *
     * <p><b>前端不能用 {@code <a href>} 打开这个地址</b>：那样不会带上
     * {@code Authorization} 头，拿到的是 401。必须走 axios 的
     * {@code responseType: 'blob'}，再用 {@code URL.createObjectURL} 触发下载。
     *
     * <p>成功时返回文件流，失败时返回项目统一的 JSON 错误体 ——
     * 所以返回类型是 {@code ResponseEntity<?>}：两种响应的形状本来就不一样。
     *
     * @param id 批次 ID
     * @return 文件流，或统一格式的错误体
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<?> download(@PathVariable Long id) {
        BizResult<BillFileVo> result = reconcileService.loadBillFile(id);
        if (!result.isSuccess()) {
            return ApiResult.of(result);
        }

        BillFileVo file = result.getData();
        // 文件名来自管理员上传时的原始名，是客户端可控的字符串。
        // ContentDisposition 会按 RFC 5987 编码，但换行与引号这类控制字符仍要先剥掉 ——
        // 少了这道，一个含换行的文件名就能往响应头里注入东西
        String safeName = file.fileName().replaceAll("[\\r\\n\"]", "_");
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(safeName, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                // 用 octet-stream 而不是 text/csv：浏览器对 text/* 倾向于直接打开，
                // 而管理员要的是把文件存下来（好拿去与微信/支付宝的原始账单比对）
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.content());
    }
}
