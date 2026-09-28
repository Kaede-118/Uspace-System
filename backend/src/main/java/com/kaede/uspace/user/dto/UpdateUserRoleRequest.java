package com.kaede.uspace.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 管理员调整用户角色的请求。
 *
 * <p>取值只接受 {@code USER} 与 {@code ADMIN}，由 Service 用
 * {@link com.kaede.uspace.user.UserRole#parse} 校验 ——
 * 不用枚举类型直接接收，是因为 Jackson 在反序列化失败时抛出的异常
 * 消息对调用方毫无帮助，不如自己校验后给出「角色取值不合法」的明确提示。
 *
 * <p><b>改角色不需要用户重新登录</b>：鉴权时角色取自数据库而非凭证载荷，
 * 所以升为管理员后刷新页面即可进后台，降为普通用户也会立即失去后台权限。
 */
@Data
public class UpdateUserRoleRequest {

    /** 目标角色，取值 USER 或 ADMIN */
    @NotBlank(message = "角色不能为空")
    private String role;
}
