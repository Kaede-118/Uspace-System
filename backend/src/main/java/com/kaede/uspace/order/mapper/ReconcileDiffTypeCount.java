package com.kaede.uspace.order.mapper;

import lombok.Data;

/**
 * 「某个批次里各类差异各有多少条」的查询结果载体。
 *
 * <p>与 {@code ProductPendingCount} 同性质：它不是对外的视图对象
 *（那在 {@code dto} 包），只是 {@link ReconcileDiffMapper#countByBatchGroupByType}
 * 的返回类型 —— 那条 SQL 用了 {@code GROUP BY}，需要一个能同时接住
 * 类型名与聚合值的类型。
 *
 * <p>批次详情的类型筛选按钮上要显示这个数（「未填流水号 12」）。
 * 一次查询取回全部类型，而不是每种类型查一次 —— 后者是六次往返。
 *
 * <p>没有差异的类型<b>不会出现在结果里</b>（{@code GROUP BY} 的本性），
 * 调用方取值时要按「查不到即 0」处理。前端的 {@code diffTypeCounts} 也照这个口径：
 * 某个键不存在等价于 0，而不是等价于「这个类型不存在」。
 */
@Data
public class ReconcileDiffTypeCount {

    /** 差异类型名，见 {@code ReconcileDiffType} */
    private String diffType;

    /** 该类型的差异条数 */
    private Integer cnt;
}
