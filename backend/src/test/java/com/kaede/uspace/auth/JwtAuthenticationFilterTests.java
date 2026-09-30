package com.kaede.uspace.auth;

import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.user.FakeSysUserMapper;
import com.kaede.uspace.user.UserService;
import com.kaede.uspace.user.dto.RegisterRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtAuthenticationFilter} 的单元测试。
 *
 * <p>用 spring-test 的 {@code MockHttpServletRequest} / {@code MockFilterChain}
 * 直接驱动过滤器，<b>不启动 Spring 容器</b>：过滤器本身不依赖容器，
 * 它只做「读请求头 → 验签 → 查库 → 写 SecurityContext」这几步。
 * 直接调用的好处是可以精确断言中间状态（请求属性里放了什么错误码、
 * SecurityContext 有没有被写入），这些用端到端测试很难观察到。
 *
 * <p>端到端的部分由 {@code SecurityIntegrationTests} 覆盖 ——
 * 两者分工：这里验过滤器的判定逻辑，那里验整条过滤器链与授权规则的配合。
 */
class JwtAuthenticationFilterTests {

    /** 测试用户名 */
    private static final String USERNAME = "xiaofeng";

    /** 测试密码 */
    private static final String RAW_PASSWORD = "Test@1234";

    /** 测试密钥 */
    private static final String SECRET = "filter-test-secret-0123456789abcdefghij";

    private final FakeSysUserMapper fakeMapper = new FakeSysUserMapper();

    private final JwtService jwtService;

    private final UserService userService;

    private final JwtAuthenticationFilter filter;

