package com.kaede.uspace.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求。
 *
 * <p>只有两个字段，刻意不加图形验证码、短信验证码之类的东西 ——
 * 那些属于风控范畴，本项目规模下用不上，加了反而拖慢演示时的操作节奏。
 * 暴力破解的防线由「BCrypt 的高计算成本」承担：
 * 每次密码校验都要几十毫秒，靠在线请求撞库的效率极低。
 */
@Data
public class LoginRequest {

    /** 登录名 */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 密码明文。
     *
     * <p>这里<b>刻意不做长度校验</b>（不像注册与改密）：
     * 密码长度规则变更前注册的老用户，其密码可能不满足现行规则，
     * 若在登录时也校验，这些用户会连登录都做不到。
     * 校验长度是「设置密码」时的事，不是「使用密码」时的事。
     */
    @NotBlank(message = "密码不能为空")
    private String password;
}
