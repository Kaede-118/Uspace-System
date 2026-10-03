package com.kaede.uspace.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 签发 QQ 验证码的请求（模块 1）。
 *
 * <p>只有一个字段。单独建一个类而不复用 {@link RegisterRequest}，是因为
 * 本接口是<b>匿名</b>可调的，请求体的形状应当尽可能小 —— 复用注册请求会让
 * 调用方以为可以传密码、昵称这些东西进来。
 */
@Data
public class QqVerifyIssueRequest {

    /**
     * 要验证的 QQ 号。
     *
     * <p>格式正则与 {@code RegisterRequest.qq} 上那条<b>后半段完全一致</b>
     * （那个还允许空串，因为 QQ 在注册时是选填的，而这里既然调了这个接口就必填）。
     * 两处写成一样是有意的：不同的话会出现「签发时收下、注册时拒绝」这种
     * 让用户完全摸不着头脑的组合。
     */
    @NotBlank(message = "QQ 号不能为空")
    @Pattern(regexp = "^[1-9]\\d{4,11}$", message = "QQ 号格式不正确")
    private String qq;
}
