package com.kaede.uspace.promotion.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 月卡实体，对应 {@code biz_monthly_card} 表。
 *
 * <p>本表<b>只放真正生效过的卡</b>：付成功了才往这里插一条，
 * 待支付与已关闭的购买尝试留在 {@link MonthlyCardOrder}。
 * 所以 {@code startDate} / {@code endDate} 是 NOT NULL ——
 * 一张卡必然有生效区间，没有生效日期的记录不是卡。
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

    /** 生效日期（= 支付当日） */
    private LocalDate startDate;

    /** 失效日期，<b>含当日</b> = start_date + (validDays − 1) */
    private LocalDate endDate;

    /** 状态。取值见 {@link com.kaede.uspace.promotion.MonthlyCardStatus} */
    private String status;

    /** 支付通道。取值同订单的 payment_method */
    private String paymentMethod;

    /** 产生本卡的购买单号（{@code biz_monthly_card_order.order_no}） */
    private String payOrderNo;

    /** 支付时刻。与 start_date 同为支付当日，但这里精确到时刻，供对账 */
    private LocalDateTime paidAt;
}
