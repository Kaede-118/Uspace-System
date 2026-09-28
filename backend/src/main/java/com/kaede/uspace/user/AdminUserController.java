package com.kaede.uspace.user;

import com.kaede.uspace.common.result.ApiResult;
import com.kaede.uspace.common.result.PageResult;
import com.kaede.uspace.common.security.UserPrincipal;
import com.kaede.uspace.user.dto.AdminUserVo;
import com.kaede.uspace.user.dto.UpdateUserRoleRequest;
import com.kaede.uspace.user.dto.UpdateUserStatusRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（模块 1 的管理员侧）。
 *
 * <p>路径前缀 {@code /api/admin}，与用户自助接口 {@code /api/user} 分开。
 *
 * <p><b>权限声明放在类上而不是每个方法上</b>：本类的每个接口都要求管理员，
 * 逐个方法写 {@code @PreAuthorize} 只是重复，且将来新增方法时容易漏掉 ——
 * 漏掉的后果是接口裸奔，而且不会有任何报错提醒。写在类上则是默认收紧、
 * 要开例外得显式声明，方向是安全的。
 *
 * <p>注意 {@code @PreAuthorize} 生效的前提是 {@code SecurityConfig} 上
 * 标了 {@code @EnableMethodSecurity} —— 漏了那个注解，这里的所有声明都会
 * <b>静默失效</b>，接口照常可访问且不报错。集成测试里专门有一条用例钉住它。
 *
 * <p>另外一个容易忽略的点：{@code @Validated} 是为了让路径变量与查询参数上的
 * {@code @Min} 生效。请求体上的 {@code @Valid} 走的是另一套机制，
 * 没有这个注解，{@code @RequestParam} 上的约束不会被执行。
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 分页查询用户列表。
     *
     * @param keyword  搜索关键字，匹配登录名 / 昵称 / QQ 号；可省略
     * @param page     页码，从 1 开始
     * @param size     每页条数，上限 100（超出由分页插件截断）
     * @return 分页的用户列表
     */
    @GetMapping
    public ResponseEntity<ApiResult<PageResult<AdminUserVo>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 100, message = "每页最多 100 条") long size) {
        return ApiResult.of(userService.listUsers(keyword, page, size));
    }

    /**
     * 查询指定用户的详情。
     *
     * @param id 目标用户 ID
     * @return 用户详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResult<AdminUserVo>> detail(@PathVariable Long id) {
        return ApiResult.of(userService.getUserDetail(id));
    }

    /**
     * 启用或禁用用户。
     *
     * <p>禁用会同时撤销该用户的所有已签发凭证，立即生效。
     * 管理员不能对自己执行此操作 —— 那会把自己锁在后台外面。
     *
     * @param id      目标用户 ID
     * @param request 目标状态
     * @param me      当前登录的管理员
     * @return 成功时 data 为 null
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResult<Void>> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserStatusRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(userService.updateStatus(id, request.getStatus(), me.id()));
    }

    /**
     * 修改用户角色。
     *
     * <p>改完立即生效，无需用户重新登录。管理员不能对自己执行此操作 ——
     * 把自己降成普通用户后就再也进不去后台了。
     *
     * @param id      目标用户 ID
     * @param request 目标角色
     * @param me      当前登录的管理员
     * @return 成功时 data 为 null
     */
    @PutMapping("/{id}/role")
    public ResponseEntity<ApiResult<Void>> updateRole(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRoleRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResult.of(userService.updateRole(id, request.getRole(), me.id()));
    }
}
