<script setup>
/**
 * 支付弹层：把一个已创建好的待支付单交给用户付款。
 *
 * <p>商城（商品与月卡）用它 —— 那些场景没有独立的页面承载支付，
 * 下单后当场付掉最顺。订单与包场有自己的页面，直接用 {@code PaymentPanel}。
 */
import PaymentPanel from './PaymentPanel.vue'

defineProps({
  /** 是否显示 */
  visible: { type: Boolean, default: false },
  /** 标题，如「订单已创建」 */
  title: { type: String, default: '订单已创建' },
  /** 单号（展示用） */
  orderNo: { type: String, default: '' },
  /** 应付金额 */
  amount: { type: [Number, String], default: null },
  /** 收款目标类型：PRODUCT / MONTHLY_CARD */
  targetType: { type: String, required: true },
  /** 目标 ID */
  targetId: { type: [Number, String], default: null }
})

const emit = defineEmits(['update:visible', 'paid'])
</script>

<template>
  <div v-if="visible" class="sheet-mask" @click.self="emit('update:visible', false)">
    <div class="sheet">
      <p class="sheet__title">{{ title }}</p>
      <div class="sheet__amount">¥{{ amount }}</div>
      <p class="sheet__no">{{ orderNo }}</p>

      <PaymentPanel
        :target-type="targetType"
        :target-id="targetId"
        :amount="amount"
        @paid="emit('paid')"
      />

      <button class="sheet__close" @click="emit('update:visible', false)">稍后再说</button>
      <p class="sheet__hint">
        未支付的单会保留在「我的」页里，随时可以回来付
      </p>
    </div>
  </div>
</template>

<style scoped>
.sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 450;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: rgba(43, 35, 64, 0.45);
  overflow-y: auto;
}

.sheet {
  width: 100%;
  max-width: 480px;
  padding: var(--sp-5) var(--sp-5) calc(var(--sp-5) + var(--safe-bottom));
  border-radius: var(--r-card) var(--r-card) 0 0;
  background: var(--c-bg);
  text-align: center;
}

.sheet__title {
  font-size: 13px;
  color: var(--c-text-sub);
}

.sheet__amount {
  margin: var(--sp-2) 0 var(--sp-1);
  font-size: 30px;
  font-weight: 600;
  color: var(--c-primary);
  font-variant-numeric: tabular-nums;
}

.sheet__no {
  margin-bottom: var(--sp-4);
  font-size: 11px;
  color: var(--c-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.sheet__close {
  margin-top: var(--sp-3);
  width: 100%;
  font-size: 13px;
  color: var(--c-text-sub);
}

.sheet__hint {
  margin-top: var(--sp-2);
  font-size: 11px;
  color: var(--c-text-muted);
}
</style>
