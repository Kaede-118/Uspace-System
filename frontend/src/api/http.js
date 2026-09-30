/**
 * axios 实例：全站唯一的 HTTP 出口。
 *
 * <p>所有请求都从这里走，为的是让认证、错误处理、超时这三件事只有一处定义。
 * 各业务模块（`api/user.js` 等）只负责拼路径与参数，不自己 new axios。
 */
import axios from 'axios'
import { ApiError } from '@/utils/error'
import { getToken, clearAuth } from '@/utils/storage'

/**
 * 后端地址。
 *
 * <p>⚠️ <b>不能写死 {@code localhost}</b>。开发时 {@code vite.config.js} 开了
 * {@code server.host = true}，用手机访问 {@code http://192.168.1.5:5173} 调试时，
 * 写死的 localhost 会让【手机去连它自己的 8080】——
 * 报出来的错既不像跨域也不像超时，很难查。
 *
 * <p>用 {@code ??} 而不是 {@code ||}：生产构建时把 {@code VITE_API_BASE_URL}
 * 显式设成空串（nginx 把 {@code /api} 反代到后端，用相对路径即可），
 * 空串是 falsy，用 {@code ||} 会被 fallback 掉、又指回 8080 端口。
 */
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? `http://${location.hostname}:8080`

/**
 * 不需要「登录态失效」处置的路径。
 *
 * <p>⚠️ <b>少了这份名单会有个很别扭的 bug</b>：登录接口在密码错误时返回的是
 * <b>401</b>（`BAD_CREDENTIALS`）。若不排除，用户输错一次密码就会被当成
 * 「登录已过期」——页面跳转一下、提示语被覆盖成「登录已过期」，
 * 而他真正需要看到的是「用户名或密码错误」。
 */
const AUTH_FREE_PATHS = ['/api/auth/login', '/api/user/register']

/** 登录态失效时的处置函数，由 main.js 注入（避免 http → router → views → http 的循环依赖）。 */
let unauthorizedHandler = null

/**
 * 注册登录态失效的处置函数。
 *
 * @param {Function} fn 无参函数，通常做「清登录态 + 跳登录页」
 */
export function setUnauthorizedHandler(fn) {
  unauthorizedHandler = fn
}

/** 并发请求同时 401 时只处置一次 —— 否则会连跳好几次路由。 */
let handlingUnauthorized = false

/** 触发登录态失效处置。 */
function fireUnauthorized() {
  if (handlingUnauthorized) return
  handlingUnauthorized = true
  clearAuth()
  if (unauthorizedHandler) unauthorizedHandler()
  // 下一轮事件循环放开，让并发的那几个 401 走完（它们已经被 clearAuth 清干净了）
  setTimeout(() => {
    handlingUnauthorized = false
  }, 500)
}

const http = axios.create({
  baseURL: BASE_URL,
  timeout: 15000,
  /**
   * ⚠️ <b>绝对不能设 {@code withCredentials: true}</b>。
   * 后端 CORS 是 {@code AllowedOriginPatterns("*")} + {@code allowCredentials(false)}，
   * 而浏览器【禁止】「来源为 * 且携带凭据」的组合 —— 设了它，所有请求会直接失败，
   * 且报的是一个含义模糊的跨域错误。本系统用 {@code Authorization} 头携带凭证，
   * 不依赖 Cookie，所以本来也不需要它。
   */
  withCredentials: false
})

/* ---------------- 请求拦截：带上凭证 ---------------- */

http.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

/* ---------------- 响应拦截：剥一层 + 统一错误 ---------------- */

http.interceptors.response.use(
  /**
   * 成功分支（HTTP 2xx）。
   *
   * <p>这里【剥掉一层】{@code response.data}，返回后端的 ApiResult 本身。
   * 于是调用方写 {@code const resp = await xxx()} 拿到的是 {@code {code, message, data}}，
   * 再用 {@code resp.data} 取业务数据。
   *
   * <p>⚠️ 调用方容易少写一层：把 {@code resp} 直接当业务数据用。
   * 那样得到的是一个恒为真的对象（ApiResult 本身），
   * 于是「有没有进行中的订单」这类判断永远为真，按钮永远不出现，
   * 而控制台里一行报错都没有。
   */
  (response) => {
    const body = response.data
    // 支付回调那两个端点返回的不是 ApiResult（微信/支付宝自己的协议），
    // 但前端不会直接调它们，这里统一按 ApiResult 处理即可
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body
      // 理论上 HTTP 2xx 时 code 一定是 0；真撞上了也当作业务失败处理，
      // 总比把 data 里的 null 当成功返回给调用方强
      return Promise.reject(new ApiError(body.message || '操作失败', body.code, response.status))
    }
    return body
  },

  /**
   * 失败分支（非 2xx，或压根没发出请求）。
   *
   * <p>后端用的是【真实 HTTP 状态码】而不是一律 200，所以绝大多数业务失败都走这里。
   */
  (error) => {
    const response = error.response
    const body = response?.data
    const status = response?.status || 0

    // 登录接口自己的 401 不触发「登录过期」处置，见 AUTH_FREE_PATHS 的说明
    const url = error.config?.url || ''
    const isAuthFree = AUTH_FREE_PATHS.some((path) => url.startsWith(path))

    if (status === 401 && !isAuthFree) {
      fireUnauthorized()
    }

    let message = body?.message || ''
    if (!message) {
      if (error.code === 'ECONNABORTED') {
        message = '请求超时，请检查网络后重试'
      } else if (!response) {
        message = '无法连接服务器，请确认后端已启动'
      } else if (status === 401) {
        message = '登录已过期，请重新登录'
      } else if (status === 403) {
        message = '没有权限执行该操作'
      } else if (status === 404) {
        message = '请求的资源不存在'
      } else if (status >= 500) {
        message = '服务异常，请稍后重试'
      } else {
        message = '操作失败，请稍后重试'
      }
    }

    // 401 且不处置时（登录接口），文案用后端给的「用户名或密码错误」，
    // 而不是上面兜底出来的「登录已过期」—— 后者会把用户引到错误的方向
    return Promise.reject(new ApiError(message, body?.code || 0, status))
  }
)

export default http
export { BASE_URL }
