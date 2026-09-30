<script setup>
/**
 * 公告列表里的一条。
 *
 * <p>公告是一条<b>消息</b>，不是一份状态：自动公告只增不改，机台修好不会把
 * 「转为维护中」那条改掉，而是再产生一条「转为良好」。所以首页读起来是一段历史 ——
 * 这是设计，别按惯性去做「同来源去重」。
 */
import { computed } from 'vue'
import { relativeTime } from '@/utils/format'

const props = defineProps({
  /** 公告对象：{ id, title, content, publishMode, createdAt, pinned } */
  notice: { type: Object, required: true }
})

/** 自动公告用喇叭、手写公告用便签 —— 一眼能区分「系统记的」和「运营说的」。 */
const icon = computed(() => (props.notice.publishMode === 'AUTO' ? '📢' : '📝'))

const time = computed(() => relativeTime(props.notice.createdAt))
</script>

<template>
  <div class="notice">
    <span class="notice__icon" aria-hidden="true">{{ icon }}</span>
    <div class="notice__main">
      <p class="notice__title">
        <!-- 置顶的加个小标记：没有它的话，用户看不出这条为什么排在最前面 -->
        <span v-if="notice.pinned" class="notice__pin">置顶</span>
        {{ notice.title }}
      </p>
      <p v-if="notice.content" class="notice__content">{{ notice.content }}</p>
    </div>
    <span class="notice__time">{{ time }}</span>
  </div>
</template>

<style scoped>
/* 置顶标记。用主色小胶囊，不抢标题的注意力 */
.notice__pin {
  display: inline-block;
  margin-right: 4px;
  padding: 0 6px;
  border-radius: var(--r-pill);
  background: var(--c-primary);
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  vertical-align: 1px;
}

.notice {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-3);
  padding: var(--sp-2) 0;
}

.notice + .notice {
  border-top: 1px solid var(--c-border);
}

.notice__icon {
  font-size: 14px;
  line-height: 1.5;
  flex-shrink: 0;
}

.notice__main {
  flex: 1;
  min-width: 0;
}

.notice__title {
  font-size: 13px;
  color: var(--c-text);
  line-height: 1.5;
}

.notice__content {
  margin-top: 2px;
  font-size: 12px;
  color: var(--c-text-sub);
  line-height: 1.5;
  /* 手写公告正文可能较长，最多显示两行 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.notice__time {
  font-size: 11px;
  color: var(--c-text-muted);
  flex-shrink: 0;
  line-height: 1.6;
}
</style>
