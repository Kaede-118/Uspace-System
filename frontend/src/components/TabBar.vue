<script setup>
/**
 * 底部导航栏。
 *
 * <p>只有三个主页面（首页 / 商城 / 我的）会显示它 —— 判据是路由 meta 上的
 * {@code tab} 字段，见 `App.vue`。
 *
 * <p><b>图标用内联 SVG 而不是 iconfont</b>：字体图标要额外加载一个字体文件，
 * 离线演示（答辩现场断网）时是隐患；而这里统共就三个图标，
 * 内联之后不依赖任何外部资源，还能跟随 {@code currentColor} 变色。
 */
import { useRoute } from 'vue-router'

const route = useRoute()

/**
 * 三个 tab。
 *
 * <p>{@code icon} 是 SVG 的 path 数据。用 {@code stroke} 描边风格而不是填充，
 * 与「淡紫 + 细线条」的整体观感一致。
 */
const tabs = [
  {
    name: 'home',
    label: '首页',
    to: '/home',
    // 房子的轮廓 + 门
    paths: ['M3 9.5 12 3l9 6.5V20a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 20z', 'M9 21.5V13h6v8.5']
  },
  {
    name: 'mall',
    label: '商城',
    to: '/mall',
    // 购物袋
    paths: ['M6.5 2.5 3.5 6.5v13A1.5 1.5 0 0 0 5 21h14a1.5 1.5 0 0 0 1.5-1.5v-13l-3-4z', 'M3.5 6.5h17', 'M15.5 10a3.5 3.5 0 0 1-7 0']
  },
  {
    name: 'mine',
    label: '我的',
    to: '/mine',
    // 人像
    paths: ['M20 21v-1.5a4.5 4.5 0 0 0-4.5-4.5h-7A4.5 4.5 0 0 0 4 19.5V21', 'M12 11.5a4.25 4.25 0 1 0 0-8.5 4.25 4.25 0 0 0 0 8.5z']
  }
]

/**
 * 判断某个 tab 是否为当前页。
 *
 * <p>用路由名比对而不是路径前缀 —— 将来订单详情、结账页这类子页面
 * 挂在 {@code /orders/xxx} 下，用前缀判断会让「首页」意外点亮。
 *
 * @param {object} tab tab 定义
 * @returns {boolean}
 */
function isActive(tab) {
  return route.name === tab.name
}
</script>

<template>
  <nav class="tabbar">
    <!--
      内层容器限宽居中，外层保持全宽白底 ——
      这样在电脑上白条仍然横贯整屏（像 App 的底栏），
      而三个图标落在与页面正文相同的 900px 内，不会散到屏幕两端。
    -->
    <div class="tabbar__inner">
      <router-link
        v-for="tab in tabs"
        :key="tab.name"
        :to="tab.to"
        class="tabbar__item"
        :class="{ 'tabbar__item--active': isActive(tab) }"
      >
        <svg
          class="tabbar__icon"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.7"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path v-for="(d, i) in tab.paths" :key="i" :d="d" />
        </svg>
        <span class="tabbar__label">{{ tab.label }}</span>
      </router-link>
    </div>
  </nav>
</template>

<style scoped>
.tabbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 100;
  /*
   * ⚠️ 高度必须【calc 进安全区】，只写 padding-bottom 的话盒子仍然只有 50px 高，
   * padding 从里面吃，图标会被挤扁。
   * 与之配套的是 base.css 里的 .page-with-tabbar —— 两者口径必须一致，
   * 否则页面底部要么被盖住、要么多出一截空白。
   */
  height: calc(var(--tabbar-h) + var(--safe-bottom));
  padding-bottom: var(--safe-bottom);
  background: #fff;
  border-top: 1px solid var(--c-border);
}

/* 限宽居中，与 .page 的 --page-max 对齐 —— 宽屏下图标不会散到屏幕两端 */
.tabbar__inner {
  display: flex;
  height: 100%;
  max-width: var(--page-max);
  margin: 0 auto;
}

.tabbar__item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: var(--c-text-muted);
  /* 移动端点击时不要出现灰色高亮块 */
  -webkit-tap-highlight-color: transparent;
}

.tabbar__item--active {
  color: var(--c-primary);
}

.tabbar__icon {
  width: 22px;
  height: 22px;
}

.tabbar__label {
  font-size: 10px;
  line-height: 1;
}
</style>
