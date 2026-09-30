<script setup>
/**
 * 支付面板：选通道 → 发起支付 → 模拟收银台 / 跳转 → 主动查单确认。
 *
 * <p>结账页、订单详情页、商城下单、包场付款都用它 —— 四类收款的差别只有
 * {@code targetType} 一个参数，把流程封在这里，加一类收款不必再写一遍。
 *
 * <p>支付成功时 emit {@code paid}，由调用方决定跳哪里 ——
 * 组件自己不碰路由，这样它在「下单成功后原地刷新」这类场景里也能用。
 */
import { ref, computed } from 'vue'
import { usePayment } from '@/composables/usePayment'
import { defaultChannel } from '@/utils/ua'
import { toastError, toastInfo } from '@/composables/useToast'
import { Channel } from '@/api/payment'
import PayChannelPicker from './PayChannelPicker.vue'
import MockCashierSheet from './MockCashierSheet.vue'

const props = defineProps({
  /** 收款目标类型：ORDER / BOOKING / MONTHLY_CARD / PRODUCT */
  targetType: { type: String, required: true },
  /** 目标 ID */
  targetId: { type: [Number, String], required: true },
  /** 应付金额（仅用于展示） */
  amount: { type: [Number, String], default: null },
  /** 是否受理「上传凭证」的降级通道。月卡与商品不受理 */
  allowQrUpload: { type: Boolean, default: false },
  /** 支付按钮文案 */
  payText: { type: String, default: '去支付' }
})

const emit = defineEmits(['paid'])

const {
  loading,
  payInfo,
  cashierVisible,
  statusMessage,
  start,
  confirmMockPay,
  pollUntilPaid
} = usePayment()

const channel = ref(defaultChannel())

async function onPay() {
  if (!channel.value) {
    toastError('请先选择支付方式')
    return
  }
  const info = await start({
    targetType: props.targetType,
    targetId: props.targetId,
    channel: channel.value
  })
  if (!info && statusMessage.value) {
    toastError(statusMessage.value)
  }
}

async function onConfirmMockPay() {
  const ok = await confirmMockPay()
  if (ok) {
    emit('paid')
  } else if (statusMessage.value) {
    toastError(statusMessage.value)
  }
}

/** 回调可能丢，所以给用户一个手动确认的入口。 */
async function onCheck() {
  const ok = await pollUntilPaid()
  if (ok) {
    emit('paid')
  } else {
    toastInfo(statusMessage.value || '尚未确认到账')
  }
}
</script>

<template>
  <div class="pay-panel">
    <div class="card">
      <div class="card-title">选择支付方式</div>
      <PayChannelPicker v-model="channel" />
    </div>

    <button class="btn btn-primary pay-panel__action" :disabled="loading" @click="onPay">
      {{ loading ? '处理中…' : payText }}
    </button>
    <button class="btn btn-ghost pay-panel__action" @click="onCheck">
      已完成支付？点此确认
    </button>

    <p v-if="statusMessage" class="pay-panel__status">{{ statusMessage }}</p>

    <MockCashierSheet
      v-model:visible="cashierVisible"
      :pay-info="payInfo"
      :loading="loading"
      :status-message="statusMessage"
      @confirm="onConfirmMockPay"
    />
  </div>
</template>

<style scoped>
.pay-panel__action {
  margin-top: var(--sp-4);
}

.pay-panel__status {
  margin-top: var(--sp-3);
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-muted);
  text-align: center;
}
</style>
