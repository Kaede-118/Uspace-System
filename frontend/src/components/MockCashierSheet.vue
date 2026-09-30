<script setup>
/**
 * 模拟收银台。
 *
 * <p>毕设阶段没有支付商户号（`uspace.payment.provider=mock`），真实的
 * 微信 / 支付宝收银台调不起来。这个面板模拟「顾客在收银台上完成了付款」，
 * 点确认后后端会走<b>完整的回调链路</b>（签名 → 验签 → 幂等 → 金额核对 →
 * 状态流转），而不是直接把订单改成已支付 —— 否则那条链路上的分支在演示时
 * 永远走不到。
 *
 * <p>⚠️ <b>必须放一个显眼的「模拟」标识</b>：这个面板长得像收银台，
 * 若有人（评委、演示时的旁听者）误以为是真的支付页面，
 * 会追问「你们的支付怎么长这样」。
 */
defineProps({
  /** 是否显示 */
  visible: { type: Boolean, default: false },
  /** 发起支付返回的数据：{ outTradeNo, amount, channelLabel, expireHint } */
  payInfo: { type: Object, default: null },
  /** 是否正在处理 */
  loading: { type: Boolean, default: false },
  /** 状态说明（如「回调未送达，正在主动查单确认…」） */
  statusMessage: { type: String, default: '' }
})

const emit = defineEmits(['update:visible', 'confirm'])

function onClose() {
  emit('update:visible', false)
}
</script>

<template>
  <div v-if="visible" class="sheet-mask" @click.self="onClose">
    <div class="sheet">
      <!-- 显眼的模拟标识，防止被误认为真实收银台 -->
      <div class="sheet__badge">模拟支付 · 不会产生真实扣款</div>

      <p class="sheet__title">订单支付</p>
      <div class="sheet__amount">¥{{ payInfo?.amount ?? '0.00' }}</div>

      <div class="sheet__rows">
        <div class="sheet__row">
          <span>订单号</span>
          <span class="sheet__mono">{{ payInfo?.outTradeNo }}</span>
        </div>
        <div class="sheet__row">
          <span>支付方式</span>
          <span>{{ payInfo?.channelLabel }}</span>
        </div>
        <div v-if="payInfo?.expireHint" class="sheet__row">
          <span>有效期</span>
          <span>{{ payInfo.expireHint }}</span>
        </div>
      </div>

      <p v-if="statusMessage" class="sheet__status">{{ statusMessage }}</p>

      <button class="btn btn-primary" :disabled="loading" @click="emit('confirm')">
        {{ loading ? '处理中…' : '模拟支付完成' }}
      </button>
      <button class="btn btn-ghost sheet__cancel" :disabled="loading" @click="onClose">
        取消
      </button>
    </div>
  </div>
</template>

<style scoped>
.sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 500;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: rgba(43, 35, 64, 0.45);
}

.sheet {
  width: 100%;
  max-width: 480px;
  padding: var(--sp-5) var(--sp-5) calc(var(--sp-5) + var(--safe-bottom));
  border-radius: var(--r-card) var(--r-card) 0 0;
  background: #fff;
  text-align: center;
}

.sheet__badge {
  display: inline-block;
  padding: 3px var(--sp-3);
  border-radius: var(--r-pill);
  /*
   * 实色，与 .tag-warning 同一套。这个标识必须一眼看得见 ——
   * 它是「这不是真实收银台」的唯一提示，用半透明底会糊在弹层里。
   */
  background: #fbe7c8;
  color: #9a6212;
  font-size: 11px;
  font-weight: 500;
  margin-bottom: var(--sp-4);
}

.sheet__title {
  font-size: 13px;
  color: var(--c-text-sub);
}

.sheet__amount {
  margin: var(--sp-2) 0 var(--sp-4);
  font-size: 32px;
  font-weight: 600;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.sheet__rows {
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--r-btn);
  background: var(--c-card);
  margin-bottom: var(--sp-4);
  text-align: left;
}

.sheet__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  font-size: 12px;
  color: var(--c-text-sub);
  padding: 3px 0;
}

.sheet__mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  word-break: break-all;
  text-align: right;
}

.sheet__status {
  margin-bottom: var(--sp-3);
  font-size: 12px;
  color: var(--c-warning);
}

.sheet__cancel {
  margin-top: var(--sp-2);
}
</style>
