/**
 * 用户管理（模块 1）。
 *
 * <p>职责：用户注册、登录及信息维护 —— 落到代码上是注册、资料维护、密码修改、
 * 凭据校验，以及管理员侧的用户列表、封禁启用与角色调整。
 *
 * <p><b>与模块 2（{@code auth} 包）的分工</b>：本包管「用户是谁」，
 * auth 包管「凭什么证明是他」。登录时本包只负责比对用户名与密码
 * （{@link com.kaede.uspace.user.UserService#verifyCredentials} 返回认证通过的用户，
 * 它不知道 JWT 是什么），签发与校验凭证全部归 auth 包。
 *
 * <p>这样划分有两个收益：一是 {@code user} 包不依赖 {@code auth} 包，
 * 依赖方向是单向的 {@code common ← user ← auth}；
 * 二是 QQ 机器人链路（不经过 HTTP 认证层，直接从群消息取 QQ 号定位用户）
 * 能原样复用本包的全部逻辑，不必为它另写一套。
 *
 * <p>包内结构：
 * <ul>
 *   <li>{@code entity} —— 数据库实体</li>
 *   <li>{@code mapper} —— 数据访问接口，全部为具名方法以便单测替换</li>
 *   <li>{@code dto} —— 请求与响应对象。响应用的是 VO 白名单，
 *       实体绝不直接对外，避免 {@code passwordHash} 这类字段外泄</li>
 * </ul>
 *
 * <p>对应毕业论文中的「用户管理」章节。
 */
package com.kaede.uspace.user;
