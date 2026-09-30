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
import './assets/styles/base.css'

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
