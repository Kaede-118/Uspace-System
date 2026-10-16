<script setup>
/**
 * 游玩偏好多选（胶囊按钮）。
 *
 * <p><b>为什么是一个共用组件</b>：这一栏原本在用户端是整页的胶囊、
 * 在运营后台却是一个「逗号分隔的类型 code」文本框。文本框那条路上，
 * 管理员得先知道 ONGEKI 是舞萌、再自己数逗号 —— 而两处不一致时
 * <b>不会有任何报错</b>，只是同一份数据在两个页面长得不一样
 * （与 {@code utils/labels.js} 文件头那条同一个理由）。
 *
 * <p>受控组件：选中集合由调用方持有（{@code modelValue} 是 code 数组），
 * 本组件只负责「点一下切换一项」。用户端「设置游玩偏好」页与
 * 运营后台「编辑用户资料」弹层都用它。
 *
 * <p>⚠️ <b>选项来自设备类型字典</b>（{@code GET /api/devices/types}，
 * 只返回<b>启用中</b>的类型），组件不内置任何对照表。
 * 而<b>已选中却不在字典里的旧 code 照常渲染</b>（虚线边框）——
 * 只渲染字典内的类型，会让一个「选过 ONGEKI、后来该类型被停用」的用户
 * 在管理员打开弹层时<b>看不见那一项，一保存就被静默清掉</b>。
 * 这与 {@code preferenceLabels}「查不到就原样显示 code」是同一条取向。
 */
import { computed } from 'vue'

const props = defineProps({
  /** 可选类型，形如 [{ code, name }]；一般来自设备类型字典 */
  types: { type: Array, default: () => [] },
  /** 已选中的 code 数组（v-model） */
  modelValue: { type: Array, default: () => [] },
  /** 字典为空、且没有历史选中项时的提示 */
  emptyText: { type: String, default: '还没有可选的设备类型' }
})

const emit = defineEmits(['update:modelValue'])

/**
 * 实际渲染的选项 = 字典里的类型 + 已选中但字典里没有的 code。
 *
 * <p>后者挂在末尾并标了 {@code unknown}（画成虚线边框），见文件头那条说明。
 */
const options = computed(() => {
  const known = new Set(props.types.map((t) => t.code))
  const extras = props.modelValue
    .filter((code) => !known.has(code))
    .map((code) => ({ code, name: code, unknown: true }))
  return [...props.types, ...extras]
})

const isOn = (code) => props.modelValue.includes(code)

/** 切换一项：已选则移除、未选则追加（不改原数组，emit 一个新数组） */
function toggle(code) {
  emit(
    'update:modelValue',
    isOn(code) ? props.modelValue.filter((c) => c !== code) : [...props.modelValue, code]
  )
}
</script>

<template>
  <div class="pref">
    <p v-if="!options.length" class="pref__empty">{{ emptyText }}</p>
    <div v-else class="pref__options">
      <button
        v-for="t in options"
        :key="t.code"
        type="button"
        class="pref__option"
        :class="{ 'pref__option--on': isOn(t.code), 'pref__option--unknown': t.unknown }"
        :title="t.unknown ? `类型字典里已没有 ${t.code}` : t.name"
        @click="toggle(t.code)"
      >
        {{ t.name }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.pref__options {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
}

.pref__option {
  padding: var(--sp-2) var(--sp-4);
  border-radius: var(--r-pill);
  border: 1px solid var(--c-border);
  background: #fff;
  font-size: 13px;
  color: var(--c-text-sub);
  cursor: pointer;
}

.pref__option--on {
  border-color: var(--c-primary);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-weight: 500;
}

/* 字典里已经不存在的旧 code：看得见，但一眼知道它不对劲 */
.pref__option--unknown {
  border-style: dashed;
}

.pref__empty {
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-muted);
}
</style>
