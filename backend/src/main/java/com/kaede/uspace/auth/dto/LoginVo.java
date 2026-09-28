package com.kaede.uspace.auth.dto;

import com.kaede.uspace.user.dto.UserProfileVo;
import lombok.Data;

/**
 * 登录成功的返回体。
 *
 * <p>随凭证一起把用户资料带上，前端登录后可以立即渲染界面，
 * 不必再调一次「查自己」的接口 —— 登录是用户等待感最强的环节，
 * 少一次往返就少一分等待。
 *
 * <p><b>不含任何敏感字段</b>：用户资料用的是 {@code UserProfileVo}，
 * 它本身就是白名单（密码哈希、token 版本号都不在其中）。
 */
@Data
public class LoginVo {

    /**
     * 访问凭证。
     *
     * <p>前端存入 {@code localStorage}，后续请求以
     * {@code Authorization: Bearer <token>} 携带。
     *
     * <p>存入 localStorage 的代价是理论上可被 XSS 窃取 ——
     * 这是选它的已知短板，靠输入转义与 7 天的有效期控制影响面。
     * 不用 Cookie 是因为微信内置浏览器对跨域 Cookie 的限制严格，
     * 而微信内恰恰是本系统支付的主战场。
     */
    private String token;

    /**
     * 凭证类型，固定为 {@code Bearer}。
     *
     * <p>按 OAuth2 的约定返回，前端拼请求头时用得上，
     * 也方便将来若引入别的凭证类型时不必改接口结构。
     */
    private String tokenType;

    /** 有效期（秒）。前端可据此提前判断凭证是否即将过期 */
    private long expiresIn;

    /** 登录用户的资料 */
    private UserProfileVo user;
}
