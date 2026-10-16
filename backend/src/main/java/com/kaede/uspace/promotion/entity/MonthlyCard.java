package com.kaede.uspace.promotion.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import com.kaede.uspace.promotion.MonthlyCardStatus;
import com.kaede.uspace.promotion.MonthlyCardType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 月卡实体，对应 {@code biz_monthly_card} 表。
 *
 * <p>本表<b>只放真正生效过的卡</b>：付成功了（或管理员在后台发放了）才往这里插一条，
 * 待支付与已关闭的购买尝试留在 {@link MonthlyCardOrder}。
 * 所以 {@code startDate} / {@code endDate} 是 NOT NULL ——
 * 一张卡必然有生效区间，没有生效日期的记录不是卡。
 * （STAFF 卡是唯一「没有真正的到期日」的卡种，但它仍落在这一条上：
 * 到期日填 {@link MonthlyCardType#STAFF_END_DATE}，见那边的说明。）
 *
 * <p><b>免单判定要同时看状态与日期</b>：
 * <pre>
 *   status = 'ACTIVE' AND start_date &lt;= 某日 AND end_date &gt;= 某日
 * </pre>
 * 日期保证「到点就停」（哪怕定时任务因停机漏跑），
 * 状态保证「管理员把卡置为已退款后立刻停」——两条缺一不可。
 *
 * <p>卡费计入 {@code sys_user.card_paid}，<b>不计入</b>月度累计消费的优惠门槛。
 * 两个「累计消费」口径的区别见 {@code docs/开发约定与设计说明.md}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_monthly_card")
public class MonthlyCard extends BaseEntity {

    /** 主键 */
    @TableId
    private Long id;

    /** 卡号，对外展示 */
    private String cardNo;

    /** 持卡用户 ID。月卡绑定本人使用，不可转赠 */
    private Long userId;

    /** 卡类型。取值见 {@link com.kaede.uspace.promotion.MonthlyCardType} */
    private String cardType;

    /** 购买价格（元）。支付时快照，事后调价不影响已售出的卡 */
    private BigDecimal price;

    /** 生效日期（= 支付当日；STAFF 卡 = 开卡当日） */
    private LocalDate startDate;

    /**
     * 失效日期，<b>含当日</b> = start_date + (validDays − 1)。
     * STAFF 卡填 {@link MonthlyCardType#STAFF_END_DATE}（2099-12-31，远期日期）
     */
    private LocalDate endDate;

    /** 状态。取值见 {@link com.kaede.uspace.promotion.MonthlyCardStatus} */
    private String status;

    /** 支付通道。取值同订单的 payment_method */
    private String paymentMethod;

    /** 产生本卡的购买单号（{@code biz_monthly_card_order.order_no}） */
    private String payOrderNo;

    /** 支付时刻。与 start_date 同为支付当日，但这里精确到时刻，供对账 */
    private LocalDateTime paidAt;

    /**
     * 判断这张卡在指定日期是否真正生效 —— <b>全站「生效中」判定的唯一实现</b>。
     *
     * <p>口径与 {@code MonthlyCardMapper#selectActiveAt} 的 SQL 逐条对应：
     * 状态为 {@code ACTIVE}，且当天落在生效与失效日期之间（两端都含）。
     * STAFF 卡走的也是这一套 —— 它的到期日在 2099，日期条件天然通过，
     * 不需要任何特判。
     *
     * <p>⚠️ <b>为什么要收到实体上</b>：这个判定原本在
     * {@code MonthlyCardService}（列表分组）与 {@code MonthlyCardVo}（剩余天数）
     * 各写了一份，两处必须逐字一致，否则会出现「列表说生效、结算说不生效」。
     * 合并成一份，改口径时就不会漏掉某一处 —— 而漏掉不会报错，
     * 只会让某个页面上的结论与别处相反。
     *
     * @param date 判定日期（通常传今天；计费侧传的是订单开始日期，口径见调用方）
     * @return 生效返回 true
     */
    public boolean isEffectiveOn(LocalDate date) {
        return MonthlyCardStatus.ACTIVE.name().equals(status)
                && startDate != null && endDate != null
                && !startDate.isAfter(date) && !endDate.isBefore(date);
    }
}
