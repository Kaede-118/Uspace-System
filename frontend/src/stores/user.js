/**
 * 当前用户状态。
 *
 * <p><b>不用 Pinia</b>：全局状态只有「当前用户」这一项，
 * 引一个状态管理库是给一个变量配一套基础设施。这里用 {@code reactive}
 * 加 localStorage 就够了 —— 刷新页面后从 localStorage 里恢复，
 * 真正的有效性由服务端每次请求校验（token 里带着 {@code token_version}）。
 *
 * <p>这是一个<b>模块级单例</b>：所有页面 import 到的是同一份 state。
 * 不写成 {@code useXxxStore()} 工厂函数是因为没有「多个实例」的需求，
 * 工厂形式反而让人以为每次调用拿到的是新对象。
 */
import { reactive, computed } from 'vue'
import * as storage from '@/utils/storage'

/** 全局唯一的状态。初始化时从 localStorage 恢复，让刷新页面不掉登录态。 */
const state = reactive({
  user: storage.getUser(),
  token: storage.getToken() || ''
})

export { state as userState }

/** 是否已登录。判断依据是本地有没有 token，不代表 token 一定有效。 */
export const isLoggedIn = computed(() => !!state.token)

/** 是否管理员。用于决定导航栏要不要显示后台入口。 */
export const isAdmin = computed(() => state.user?.role === 'ADMIN')

/** 展示名：优先昵称，退回用户名。 */
export const displayName = computed(
  () => state.user?.nickname || state.user?.username || '顾客'
)

/** 当前用户的头像路径，未设置时为 null（前端回落默认头像）。 */
export const avatar = computed(() => state.user?.avatar || null)

/** 当前用户的背景图路径，未设置时为 null（卡片回落纯色底）。 */
export const banner = computed(() => state.user?.banner || null)

/**
 * 登录成功后写入凭证与用户资料。
 *
 * @param {string} token JWT
 * @param {object} user  用户资料（后端登录响应里的 user）
 */
export function setAuth(token, user) {
  state.token = token
  state.user = user
  storage.setToken(token)
  storage.setUser(user)
}

/**
 * 更新用户资料（改资料、换头像后调用）。
 *
 * <p>接口返回的都是完整的 {@code UserProfileVo}，所以直接整体替换即可，
 * 不必逐个字段合并。
 *
 * @param {object} user 完整的用户资料
 */
export function setUser(user) {
  state.user = user
  storage.setUser(user)
}

/**
 * 清空登录态。
 *
 * <p>登出、token 失效、改密码成功后都要调它。
 * 本地状态与 localStorage 一起清，避免出现「内存里没了、刷新又回来」的怪象。
 */
export function clear() {
  state.token = ''
  state.user = null
  storage.clearAuth()
}
