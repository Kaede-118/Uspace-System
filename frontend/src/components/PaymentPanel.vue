<script setup>
/**
 * 支付面板：选通道 → 发起支付 → 模拟收银台 / 跳转 / 扫码上传凭证 → 确认到账。
 *
 * <p>结账页、订单详情页、商城下单、包场付款都用它 —— 四类收款的差别只有
 * {@code targetType} 一个参数，把流程封在这里，加一类收款不必再写一遍。
 *
 * <p><b>通道列表由后端给</b>（见 {@link getChannels}），本组件不写死：
 * 12 月投产时线上三条全部关闭、只剩扫码转账，而本机演示时四条都能用。
 * 写死的清单会让页面与部署配置脱节。
 *
 * <p>支付成功时 emit {@code paid}，由调用方决定跳哪里 ——
 * 组件自己不碰路由，这样它在「下单成功后原地刷新」这类场景里也能用。
 */
import { ref, computed, watch } from 'vue'
import { usePayment } from '@/composables/usePayment'
import { getChannels } from '@/api/payment'
import { defaultChannel } from '@/utils/ua'
import { toastError, toastInfo } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import PayChannelPicker from './PayChannelPicker.vue'
import MockCashierSheet from './MockCashierSheet.vue'
import ProofSubmitSheet from './ProofSubmitSheet.vue'

const props = defineProps({
  /** 收款目标类型：ORDER / BOOKING / MONTHLY_CARD / PRODUCT */
  targetType: { type: String, required: true },
  /** 目标 ID */
  targetId: { type: [Number, String], required: true },
  /** 应付金额（仅用于展示） */
  amount: { type: [Number, String], default: null },
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

/** 可选通道，来自后端 */
const channels = ref([])
/** 是否显示扫码转账的收银台（上传付款凭证那条路） */
const proofVisible = ref(false)
/** 当前选中的通道名 */
const channel = ref(null)

/**
 * 选中的那条通道对象。
 *
 * <p>{@code needProof} / {@code online} 都从它上面读 ——
 * <b>不自己判「通道名是不是 QR_UPLOAD」</b>：哪条通道要传凭证是业务定义，
 * 将来加一条「现金」通道（管理员线下收款）时，它同样不是线上通道、
 * 却不需要用户传凭证，那时按名字判的写法会集体出错。
 */
const selected = computed(
  () => channels.value.find((c) => c.channel === channel.value) || null
)

/**
 * 拉通道列表并定一个默认选中。
 *
 * <p>取法是「环境建议值 ∩ 可用列表」：环境建议的那个若没开放
 * （投产时线上通道全关，正是这种情形），再看列表长度 ——
 * 只剩一条时直接选中它（多半就是扫码转账，那条在桌面上照样能用），
 * 多条时留空，让用户自己挑。
 *
 * <p>留空是有意的：桌面浏览器上线上通道<b>其实都调不起来</b>，
 * 随便预选一个反而误导用户以为能用（见 {@code utils/ua.js} 的 defaultChannel）。
 */
async function loadChannels() {
  try {
    const resp = await getChannels(props.targetType)
    channels.value = resp.data || []
    const preferred = defaultChannel()
    const hit = channels.value.find((c) => c.channel === preferred)
    if (hit) {
      channel.value = hit.channel
    } else {
      channel.value = channels.value.length === 1 ? channels.value[0].channel : null
    }
  } catch (err) {
    toastError(errorMessage(err, '支付方式加载失败'))
  }
}

// 换一个收款目标就重拉一次：通道是按「这类收款受理哪些」过滤过的，
// 订单与月卡的可用列表不一定相同
watch(() => [props.targetType, props.targetId], loadChannels, { immediate: true })

async function onPay() {
  if (!selected.value) {
    toastError('请先选择支付方式')
    return
  }

  // 扫码转账不走网关 —— 没有线上支付可发起，直接弹上传凭证的收银台。
  // 这一步不调 createPayment：那条路对 QR_UPLOAD 只会返回一个
  // 没有任何支付参数的「成功」，白白多一次往返
  if (selected.value.needProof) {
    proofVisible.value = true
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
      <PayChannelPicker v-model="channel" :channels="channels" />
    </div>

    <button class="btn btn-primary pay-panel__action" :disabled="loading" @click="onPay">
      {{ loading ? '处理中…' : payText }}
    </button>
    <!--
      「已完成支付？点此确认」只对线上通道有意义：它查的是支付平台那边的
      订单状态，而扫码转账没有平台单号可查 —— 那种情况走的是上传凭证那条路
    -->
    <button v-if="selected?.online" class="btn btn-ghost pay-panel__action" @click="onCheck">
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

    <ProofSubmitSheet
      v-model:visible="proofVisible"
      :target-type="targetType"
      :target-id="targetId"
      :amount="amount"
      @submitted="emit('paid')"
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
