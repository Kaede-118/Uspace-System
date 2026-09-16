package com.kaede.uspace.billing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次计费的完整结果。
 *
 * <p><b>刻意保留分段结构，而不是只返回一个总金额。</b> 用途：
 * <ul>
 *   <li>前端账单页可以直接按段展示，用户能自己核对钱是怎么算出来的</li>
 *   <li>订单详情、QQ 机器人查询复用同一份数据，不必各自重新拆分时段</li>
 *   <li>出现计费争议时可追溯到具体是哪一段、几档、是否封顶</li>
 * </ul>
 *
 * <p>单时段订单同样返回本结构（该段有值、另一段不存在），
 * 调用方无需按订单类型分支处理。
 */
@Data
public class BillingResult {

    /** 计费起始时间 */
    private LocalDateTime startTime;

    /** 计费结束时间 */
    private LocalDateTime endTime;

    /** 总时长（分钟），向下取整到整分钟 */
    private long totalMinutes;

    /**
     * 分段明细，按时间先后排列。
     *
     * <p>不含时长的段不会出现在列表中 —— 即订单恰好从时段边界开始时，
     * 不会产生一个 0 分钟的段。
     */
    private List<SegmentBill> segments;

    /** 总金额 = 各段金额之和（各段均已各自封顶） */
    private BigDecimal totalAmount;
}
