/**
 * 路由配置与全局守卫。
 *
 * <p><b>用 hash 模式</b>（URL 里带 {@code #}）。reason：
 * history 模式少配一条 nginx {@code try_files $uri /index.html}，
 * 刷新子页面就会 404 —— 而这种问题往往在演示当天才暴露。
 * hash 模式部署到任何静态服务器都能跑，代价只是 URL 多一个 {@code #}。
 *
 * <p>⚠️ <b>路由守卫不是安全边界</b>：改前端代码就能进 {@code /admin} 页面。
 * 真正的隔离是服务端的 {@code @PreAuthorize("hasRole('ADMIN')")} ——
 * 越权进到页面里也拿不到任何数据，每个请求都会被挡回 403。
 * 守卫的价值只是省掉「进去看到一片空白」的坏体验。
 */
import { createRouter, createWebHashHistory } from 'vue-router'
import userRoutes from './routes.user'
import { isLoggedIn, isAdmin } from '@/stores/user'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    ...userRoutes
    // 运营后台路由（任务 10）挂在这里。它会自带一层 { path: '/admin', meta: { admin: true } }，
    // 下面的守卫已经支持 admin 标记，届时不必改这个文件
  ],
  /**
   * 切换路由时回到页面顶部。
   *
   * 不加的话，从长列表页点进详情页时会停在原滚动位置，用户以为页面没加载完。
   * 返回时恢复位置的体验更好，但需要额外保存滚动位置，本期不做。
   */
  scrollBehavior() {
    return { top: 0 }
  }
})

router.beforeEach((to) => {
  const loggedIn = isLoggedIn.value

  // 公开页面：已登录的话就别再去登录/注册页了
  if (to.meta.public) {
    if (loggedIn && (to.name === 'login' || to.name === 'register')) {
      return { name: 'home' }
    }
    return true
  }

  // 其余页面一律要求登录，并把原目标记在 query 里，登录后跳回去
  if (!loggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  // 后台页面要求 ADMIN。非管理员回首页 ——
  // 不跳登录页是有意的：他已经登录了，再让他登录一次会让人困惑
  if (to.meta.admin && !isAdmin.value) {
    return { name: 'home' }
  }

  return true
})

router.afterEach((to) => {
  const base = '共享娱乐空间'
  document.title = to.meta.title ? `${to.meta.title} · ${base}` : base
})

export default router
