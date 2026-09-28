package com.kaede.uspace.space.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kaede.uspace.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 包场实体，对应 {@code biz_booking} 表。
 *
 * <p>默认经营方式是<b>共享</b> —— 谁都能下单进店，多组顾客同时在店内各玩各的。
 * 管理员可为某个时段安排「包场」，该时段内<b>只有包场人与被邀请者能进</b>。
 *
 * <p><b>准入控制靠「不发密码」实现，不靠门锁的禁止名单</b>：
 * 非被邀请者在该时段内下不了单，自然也拿不到密码。
 * 因此本表不维护「谁能进」的名单，只维护「哪个时段被谁包下」
 * 与一个用于鉴权的 {@code inviteToken}。
 *
 * <p><b>包场时段内不计费</b>（{@code price} 是预付的一口价），
 * 时段结束后仍逗留的部分按普通规则计时 —— 这部分由模块 8 的订单逻辑处理。
 *
 * <p>{@code paymentNo} / {@code paidAt} / {@code inviteToken} 三个字段
 * 由模块 8 的支付回调写入，模块 3 只负责排期（创建、改期、取消）与展示。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("biz_booking")
public class Booking extends BaseEntity {

    /** 主键 */
    @TableId
    private Long id;

    /**
     * 包场单号，对外展示。
     *
     * <p>支付时充当<b>商户订单号</b>（微信/支付宝的 {@code out_trade_no}），
     * 回调靠它做幂等，因此有唯一索引。
     */
    private String bookingNo;

    /** 所属门店 ID */
    private Long storeId;

    /** 包场人用户 ID */
    private Long hostUserId;

    /** 包场开始时刻 */
    private LocalDateTime startAt;

    /** 包场结束时刻 */
    private LocalDateTime endAt;

    /** 包场价格（元）。一口价预付，不按分钟计 */
    private BigDecimal price;

    /** 状态。取值见 {@link com.kaede.uspace.space.BookingStatus} */
    private String status;

    /** 支付通道。取值同订单的 payment_method，由模块 8 写入 */
    private String paymentMethod;

    /** 支付平台交易号（微信 transaction_id / 支付宝 trade_no），由模块 8 写入 */
    private String paymentNo;

    /** 支付完成时刻。也是邀请链接开始可用的时刻，由模块 8 写入 */
    private LocalDateTime paidAt;

    /**
     * 邀请令牌。付款后生成，被邀请者凭它鉴权。
     *
     * <p>有唯一索引，且未付款时为空 —— 空值不影响唯一性
     * （MySQL 的唯一索引允许多行为 NULL）。
     */
    private String inviteToken;

    /** 备注 */
    private String remark;

    /** 安排人（管理员用户 ID） */
    private Long createdBy;
}