    /**
     * 组装被测对象：真实的 JwtService 与 UserService，内存版数据访问层。
     */
    JwtAuthenticationFilterTests() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        this.jwtService = new JwtService(properties);
        this.userService = new UserService(fakeMapper.asMapper(), new BCryptPasswordEncoder(4));
        this.filter = new JwtAuthenticationFilter(jwtService, userService);
    }

    /**
     * 每个用例后清空安全上下文。
     *
     * <p>必须清：{@code SecurityContextHolder} 默认用 ThreadLocal 存身份，
     * 而 JUnit 在同一个线程上跑完所有用例 —— 不清的话，
     * 前一个用例写入的身份会漏给后一个，制造出难以察觉的假通过。
     */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ==================================================================
    // 通过路径
    // ==================================================================

    @Test
    @DisplayName("有效凭证：身份写入上下文，权限带 ROLE_ 前缀")
    void doFilter_setsAuthenticationWhenTokenValid() throws Exception {
        Long userId = registerUser(USERNAME);
        MockHttpServletRequest request = requestWithToken(currentTokenOf(userId));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth, "验签通过就必须写入身份，否则后续 @PreAuthorize 一律判定为未登录");

        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        assertEquals(userId, principal.id());
        assertEquals(USERNAME, principal.username());

        assertTrue(auth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_USER")),
                "Spring Security 的 hasRole('USER') 实际比对的是 ROLE_USER —— 少这个前缀，"
                        + "@PreAuthorize 会静默地永远不通过");
    }

    @Test
    @DisplayName("不带凭证：保持匿名且不报错，放行给后续规则判断")
    void doFilter_leavesAnonymousWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(request.getAttribute(JwtAuthenticationFilter.ATTR_AUTH_ERROR),
                "没带凭证不是错误 —— 要不要放行由接口的授权规则决定，"
                        + "否则未登录访问登录接口也会被拦下");
    }

    @Test
    @DisplayName("Bearer 前缀大小写不敏感")
    void doFilter_toleratesLowercaseBearerPrefix() throws Exception {
        Long userId = registerUser(USERNAME);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "bearer " + currentTokenOf(userId));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNotNull(SecurityContextHolder.getContext().getAuthentication(),
                "HTTP 头字段名本就大小写不敏感，客户端写小写就报 401 会让人完全摸不着头脑");
    }

    // ==================================================================
    // 拒绝路径：每一种失败都要留下可区分的错误码
    // ==================================================================

    @Test
    @DisplayName("凭证被篡改：标记为无效，不写入身份")
    void doFilter_rejectsTamperedToken() throws Exception {
        Long userId = registerUser(USERNAME);
        String token = currentTokenOf(userId);
        // 篡改签名【中段】的字符，不能改最后一个：HMAC-SHA256 签名是 32 字节，
        // Base64URL 编码后末位字符只有 4 个有效 bit，低 2 位是编码填充 ——
        // 只改那 2 位时解码结果一模一样，签名照样验得过，
        // 于是用例变成「有时通过、有时失败」的假通过（此前修过一次，改的仍是末位）
        int at = token.length() - 5;
        char replacement = token.charAt(at) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, at) + replacement + token.substring(at + 1);
        MockHttpServletRequest request = requestWithToken(tampered);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(ErrorCode.TOKEN_INVALID, request.getAttribute(JwtAuthenticationFilter.ATTR_AUTH_ERROR));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("版本号对不上：判定为已撤销（改密或封禁过）")
    void doFilter_rejectsWhenTokenVersionMismatch() throws Exception {
        Long userId = registerUser(USERNAME);
        String token = currentTokenOf(userId);
        // 模拟「签发之后用户改了密码」—— 库里的版本号被 +1
        fakeMapper.get(userId).setTokenVersion(fakeMapper.get(userId).getTokenVersion() + 1);

        MockHttpServletRequest request = requestWithToken(token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(ErrorCode.TOKEN_INVALID, request.getAttribute(JwtAuthenticationFilter.ATTR_AUTH_ERROR),
                "这正是「改密 / 封禁立即生效」的实现方式：比对不上就视为已撤销");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("账号被禁用：给出专门的错误码，便于前端提示「联系管理员」")
    void doFilter_rejectsWhenAccountDisabled() throws Exception {
        Long userId = registerUser(USERNAME);
        String token = currentTokenOf(userId);
        fakeMapper.get(userId).setStatus(0);

        MockHttpServletRequest request = requestWithToken(token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(ErrorCode.ACCOUNT_DISABLED, request.getAttribute(JwtAuthenticationFilter.ATTR_AUTH_ERROR),
                "与「凭证无效」区分开：前者该跳登录页，后者重登也进不去，提示语完全不同");
    }

    @Test
    @DisplayName("凭证指向不存在的用户：判定为无效")
    void doFilter_rejectsTokenOfMissingUser() throws Exception {
        // 直接签一个指向不存在用户 ID 的凭证，模拟密钥泄露后被伪造的情况
        String token = jwtService.issue(999999L, "ADMIN", 0);
        MockHttpServletRequest request = requestWithToken(token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(ErrorCode.TOKEN_INVALID, request.getAttribute(JwtAuthenticationFilter.ATTR_AUTH_ERROR));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ==================================================================
    // 角色以数据库为准
    // ==================================================================

    @Test
    @DisplayName("角色取数据库的值，不取凭证里的 —— 改角色立即生效")
    void doFilter_usesRoleFromDatabaseNotFromToken() throws Exception {
        Long userId = registerUser(USERNAME);
        // 凭证是在用户还是 USER 的时候签发的
        String token = currentTokenOf(userId);
        assertEquals("USER", jwtService.parse(token).role(), "前提：凭证里记的是 USER");

        // 签发之后把用户升为管理员
        fakeMapper.get(userId).setRole("ADMIN");

        MockHttpServletRequest request = requestWithToken(token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(auth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN")),
                "用旧凭证也必须立即获得新角色 —— 这正是「改角色不升版本号」能成立的原因："
                        + "版本号验证本来就要查库，既然查了就没理由再用一份过期的角色快照");
    }

    // ==================================================================
    // 测试辅助
    // ==================================================================

    /**
     * 注册一个用户。
     *
     * @param username 用户名
     * @return 用户 ID
     */
    private Long registerUser(String username) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(RAW_PASSWORD);
        return userService.register(request).getData().getId();
    }

    /**
     * 按用户当前状态签发一份凭证。
     *
     * @param userId 用户 ID
     * @return JWT 字符串
     */
    private String currentTokenOf(Long userId) {
        var user = fakeMapper.get(userId);
        return jwtService.issue(user.getId(), user.getRole(), user.getTokenVersion());
    }

    /**
     * 构造一个带 {@code Authorization: Bearer <token>} 头的请求。
     *
     * @param token JWT 字符串
     * @return 模拟请求
     */
    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
