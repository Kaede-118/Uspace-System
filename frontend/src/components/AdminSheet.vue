<script setup>
/**
 * 运营后台的弹层：<b>一个组件三种用途</b> —— 表单、确认、选择器。
 *
 * <p>四个后台页面都要弹东西（新增/编辑表单、删除确认、选包场人），
 * 各写一份遮罩 + 面板的话，四份的关闭行为、层级、安全区处理迟早不一致 ——
 * 而那种不一致不会有任何报错，只是某个页面在 iPhone 上手势条压住了按钮。
 * 所以这里只做「壳」，内容一律由插槽给。
 *
 * <p>⚠️ <b>刻意不点遮罩关闭</b>（{@code maskClosable} 默认 false）：
 * 弹层里装的多半是一个填了一半的表单，手指一滑点偏就把输入全丢了 ——
 * 而这种丢失是静默的，用户只会觉得「刚才填的东西没了」。
 * 要关就点右上角的 × 或底部的取消，那两处是明确的意图。
 *
 * <p>⚠️ <b>错误必须走 {@code error} 属性，不要在弹层里 toast</b>：
 * {@code ToastHost} 的层级是 200，比本弹层的遮罩（450）低，提示会被压在下面 ——
 * 表现为「点了保存，什么都没发生」，而实际是保存失败了。
 */
import { watch, onUnmounted } from 'vue'

const props = defineProps({
  /** 是否显示 */
  visible: { type: Boolean, default: false },
  /** 标题。留空则不显示标题栏 */
  title: { type: String, default: '' },
  /** 是否允许点遮罩关闭。表单场景不要开，确认框可以开 */
  maskClosable: { type: Boolean, default: false },
  /** 错误文案。有值时在按钮上方显示一条红色提示，见文件头那段说明 */
  error: { type: String, default: '' }
})

const emit = defineEmits(['update:visible'])

/** 关闭弹层。统一从这里走，免得各页面写各自的 emit 拼写。 */
function close() {
  emit('update:visible', false)
}

/**
 * 打开期间锁住背景页面的滚动。
 *
 * <p>不锁的话，弹层里滑到底会带着底下的页面一起滚，键盘收起后页面停在
 * 一个莫名其妙的位置上 —— 用户以为自己填错了什么。
 */
let bodyLocked = false

watch(
  () => props.visible,
  (open) => {
    bodyLocked = open
    document.body.style.overflow = open ? 'hidden' : ''
  }
)

/** 组件被路由切走时（弹层还开着）必须把锁解开，否则整页从此滚不动。 */
onUnmounted(() => {
  if (bodyLocked) document.body.style.overflow = ''
})
</script>

<template>
  <Transition name="sheet-fade">
    <div
      v-if="visible"
      class="admin-sheet__mask"
      @click.self="maskClosable && close()"
    >
      <div class="admin-sheet">
        <div v-if="title" class="admin-sheet__head">
          <h3 class="admin-sheet__title">{{ title }}</h3>
          <button class="admin-sheet__close" aria-label="关闭" @click="close">×</button>
        </div>

        <div class="admin-sheet__body">
          <slot />
        </div>

        <p v-if="error" class="admin-sheet__error">{{ error }}</p>

        <div class="admin-sheet__foot">
          <slot name="footer">
            <button class="btn btn-ghost" @click="close">关闭</button>
          </slot>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.admin-sheet__mask {
  position: fixed;
  inset: 0;
  z-index: 450;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: rgba(43, 35, 64, 0.45);
  overflow-y: auto;
  /*
   * 阻止滚动穿透：没有它，弹层里滑到底之后继续滑，滚动会「传」给底下的页面，
   * 松手时用户发现自己填表单的位置已经被挪走了。
   */
  overscroll-behavior: contain;
}

.admin-sheet {
  width: 100%;
  max-width: 480px;
  /* 长表单（机台有 7 个字段）必须能滚，不然小屏上按钮会被顶出屏幕 */
  max-height: 88vh;
  display: flex;
  flex-direction: column;
  padding: var(--sp-4) var(--sp-5) calc(var(--sp-4) + var(--safe-bottom));
  border-radius: var(--r-card) var(--r-card) 0 0;
  background: var(--c-bg);
}

.admin-sheet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  flex-shrink: 0;
}

.admin-sheet__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text);
}

.admin-sheet__close {
  width: 30px;
  height: 30px;
  margin-right: -6px;
  font-size: 22px;
  line-height: 1;
  color: var(--c-text-muted);
}

.admin-sheet__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--sp-3) 0;
}

.admin-sheet__foot {
  flex-shrink: 0;
  display: flex;
  gap: var(--sp-3);
  padding-top: var(--sp-2);
}

/*
 * 行内错误提示。不能靠 toast —— 它的层级（200）比本弹层（450）低，
 * 会被遮罩压住而看不见。
 */
.admin-sheet__error {
  flex-shrink: 0;
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--r-btn);
  background: #f8d7d5;
  color: #a33a36;
  font-size: 12px;
  line-height: 1.6;
}

/* 底部两个并排按钮等宽：一个主操作、一个取消 */
.admin-sheet__foot :deep(.btn) {
  flex: 1;
}

.sheet-fade-enter-active,
.sheet-fade-leave-active {
  transition: opacity 0.18s;
}

.sheet-fade-enter-from,
.sheet-fade-leave-to {
  opacity: 0;
}
</style>
