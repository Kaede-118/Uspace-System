package com.kaede.uspace.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 查询 QQ 验证状态的请求（模块 1）。
 *
 * <p>⚠️ <b>这是个纯查询，却做成了 POST 而不是 GET</b> —— 刻意的。
 * 查询需要带 {@code challengeId}，而它是<b>能换取账号绑定的凭证</b>
 * （谁拿到它，谁就能在验证有效期内用那个 QQ 注册）。放进 URL 查询参数
 * 会落进访问日志、浏览器历史、反向代理的日志 —— 任何一个看到日志的人
 * 都能抢先注册。放进请求体则不会。
 *
 * <p>代价是这个接口不能被浏览器直接打开、不能缓存。两者都不需要。
 */
@Data
public class QqVerifyStatusRequest {

    /** 用户在注册页填的 QQ 号。用来定位验证记录 */
    @NotBlank(message = "QQ 号不能为空")
    private String qq;

    /** 签发接口给出的凭证，见类注释。轮询与提交注册都要带上它 */
    @NotBlank(message = "验证凭证不能为空")
    private String challengeId;
}
