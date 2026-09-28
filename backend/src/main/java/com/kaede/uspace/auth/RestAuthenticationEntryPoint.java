package com.kaede.uspace.auth;

import com.kaede.uspace.common.result.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 未认证请求的统一出口（401）。
 *
 * <p>当请求没有通过认证、且访问的是需要认证的资源时，Spring Security 会调用这里。
 * <b>没有它的话</b>，响应会走 Spring Security 的默认行为 ——
 * 返回一个空 body 的 401，或是一个浏览器弹窗式的 HTML，
 * 与项目「所有响应都是 {@code ApiResult}」的约定不一致，
 * 前端就得为这一种情况写特殊处理。
 *
 * <p>错误码优先取认证过滤器留下的那个（{@link JwtAuthenticationFilter#ATTR_AUTH_ERROR}），
 * 因为那里判得更细 —— 能区分「凭证过期」「凭证被撤销」「账号被禁用」。
 * 取不到才回退到笼统的「请先登录」，对应「压根没带凭证」的情况。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final AuthErrorWriter errorWriter;

    public RestAuthenticationEntryPoint(AuthErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    /**
     * 输出 401 响应。
     *
     * @param request       当前请求，从中读取过滤器留下的具体错误码
     * @param response      当前响应
     * @param authException Spring Security 传进来的异常，本实现不使用 ——
     *                      它的消息是英文且面向开发者，不适合直接展示给用户
     * @throws IOException 写响应失败时抛出
     */
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object attr = request.getAttribute(JwtAuthenticationFilter.ATTR_AUTH_ERROR);
        ErrorCode error = attr instanceof ErrorCode code ? code : ErrorCode.UNAUTHORIZED;
        errorWriter.write(response, error);
    }
}
