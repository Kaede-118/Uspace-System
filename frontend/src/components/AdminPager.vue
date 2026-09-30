<script setup>
/**
 * 分页条（运营后台的公告 / 包场 / 商品三页，以及用户端的「全部公告」页共用）。
 *
 * <p>几个列表都要分页，各写一份的话，
 * 「最后一页怎么算」「没有下一页时按钮什么样」会在三处慢慢分岔 ——
 * 而分页算错的表现是「翻着翻着少了几条」，不是报错。
 *
 * <p>⚠️ <b>本组件不认识接口参数名</b>。后端的分页参数不统一
 * （公告/包场用 {@code page}+{@code size}，商品用 {@code pageNum}+{@code pageSize}），
 * 这个差异属于各页面的调用细节，由页面自己拼参数，这里只管「第几页、共几页」。
 */
import { computed } from 'vue'

const props = defineProps({
  /** 当前页码，从 1 开始 */
  page: { type: Number, required: true },
  /** 每页条数 */
  size: { type: Number, required: true },
  /** 总记录数 */
  total: { type: Number, default: 0 }
})

const emit = defineEmits(['update:page'])

/** 总页数。至少为 1 —— 0 条时也该显示「第 1 / 1 页」而不是「第 1 / 0 页」。 */
const pageCount = computed(() => Math.max(1, Math.ceil(props.total / props.size)))

const canPrev = computed(() => props.page > 1)
const canNext = computed(() => props.page < pageCount.value)

/**
 * 翻页。
 *
 * @param {number} target 目标页码
 */
function go(target) {
  if (target < 1 || target > pageCount.value || target === props.page) return
  emit('update:page', target)
}
</script>

<template>
  <!-- 一条记录都没有时不显示分页条：空列表上面挂个「第 1/1 页」只会让人以为加载错了 -->
  <div v-if="total > 0" class="pager">
    <span class="pager__info">共 {{ total }} 条 · 第 {{ page }} / {{ pageCount }} 页</span>
    <div class="pager__btns">
      <button class="pager__btn" :disabled="!canPrev" @click="go(page - 1)">上一页</button>
      <button class="pager__btn" :disabled="!canNext" @click="go(page + 1)">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-1);
  font-size: 12px;
  color: var(--c-text-muted);
}

.pager__btns {
  display: flex;
  gap: var(--sp-2);
}

.pager__btn {
  height: 30px;
  padding: 0 var(--sp-3);
  border-radius: var(--r-btn);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-size: 12px;
}

.pager__btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
