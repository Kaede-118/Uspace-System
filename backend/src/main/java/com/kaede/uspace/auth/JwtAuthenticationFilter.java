package com.kaede.uspace.auth;

import com.kaede.uspace.auth.dto.JwtPayload;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.user.UserService;
import com.kaede.uspace.user.entity.SysUser;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器（模块 2）。
 *
 * <p>每个请求只跑一次（{@link OncePerRequestFilter}），做四件事：
 * <ol>
 *   <li>从 {@code Authorization} 头里取出凭证</li>
 *   <li>验签并检查是否过期（{@link JwtService#parse}）</li>
 *   <li>查库比对 token 版本号与账号状态</li>
 *   <li>三者都通过则把身份写进 {@code SecurityContext}，后续由 Spring Security
 *       的授权规则与 {@code @PreAuthorize} 接管</li>
 * </ol>
 *
 * <p><b>「角色以数据库为准」是这里最关键的一个决定。</b>载荷里也带了 role，
 * 但那是签发那一刻的快照。本过滤器为了比对版本号<b>本来就要查一次库</b>，
 * 既然用户记录已经在手上，就没有理由再用一份可能过期的副本 ——
 * 用库里的角色，改角色（含降级）立即生效，不必把用户踢下线重新登录。
 * 这也是「改角色不升 token 版本号」这条规则的依据。
 *
 * <p><b>失败时不抛异常、也不直接写响应</b>，只把错误码塞进请求属性，
 * 交给 {@code RestAuthenticationEntryPoint} 统一输出。理由是响应体格式
 * 只应有一处定义 —— 如果过滤器自己也拼一份 JSON，将来改返回体结构时
 * 就有两个地方要同步，迟早漏一个。
 *
 * <p><b>刻意不注册为 Spring Bean</b>：Spring Boot 会把容器里所有 Filter 类型的
 * Bean 自动注册到 Servlet 链上，那样本过滤器会执行两次（一次由 Servlet 容器，
 * 一次由 Spring Security 的过滤器链）。这里改为在 {@code SecurityConfig} 里
 * 直接 new 出来，位置与执行次数都完全可控。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * 认证失败时写入请求属性的键，值是 {@link ErrorCode}。
     *
     * <p>用请求属性而非异常在过滤器之间传递信息：异常会打断过滤器链，
     * 而我们需要「继续往下走，但身份为空」，最终由授权规则决定是 401 还是放行
     * （登录接口本来就允许匿名访问，不该因为没带 token 就报错）。
     */
    public static final String ATTR_AUTH_ERROR = "uspace.auth.error";

    /** 携带凭证的请求头名 */
    private static final String HEADER_AUTHORIZATION = "Authorization";

    /** 凭证前缀。标准写法是 {@code Bearer <token>} */
    private static final String PREFIX_BEARER = "Bearer ";

    /** 账号正常状态，与 sys_user.status 的取值一致 */
    private static final int STATUS_ENABLED = 1;

    private final JwtService jwtService;

    /**
     * 用于按 ID 查用户。
     *
     * <p>这里直接依赖模块 1 的服务，方向是 {@code auth → user}，
     * 与既定的单向依赖一致。反过来让 user 包依赖 auth 包是不允许的 ——
     * 那样 QQ 机器人链路（不经 HTTP 认证层）就会被无关的依赖拖住。
     */
    private final UserService userService;

    public JwtAuthenticationFilter(JwtService jwtService, UserService userService) {
        this.jwtService = jwtService;
        this.userService = userService;
    }

    /**
     * 过滤逻辑：能认证就认证，不能就留空身份继续往下走。
     *
     * @param request  当前请求
     * @param response 当前响应（本方法不写它）
     * @param chain    过滤器链
     * @throws ServletException Servlet 层异常
     * @throws IOException      IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            authenticate(token, request);
        }
        // 无论认证成功与否都要继续走：是否放行由 SecurityConfig 的授权规则决定。
        // 在这里提前 return 会把「未登录访问登录接口」也一并拦掉
        chain.doFilter(request, response);
    }

    /**
     * 校验凭证并把身份写入 SecurityContext。
     *
     * <p>失败时只设置 {@link #ATTR_AUTH_ERROR}，不做其他动作。
     *
     * @param token   凭证字符串
     * @param request 当前请求，用于写入错误码
     */
    private void authenticate(String token, HttpServletRequest request) {
        JwtPayload payload;
        try {
            payload = jwtService.parse(token);
        } catch (ExpiredJwtException e) {
            // 过期与无效分开：前端可以据此决定「静默刷新」还是「跳登录页」
            request.setAttribute(ATTR_AUTH_ERROR, ErrorCode.TOKEN_EXPIRED);
            return;
        } catch (JwtException | IllegalArgumentException e) {
            request.setAttribute(ATTR_AUTH_ERROR, ErrorCode.TOKEN_INVALID);
            return;
        }

        SysUser user = userService.findById(payload.userId());
        if (user == null) {
            // 用户被删除，或凭证指向一个不存在的 ID（如密钥泄露后被伪造）
            request.setAttribute(ATTR_AUTH_ERROR, ErrorCode.TOKEN_INVALID);
            return;
        }

        Integer currentVersion = user.getTokenVersion();
        if (currentVersion == null || currentVersion != payload.tokenVersion()) {
            // 版本号对不上：改密或封禁时被主动撤销过
            request.setAttribute(ATTR_AUTH_ERROR, ErrorCode.TOKEN_INVALID);
            return;
        }

        if (!Integer.valueOf(STATUS_ENABLED).equals(user.getStatus())) {
            // 账号被禁用。用 AccountDisabled 而非 TokenInvalid：
            // 前端应提示「联系管理员」而不是「重新登录」，因为重登也进不去
            request.setAttribute(ATTR_AUTH_ERROR, ErrorCode.ACCOUNT_DISABLED);
            return;
        }

        // 角色取库里的值，不取载荷里的 —— 见类注释
        UserPrincipal principal = new UserPrincipal(
                user.getId(), user.getUsername(), user.getNickname(), user.getRole());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * 从请求头中解析出凭证。
     *
     * <p>前缀比较<b>不区分大小写</b>：HTTP 头字段名本身不区分大小写，
     * 而 {@code Bearer} 这个方案名在实际客户端实现里有写 {@code bearer} 的。
     * 那种情况下报 401，排查起来会非常费解 —— 用户看不出哪里不对。
     *
     * @param request 当前请求
     * @return 凭证字符串；请求头缺失、前缀不符或内容为空时返回 null
     */
    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER_AUTHORIZATION);
        if (header == null || header.length() <= PREFIX_BEARER.length()) {
            return null;
        }
        if (!header.regionMatches(true, 0, PREFIX_BEARER, 0, PREFIX_BEARER.length())) {
            return null;
        }
        String token = header.substring(PREFIX_BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
