package com.kaede.uspace.user;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.user.dto.ChangePasswordRequest;
import com.kaede.uspace.user.dto.RegisterRequest;
import com.kaede.uspace.user.dto.UpdateProfileRequest;
import com.kaede.uspace.user.dto.UserProfileVo;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户自助接口（模块 1）。
 *
 * <p>路径统一挂在 {@code /api/user} 下，操作对象都是「当前登录的用户自己」——
 * 因此路径里不出现用户 ID，身份一律从凭证中取。
 * 这从根上杜绝了「改一下 URL 里的 ID 就能改别人的资料」这类越权，
 * 因为压根就没有那个 ID 可改。
 *
 * <p>管理员的接口在 {@link AdminUserController}，路径前缀 {@code /api/admin}，
 * 两者分开是有意为之：前端可以按前缀一眼区分「这个接口是不是后台功能」，
 * 后端的权限配置也能按前缀成片地管，不必逐个方法声明。
 *
 * <p><b>Controller 的职责到此为止</b>：接收请求、取当前用户、调 Service、
 * 把结果交给 {@link ApiResult#of} 统一转换。任何业务判断都不该写在这一层 ——
 * 那样 QQ 机器人复用 Service 时就会漏掉这些判断。
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 注册新用户。无需登录。
     *
     * <p>注册成功只返回用户资料、<b>不返回登录凭证</b>，前端随后调用
     * {@code /api/auth/login} 完成登录。这样模块 1 不必反过来依赖模块 2，
     * 论文里「用户管理」与「权限管理」两章的边界也对得上。
     *
     * @param request 注册请求
     * @return 新用户的资料视图
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResult<UserProfileVo>> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResult.of(userService.register(request));
    }

    /**
     * 查询当前登录用户的资料。
     *
     * @param me 当前登录用户，由认证过滤器写入 SecurityContext
     * @return 资料视图
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResult<UserProfileVo>> me(@AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(userService.getProfile(me.id()));
    }

    /**
     * 修改当前登录用户的资料。
     *
     * <p>PUT 语义为全量替换：未提交的字段视为清空。前端提交表单时
     * 应把当前所有值一并带上。
     *
     * @param me      当前登录用户
     * @param request 资料请求
     * @return 更新后的资料视图
     */
    @PutMapping("/me")
    public ResponseEntity<ApiResult<UserProfileVo>> updateProfile(
            @AuthenticationPrincipal UserPrincipal me,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResult.of(userService.updateProfile(me.id(), request));
    }

    /**
     * 修改当前登录用户的密码。
     *
     * <p><b>成功后该用户所有已签发的凭证立即失效</b>，前端应跳回登录页。
     * 这是刻意的 —— 改密码的常见动机是怀疑密码泄露，
     * 若旧凭证还能继续用，改密码就失去了意义。
     *
     * @param me      当前登录用户
     * @param request 改密请求，需提供原密码
     * @return 成功时 data 为 null
     */
    @PutMapping("/me/password")
    public ResponseEntity<ApiResult<Void>> changePassword(
            @AuthenticationPrincipal UserPrincipal me,
            @Valid @RequestBody ChangePasswordRequest request) {
        return ApiResult.of(userService.changePassword(me.id(), request));
    }
}
