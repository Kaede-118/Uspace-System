package com.kaede.uspace.order;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.order.dto.InstoreSnapshotVo;
import com.kaede.uspace.order.dto.OrderTrafficEventVo;
import com.kaede.uspace.order.dto.OrderTrafficVo;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 后台「进出记录」接口（模块 6）。
 *
 * <p><b>口径</b>（2026-10-16 由用户定）：<b>到店 = 开始计时</b>（{@code start_time}），
 * <b>离店 = 停止计时</b>（{@code end_time}）。这两件事都发生在订单上，
 * 所以本接口读的是 {@code biz_order}，而不是门锁的开门记录 ——
 * 封闭空间的出门是机械推杠、不产生门锁记录，拿门锁记录当离店会永远等不到那一条。
 * 门锁记录（{@code /api/admin/access-records}）仍在，定位是<b>旁证</b>。
 *
 * <p><b>为什么住在 order 包、路径却是 {@code /api/admin/instore}</b>：
 * 数据源是订单表，而 {@code access} 包与 {@code order} 包互不依赖 ——
 * 「读订单」只能发生在 order 包，这与 {@link InstoreController}
 * （住在 order 包、路径 {@code /api/store/instore}）是同一条理由。
 *
 * <p><b>名册快照是推算的</b>，不是当时存下来的：{@code InstoreService} 按
 * 开始 / 结束时刻反推任意时刻谁在店。好处是历史记录一次都不用补，
 * 代价是人工调整过离店时刻的单子，受影响的快照会跟着变。
 */
@RestController
@RequestMapping("/api/admin/instore")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminInstoreController {

    private final InstoreService instoreService;

    /**
     * 构造方法。
     *
     * @param instoreService 在店名册与进出记录服务
     */
    public AdminInstoreController(InstoreService instoreService) {
        this.instoreService = instoreService;
    }

    /**
     * 分页查询进出记录。
     *
     * <p>每条记录带两份名册快照：到店那一刻的在店名册（含本人）、
     * 离店之后的在店名册（不含本人）。仍在店的那条单子没有离店端，
     * 因此也没有「离店后」那份快照。
     *
     * @param page   页码，从 1 开始
     * @param size   每页条数，上限 100
     * @param userId 按用户筛选，不传表示不限
     * @param from   时间下界（含），不传表示不限
     * @param to     时间上界（不含），不传表示不限
     * @param sort   排序视图：{@code arrive}（默认，按到店时刻）/ {@code leave}（按离店时刻）
     * @return 分页的进出记录
     */
    @GetMapping("/traffic")
    public ResponseEntity<ApiResult<PageResult<OrderTrafficVo>>> traffic(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size,
            @RequestParam(required = false) Long userId,
            // 查询参数走 Spring 的类型转换，不是 Jackson —— 必须显式声明格式，
            // 否则带空格的日期串会在进入方法前就被拒掉（与 AdminAccessController 同一个坑）
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            @RequestParam(defaultValue = "arrive") String sort) {
        return ApiResult.of(instoreService.listTraffic(
                page, size, userId, from, to, "leave".equals(sort)));
    }

    /**
     * 分页查询进出<b>事件</b>（到店 / 离店各一条）—— 后台进出页用的就是这一条。
     *
     * <p>与 {@code /traffic} 的分工：那是<b>按订单看</b>（一行一单、两端并排），
     * 这是<b>按事件看</b>（一行一件事、排成时间线）。界面用事件流 ——
     * 一行一单的读法「既不是到店也不是离店」，扫一屏看不出刚才谁走了。
     *
     * <p>每条事件带<b>该事件之后</b>的在店名册：到店那条含本人、离店那条不含
     *（半开区间），于是「某人离店、店里还剩谁」就是卡片上的一个字段。
     *
     * @param page   页码，从 1 开始
     * @param size   每页条数，上限 100
     * @param userId 按当事人筛选，不传表示不限
     * @param from   事件时刻下界（含），不传表示不限
     * @param to     事件时刻上界（不含），不传表示不限
     * @return 分页的事件时间线，新发生的在前
     */
    @GetMapping("/timeline")
    public ResponseEntity<ApiResult<PageResult<OrderTrafficEventVo>>> timeline(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        return ApiResult.of(instoreService.listTimeline(page, size, userId, from, to));
    }

    /**
     * 还原<b>任意时刻</b>的在店名册。
     *
     * <p>用于复盘：查到某条记录之后想看看「那个时刻店里到底有谁」，
     * 直接给一个时刻就能得到当时的名单与人数。它是推算的（见类注释），
     * 因此对任意历史时刻都能给出答案，不需要当时有人在页面上盯着。
     *
     * @param at 要还原的时刻（{@code yyyy-MM-dd HH:mm:ss}）
     * @return 该时刻的在店名册
     */
    @GetMapping("/snapshot")
    public ResponseEntity<ApiResult<InstoreSnapshotVo>> snapshot(
            @RequestParam
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime at) {
        return ApiResult.of(instoreService.snapshotAt(at));
    }
}
