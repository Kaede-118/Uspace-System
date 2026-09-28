package com.kaede.uspace.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码的请求。
 *
 * <p><b>必须提供原密码</b>，即使调用方已经登录：这是为了防止
 * 「用户离开时没退出登录，他人拿到设备后直接改密码锁死账号」。
 * 已登录只证明「这次请求持有凭证」，不证明「操作者是本人」。
 *
 * <p>改密成功后，该用户<b>所有</b>已签发的凭证立即失效（靠 token 版本号 +1），
 * 前端需要跳回登录页。这是刻意的：改密码的常见动机之一就是「怀疑密码泄露」，
 * 若旧凭证还能用，改密码就白改了。
 */
@Data
public class ChangePasswordRequest {

    /** 原密码明文，仅用于校验，不落库 */
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    /**
     * 新密码明文。
     *
     * <p>长度规则与注册时保持一致，否则会出现「注册时能设的密码改不回去」的怪事。
     * 同时也要求与原密码不同 —— 见 Service 的校验。
     */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 32, message = "新密码长度需在 8~32 位之间")
    private String newPassword;
}
