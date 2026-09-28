package com.kaede.uspace.auth;

import com.kaede.uspace.common.result.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 权限不足的统一出口（403）。
 *
 * <p>与 {@link RestAuthenticationEntryPoint} 的分工：
 * <ul>
 *   <li><b>401</b> —— 不知道你是谁（没带凭证、凭证无效 / 过期）</li>
 *   <li><b>403</b> —— 知道你是谁，但你的角色不够（普通用户访问运营后台接口）</li>
 * </ul>
 * 两者对前端意味着完全不同的处置：401 跳登录页，403 提示「无权限」并留在原页。
 * 混用一个状态码会让前端无从判断。
 *
 * <p>这个出口覆盖两个来源：URL 级的授权规则拒绝，以及
 * {@code @PreAuthorize} 的方法级拒绝 —— 后者抛出的异常同样会被
 * Spring Security 的 {@code ExceptionTranslationFilter} 捕获后送到这里。
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final AuthErrorWriter errorWriter;

    public RestAccessDeniedHandler(AuthErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    /**
     * 输出 403 响应。
     *
     * @param request               当前请求
     * @param response              当前响应
     * @param accessDeniedException Spring Security 传进来的异常，本实现不使用
     * @throws IOException 写响应失败时抛出
     */
    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        errorWriter.write(response, ErrorCode.FORBIDDEN);
    }
}
