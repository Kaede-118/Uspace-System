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
 *   <li>出现计费争议时可追溯到具体是哪一段、几档、单价多少、是否封顶</li>
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

    /** 总金额 = 各段金额之和（各段均已各自封顶，已扣除月卡免除的部分） */
    private BigDecimal totalAmount;

    /**
     * 本次结算前的当月累计实付额（元），由调用方传入。
     *
     * <p>留痕用途：事后核对某笔订单为什么走了（或没走）优惠价时，
     * 直接看这个数字与门槛的关系即可，不必再去翻当月订单明细。
     */
    private BigDecimal monthSpentBefore;

    /** 本单是否按月度优惠价计费。整单统一，不会一段优惠一段不优惠 */
    private boolean discounted;

    /**
     * 本单因月度优惠少收的金额（元）。未优惠时为 0。
     *
     * <p>对应订单表的 {@code discount_amount} 列，由计费服务直接给出，
     * 免得订单、统计、QQ 机器人各处重复实现一遍优惠金额的算法。
     *
     * <p>注意它<b>不含</b>月卡免掉的部分（那是 {@link #cardFreeAmount}）、
     * 也<b>不含</b>免费活动免掉的部分（那是 {@link #activityFreeAmount}）——
     * 三种优惠并行存在、互不重叠，混在一起的话，
     * 统计口径里「优惠活动的效果」会虚高，且事后无法拆开。
     */
    private BigDecimal discountAmount;

    /**
     * 本单因月卡免掉的金额（元）。无卡或卡未覆盖任何段时为 0。
     *
     * <p>对应订单表的 {@code card_free_amount} 列。它由各段的
     * {@link SegmentBill#getCardFreeAmount()} 相加得出，逐段累加而非反推 ——
     * 理由见该字段的说明。
     */
    private BigDecimal cardFreeAmount;

    /**
     * 本单因免费活动免掉的金额（元）。不在活动区间内时为 0。
     *
     * <p>对应订单表的 {@code activity_free_amount} 列。与 {@link #cardFreeAmount}
     * 一样<b>逐段累加</b>而非反推，理由同该字段的说明。
     *
     * <p>⚠️ <b>不含被月卡覆盖的段</b>：月卡用户本来就免费，活动并没有为他省下什么。
     * 两个口径混在一起的话，复盘一场活动「送出去多少钱」会虚高 ——
     * 而那种错不会有任何报错，只会让运营以为活动比实际更划算。
     */
    private BigDecimal activityFreeAmount;
}
