/**
 * 用户端路由。
 *
 * <p>meta 约定：
 * <ul>
 *   <li>{@code public: true} —— 不需要登录（登录、注册页）</li>
 *   <li>{@code bare: true}   —— 不显示底部 TabBar（登录、注册、以及将来的详情类页面）</li>
 *   <li>{@code tab: 'xxx'}   —— 该页对应 TabBar 上的哪一个 tab，用于高亮</li>
 *   <li>{@code title: 'xxx'} —— 浏览器标题</li>
 * </ul>
 *
 * <p>页面组件一律用<b>动态 import</b>：Vite 会据此做代码分割，
 * 首屏只加载当前页面的代码。用户端页面将来有二十来个，
 * 全量打包会让首次打开白屏时间明显变长。
 */
export default [
  { path: '/', redirect: '/home' },

  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/user/LoginView.vue'),
    meta: { public: true, bare: true, title: '登录' }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/user/RegisterView.vue'),
    meta: { public: true, bare: true, title: '注册' }
  },

  {
    path: '/home',
    name: 'home',
    component: () => import('@/views/user/HomeView.vue'),
    meta: { tab: 'home', title: '首页' }
  },
  {
    path: '/mall',
    name: 'mall',
    component: () => import('@/views/user/MallView.vue'),
    meta: { tab: 'mall', title: '商城' }
  },
  {
    path: '/mine',
    name: 'mine',
    component: () => import('@/views/user/MineView.vue'),
    meta: { tab: 'mine', title: '我的' }
  },

  {
    path: '/instore',
    name: 'instore',
    component: () => import('@/views/user/InstoreView.vue'),
    meta: { title: '在店用户' }
  },
  {
    path: '/devices',
    name: 'devices',
    component: () => import('@/views/user/DeviceView.vue'),
    meta: { title: '店内机台' }
  },

  /* ---- 个人 ---- */

  {
    path: '/profile',
    name: 'profile',
    component: () => import('@/views/user/ProfileEditView.vue'),
    meta: { title: '修改个人资料' }
  },
  {
    path: '/preference',
    name: 'preference',
    component: () => import('@/views/user/PreferenceView.vue'),
    meta: { title: '游玩偏好' }
  },
  {
    path: '/cards',
    name: 'cards',
    component: () => import('@/views/user/CardWalletView.vue'),
    meta: { title: '我的月卡' }
  },
  {
    path: '/product-orders',
    name: 'product-orders',
    component: () => import('@/views/user/ProductOrderView.vue'),
    meta: { title: '我的商品订单' }
  },

  /* ---- 包场 ---- */

  {
    path: '/bookings/host',
    name: 'booking-host',
    component: () => import('@/views/user/BookingHostView.vue'),
    meta: { title: '我发起的包场' }
  },
  {
    path: '/bookings/joined',
    name: 'booking-joined',
    component: () => import('@/views/user/BookingJoinedView.vue'),
    meta: { title: '我参与的包场' }
  },
  {
    // 邀请落地页。**需登录** —— 参与者名单要按人判定，匿名进来做不了这件事。
    // 未登录时路由守卫会先带到登录页，登录后凭 redirect 回到这里
    path: '/invite/:token',
    name: 'invite',
    component: () => import('@/views/user/InviteLandingView.vue'),
    meta: { title: '包场邀请' }
  },

  /* ---- 订单 ---- */

  {
    path: '/orders',
    name: 'orders',
    component: () => import('@/views/user/OrderListView.vue'),
    meta: { title: '我的订单' }
  },
  {
    // 放在 /orders/:id 前面只是为了一眼能看出「这是一条更具体的路径」，
    // vue-router 4 本身就按静态段多少打分，顺序不影响匹配结果
    path: '/orders/:id/settle',
    name: 'order-settle',
    component: () => import('@/views/user/SettleView.vue'),
    meta: { title: '结账' }
  },
  {
    path: '/orders/:id',
    name: 'order-detail',
    component: () => import('@/views/user/OrderDetailView.vue'),
    meta: { title: '订单详情' }
  },

  // 兜底：未匹配的路径回首页。
  // 放在用户端路由数组的末尾 —— 后台路由（任务 10 加入）会在此之前匹配，
  // 不会被这条吃掉（vue-router 按注册顺序匹配，后台路径更具体）。
  { path: '/:pathMatch(.*)*', redirect: '/home' }
]
