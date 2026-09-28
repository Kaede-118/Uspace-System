package com.kaede.uspace.auth.dto;

/**
 * JWT 载荷。
 *
 * <p>只放三样东西：用户 ID、角色、token 版本号。载荷是<b>明文可读</b>的
 * （Base64 编码，不是加密），因此绝不能往里面放手机号、密码之类的敏感信息。
 *
 * <p>三个字段各自的用途：
 * <ul>
 *   <li>{@code userId} —— 请求到达时据此定位用户，是载荷的核心</li>
 *   <li>{@code role} —— <b>仅供参考与留痕，鉴权不用它</b>。
 *       真实角色取自数据库：每次请求都要查库比对版本号，
 *       既然已经查了，就没有理由再用一份可能过期的快照。
 *       好处是改角色立即生效，不必把用户踢下线</li>
 *   <li>{@code tokenVersion} —— 与库里的值比对，不一致即表示凭证已被撤销</li>
 * </ul>
 *
 * @param userId       用户 ID
 * @param role         签发时的角色（USER / ADMIN）
 * @param tokenVersion 签发时的 token 版本号
 */
public record JwtPayload(
        Long userId,
        String role,
        int tokenVersion
) {
}
