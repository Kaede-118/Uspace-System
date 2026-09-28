package com.kaede.uspace.auth;

import com.kaede.uspace.auth.dto.LoginRequest;
import com.kaede.uspace.auth.dto.LoginVo;
import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.BizResult;
import com.kaede.uspace.common.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（模块 2）。
 *
 * <p>路径前缀 {@code /api/auth}，与用户自助接口 {@code /api/user} 分开 ——
 * 「获得身份」与「管理自己的信息」是两件事，前者属于权限管理模块。
 * 注册接口在 {@link com.kaede.uspace.user.UserController}，
 * 因为注册是用户管理模块的职责，且它不签发凭证。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录。无需认证。
     *
     * @param request 登录请求
     * @return 凭证与用户资料；凭据错误返回 401，账号被禁用返回 403
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResult<LoginVo>> login(@Valid @RequestBody LoginRequest request) {
        return ApiResult.of(authService.login(request));
    }

    /**
     * 登出。
     *
     * <p><b>服务端不做任何事</b>，只记一条日志；真正的登出动作是前端
     * 删掉 localStorage 里的凭证。这是 JWT 无状态的必然结果 ——
     * 详见 {@link AuthService#logout}。
     *
     * <p>这个端点仍要求携带有效凭证：一来可以记下「是谁登出的」，
     * 二来让前端有个统一的调用点，不必在代码里散落地删 key。
     *
     * @param me 当前登录用户
     * @return 成功时 data 为 null
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResult<Void>> logout(@AuthenticationPrincipal UserPrincipal me) {
        authService.logout(me.id());
        return ApiResult.of(BizResult.ok(null));
    }
}
