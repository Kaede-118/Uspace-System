<script setup>
/**
 * 顶部导航栏（返回 + 标题）。
 *
 * <p>详情类页面用它 —— 这些页面不在 TabBar 上，用户靠它返回。
 * 用 {@code router.back()} 而不是跳固定路径：从首页进来和从订单列表进来，
 * 「返回」应该回到各自来的地方。
 *
 * <p>已经在历史的入口页时 {@code back()} 会退出应用（在某些 WebView 里表现为白屏），
 * 所以先判断 {@code history.state.back} —— 为空说明没有上一页，改跳首页。
 */
import { useRouter } from 'vue-router'

defineProps({
  /** 标题 */
  title: { type: String, default: '' },
  /** 是否显示返回按钮 */
  back: { type: Boolean, default: true }
})

const router = useRouter()

function onBack() {
  // history.state.back 为 null 说明这是历史的第一个条目，没有可返回的页面
  if (window.history.state?.back) {
    router.back()
  } else {
    router.replace('/home')
  }
}
</script>

<template>
  <header class="navbar">
    <button v-if="back" class="navbar__back" aria-label="返回" @click="onBack">‹</button>
    <h1 class="navbar__title">{{ title }}</h1>
    <div class="navbar__right">
      <slot name="right" />
    </div>
  </header>
</template>

<style scoped>
.navbar {
  position: sticky;
  top: 0;
  z-index: 50;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  height: calc(var(--navbar-h) + var(--safe-top));
  padding: var(--safe-top) var(--sp-4) 0;
  background: var(--c-bg);
  /* 内容滚动到下面时给一点分界，避免标题与卡片糊在一起 */
  border-bottom: 1px solid var(--c-border);
}

.navbar__back {
  width: 32px;
  height: 32px;
  margin-left: -8px;
  font-size: 26px;
  line-height: 1;
  color: var(--c-text);
  display: flex;
  align-items: center;
  justify-content: center;
}

.navbar__title {
  flex: 1;
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.navbar__right {
  flex-shrink: 0;
}
</style>
