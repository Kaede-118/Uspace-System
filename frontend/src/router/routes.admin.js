/**
 * 运营后台路由。
 *
 * <p>四个页面（公告 / 机台 / 包场 / 商品）挂在一个带左侧导航的父路由下，
 * 父组件是 {@code AdminLayout}。父路由自己不渲染页面，直接重定向到第一个 tab。
 *
 * <p>{@code meta.admin = true} <b>只写在父路由上</b>：vue-router 4 的
 * {@code route.meta} 是「所有匹配到的路由记录的 meta 合并结果」
 * （父在前、子在后，同名时子覆盖父，见 vue-router 的 {@code mergeMetaFields}），
 * 所以四个子路由天然继承这个标记，不必逐个再写一遍 ——
 * 逐个写反而会在将来加页时漏掉，而漏掉的后果是那个页面**不设防地对外开**。
 *
 * <p>⚠️ 但无论如何，<b>路由守卫不是安全边界</b>：真正的隔离是服务端的
 * {@code @PreAuthorize("hasRole('ADMIN')")}，越权进到页面里也拿不到任何数据。
 *
 * <p>页面组件一律<b>动态 import</b>，与用户端同一套代码分割策略 ——
 * 普通顾客永远不会加载后台这几页的代码。
 */
export default [
  {
    path: '/admin',
    component: () => import('@/views/admin/AdminLayout.vue'),
    redirect: '/admin/notices',
    meta: { admin: true, title: '运营后台' },
    children: [
      {
        path: 'notices',
        name: 'admin-notices',
        component: () => import('@/views/admin/NoticeManageView.vue'),
        meta: { title: '公告管理' }
      },
      {
        path: 'devices',
        name: 'admin-devices',
        component: () => import('@/views/admin/DeviceManageView.vue'),
        meta: { title: '机台管理' }
      },
      {
        path: 'bookings',
        name: 'admin-bookings',
        component: () => import('@/views/admin/BookingManageView.vue'),
        meta: { title: '包场排期' }
      },
      {
        path: 'products',
        name: 'admin-products',
        component: () => import('@/views/admin/ProductManageView.vue'),
        meta: { title: '商品管理' }
      }
    ]
  }
]
