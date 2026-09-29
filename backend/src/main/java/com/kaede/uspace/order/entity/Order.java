package com.kaede.uspace.order.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体，对应 {@code biz_order} 表。
 *
 * <p>一条订单 = 某人一次到店。它同时承担三件事：<b>准入凭证</b>（下发的限时密码）、
 * <b>计费区间</b>（{@code startTime} 到 {@code endTime}）、<b>收款依据</b>（各项金额）。
 *
 * <p><b>⚠️ 三个金额列的口径</b>：{@code dayAmount} / {@code nightAmount} 与
 * {@code totalAmount} 存的都是<b>实收</b>（已按分段封顶、已含优惠），
 * {@code discountAmount} <b>只是说明性字段</b>，已包含在 {@code totalAmount} 里 ——
 * 不可再用「合计 − 优惠」减第二次。这条在建表脚本里也有标注。
 *
 * <p>{@code payableAmount} 目前恒等于 {@code totalAmount}，是预留的独立列 ——
 * 将来若有优惠券、押金这类不进入计费规则的费用，加在这里而不污染计费结果。
 * 发起支付时取的是它，不是 {@code totalAmount}。
 *
 * @see com.kaede.uspace.order.OrderStatus 状态取值与流转
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_order")
public class Order extends BaseEntity {

    /** 主键。策略取 application.properties 里配的数据库自增 */
    @TableId
    private Long id;

    /**
     * 业务单号，对外展示，唯一索引 {@code uk_order_no}。
     *
     * <p>格式 {@code OD + yyyyMMddHHmmss + 4 位随机}，共 20 位。
     * 它同时是支付平台的<b>商户订单号</b>（{@code out_trade_no}），
     * 回调按它定位订单 —— 所以前缀 {@code OD} 不只是给人看的，
     * 也是回调路由包场（{@code BK}）与订单的依据。
     */
    private String orderNo;

    /** 下单用户 ID */
    private Long userId;

    /** 使用门店 ID */
    private Long storeId;

    /** 门锁 ID，点击开门时快照。此后换锁也不影响这张历史订单 */
    private Long lockId;

    /**
     * 关联的包场 ID。进店时命中包场才记，结算时据此把包场时段从计费区间剪掉。
     *
     * <p><b>为 null 不代表这个用户没有包场</b>：包场人可能提前到店，
     * 那时包场还没开始、三层准入判断落到「普通」分支，订单就没挂包场 ID。
     * 所以结算时还要按 {@code hostUserId} 回查一次，否则他会被重复计费。
     */
    private Long bookingId;

    /** 下发的限时密码。仅模拟/真实门锁使用，不对外暴露在订单视图里 */
    private String passcode;

    /** 密码生效时间 */
    private LocalDateTime passcodeStart;

    /** 密码失效时间。过期后用户点「查看密码」会自动续期（不换密码，只推有效期） */
    private LocalDateTime passcodeEnd;

    /** 计费起点 = 用户点击「开门」的时刻。与订单创建同一时刻 */
    private LocalDateTime startTime;

    /** 离场时刻。使用中为 null */
    private LocalDateTime endTime;

    /** 日场时长（分钟） */
    private Integer dayMinutes;

    /** 日场费用（实收，已封顶、已含优惠） */
    private BigDecimal dayAmount;

    /** 夜场时长（分钟） */
    private Integer nightMinutes;

    /** 夜场费用（实收，已封顶、已含优惠） */
    private BigDecimal nightAmount;

    /** 实收合计 = 日场 + 夜场。各段已分别封顶 */
    private BigDecimal totalAmount;

    /** 本单优惠金额。说明性字段，已包含在 {@link #totalAmount} 中，不可再减第二次 */
    private BigDecimal discountAmount;

    /** 应付金额。当前等于 {@link #totalAmount}，预留独立列供将来的优惠券、押金等 */
    private BigDecimal payableAmount;

    /** 状态名，取值见 {@link com.kaede.uspace.order.OrderStatus} */
    private String status;

    /** 支付通道名，取值见 {@link com.kaede.uspace.order.PaymentChannel}。0 元自动结清时为空 */
    private String paymentMethod;

    /** 支付截图存储路径。仅人工核销降级路径会写 */
    private String paymentProof;

    /** 支付平台交易号：微信 transaction_id / 支付宝 trade_no。人工核销时由管理员填写 */
    private String paymentNo;

    /** 支付完成时刻。0 元自动结清时也写，便于对账时区分「不用付」与「没记录」 */
    private LocalDateTime paidAt;

    /** 核销管理员 ID。为空表示系统自动确认（线上回调或 0 元结清） */
    private Long confirmedBy;

    /** 时长是否经人工调整：0=否 1=是 */
    private Integer adjusted;

    /** 调整人（管理员 ID） */
    private Long adjustedBy;

    /** 调整时间 */
    private LocalDateTime adjustedAt;

    /** 调整原因，如「用户忘记结束，监控核实 21:30 已离场」 */
    private String adjustReason;

    /** 备注 */
    private String remark;
}
