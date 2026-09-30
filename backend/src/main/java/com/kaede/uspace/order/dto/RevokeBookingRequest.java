package com.kaede.uspace.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 撤销包场并退款的请求。
 *
 * <p>只有一个字段 —— 退款方式。金额固定为全额（包场是一口价，撤销就是整场作废），
 * <b>刻意不由调用方传入</b>：能传就能传错，而这里错一分钱都是要跟顾客解释的。
 * 真要做部分退款时再加字段，那时也该配一套更严的校验（不超过原价、不为负）。
 *
 * <p>没有「退款原因」字段：撤销本身就是原因，需要记细节的话写在包场的备注里。
 */
@Data
public class RevokeBookingRequest {

    /**
     * 退款方式：{@code MANUAL}（人工退）/ {@code ONLINE}（原路退回）。
     *
     * <p>取值校验在 Service 里做（走 {@code RefundMode.isValid}）——
     * 枚举的权威定义只有一处，不在这里再写一份正则。
     */
    @NotBlank(message = "请选择退款方式")
    private String refundMode;
}
