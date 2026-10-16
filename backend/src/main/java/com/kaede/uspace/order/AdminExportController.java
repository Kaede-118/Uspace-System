package com.kaede.uspace.order;

import com.kaede.uspace.common.export.ExportCache;
import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.order.dto.ExportRequest;
import com.kaede.uspace.order.dto.ExportTicketVo;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 后台流水导出（模块 8）。
 *
 * <p><b>生成与下载分两个接口</b>：{@code POST} 生成并放进服务端缓存、
 * 回一个短 token；{@code GET} 按 token 取文件。理由见 {@code ExportCache} 的类注释
 * （导出要查全库、拼几千行，不该让前端一直等一个不确定大小的响应）。
 *
 * <p>⚠️ <b>下载接口不能写成 {@code <a href>} 能直接点的地址</b>：那样不带
 * {@code Authorization} 头，只会拿到 401。前端必须走 axios 的
 * {@code responseType: 'blob'} 再触发下载 —— 与对账账单下载是同一条规矩，
 * 也正因为如此，下载失败时返回的是<b>项目统一的 JSON 错误体</b>，
 * 所以返回类型是 {@code ResponseEntity<?>}。
 */
@RestController
@RequestMapping("/api/admin/exports")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminExportController {

    private final OrderExportService exportService;
    private final ExportCache exportCache;

    public AdminExportController(OrderExportService exportService, ExportCache exportCache) {
        this.exportService = exportService;
        this.exportCache = exportCache;
    }

    /**
     * 生成一份导出文件。
     *
     * @param request 导出类型与筛选条件
     * @param me      当前管理员
     * @return 下载票据（token、文件名、行数、是否被截断）
     */
    @PostMapping
    public ResponseEntity<ApiResult<ExportTicketVo>> create(@Valid @RequestBody ExportRequest request,
                                                            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(BizResult.ok(exportService.export(request, me.id())));
    }

    /**
     * 下载导出的文件。
     *
     * @param token 生成时拿到的 token
     * @param me    当前管理员（只能下自己生成的那一份）
     * @return xlsx 文件流，或统一格式的错误体
     */
    @GetMapping("/{token}")
    public ResponseEntity<?> download(@PathVariable String token,
                                      @AuthenticationPrincipal UserPrincipal me) {
        ExportCache.Entry entry = exportCache.get(token, me.id());
        if (entry == null) {
            return ApiResult.of(BizResult.fail(ErrorCode.EXPORT_NOT_FOUND));
        }

        // 文件名是我们自己拼的（前缀 + 时刻），但仍然过一道控制字符清洗 ——
        // 与对账账单下载同一条：少了它，一个含换行的名字就能往响应头里注入东西
        String safeName = entry.fileName().replaceAll("[\\r\\n\"]", "_");
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(safeName, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                // octet-stream：浏览器对 xlsx 的默认处理是「下载」，但显式声明更稳，
                // 也让「这不是给人直接在浏览器里渲染的东西」一目了然
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(entry.content());
    }
}
