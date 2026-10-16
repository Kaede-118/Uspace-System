package com.kaede.uspace.stats;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.stats.dto.FinanceSummaryVo;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计接口（模块 10，后台）。
 *
 * <p>权限声明放在类上：这些数都是经营数据，一个都不能漏给普通用户。
 * 与 {@code AdminOrderController} 同一写法。
 */
@RestController
@RequestMapping("/api/admin/stats")
@PreAuthorize("hasRole('ADMIN')")
public class AdminStatsController {

    private final FinanceSummaryService financeSummaryService;

    public AdminStatsController(FinanceSummaryService financeSummaryService) {
        this.financeSummaryService = financeSummaryService;
    }

    /**
     * 收支概览：本月 / 上月 / 累计。
     *
     * <p>只读、无参数 —— 「本月」由服务端按当前时刻算，
     * 不让前端传日期范围：那会让「本月」有两个定义。
     *
     * @return 收支概览
     */
    @GetMapping("/finance")
    public ResponseEntity<ApiResult<FinanceSummaryVo>> finance() {
        return ApiResult.of(BizResult.ok(financeSummaryService.summary()));
    }
}
