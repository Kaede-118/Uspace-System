/**
 * 认证接口（模块 2）。
 *
 * <p>路径前缀 {@code /api/auth}。登录匿名可访问，登出需登录。
 */
import http from './http'

/**
 * 登录。
 *
 * <p>⚠️ 密码错误时后端返回的是 <b>401</b>，而 `http.js` 的 401 处置名单里
 * 排除了本接口 —— 否则用户输错一次密码，看到的会是「登录已过期」而不是
 * 「用户名或密码错误」。
 *
 * @param {string} username 用户名
 * @param {string} password 明文密码（HTTPS 下传输；后端用 BCrypt 校验）
 * @returns {Promise<{code:number, message:string, data:{token:string, user:object}}>}
 *          登录成功后 data 里是 JWT 与用户资料
 */
export function login(username, password) {
  return http.post('/api/auth/login', { username, password })
}

/**
 * 登出。
 *
 * <p>服务端做的是把 {@code token_version} 加一（令所有旧凭证立即失效），
 * 而不是清 session —— 本系统无 session。前端另需清掉本地的 token 与用户缓存。
 *
 * @returns {Promise} 无论成功失败，前端都会清本地登录态
 */
export function logout() {
  return http.post('/api/auth/logout')
}
