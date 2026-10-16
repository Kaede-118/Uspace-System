<script setup>
/**
 * 「添加到桌面」引导提示条。
 *
 * <p>在根组件里挂一个实例即可。形态由 `useInstallPrompt` 判定，
 * 共三种（能弹原生窗 / iOS 只能教 / 微信内得先跳浏览器），
 * 判定逻辑与「为什么 iOS 弹不了」都在那个 composable 的注释里。
 *
 * <p>两处刻意的设计：
 * ① <b>延迟 3 秒出现</b> —— 一进门就被问「要不要添加到桌面」很唐突，
 *    先让用户看到页面本身；穿过隧道首屏本就要等一两秒，更不该抢在前面；
 * ② <b>关掉即永久收起</b>（记 localStorage）—— 这是「建议」不是「通知」。
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { installMode, guideDismissed, promptInstall, dismissGuide } from '@/composables/useInstallPrompt'

const route = useRoute()

/** 延迟多久出现（毫秒）。 */
const DELAY_MS = 3000

const shown = ref(false)
let timer = null

onMounted(() => {
  timer = setTimeout(() => {
    shown.value = true
  }, DELAY_MS)
})

onUnmounted(() => {
  if (timer) clearTimeout(timer)
})

/**
 * 是否显示提示条。
 *
 * <p>⚠️ <b>后台（/admin）不显示</b> —— 那是店主自己的界面，不需要引导
 * 装桌面图标（后台还是电脑上的工具，装到手机桌面上也不合用）。
 */
const visible = computed(
  () =>
    shown.value &&
    !guideDismissed.value &&
    installMode.value !== null &&
    !route.path.startsWith('/admin')
)

/**
 * 说明文案。
 *
 * <p>iOS 那条不走这里 —— 它中间要嵌一个分享图标（见 template），
 * 拆开写反而更绕。
 */
const desc = computed(() => {
  switch (installMode.value) {
    case 'prompt':
      // ⚠️ 文案控制在 ~7 字以内：提示条宽度被 max-width 钉在 336px，
      // 减去图标与两个按钮后，说明行只剩约 140px ——
      // 「像 App 一样打开，免输网址」13 字在这里会折出孤字（实测过）
      return '像 App 一样打开'
    case 'inapp':
      // 微信与 QQ 的菜单都在右上角，一句文案通用
      return '点右上角 ⋯ → 在浏览器打开'
    default:
      return ''
  }
})

/** 点「添加」：调原生安装窗口。无论用户选装还是不装，提示条都会收起。 */
async function onAdd() {
  await promptInstall()
}
</script>

<template>
  <Transition name="guide">
    <div v-if="visible" class="install-guide">
      <!--
        左侧就是将来桌面上的那个图标 —— 让用户先看见它长什么样，
        装完之后在桌面上找的就是它（同一个文件，见 public/icons/）
      -->
      <img class="install-guide__icon" src="/icons/icon-192.png" alt="" />

      <div class="install-guide__body">
        <p class="install-guide__title">添加到桌面</p>
        <p class="install-guide__desc">
          <template v-if="installMode === 'ios'">
            点底部
            <!-- iOS 的「分享」图标（方框 + 向上箭头），比文字更好认 -->
            <svg
              class="install-guide__share"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-label="分享"
            >
              <path d="M12 15V3" />
              <path d="M8 7l4-4 4 4" />
              <path d="M5 12v7a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-7" />
            </svg>
            → 添加到主屏幕
          </template>
          <template v-else>{{ desc }}</template>
        </p>
      </div>

      <!--
        能弹原生窗时给两个按钮（点「添加」弹窗，点「暂不」收起）；
        iOS 与微信内没有可调的接口，只有一个「知道了」——
        该怎么做已经写在说明里了。
      -->
      <div class="install-guide__actions">
        <template v-if="installMode === 'prompt'">
          <button class="install-guide__btn" @click="dismissGuide">暂不</button>
          <button class="install-guide__btn install-guide__btn--primary" @click="onAdd">
            添加
          </button>
        </template>
        <button v-else class="install-guide__btn" @click="dismissGuide">知道了</button>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.install-guide {
  position: fixed;
  /* 顶部安全区之下 —— 与 .page 的 padding-top 同一口径 */
  top: calc(var(--safe-top) + var(--sp-2));
  left: var(--sp-3);
  right: var(--sp-3);
  /*
   * 高于 TabBar(100)、低于 Toast(200) 与请求遮罩(300)：
   * 它是常驻到用户处理为止的一条建议，而 Toast 是瞬时的反馈 ——
   * 两者撞上时该让 Toast 压在它上面。
   */
  z-index: 150;
  /* 与 .page 同一条 360px 居中小列，电脑上不会被拉成一整条 */
  max-width: calc(var(--page-max) - var(--sp-3) * 2);
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3);
  border-radius: var(--r-card);
  /*
   * 白底 + 阴影，而不是 .card 的 --c-card：后者只比页面底色深一档，
   * 浮在这种「压在其他内容上面」的地方对比度不够，「浮起来」的感觉出不来。
   * （.field-input 用纯白也是同一个理由。）
   */
  background: #fff;
  box-shadow: 0 6px 24px rgba(43, 35, 64, 0.16);
}

.install-guide__icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  flex-shrink: 0;
}

/* min-width: 0 不能省：flex 子项默认 min-width:auto，
   长文案会把按钮挤出容器而不是换行 */
.install-guide__body {
  flex: 1;
  min-width: 0;
}

.install-guide__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}

.install-guide__desc {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--c-text-sub);
}

/* 行内的小图标：与 12px 的文字对齐，所以垂直位置微调 2px */
.install-guide__share {
  display: inline-block;
  vertical-align: -2px;
  width: 13px;
  height: 13px;
}

.install-guide__actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.install-guide__btn {
  height: 30px;
  padding: 0 var(--sp-3);
  border-radius: var(--r-pill);
  font-size: 13px;
  font-weight: 500;
  background: var(--c-primary-pale);
  color: var(--c-primary);
  transition: opacity 0.15s;
}

.install-guide__btn:active {
  opacity: 0.75;
}

.install-guide__btn--primary {
  background: var(--c-primary);
  color: #fff;
}

/* 从顶部滑入 —— 方向与它「挂在页面顶端」的位置一致 */
.guide-enter-active,
.guide-leave-active {
  transition: opacity 0.25s, transform 0.25s;
}

.guide-enter-from,
.guide-leave-to {
  opacity: 0;
  transform: translateY(-16px);
}
</style>
