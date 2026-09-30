package com.kaede.uspace.user;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.user.dto.ChangePasswordRequest;
import com.kaede.uspace.user.dto.RegisterRequest;
import com.kaede.uspace.user.dto.UpdateProfileRequest;
import com.kaede.uspace.user.dto.UserProfileVo;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    private final ImageUploadService imageUploadService;

    public UserController(UserService userService, ImageUploadService imageUploadService) {
        this.userService = userService;
        this.imageUploadService = imageUploadService;
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
     * 上传当前登录用户的头像。
     *
     * <p><b>为什么不并进上面的 {@code PUT /api/user/me}</b>：那个接口是全量替换语义
     * （传 null 即清空）。头像混进去的话，前端提交「只改昵称」的表单时会因为
     * 没带头像路径而<b>把它清空，且不报任何错</b>。图片有自己的产生方式（上传），
     * 就该有自己的接口。
     *
     * <p><b>路径里不出现用户 ID</b>：身份从凭证取，与 {@code /me} 系列一致 ——
     * 压根没有那个 ID 可改，也就没有「改一下 URL 就改别人的头像」这回事。
     *
     * <p><b>{@code required = false} 是刻意的</b>：请求里没带 {@code file} 部分时，
     * 若声明为必填，Spring 抛的 {@code MissingServletRequestPartException}
     * 不在全局异常处理器的名单里，会落到兜底分支返回 500 ——
     * 而它明明是「你忘了传文件」，该给 400。声明为可选、在 Service 里判空，
     * 才能拿到正确的错误码与提示语。
     *
     * @param me   当前登录用户
     * @param file 上传的图片，表单字段名固定为 {@code file}
     * @return 更新后的完整资料视图
     */
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResult<UserProfileVo>> uploadAvatar(
            @AuthenticationPrincipal UserPrincipal me,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return ApiResult.of(imageUploadService.uploadAvatar(me.id(), file));
    }

    /**
     * 上传当前登录用户的背景图（约 6:1 的横长图，用作个人卡片背景）。
     *
     * <p>与头像严格同构，区别只有落哪个子目录、写哪一列 —— 理由见
     * {@link #uploadAvatar}。
     *
     * <p><b>不做宽高比校验</b>：6:1 是<b>展示约定</b>，前端用
     * {@code object-fit: cover} 居中裁切。后端强制校验会让「6.1:1」的图被拒，
     * 用户体验很差，而且提示语也说不清错在哪。
     *
     * @param me   当前登录用户
     * @param file 上传的图片，表单字段名固定为 {@code file}
     * @return 更新后的完整资料视图
     */
    @PostMapping(value = "/me/banner", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResult<UserProfileVo>> uploadBanner(
            @AuthenticationPrincipal UserPrincipal me,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return ApiResult.of(imageUploadService.uploadBanner(me.id(), file));
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
