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
import LoadingMask from '@/components/LoadingMask.vue'
import InstallGuide from '@/components/InstallGuide.vue'
import { useLoading } from '@/composables/useLoading'

const route = useRoute()

/**
 * 全局请求遮罩的状态。
 *
 * <p>由 `api/http.js` 的拦截器驱动 —— 任何走 http 实例的请求在途时都会亮起
 * （轮询类请求除外，它们带 silent 标记，见 `usePreviewPolling` / `usePayment`）。
 */
const { loadingVisible, loadingText } = useLoading()

/**
 * 需要保活（keep-alive）的页面 —— 组件名，不是路由名。
 *
 * <p>缓存的收益：切走再回来时【不重新挂载、不重新拉接口】，立刻显示上次的内容
 *（数据过期时由页面自己的 {@code useKeepAliveRefresh} 静默补一次）。
 * 经隧道时每一次重新加载都是实打实的一秒多。
 *
 * <p>⚠️ 名字必须与组件文件名一致 —— Vue 从文件名推断 {@code <script setup>}
 * 的组件名，各页面里也有显式的 {@code defineOptions({ name })} 兜底。
 * 写错的表现是<b>静默失效</b>：页面照常工作，只是缓存没生效、返回时仍然重新加载。
 *
 * <p>⚠️ <b>只放列表类页面</b>。订单详情、结账、邀请落地页这类要按单据实时
 * 反映状态的页面不能缓存：缓存的旧状态会让用户以为自己的操作没生效。
 */
const KEEP_ALIVE_PAGES = ['HomeView', 'MallView', 'MineView']

/**
 * 是否显示底部 TabBar。
 *
 * <p>判据是路由 meta 上的 {@code tab} 字段：只有三个主页面（首页 / 商城 / 我的）
 * 声明了它。登录、注册以及将来的各种详情页都不声明，自然就不显示。
 */
const showTabBar = computed(() => !!route.meta.tab)
</script>

<template>
  <router-view v-slot="{ Component }">
    <keep-alive :include="KEEP_ALIVE_PAGES">
      <component :is="Component" />
    </keep-alive>
  </router-view>
  <TabBar v-if="showTabBar" />
  <ToastHost />
  <!--
    「添加到桌面」引导提示条。自己判断该不该显示（环境、是否关过、是否后台页），
    所以这里无条件挂着即可 —— 见组件内注释。
  -->
  <InstallGuide />
  <!--
    全局请求遮罩，放在最后 —— 它的 z-index 高于 TabBar 与 Toast，
    「点了按钮有没有把请求发出去」一眼可见（慢网络下还能看到已等待秒数）
  -->
  <LoadingMask :loading="loadingVisible" :text="loadingText" />
</template>
