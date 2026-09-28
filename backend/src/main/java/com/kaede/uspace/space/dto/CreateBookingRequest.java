package com.kaede.uspace.space.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建包场的请求（管理员在后台排期）。
 *
 * <p>创建出来的包场处于 {@code PENDING_PAYMENT} 状态 —— 排期已占位，
 * 但准入尚未生效。要等包场人付款后才转 {@code PAID}，
 * 那时才产生排他性（见 {@code BookingMapper#selectCoveringAt} 的说明）。
 *
 * <p><b>包场人必须是已存在的用户</b>：他要能登录自己的账号取邀请链接，
 * 因此这里给的是 {@code hostUserId} 而不是手机号或昵称。
 * Service 会校验该用户存在。
 */
@Data
public class CreateBookingRequest {

    /** 包场人用户 ID，必填 */
    @NotNull(message = "包场人不能为空")
    private Long hostUserId;

    /**
     * 包场开始时刻，必填。
     *
     * <p>与停业记录不同，这里<b>要求是将来时刻</b>：包场是卖给用户的，
     * 排一个已经开始甚至已经过去的时段没有意义，且会让邀请链接
     * 「刚生成就只剩一半可用」。Service 会校验这一点。
     */
    @NotNull(message = "包场开始时间不能为空")
    private LocalDateTime startAt;

    /** 包场结束时刻，必填。必须晚于开始时刻 */
    @NotNull(message = "包场结束时间不能为空")
    private LocalDateTime endAt;

    /**
     * 包场价格（元），必填。
     *
     * <p>一口价，不按分钟计 —— 与该时段的实际使用时长无关。
     * 允许 0 元（如赠送给熟客），但不允许负数。
     */
    @NotNull(message = "包场价格不能为空")
    @DecimalMin(value = "0.00", message = "包场价格不能为负数")
    private BigDecimal price;

    /** 备注。选填 */
    @Size(max = 255, message = "备注不能超过 255 个字符")
    private String remark;
}
