<script setup>
/**
 * 支付通道选择。
 *
 * <p><b>选项列表由后端给</b>（{@code GET /api/payments/channels}），本组件不自己攒：
 * 「本店收不收这种钱」是部署配置（{@code uspace.payment.enabled-channels}），
 * 「这类收款受不受理它」是业务规则 —— 两者都只有服务端知道。
 * 前端维护一份清单的话，会出现「配置改了页面没跟着变」，以及更糟的
 * 「选项在那里、点了却报错」。<b>页面上选得到 ⟺ 点下去不报错</b>，
 * 靠的就是两边用同一份判断。
 *
 * <p>本组件只补两样后端没给的东西：一个 emoji 图标，以及按当前浏览器环境
 * 给出的一句说明。<b>两样都是纯呈现</b>，与业务无关 —— 与
 * {@code utils/labels.js} 那条「配色没有第二个来源」是同一条边界：
 * 服务端给事实，客户端给呈现。
 *
 * <p><b>默认勾选由父组件算好传进来</b>（见 {@code PaymentPanel}）：
 * 「该默认选哪个」要同时看浏览器环境与可用列表，而可用列表在父组件手里。
 */
import { computed } from 'vue'
import { isAlipay, isMobile } from '@/utils/ua'

const props = defineProps({
  /**
   * 可选通道，来自 {@code GET /api/payments/channels}。
   *
   * <p>每项形如 {@code {channel, label, needProof, online}} ——
   * {@code label} 是用户口径的中文名，直接渲染。
   */
  channels: { type: Array, default: () => [] },
  /** 当前选中的通道名（v-model） */
  modelValue: { type: String, default: null }
})

const emit = defineEmits(['update:modelValue'])

/**
 * 各通道的图标与说明文案。
 *
 * <p>⚠️ <b>用映射表而不是 if-else 链</b>：认不出的通道会退回
 * {@link FALLBACK}，而不是显示成上一条通道的样子 ——
 * 后者会让用户以为两条通道是同一个东西。
 *
 * <p>{@code hint} 写成函数而不是字符串：它要在渲染那一刻看浏览器环境，
 * 而不是在模块加载时定死。
 */
const PRESENTATION = {
  WXPAY_JSAPI: { icon: '💚', hint: () => '在微信内直接完成支付' },
  WXPAY_H5: { icon: '💚', hint: () => '将跳转到微信完成支付' },
  ALIPAY_WAP: {
    icon: '💙',
    hint: () => (isAlipay() ? '在支付宝内直接完成支付' : '将跳转到支付宝完成支付')
  },
  QR_UPLOAD: { icon: '📷', hint: () => '扫码转账后上传付款截图，管理员核对' }
}

/** 认不出的通道用的兜底：中性图标，且不编造说明（宁可不写） */
const FALLBACK = { icon: '💳', hint: () => '' }

/**
 * 取某个通道的呈现信息。
 *
 * @param {string} channel 通道名
 * @returns {{icon: string, hint: () => string}}
 */
function presentationOf(channel) {
  return PRESENTATION[channel] || FALLBACK
}

/** 桌面浏览器上线上通道其实都调不起来，给一句提醒。扫码转账不受影响。 */
const desktopHint = computed(() =>
  !isMobile() && props.channels.some((c) => c.online)
    ? '当前是电脑浏览器，线上支付需要手机端完成'
    : ''
)

/**
 * 选中一条通道。
 *
 * @param {string} channel 通道名
 */
function pick(channel) {
  emit('update:modelValue', channel)
}
</script>

<template>
  <div class="picker">
    <p v-if="!channels.length" class="picker__empty">
      当前没有可用的支付方式，请联系管理员
    </p>

    <button
      v-for="ch in channels"
      :key="ch.channel"
      class="picker__item"
      :class="{ 'picker__item--active': modelValue === ch.channel }"
      @click="pick(ch.channel)"
    >
      <span class="picker__icon">{{ presentationOf(ch.channel).icon }}</span>
      <span class="picker__main">
        <span class="picker__label">{{ ch.label }}</span>
        <span class="picker__hint">{{ presentationOf(ch.channel).hint() }}</span>
      </span>
      <span class="picker__radio" :class="{ 'picker__radio--on': modelValue === ch.channel }" />
    </button>

    <p v-if="desktopHint" class="picker__warn">{{ desktopHint }}</p>
  </div>
</template>

<style scoped>
.picker__empty {
  padding: var(--sp-4);
  border-radius: var(--r-btn);
  background: var(--c-card);
  font-size: 13px;
  color: var(--c-text-muted);
  text-align: center;
}

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
