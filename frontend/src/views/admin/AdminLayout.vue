<script setup>
/**
 * 运营后台的外壳：顶栏 + 七个 tab 的导航 + 子页面出口。
 *
 * <p>与用户端不同，后台<b>没有底部 TabBar</b>（那三个图标是给顾客用的）。
 * 导航放在顶部，七个入口都是 {@code <router-link>}，分两行排
 * （一行四个 —— 单个一行装不下，而横向拉动的入口最难找，见下方样式里的说明）。
 *
 * <p>⚠️ <b>高亮交给 {@code router-link-active}</b>，不要自己监听路由算当前项 ——
 * 将来加了 {@code /admin/bookings/:id} 这类子路径，手工判断必然会漏，
 * 而漏掉的表现只是「导航没有高亮」，不会有任何报错。
 *
 * <p>⚠️ <b>必须有一个回用户端的出口</b>：管理员从后台进去之后，
 * 浏览器后退之外没有别的路可走（后台没有 TabBar，NavBar 的返回按钮也不适用）。
 */
import { useRouter } from 'vue-router'
import { logout } from '@/api/auth'
import { clear } from '@/stores/user'

const router = useRouter()

/** 七个 tab。顺序即导航顺序，与论文里模块的排列无关，按运营的使用频率排。 */
const TABS = [
  { to: '/admin/users', icon: '👤', label: '用户' },
  { to: '/admin/notices', icon: '📢', label: '公告' },
  { to: '/admin/devices', icon: '🕹', label: '机台' },
  { to: '/admin/bookings', icon: '📅', label: '包场' },
  { to: '/admin/store', icon: '🏠', label: '门店' },
  { to: '/admin/payments', icon: '💳', label: '收款' },
  { to: '/admin/products', icon: '🛍', label: '商品' }
]

/**
 * 退出登录。
 *
 * <p>与「我的」页同一套做法：后端的 logout 接口什么也不做（JWT 无状态，
 * 没有会话可销毁），真正的登出是删掉本地凭证 —— 所以接口失败也必须清干净并跳走。
 */
async function onLogout() {
  try {
    await logout()
  } catch (err) {
    // 凭证已失效时返回 401，那也意味着「已经登出了」，忽略即可
  }
  clear()
  router.replace('/login')
}
</script>

<template>
  <div class="admin">
    <header class="admin__bar">
      <div class="admin__bar-inner">
        <h1 class="admin__title">运营后台</h1>
        <div class="admin__actions">
          <router-link class="admin__link" to="/home">回用户端</router-link>
          <button class="admin__link" @click="onLogout">退出</button>
        </div>
      </div>

      <nav class="admin__nav">
        <div class="admin__nav-inner">
          <router-link v-for="tab in TABS" :key="tab.to" class="admin__tab" :to="tab.to">
            <span class="admin__tab-icon">{{ tab.icon }}</span>
            <span>{{ tab.label }}</span>
          </router-link>
        </div>
      </nav>
    </header>

    <router-view />
  </div>
</template>

<style scoped>
/*
 * 顶栏与导航各自贴顶、整体吸顶。
 *
 * 顶栏自己吃掉安全区（刘海），所以下面子页面的 .page 不需要再让一次 ——
 * 两处各让一次的话，iPhone 上标题与内容之间会空出一大片。
 */
.admin__bar {
  position: sticky;
  top: 0;
  z-index: 100;
  background: var(--c-bg);
  border-bottom: 1px solid var(--c-border);
  padding-top: var(--safe-top);
}

/*
 * ⚠️ 后台的三个容器都用 --page-max-admin（900）而不是 --page-max：
 * 后者是【用户端】的宽度（480，按手机来）。后台是电脑上的工具，
 * 页面里是表格与多列表单，窄了会出现横向滚动 —— 两处必须一起改，
 * 漏掉 .page 这一处的话，顶栏与页面内容会在窄列与宽列之间错位。
 */
.admin :deep(.page) {
  padding-top: var(--sp-4);
  max-width: var(--page-max-admin);
}

.admin__bar-inner,
.admin__nav-inner {
  max-width: var(--page-max-admin);
  margin: 0 auto;
  padding: 0 var(--sp-4);
}

.admin__bar-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  height: var(--navbar-h);
}

.admin__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-primary);
}

.admin__actions {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
}

.admin__link {
  font-size: 12px;
  color: var(--c-text-sub);
}

/*
 * ⚠️ 两行、一行四个（2026-10-03 改）。
 *
 * 原先是一行 `overflow-x: auto`：六个 tab 在手机上放不下，要靠横向拉动
 * 才够得着后面两个 —— 而后台恰恰是「急着办事」的地方，藏起来的入口最难找。
 * 改成 grid 之后每个格子等宽、两行排齐，一眼全在。
 *
 * ⚠️ 因此 `.admin__tab` 要能填满格子（去掉 flex-shrink 与定宽 padding）——
 * 留着 flex-shrink: 0 在 grid 里是无害的废样式，但 padding 会把人挤得贴边。
 */
.admin__nav-inner {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--sp-2);
  padding-bottom: var(--sp-2);
}

.admin__tab {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  height: 32px;
  padding: 0 var(--sp-2);
  border-radius: var(--r-pill);
  background: var(--c-card);
  color: var(--c-text-sub);
  font-size: 13px;
}

/* 高亮态由 vue-router 自动挂上，见文件头那段说明 */
.admin__tab.router-link-active {
  background: var(--c-primary);
  color: #fff;
}

.admin__tab-icon {
  font-size: 13px;
}
</style>
