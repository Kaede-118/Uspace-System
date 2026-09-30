<script setup>
/**
 * 提示（toast）的渲染容器。
 *
 * <p>在根组件里挂一个实例即可。提示的状态存在 `composables/useToast.js` 的
 * 模块级数组里，任何页面调用 {@code toastError(...)} 都会渲染到这里。
 */
import { toasts, dismiss } from '@/composables/useToast'
</script>

<template>
  <!--
    aria-live="polite"：屏幕阅读器会在提示出现时朗读出来。
    本系统的用户端是手机 Web，无障碍支持是顺手做的事，代价只有这一个属性。
  -->
  <div class="toast-host" aria-live="polite">
    <TransitionGroup name="toast">
      <div
        v-for="item in toasts"
        :key="item.id"
        class="toast"
        :class="`toast--${item.type}`"
        @click="dismiss(item.id)"
      >
        {{ item.message }}
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-host {
  position: fixed;
  /* 浮在 TabBar 之上，且不被手势条压住 */
  left: var(--sp-4);
  right: var(--sp-4);
  bottom: calc(var(--tabbar-h) + var(--safe-bottom) + var(--sp-4));
  z-index: 200;
  /* 容器本身不挡点击，只有里面的 toast 可点 */
  pointer-events: none;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-2);
}

.toast {
  pointer-events: auto;
  max-width: 100%;
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--r-btn);
  background: rgba(43, 35, 64, 0.92);
  color: #fff;
  font-size: 13px;
  line-height: 1.5;
  text-align: center;
  word-break: break-word;
  box-shadow: 0 4px 16px rgba(43, 35, 64, 0.18);
}

/* 错误提示用一点红边强调，扫一眼就知道这不是「操作成功」 */
.toast--error {
  background: var(--c-danger);
}

.toast--success {
  background: var(--c-success);
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.2s, transform 0.2s;
}

.toast-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.toast-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
