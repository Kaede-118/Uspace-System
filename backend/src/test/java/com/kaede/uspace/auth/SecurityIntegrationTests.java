package com.kaede.uspace.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaede.uspace.user.entity.SysUser;
import com.kaede.uspace.user.mapper.SysUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全链路的端到端集成测试。
 *
 * <p><b>它验证的是「所有部件装在一起之后还能不能正常工作」</b>：
 * 过滤器链的顺序、放行路径配得对不对、{@code @PreAuthorize} 有没有真的生效、
 * 401 与 403 的响应体是不是统一格式。这些在单元测试里都测不到 ——
 * 单测能证明每个零件是对的，证明不了装配起来是对的。
 *
 * <p>几条<b>容易漏且后果严重</b>的用例，值得单独说明：
 * <ul>
 *   <li>{@code adminEndpoint_*} 一正一反两条。只测「普通用户被拒绝」是不够的 ——
 *       若 {@code @EnableMethodSecurity} 漏了、所有请求一律 403，
 *       那条用例照样通过。必须有「管理员能进」这条作为对照</li>
 *   <li>{@code changedPassword_*} 与 {@code bannedUser_*} —— 撤销机制的验收。
 *       这是模块 2 最核心的安全性质：改密与封禁必须<b>立即</b>让旧凭证失效</li>
 *   <li>{@code corsPreflight_*} —— CORS 配在 Security 层而非 MVC 层的验收。
 *       配错位置时预检会被 401 拦掉，浏览器只报一个含义模糊的跨域错误</li>
 * </ul>
 *
 * <p>连真库，用 {@code @Transactional} 回滚，{@code MYSQL_PASSWORD} 未配置时整体跳过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+",
        disabledReason = "未配置 MYSQL_PASSWORD，跳过需要真实数据库的集成测试")
class SecurityIntegrationTests {

    /** 测试用户名前缀，避免与开发库中的真实数据混淆 */
    private static final String PREFIX = "sit_";

    /** 测试密码，满足 8 位下限 */
    private static final String PASSWORD = "Test@1234";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper userMapper;

    // ==================================================================
    // 主链路
    // ==================================================================

    @Test
    @DisplayName("注册 → 登录 → 查自己，全程 200")
    void registerThenLoginThenMe_succeeds() throws Exception {
        register(USER("happy"));

        String token = login(USER("happy"));

        mockMvc.perform(get("/api/user/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value(USER("happy")));
    }

    @Test
    @DisplayName("查自己：响应里不含密码哈希")
    void me_doesNotExposePasswordHash() throws Exception {
        register(USER("nohash"));
        String token = login(USER("nohash"));

        String body = mockMvc.perform(get("/api/user/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertFalse(body.contains("$2a$"),
                "响应体里出现 BCrypt 哈希的痕迹，说明某个环节把实体直接返回了");
        assertFalse(body.contains("passwordHash"), "连字段名都不该出现");
    }

    // ==================================================================
    // 认证：401 与统一返回体
    // ==================================================================

    @Test
    @DisplayName("不带凭证访问受保护接口：401，且响应体是统一结构")
    void me_withoutToken_returns401WithUnifiedBody() throws Exception {
        mockMvc.perform(get("/api/user/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("伪造的凭证：401，错误码与「未带凭证」区分开")
    void me_withGarbageToken_returns401() throws Exception {
        mockMvc.perform(get("/api/user/me").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    // ==================================================================
    // 授权：403，且必须有对照组
    // ==================================================================

    @Test
    @DisplayName("普通用户访问运营后台接口：403")
    void adminEndpoint_asNormalUser_returns403() throws Exception {
        register(USER("normal"));
        String token = login(USER("normal"));

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    @DisplayName("管理员访问运营后台接口：200 —— 这条是上一条的对照组")
    void adminEndpoint_asAdmin_returns200() throws Exception {
        register(USER("boss"));
        String token = login(USER("boss"));
        promoteToAdmin(USER("boss"));

        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("改角色立即生效：用改角色之前的旧凭证即可进后台")
    void roleChange_takesEffectWithOldToken() throws Exception {
        register(USER("promoted"));
        String token = login(USER("promoted"));

        // 先确认此时进不去
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        promoteToAdmin(USER("promoted"));

        // 同一个凭证，改完角色立刻就能进 —— 因为鉴权读的是库里的角色
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ==================================================================
    // 撤销机制：本模块最核心的安全性质
    // ==================================================================

    @Test
    @DisplayName("改密后，旧凭证立即失效（401 而非等到过期）")
    void changedPassword_oldTokenImmediatelyReturns401() throws Exception {
        register(USER("chpw"));
        String oldToken = login(USER("chpw"));

        mockMvc.perform(put("/api/user/me/password")
                        .header("Authorization", "Bearer " + oldToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword":"%s","newPassword":"NewPass@5678"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/user/me").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("封禁后，旧凭证立即失效")
    void bannedUser_oldTokenImmediatelyReturns401() throws Exception {
        register(USER("banned"));
        String victimToken = login(USER("banned"));

        // 造一个管理员来执行封禁
        register(USER("admin"));
        String adminToken = login(USER("admin"));
        promoteToAdmin(USER("admin"));

        Long victimId = userMapper.selectByUsername(USER("banned")).getId();
        mockMvc.perform(put("/api/admin/users/" + victimId + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/user/me").header("Authorization", "Bearer " + victimToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    // ==================================================================
    // 参数校验、错误码与跨域
    // ==================================================================

    @Test
    @DisplayName("参数校验失败：400，且消息指出是哪个字段的问题")
    void validationError_returns400WithFieldMessage() throws Exception {
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ab\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("用户名重复：409 而非 500 —— 业务冲突不是服务端错误")
    void register_duplicateUsername_returns409() throws Exception {
        register(USER("dup"));

        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(USER("dup"), PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    @DisplayName("未知路径：404 而非 500 —— 错误率不该被爬虫污染")
    void unknownPath_returns404() throws Exception {
        register(USER("notfound"));
        String token = login(USER("notfound"));

        mockMvc.perform(get("/api/no-such-endpoint").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    @DisplayName("跨域预检不带凭证也能通过 —— CORS 确实配在 Security 层的验收")
    void corsPreflight_succeedsWithoutToken() throws Exception {
        mockMvc.perform(options("/api/user/me")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk());
    }

    // ==================================================================
    // 测试辅助
    // ==================================================================

    /**
     * 拼测试用户名。
     *
     * @param suffix 后缀
     * @return 带前缀的用户名
     */
    private static String USER(String suffix) {
        return PREFIX + suffix;
    }

    /**
     * 通过真实接口注册用户。
     *
     * @param username 用户名
     * @throws Exception 请求失败时抛出
     */
    private void register(String username) throws Exception {
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk());
    }

    /**
     * 通过真实接口登录并取出凭证。
     *
     * @param username 用户名
     * @return JWT 字符串
     * @throws Exception 请求失败或响应结构不符时抛出
     */
    private String login(String username) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    /**
     * 直接把库里的角色改成管理员。
     *
     * <p>不走接口：改角色接口本身要求管理员权限，而这里正是要造出第一个管理员。
     * 测试里直接改库是合理的 —— 真实系统由建表脚本预置管理员账号。
     *
     * @param username 用户名
     */
    private void promoteToAdmin(String username) {
        SysUser user = userMapper.selectByUsername(username);
        userMapper.updateRole(user.getId(), "ADMIN");
    }
}
