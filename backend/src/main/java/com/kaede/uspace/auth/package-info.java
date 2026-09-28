/**
 * 权限管理（模块 2）。
 *
 * <p>职责：管理员与普通用户权限控制 —— 落到代码上是三件事：
 * 凭用户名密码换凭证（登录）、每个请求的身份校验、以及基于角色的接口鉴权。
 *
 * <p><b>与模块 1（{@code user} 包）的分工</b>：本包管「凭什么证明是他」，
 * user 包管「用户是谁」。登录时本包调用
 * {@link com.kaede.uspace.user.UserService#verifyCredentials} 拿认证结果，
 * 再签发凭证；密码如何比对、账号是否禁用这些规则全在模块 1，
 * 本包不重复判断。
 *
 * <p><b>依赖方向：{@code common ← user ← auth}</b>，单向无环。
 * 本包可以调用 user 包的 Service，反过来不行 ——
 * 否则 QQ 机器人链路（不经 HTTP 认证层，直接从群消息取 QQ 号定位用户）
 * 会被无关的依赖拖住。
 *
 * <p>包内主要构成：
 * <ul>
 *   <li>{@code JwtService} —— 签发与解析凭证。不查库，可脱离容器单测</li>
 *   <li>{@code JwtAuthenticationFilter} —— 每请求验签 + 查库比对版本号，写入身份</li>
 *   <li>{@code SecurityConfig} —— 过滤器链、密码编码器、跨域配置</li>
 *   <li>{@code AuthService} / {@code AuthController} —— 登录与登出</li>
 *   <li>{@code RestAuthenticationEntryPoint} / {@code RestAccessDeniedHandler}
 *       —— 401 与 403 的统一 JSON 出口</li>
 * </ul>
 *
 * <p><b>撤销机制</b>：JWT 签发后在有效期内本来无法收回，本系统靠
 * {@code sys_user.token_version} 补上这一环 —— 封禁与改密时该字段 +1，
 * 而每个请求都要拿凭证里的版本号与库里的比对，对不上即视为已撤销。
 * 因此这两类操作是<b>立即生效</b>的，不必等凭证自然过期。
 * 改角色是例外：鉴权时角色取自数据库，改完立即生效，不需要撤销任何东西。
 *
 * <p>对应毕业论文中的「权限管理」章节。
 */
package com.kaede.uspace.auth;
