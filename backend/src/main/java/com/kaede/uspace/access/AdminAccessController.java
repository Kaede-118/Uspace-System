package com.kaede.uspace.access;

import com.kaede.uspace.access.dto.AccessRecordVo;
import com.kaede.uspace.access.dto.ManualOpenRequest;
import com.kaede.uspace.access.dto.RecordOpenVo;
import com.kaede.uspace.access.dto.SyncRecordsRequest;
import com.kaede.uspace.access.dto.SyncRecordsVo;
import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 开门记录管理接口（模块 6 的管理员侧）。
 *
 * <p>路径前缀 {@code /api/admin}，与用户端的 {@code /api/access-records} 分开。
 * 权限声明放在类上而不是每个方法上，理由同 {@code AdminStoreController}。
 *
 * <p><b>关于 {@code /sync} 接口的调用额度</b>：它每次调用会消耗门锁云的配额 ——
 * 正常 1 次，拉取结果为空时再探活一次变成 2 次。通通锁开放平台的免费额度是
 * <b>30000 次/月</b>，是整套系统的硬约束。所以这个接口设计成<b>只在管理员点击时
 * 调用一次</b>，<b>前端不要做成定时轮询</b> —— 哪怕 10 分钟一次，一把锁一个月
 * 也要 4300 多次。响应体里的 {@code cloudCalls} 会如实报出本次消耗了几次。
 */
@RestController
@RequestMapping("/api/admin/access-records")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminAccessController {

    private final AccessRecordService accessRecordService;

    /**
     * 构造方法。
     *
     * @param accessRecordService 开门记录服务
     */
    public AdminAccessController(AccessRecordService accessRecordService) {
        this.accessRecordService = accessRecordService;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 分页查询开门记录。
     *
     * <p>典型用途：用户忘记点「结束使用」时，管理员按时间范围查出这段期间的开门记录，
     * 与监控录像比对后手工修正订单时长。
     *
     * @param page   页码，从 1 开始
     * @param size   每页条数，上限 100（超出由分页插件截断）
     * @param userId 按开门人过滤，不传表示不限
     * @param from   时间范围起点（含），不传表示不限
     * @param to     时间范围终点（不含），不传表示不限
     * @return 分页的开门记录
     */
    @GetMapping
    public ResponseEntity<ApiResult<PageResult<AccessRecordVo>>> listRecords(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size,
            @RequestParam(required = false) Long userId,
            // 查询参数走的是 Spring 的类型转换，不是 Jackson —— JacksonConfig 里配的
            // 时间格式对 @RequestParam 不生效，这里必须显式声明格式，
            // 否则带空格的日期串会在进入方法前就被拒掉（400）
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        return ApiResult.of(accessRecordService.listRecords(page, size, userId, from, to));
    }

    // ==================================================================
    // 同步
    // ==================================================================

    /**
     * 从门锁云同步开门记录（<b>按需手动触发，请勿轮询</b>）。
     *
     * <p>同步是幂等的：重复同步同一区间不会写入重复数据，
     * 响应体里 {@code inserted=0 skipped=N} 就是幂等生效的证据。
     *
     * <p>若门锁云不可达或锁离线导致结果不可确认，会返回 502 而不是「成功、0 条」——
     * 把「没拉到」伪装成「确实没有」会让计费与审计悄悄丢数据。
     *
     * @param request 锁 ID 与同步区间（区间跨度上限 31 天）
     * @return 同步统计：拉取、新增、跳过、丢弃的条数与本次消耗的云端调用次数
     */
    @PostMapping("/sync")
    public ResponseEntity<ApiResult<SyncRecordsVo>> sync(
            @Valid @RequestBody SyncRecordsRequest request) {
        return ApiResult.of(accessRecordService.syncRecords(
                request.getLockId(), request.getFrom(), request.getTo()));
    }

    // ==================================================================
    // 补录
    // ==================================================================

    /**
     * 手工补录一条开门记录。
     *
     * <p>用于系统或门锁故障期间开门没能留下记录的场景，事后按纸质登记或监控补入。
     * 补录记录的来源固定记为「管理员补录」，请求方无法指定 —— 审计上必须能一眼看出
     * 哪些记录是人工填的。
     *
     * @param request 锁 ID、开门时刻与可选的用户、订单、密码
     * @return 补录后的记录，以及本次是否新增
     */
    @PostMapping
    public ResponseEntity<ApiResult<RecordOpenVo>> recordManual(
            @Valid @RequestBody ManualOpenRequest request) {
        return ApiResult.of(accessRecordService.recordManualOpen(request));
    }
}
