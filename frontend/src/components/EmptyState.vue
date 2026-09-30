<script setup>
/**
 * 空状态占位。
 *
 * <p>列表为空时给一句话说明「为什么是空的」，而不是留一片空白 ——
 * 空白让用户分不清「没有数据」和「页面坏了」。
 */
defineProps({
  /** 主文案，如「还没有进行中的订单」 */
  text: { type: String, default: '暂无内容' },
  /** 补充说明，可选 */
  hint: { type: String, default: '' },
  /** 图标 emoji，可选。轻量起见不画 SVG */
  icon: { type: String, default: '' }
})
</script>

<template>
  <div class="empty">
    <div v-if="icon" class="empty__icon">{{ icon }}</div>
    <p class="empty__text">{{ text }}</p>
    <p v-if="hint" class="empty__hint">{{ hint }}</p>
    <!-- 需要按钮的页面把按钮传进来，避免这里写死某种操作 -->
    <slot />
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--sp-6) var(--sp-4);
  text-align: center;
}

.empty__icon {
  font-size: 28px;
  margin-bottom: var(--sp-3);
  opacity: 0.65;
}

.empty__text {
  font-size: 14px;
  color: var(--c-text-sub);
}

.empty__hint {
  margin-top: var(--sp-2);
  font-size: 12px;
  color: var(--c-text-muted);
  line-height: 1.6;
}
</style>
