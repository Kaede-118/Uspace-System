package com.kaede.uspace.access.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 模拟开门的请求（<b>仅模拟门锁下可用</b>，见 {@code MockOpenController}）。
 *
 * <p>真实场景下开门记录由锁自身产生、经网关上报到门锁云；模拟实现没有硬件，
 * 因此提供一个接口供演示与联调时「制造」一次开门。切换到真实门锁后，
 * 这个接口连同它的 Controller 会一起消失（由 {@code @ConditionalOnProperty} 控制）。
 *
 * <p>完整的一次演示调用会走完三步：<b>若未给密码则先下发一个 → 模拟开门 → 记录落库</b>，
 * 这样只需一次请求就能把「下发密码 → 开门 → 写进出记录」跑通。
 */
@Data
public class MockOpenRequest {

    /** 锁 ID，必填 */
    @NotNull(message = "锁 ID 不能为空")
    private Long lockId;

    /**
     * 开门所用密码。
     *
     * <p><b>留空则自动下发一个</b>（有效期 3 小时），并把生成的密码回显在响应里 ——
     * 模拟门锁要求「密码必须已下发」才能开门，而当前还没有下单流程来下发它
     * （模块 8 尚未落地），留空自动下发是为了让演示一步到位。
     * 模块 8 落地后密码由下单流程给出，届时传值即可。
     */
    private String passcode;

    /** 开门人用户 ID，可为 null */
    private Long userId;

    /** 关联订单 ID，可为 null。演示按订单串联时填上 */
    private Long orderId;
}
