package com.kaede.uspace.space.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 修改包场排期的请求。
 *
 * <p><b>不含 {@code hostUserId}</b>：换包场人等于换了一单生意，
 * 正确的做法是取消原场、另建一场。允许在这里改，
 * 会让「谁付的款」与「谁是被邀请者」两件事对不上 ——
 * 付款是原包场人完成的，改完之后他付款的那场却记在别人名下。
 *
 * <p>修改只允许在 {@code PENDING_PAYMENT} 状态进行：已付款的包场
 * 若要改期，需要同时处理退款，那属于模块 8 与运营流程，
 * 不应由一个「改期」接口顺带完成。
 */
@Data
public class UpdateBookingRequest {

    /** 包场开始时刻，必填 */
    @NotNull(message = "包场开始时间不能为空")
    private LocalDateTime startAt;

    /** 包场结束时刻，必填。必须晚于开始时刻 */
    @NotNull(message = "包场结束时间不能为空")
    private LocalDateTime endAt;

    /** 包场价格（元），必填。允许 0 元，不允许负数 */
    @NotNull(message = "包场价格不能为空")
    @DecimalMin(value = "0.00", message = "包场价格不能为负数")
    private BigDecimal price;

    /** 备注。传 null 或空串表示清除 */
    @Size(max = 255, message = "备注不能超过 255 个字符")
    private String remark;
}
