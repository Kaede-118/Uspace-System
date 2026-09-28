package com.kaede.uspace.auth;

import com.kaede.uspace.auth.dto.JwtPayload;
import com.kaede.uspace.auth.dto.LoginRequest;
import com.kaede.uspace.auth.dto.LoginVo;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.result.ErrorCode;
import com.kaede.uspace.user.FakeSysUserMapper;
import com.kaede.uspace.user.UserService;
import com.kaede.uspace.user.dto.ChangePasswordRequest;
import com.kaede.uspace.user.dto.RegisterRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuthService} 的单元测试。
 *
 * <p><b>纯单测，不启动 Spring</b>。这里用的是<b>真实的</b> UserService 与 JwtService，
 * 只把数据访问层换成内存实现 —— 登录是「校验凭据」与「签发凭证」两步的组合，
 * 若把那两步都换成假的，测出来的就只是「编排代码按顺序调用」，
 * 而真正要验证的「签发的凭证能解回同一个用户」「改密后旧凭证失效」
 * 这些跨模块的性质就测不到了。
 *
 * <p>最有价值的一条是
 * {@link #login_afterPasswordChange_oldTokenVersionNoLongerMatches}：
 * 它端到端地验证了撤销机制 —— 登录拿到凭证、改密码、再用凭证里的版本号
 * 与库里的比对，两者必须不再相等。这正是「封禁与改密立即生效」的实现原理。
 */
class AuthServiceTests {

    /** 测试用户名 */
    private static final String USERNAME = "xiaofeng";

    /** 测试密码 */
    private static final String RAW_PASSWORD = "Test@1234";

    /** 改密后的新密码 */
    private static final String NEW_PASSWORD = "NewPass@5678";

    /** 测试密钥，仅用于单测 */
    private static final String SECRET = "auth-service-test-secret-0123456789abcdef";

    /** 内存版数据访问层 */
    private final FakeSysUserMapper fakeMapper = new FakeSysUserMapper();

    /** 密码编码器，强度 4 让测试跑得快 */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private final JwtService jwtService;

    private final UserService userService;

    private final AuthService authService;

    /**
     * 组装被测对象：全部用真实实现，只替换掉数据访问层。
     */
    AuthServiceTests() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        this.jwtService = new JwtService(properties);
        this.userService = new UserService(fakeMapper.asMapper(), passwordEncoder);
        this.authService = new AuthService(userService, jwtService);
    }

    // ==================================================================
    // 正常路径
    // ==================================================================

    @Test
    @DisplayName("登录成功：返回的凭证能解回同一个用户")
    void login_returnsParsableToken() {
        Long userId = registerUser();

        BizResult<LoginVo> result = authService.login(loginRequest(RAW_PASSWORD));

        assertTrue(result.isSuccess());
        LoginVo vo = result.getData();
        assertNotNull(vo.getToken(), "必须返回凭证，否则前端拿什么调后续接口");
        assertEquals("Bearer", vo.getTokenType(), "按 OAuth2 约定固定为 Bearer");
        assertEquals(7 * 24 * 60 * 60L, vo.getExpiresIn(), "有效期 7 天");

        JwtPayload payload = jwtService.parse(vo.getToken());
        assertEquals(userId, payload.userId(), "凭证必须指向刚登录的这个用户");
    }

    @Test
    @DisplayName("登录成功：凭证里的版本号与库里一致，否则立即就会被判为已撤销")
    void login_tokenCarriesCurrentTokenVersion() {
        Long userId = registerUser();
        int expected = fakeMapper.get(userId).getTokenVersion();

        LoginVo vo = authService.login(loginRequest(RAW_PASSWORD)).getData();

        assertEquals(expected, jwtService.parse(vo.getToken()).tokenVersion(),
                "版本号若与库里对不上，这个凭证签发出来就是废的");
    }

    @Test
    @DisplayName("登录成功：返回的用户资料不含密码哈希")
    void login_returnsProfileWithoutPasswordHash() {
        registerUser();

        LoginVo vo = authService.login(loginRequest(RAW_PASSWORD)).getData();

        assertNotNull(vo.getUser(), "顺带返回资料，前端登录后可直接渲染，少一次往返");
        assertEquals(USERNAME, vo.getUser().getUsername());
        assertFalse(vo.getUser().toString().contains("$2a$"),
                "响应的任何角落都不该出现密码哈希");
    }

    // ==================================================================
    // 失败路径：错误码必须原样透传自模块 1
    // ==================================================================

    @Test
    @DisplayName("登录失败：用户不存在与密码错误返回同一个错误码")
    void login_hidesWhetherAccountExists() {
        registerUser();

        // 路径一：用户存在，但密码错了
        BizResult<LoginVo> wrongPassword = authService.login(loginRequest("WrongPass@999"));

        // 路径二：用户根本不存在
        LoginRequest noSuchUserRequest = new LoginRequest();
        noSuchUserRequest.setUsername("nobody");
        noSuchUserRequest.setPassword(RAW_PASSWORD);
        BizResult<LoginVo> noSuchUser = authService.login(noSuchUserRequest);

        assertEquals(ErrorCode.BAD_CREDENTIALS, wrongPassword.getError());
        assertEquals(ErrorCode.BAD_CREDENTIALS, noSuchUser.getError(),
                "两条路径的错误码与提示必须完全一致，否则可以靠它枚举出系统里有哪些账号");
    }

    @Test
    @DisplayName("登录失败：账号被禁用返回 403 类的错误码")
    void login_rejectsDisabledAccount() {
        Long userId = registerUser();
        fakeMapper.get(userId).setStatus(0);

        BizResult<LoginVo> result = authService.login(loginRequest(RAW_PASSWORD));

        assertEquals(ErrorCode.ACCOUNT_DISABLED, result.getError(),
                "前端据此提示「联系管理员」而不是「重新登录」—— 后者重登也进不去");
    }

    // ==================================================================
    // 撤销机制：这是本模块最核心的安全性质
    // ==================================================================

    @Test
    @DisplayName("改密后，旧凭证的版本号与库里不再相等 —— 即旧凭证已失效")
    void login_afterPasswordChange_oldTokenVersionNoLongerMatches() {
        Long userId = registerUser();
        JwtPayload oldPayload = jwtService.parse(
                authService.login(loginRequest(RAW_PASSWORD)).getData().getToken());

        ChangePasswordRequest change = new ChangePasswordRequest();
        change.setOldPassword(RAW_PASSWORD);
        change.setNewPassword(NEW_PASSWORD);
        assertTrue(userService.changePassword(userId, change).isSuccess());

        int currentVersion = fakeMapper.get(userId).getTokenVersion();
        assertFalse(oldPayload.tokenVersion() == currentVersion,
                "版本号必须不再相等 —— 否则过滤器比对不出差异，旧凭证会继续有效，"
                        + "「怀疑密码泄露所以改密码」这个动作就白做了");
    }

    @Test
    @DisplayName("改密后可以用新密码重新登录")
    void login_succeedsWithNewPasswordAfterChange() {
        Long userId = registerUser();
        ChangePasswordRequest change = new ChangePasswordRequest();
        change.setOldPassword(RAW_PASSWORD);
        change.setNewPassword(NEW_PASSWORD);
        userService.changePassword(userId, change);

        BizResult<LoginVo> result = authService.login(loginRequest(NEW_PASSWORD));

        assertTrue(result.isSuccess(), "改完密码必须能用新密码登进来");
        assertEquals(userId, jwtService.parse(result.getData().getToken()).userId());
    }

    // ==================================================================
    // 测试辅助
    // ==================================================================

    /**
     * 注册一个测试用户并返回其 ID。
     *
     * @return 用户 ID
     */
    private Long registerUser() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(USERNAME);
        request.setPassword(RAW_PASSWORD);
        return userService.register(request).getData().getId();
    }

    /**
     * 构造登录请求。
     *
     * @param password 密码明文
     * @return 登录请求
     */
    private static LoginRequest loginRequest(String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(USERNAME);
        request.setPassword(password);
        return request;
    }
}
