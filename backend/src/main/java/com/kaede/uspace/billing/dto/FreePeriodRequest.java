package com.kaede.uspace.billing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 新增 / 修改免费时段（活动）的请求体。
 *
 * <p>与 {@code ClosureRequest} 同形。<b>起止先后的校验刻意不放在这里</b>：
 * 它要跨两个字段判，而字段级注解只能看自己 —— 放到 Service 里判，
 * 错误码也才能是业务码而不是「参数校验失败」。
 */
@Data
public class FreePeriodRequest {

    /** 免费开始时刻（含） */
    @NotNull(message = "请选择活动开始时刻")
    private LocalDateTime startAt;

    /** 免费结束时刻（不含） */
    @NotNull(message = "请选择活动结束时刻")
    private LocalDateTime endAt;

    /** 活动名称，如「跨年活动」。选填 */
    @Size(max = 255, message = "活动名称不能超过 255 字")
    private String reason;
}
