package com.kaede.uspace.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员人工调整订单时长的请求体。
 *
 * <p><b>典型场景</b>：顾客玩完直接走了，忘了点「结束使用」，订单一直挂在使用中。
 * 管理员查监控确认他 21:30 离场，把离场时刻改过来、让账单能出来收款。
 *
 * <p><b>为什么只有「离场时刻」可改、不能改「开门时刻」</b>：开门时刻是用户
 * 点按钮那一刻由系统记的，不是人工填的，没有出错的余地；而离场时刻靠用户自觉点击，
 * 恰恰是唯一会缺、会错的环节。少一个可改字段，就少一处需要审计的地方。
 *
 * <p>调整会重算金额并写入 {@code adjusted} 系列字段留痕，
 * 已支付的订单不允许调整（涉及退款，走人工流程）。
 */
@Data
public class AdjustOrderRequest {

    /**
     * 核实后的离场时刻。
     *
     * <p>必须在「开门时刻」之后、且不能是未来 —— 两条都由 Service 校验。
     * 提交的时间会截断到秒（与系统记录的其它时刻保持同一精度）。
     */
    @NotNull(message = "离场时刻不能为空")
    private LocalDateTime endTime;

    /**
     * 调整原因。
     *
     * <p><b>必填且要写清依据</b>，因为它会被写进订单、永久留痕：
     * 「用户忘记结束，监控核实 21:30 已离场」这样的记录，事后无论是顾客质疑
     * 还是运营复盘都说得清；只写「调整」两个字，等于把这单改成了无据可查。
     */
    @NotBlank(message = "调整原因不能为空")
    @Size(max = 255, message = "调整原因不能超过 255 个字符")
    private String reason;
}
