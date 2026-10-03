package com.kaede.uspace.order.mapper;

import lombok.Data;

/**
 * 「某个批次还有几条差异没处理」的查询结果载体。
 *
 * <p>只用于 {@link ReconcileDiffMapper#countUnhandledByBatchIds} ——
 * 批次列表要给每一行显示「未处理 3」，一条条查就是 N+1
 *（与 {@code PaymentProofService#listForAdmin} 批量补昵称是同一套做法）。
 *
 * <p>一条待处理差异都没有的批次<b>不会出现在结果里</b>，
 * 调用方按「查不到即 0」处理。
 */
@Data
public class ReconcileBatchDiffCount {

    /** 对账批次 ID */
    private Long batchId;

    /** 该批次里 {@code handled = 0} 的差异条数 */
    private Integer cnt;
}
