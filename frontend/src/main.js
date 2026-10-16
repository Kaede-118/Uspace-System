/**
 * 应用入口。
 *
 * <p>这里做三件事：挂载 Vue、装配路由、把「登录态失效」的处置函数注入给
 * axios 实例。第三件事之所以绕一圈（而不是在 `api/http.js` 里直接 import router），
 * 是为了避开一条循环依赖：{@code http.js → router → views → api/*.js → http.js}。
 * 循环依赖在打包时未必报错，但会让初始化顺序变得不可预测。
 */
import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api/http'
import { clear } from './stores/user'
import { installUiScale } from './utils/uiScale'
import './assets/styles/base.css'

/**
 * 桌面端整体等比放大（宽窗口下 > 1，见 `utils/uiScale.js`）。
 *
 * <p>必须在 `mount` 之前：这时页面上还没有内容，不会「先按原样画一遍再跳一下」。
 */
installUiScale()

const app = createApp(App)
app.use(router)

/**
 * 登录态失效时的处置：清本地凭证 + 跳登录页。
 *
 * <p>已经在登录页时不再跳 —— 否则用户在登录页上输错一次密码，
 * 会被「跳转到登录页」再刷一次，看起来像页面闪了一下。
 * （登录接口的 401 本来就被 `http.js` 排除在处置之外，这是第二道保险。）
 */
setUnauthorizedHandler(() => {
  clear()
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    router.replace({ name: 'login', query: { redirect: current.fullPath } })
  }
})

app.mount('#app')

/**
 * 注册 Service Worker。
 *
 * <p><b>它存在的唯一理由是让站点「可安装」</b> —— 手机浏览器弹
 * 「添加到桌面」原生窗口的前提之一，就是站点注册了一个带 fetch 处理器的 SW
 * （见 `public/sw.js`）。那个 SW 是空的、【不做任何缓存】，对请求完全透明，
 * 所以这对现有功能零影响。
 *
 * <p>两处刻意的写法：
 * ① 在 {@code load} 之后注册 —— 不跟首屏资源抢带宽（穿透环境下一个请求一秒多，
 *    而 SW 注册并不急着完成）；
 * ② <b>失败静默</b> —— 非 HTTPS（局域网调试的 `http://192.168.x.x:5173` 就是）、
 *    隐私模式、浏览器不支持，都会注册失败。它只影响「能否添加到桌面」，
 *    不影响任何正常功能，为它报错只会让用户困惑。
 */
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      // 静默：可安装能力不可用而已，功能一切照常
    })
  })
}
