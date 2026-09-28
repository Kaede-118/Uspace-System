package com.kaede.uspace.common.security;

/**
 * 当前登录用户的身份载体。
 *
 * <p>认证过滤器验签通过后，把从数据库读出的用户信息装进它，放进
 * {@code SecurityContext}；Controller 用 {@code @AuthenticationPrincipal} 取出来，
 * 再把 {@code id} 传给 Service：
 *
 * <pre>
 *   &#64;GetMapping("/me")
 *   public ResponseEntity&lt;ApiResult&lt;UserProfileVo&gt;&gt; me(&#64;AuthenticationPrincipal UserPrincipal me) {
 *       return ApiResult.of(userService.getProfile(me.id()));
 *   }
 * </pre>
 *
 * <p><b>为什么放在 common 而不是 auth 包</b>：它是「当前用户」的通用概念，
 * 每个模块的 Controller 都要用它接收身份。若放在 auth 包，
 * 那么 user 包引用它就会形成 {@code user → auth} 的依赖，
 * 与既定的 {@code common ← user ← auth} 单向依赖方向冲突。
 * 放在 common，所有业务包都能引用它而不产生横向耦合。
 *
 * <p><b>Service 层不该见到本类</b>。Service 只接收 {@code userId} 参数、
 * 不感知认证方式 —— 这正是 Web 端与 QQ 机器人能复用同一套业务逻辑的前提：
 * 机器人链路从群消息取 QQ 号定位用户，直接传 userId 调 Service，
 * 全程不经过 HTTP 认证层。
 *
 * <p>刻意用 {@code record} 而非可变类：身份信息在单次请求内不应被修改，
 * 不可变对象可以在过滤器与业务代码之间安全传递。
 *
 * @param id       用户主键
 * @param username 登录名
 * @param nickname 昵称，用于界面展示与 QQ 群播报
 * @param role     角色，取值 USER / ADMIN
 */
public record UserPrincipal(
        Long id,
        String username,
        String nickname,
        String role
) {
}
