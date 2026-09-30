/**
 * 错误类型与错误码常量。
 *
 * <p>后端所有失败响应都是同一个结构 {@code {code, message, data}}，
 * 其中 {@code code} 是五位业务码（前三位与 HTTP 状态码对齐）。
 * 这里把前端需要【按码分支处理】的几个挑出来做成常量 ——
 * 其余的错误直接展示后端给的 {@code message} 即可，不必在前端再维护一份文案表
 * （维护两份的下场是后端改了文案、前端还显示旧的，而且没人发现）。
 */

/**
 * 业务错误。
 *
 * <p>比 axios 原生的 Error 多带一个 {@code code}，
 * 让调用方能按业务码分支（例如 40931 要引导用户去支付，而不是弹个提示了事）。
 */
export class ApiError extends Error {
  /**
   * @param {string} message 面向用户的错误文案（直接来自后端）
   * @param {number} code    五位业务码，网络层错误时为 0
   * @param {number} status  HTTP 状态码，网络层错误时为 0
   */
  constructor(message, code = 0, status = 0) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

/**
 * 前端需要按码分支处理的业务码。
 *
 * <p>⚠️ 只放【需要不同处置】的码。能在界面上统一弹一句 message 就解决的，
 * 不要往这里加 —— 加多了之后没人说得清哪些是真的有分支。
 */
export const ErrorCode = {
  /** 400：参数校验失败（message 里是各字段错误的拼接，用「；」分隔） */
  PARAM_INVALID: 40000,
  /** 401：请先登录（压根没带凭证） */
  UNAUTHORIZED: 40100,
  /** 401：凭证无效（签名不对、被篡改） */
  TOKEN_INVALID: 40101,
  /** 401：凭证过期 */
  TOKEN_EXPIRED: 40102,
  /** 401：用户名或密码错误 */
  BAD_CREDENTIALS: 40103,
  /** 403：角色不足 */
  FORBIDDEN: 40300,
  /** 403：账号被禁用 */
  ACCOUNT_DISABLED: 40301,
  /** 409：已有进行中的订单 —— 引导去「查看密码」 */
  ORDER_ALREADY_ACTIVE: 40919,
  /** 409：有未支付的订单 —— 引导去「去支付」 */
  ORDER_UNPAID_EXISTS: 40931,
  /** 409：该时段已被包场，非参与者进不去 */
  BOOKING_ACCESS_DENIED: 40917,
  /** 409：商品已售罄 */
  PRODUCT_SOLD_OUT: 40932,
  /** 413：图片太大 */
  UPLOAD_FILE_TOO_LARGE: 41300,
  /** 400：图片格式不支持 */
  UPLOAD_FILE_INVALID: 40001
}

/**
 * 判断一个错误是不是指定的业务码。
 *
 * @param {unknown} err  捕获到的错误对象
 * @param {number}  code 业务码
 * @returns {boolean} 不是 ApiError 时返回 false（不抛异常）
 */
export function isCode(err, code) {
  return err instanceof ApiError && err.code === code
}

/**
 * 从一个未知的错误对象里取出可以展示给用户的文案。
 *
 * @param {unknown} err     捕获到的错误
 * @param {string}  fallback 取不到时的兜底文案
 * @returns {string} 面向用户的提示
 */
export function errorMessage(err, fallback = '操作失败，请稍后重试') {
  if (err instanceof ApiError && err.message) return err.message
  if (err instanceof Error && err.message) return err.message
  return fallback
}
