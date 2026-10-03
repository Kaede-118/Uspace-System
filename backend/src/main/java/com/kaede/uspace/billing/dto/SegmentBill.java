package com.kaede.uspace.billing.dto;

import com.kaede.uspace.billing.BillingPeriod;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单个时段的计费明细。
 *
 * <p>保留「计费档数」「单价」「封顶前金额」三项中间结果，是为了让账单可追溯 ——
 * 用户或管理员质疑金额时，能直接看出钱是按几档、单价多少、有没有被封顶算出来的，
 * 而不只是一个最终数字。
 */
@Data
public class SegmentBill {

    /** 所属时段（日场 / 夜场） */
    private BillingPeriod period;

    /** 该段起始时间 */
    private LocalDateTime startTime;

    /** 该段结束时间 */
    private LocalDateTime endTime;

    /** 该段实际时长（分钟），向下取整到整分钟 */
    private long minutes;

    /** 计费档数。每档对应一个计费单位时长（默认 30 分钟）。时长在宽限内的档数为 0 */
    private int units;

    /**
     * 本段采用的单价（元 / 计费单位）。
     *
     * <p>日夜场单价不同（日 4 元 / 夜 3.5 元），月度优惠生效时还会进一步下降
     * （日 3.5 元 / 夜 3 元），所以单价必须逐段记下来 ——
     * 只留一个「档数 × 金额」的话，事后没法解释这一段的钱是按哪个价格算的。
     */
    private BigDecimal unitPrice;

    /**
     * 本段适用的封顶金额（元）。
     *
     * <p>封顶同样分日夜、分是否优惠（日 40 / 35，夜 35 / 30），
     * 不记下来的话，账单上「原始 44 元为什么只收 40 元」就没有依据 ——
     * 用户只能看到一个被压低的数字，看不到压到哪儿为止。
     */
    private BigDecimal capAmount;

    /** 封顶前的原始金额 = 档数 × 单价 */
    private BigDecimal rawAmount;

    /**
     * 该段最终金额 = min(原始金额, 该时段该优惠状态下的封顶)。
     *
     * <p>被月卡覆盖的段此值为 0，但 {@code units}、{@code unitPrice}、
     * {@code rawAmount}、{@code capAmount} 仍是照常算出来的值 ——
     * 它们是账单页解释「这段为什么免费」的依据，全部归零就只剩一个没有来由的 0。
     */
    private BigDecimal amount;

    /** 是否触发了封顶。为 true 时说明原始金额已超过该时段的封顶金额 */
    private boolean capped;

    /** 本段是否被月卡覆盖（免费）。为 true 时 {@link #amount} 为 0 */
    private boolean freeByCard;

    /**
     * 本段因月卡而免掉的金额（元）。未被覆盖时为 0。
     *
     * <p>口径是「<b>不持卡时本段应付的金额</b>」= min(封顶前金额, 封顶)，
     * 已经含了月度优惠价 —— 也就是说，一个已享月度优惠的用户拿卡免掉的
     * 是按优惠价算的钱，不会把两种优惠重复算一遍（两者互不重叠）。
     *
     * <p>注意它与 {@link #amount} 的关系：{@code amount + cardFreeAmount}
     * 等于「不持卡时本段应付的金额」。
     *
     * <p>由计费侧逐段给出而不是事后用「原价总额 − 实收」反推 ——
     * 包场时段会被调用方从计费区间里剪掉、根本不产生分段，
     * 反推会把包场免掉的钱一并算进月卡的口径里。
     */
    private BigDecimal cardFreeAmount;

    /** 本段是否落在某个免费活动区间内（活动免费）。为 true 时 {@link #amount} 为 0 */
    private boolean freeByActivity;

    /**
     * 本段因活动免掉的金额（元）。不在活动区间内时为 0。
     *
     * <p>口径与 {@link #cardFreeAmount} 完全一致：「不免费时本段应付的金额」
     *（已含月度优惠价）。
     *
     * <p>⚠️ <b>两者【互斥】</b>：被月卡覆盖的段只记月卡、不再记活动 ——
     * 月卡用户本来就免费，活动并没有为他省下什么。两个都记会把同一笔钱
     * 算两遍，活动复盘时「送出去多少」会虚高，而那种错不会有任何报错。
     */
    private BigDecimal activityFreeAmount;
}
