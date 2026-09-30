<script setup>
/**
 * 根组件。
 *
 * <p>它只负责两件全局的事：渲染当前路由对应的页面、按需显示底部 TabBar。
 *
 * <p>TabBar 用 {@code v-if} 挂在根组件上、而不是让每个页面自己包含一个 ——
 * 后者会在页面切换时销毁重建，底部导航会闪一下。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import TabBar from '@/components/TabBar.vue'
import ToastHost from '@/components/ToastHost.vue'

const route = useRoute()

/**
 * 是否显示底部 TabBar。
 *
 * <p>判据是路由 meta 上的 {@code tab} 字段：只有三个主页面（首页 / 商城 / 我的）
 * 声明了它。登录、注册以及将来的各种详情页都不声明，自然就不显示。
 */
const showTabBar = computed(() => !!route.meta.tab)
</script>

<template>
  <router-view />
  <TabBar v-if="showTabBar" />
  <ToastHost />
</template>
