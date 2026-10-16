/**
 * localStorage 封装。
 *
 * <p>统一在这里读写而不是各处直接调 {@code localStorage}，有两个理由：
 * 一是 key 名只有一处定义，改名不会漏掉某个页面；
 * 二是 {@code localStorage} 在隐私模式、存储配额满时会抛异常，
 * 裸调会让整个页面挂掉，这里统一吞掉并返回默认值。
 *
 * <p>⚠️ <b>token 存在 localStorage 是有 XSS 风险的</b>（脚本能读到它）。
 * 这是权衡后的选择：本系统用 {@code Authorization} 头携带凭证（不用 Cookie），
 * 而前端只有这一个存储位置。缓解手段是后端对用户输入做转义、不渲染富文本。
 * 答辩时要主动承认这一点，并说明为什么没选 HttpOnly Cookie
 * （跨域 + 微信内置浏览器的限制，见项目 CLAUDE.md 的「认证与权限方案」）。
 */

const TOKEN_KEY = 'uspace_token'
const USER_KEY = 'uspace_user'

/**
 * 安全地读一个 key。
 *
 * @param {string} key 键名
 * @returns {string|null} 读不到或抛异常时返回 null
 */
function safeGet(key) {
  try {
    return window.localStorage.getItem(key)
  } catch {
    return null
  }
}

/**
 * 安全地写一个 key。
 *
 * @param {string} key   键名
 * @param {string} value 值
 */
function safeSet(key, value) {
  try {
    window.localStorage.setItem(key, value)
  } catch {
    // 存储不可用（隐私模式、配额满）时静默失败 ——
    // 用户的这次操作仍然会成功，只是刷新页面后需要重新登录
  }
}

/** 取 token。 */
export function getToken() {
  return safeGet(TOKEN_KEY)
}

/**
 * 存 token。
 *
 * @param {string} token JWT
 */
export function setToken(token) {
  safeSet(TOKEN_KEY, token)
}

/** 清除 token（登出、401 时调用）。 */
export function clearToken() {
  try {
    window.localStorage.removeItem(TOKEN_KEY)
  } catch {
    // 同上，静默失败
  }
}

/** 取缓存的用户信息。解析失败返回 null。 */
export function getUser() {
  const raw = safeGet(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

/**
 * 缓存用户信息。
 *
 * @param {object} user 用户资料对象
 */
export function setUser(user) {
  safeSet(USER_KEY, JSON.stringify(user))
}

/** 清除缓存的用户信息。 */
export function clearUser() {
  try {
    window.localStorage.removeItem(USER_KEY)
  } catch {
    // 静默失败
  }
}

/** 清空全部登录态（登出时一次做完，避免漏掉某一项）。 */
export function clearAuth() {
  clearToken()
  clearUser()
}

/* ---------------- 「添加到桌面」引导 ---------------- */

/**
 * ⚠️ 这个 key 刻意不跟着 {@link clearAuth} 一起清：它记的是「这个用户
 * 不想再看到那条提示」，与登录态是两件事 —— 登出后再进站仍然不该提示。
 */
const INSTALL_GUIDE_KEY = 'uspace_install_guide_dismissed'

/**
 * 「添加到桌面」引导提示是否已被用户关掉过。
 *
 * <p>关掉就永久不再提示 —— 它是「建议」而不是「通知」，
 * 反复出现只会让人烦；想再看到它，清一次浏览器数据即可。
 *
 * @returns {boolean} 关过返回 true
 */
export function isInstallGuideDismissed() {
  return safeGet(INSTALL_GUIDE_KEY) === '1'
}

/** 记下「用户关掉了添加到桌面的引导」（点「暂不」「知道了」，或已经装完了）。 */
export function dismissInstallGuide() {
  safeSet(INSTALL_GUIDE_KEY, '1')
}
