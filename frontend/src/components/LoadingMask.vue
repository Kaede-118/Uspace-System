<script setup>
/**
 * 加载遮罩。
 *
 * <p>用于「一次点击会发好几个请求」的场景（如结算、支付），
 * 期间挡住重复点击。单纯的转圈（不挡操作）用 {@code inline} 模式。
 */
defineProps({
  /** 是否显示 */
  loading: { type: Boolean, default: false },
  /** 提示文案 */
  text: { type: String, default: '加载中…' },
  /** 是否覆盖全屏。false 时只在原地显示一个小转圈 */
  fullscreen: { type: Boolean, default: true }
})
</script>

<template>
  <Transition name="fade">
    <div v-if="loading" class="mask" :class="{ 'mask--inline': !fullscreen }">
      <div class="mask__box">
        <span class="mask__spinner" />
        <span v-if="text" class="mask__text">{{ text }}</span>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 300;
  display: flex;
  align-items: center;
  justify-content: center;
  /* 半透明而不是全黑：让用户知道页面还在，只是暂时不能点 */
  background: rgba(250, 249, 253, 0.72);
}

.mask--inline {
  position: static;
  inset: auto;
  background: none;
  padding: var(--sp-3) 0;
}

.mask__box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-4) var(--sp-5);
  border-radius: var(--r-card);
  background: #fff;
  box-shadow: 0 4px 20px rgba(43, 35, 64, 0.12);
}

.mask--inline .mask__box {
  padding: 0;
  background: none;
  box-shadow: none;
}

.mask__spinner {
  width: 22px;
  height: 22px;
  border: 2px solid var(--c-primary-pale);
  border-top-color: var(--c-primary);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

.mask__text {
  font-size: 13px;
  color: var(--c-text-sub);
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.18s;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
