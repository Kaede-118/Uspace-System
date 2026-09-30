<script setup>
/**
 * 支付通道选择。
 *
 * <p><b>默认按环境自动勾选，但不禁用切换</b>：微信内打不开支付宝、
 * 支付宝内打不开微信（互相屏蔽外链），所以不给默认值的话用户多半会选错。
 * 但也不能做成「自动跳转且不可改」—— 微信里想用支付宝是常见需求
 * （零钱不够、有红包），强制跳转会让这类用户完全没法付款。
 *
 * <p>微信这一条在微信内是 <b>JSAPI</b>、在微信外是 <b>H5</b>：
 * 同一个「微信支付」在两处的技术实现不同，用户看到的按钮是同一个。
 */
import { computed } from 'vue'
import { isWechat, isAlipay, isMobile } from '@/utils/ua'
import { Channel } from '@/api/payment'

const props = defineProps({
  /** 当前选中的通道（v-model） */
  modelValue: { type: String, default: null }
})

const emit = defineEmits(['update:modelValue'])

/** 可选通道列表。 */
const options = computed(() => {
  const list = [
    {
      // 微信内走 JSAPI（页面内唤起），微信外走 H5（拉起微信 App）
      key: isWechat() ? Channel.WXPAY_JSAPI : Channel.WXPAY_H5,
      label: '微信支付',
      icon: '💚',
      hint: isWechat() ? '在微信内直接完成支付' : '将跳转到微信完成支付'
    },
    {
      key: Channel.ALIPAY_WAP,
      label: '支付宝',
      icon: '💙',
      hint: isAlipay() ? '在支付宝内直接完成支付' : '将跳转到支付宝完成支付'
    }
  ]
  return list
})

/** 桌面浏览器上两条通道其实都调不起来，给一句提醒。 */
const desktopHint = computed(() => (!isMobile() ? '当前是电脑浏览器，支付需要手机端完成' : ''))

function pick(key) {
  emit('update:modelValue', key)
}
</script>

<template>
  <div class="picker">
    <button
      v-for="opt in options"
      :key="opt.key"
      class="picker__item"
      :class="{ 'picker__item--active': modelValue === opt.key }"
      @click="pick(opt.key)"
    >
      <span class="picker__icon">{{ opt.icon }}</span>
      <span class="picker__main">
        <span class="picker__label">{{ opt.label }}</span>
        <span class="picker__hint">{{ opt.hint }}</span>
      </span>
      <span class="picker__radio" :class="{ 'picker__radio--on': modelValue === opt.key }" />
    </button>

    <p v-if="desktopHint" class="picker__warn">{{ desktopHint }}</p>
  </div>
</template>

<style scoped>
.picker__item {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  width: 100%;
  padding: var(--sp-3) var(--sp-4);
  border: 1px solid var(--c-border);
  border-radius: var(--r-btn);
  background: #fff;
  text-align: left;
}

.picker__item + .picker__item {
  margin-top: var(--sp-2);
}

.picker__item--active {
  border-color: var(--c-primary);
  background: var(--c-primary-pale);
}

.picker__icon {
  font-size: 20px;
}

.picker__main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.picker__label {
  font-size: 14px;
  color: var(--c-text);
}

.picker__hint {
  font-size: 11px;
  color: var(--c-text-muted);
}

.picker__radio {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 1.5px solid var(--c-border);
  flex-shrink: 0;
  position: relative;
}

.picker__radio--on {
  border-color: var(--c-primary);
}

.picker__radio--on::after {
  content: '';
  position: absolute;
  inset: 3px;
  border-radius: 50%;
  background: var(--c-primary);
}

.picker__warn {
  margin-top: var(--sp-2);
  font-size: 11px;
  color: var(--c-warning);
}
</style>
