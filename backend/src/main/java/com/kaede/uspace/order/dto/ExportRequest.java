package com.kaede.uspace.order.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导出请求（后台流水导出）。
 *
 * <p>字段与后台列表的筛选条件一一对应 —— 导出的是「当前筛选条件下的全量」，
 * 而不是页面上那一页：拿出去做流水计算时，只导十行没有意义。
 *
 * <p>⚠️ {@code type} 用 {@link Pattern} 卡死取值，而不是在 Service 里
 * {@code switch} 一个 default 分支：非法值应该在进 Controller 之前就被拒
 * （400 + 统一异常处理），而不是走一条「猜用户想导什么」的兜底路。
 *
 * <p>时间是 JSON body 里的字段，走 {@code JacksonConfig} 配的全局格式
 * （{@code yyyy-MM-dd HH:mm:ss}）—— 与 {@code @RequestParam} 那条路不同，
 * 后者要显式写 {@code @DateTimeFormat}，见 {@code AdminOrderController} 的注释。
 */
@Data
public class ExportRequest {

    /**
     * 导出哪份流水：
     * <ul>
     *   <li>{@code TIMING_ORDER} 计时订单</li>
     *   <li>{@code PRODUCT_ORDER} 商品购买单</li>
     *   <li>{@code ALL_ORDERS} <b>两种合并成一张总流水</b>（2026-10-16 店主提的）——
     *       它的行结构与上面两张都不同，见 {@code OrderExportService#ALL_HEADERS}；
     *       而且<b>不接筛选条件</b>：两张表能筛的维度本来就不一样，
     *       混着用只会让「这次导的是什么口径」说不清</li>
     * </ul>
     */
    @Pattern(regexp = "TIMING_ORDER|PRODUCT_ORDER|ALL_ORDERS",
            message = "导出类型只能是 TIMING_ORDER、PRODUCT_ORDER 或 ALL_ORDERS")
    private String type;

    /** 用户 ID 筛选，可空 */
    private Long userId;

    /** 状态筛选，可空 */
    private String status;

    /** 计费起点下界（含），可空。仅计时订单用得上 */
    private LocalDateTime from;

    /** 计费起点上界（不含），可空。仅计时订单用得上 */
    private LocalDateTime to;

    /** 是否经人工调整（0/1），可空。仅计时订单用得上 */
    private Integer adjusted;
}
