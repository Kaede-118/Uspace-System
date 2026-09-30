package com.kaede.uspace.promotion.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 月卡购买单实体，对应 {@code biz_monthly_card_order} 表。
 *
 * <p>记录「用户想买一张月卡」这件事，从发起到支付成功或关闭。
 *
 * <p><b>本表而不是月卡表充当支付目标</b>：支付回调按商户订单号反查，
 * 要求目标先落库；而「还没付钱的卡」不该出现在月卡表里
 * （见 {@link MonthlyCard} 的说明）。所以流程是：
 * <pre>
 *   本表落一条 PENDING_PAYMENT（order_no 即 out_trade_no）
 *          │
 *     支付回调（同一事务两跳）
 *          ├─ ① 本表 → PAID（带 status 守卫，幂等第一道）
 *          └─ ② 往 biz_monthly_card 插一张 ACTIVE 卡
 * </pre>
 *
 * <p>用户放弃付款是常事，那些单子会停在 {@code PENDING_PAYMENT}，
 * 到期后由下次购卡顺手置为 {@code CLOSED}，不影响任何账目。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_monthly_card_order")
public class MonthlyCardOrder extends BaseEntity {

    /** 主键 */
    @TableId
    private Long id;

    /**
     * 购买单号。
     *
     * <p>支付时充当<b>商户订单号</b>（微信/支付宝的 {@code out_trade_no}），
     * 回调靠它做幂等，因此有唯一索引。前缀 {@code MC} 是回调路由的依据。
     */
    private String orderNo;

    /** 购买人用户 ID */
    private Long userId;

    /** 卡类型。取值见 {@link com.kaede.uspace.promotion.MonthlyCardType} */
    private String cardType;

    /** 应付金额（元）。下单时按配置快照，之后调价不影响这一单 */
    private BigDecimal price;

    /** 状态。取值见 {@link com.kaede.uspace.promotion.CardOrderStatus} */
    private String status;

    /** 支付通道。取值同订单的 payment_method */
    private String paymentMethod;

    /** 支付平台交易号（微信 transaction_id / 支付宝 trade_no） */
    private String paymentNo;

    /** 支付完成时刻 */
    private LocalDateTime paidAt;
}
