<script setup>
/**
 * 结账页（四步流程的第二步与第三步）。
 *
 * <p>四步：预览（本页）→ 停止计时（本页）→ 支付（本页）→ 结束（跳详情）。
 *
 * <p><b>第一步的预览就是那个确认环节</b>：用户看完时长、金额、是否封顶，
 * 点「停止计时」即视为确认离场 —— <b>没有二次确认弹窗，也没有反悔路径</b>，
 * 停止即定格。想继续玩只能重新开门、重新计费。
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { settleOrder } from '@/api/order'
import { usePreviewPolling } from '@/composables/usePreviewPolling'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatMoney, formatDuration, formatCountdown } from '@/utils/format'
import NavBar from '@/components/NavBar.vue'
import BillSegmentList from '@/components/BillSegmentList.vue'
import PaymentPanel from '@/components/PaymentPanel.vue'
import LoadingMask from '@/components/LoadingMask.vue'

const route = useRoute()
const router = useRouter()
const orderId = route.params.id

/** 阶段：preview 计费中 / settled 已定格待支付 */
const phase = ref('preview')
const stopping = ref(false)
/** 结算结果（含最终账单，金额一律以此为准，不再沿用预览值）。 */
const settleResult = ref(null)

/** 结账页轮询比首页密：这里的金额是用户即将确认的数字。 */
const { preview, elapsedSeconds, countdownSeconds, start, stop, refresh } = usePreviewPolling({
  interval: 10000
})

/* ---------------- 展示值 ---------------- */

const payableText = computed(() => {
  if (!preview.value) return '—'
  return formatYuan(preview.value.bill?.totalAmount ?? 0)
})

const elapsedText = computed(() => formatDuration(Math.floor(elapsedSeconds.value / 60)))

const showCountdown = computed(() => countdownSeconds.value !== null)

/** 结算后的应付金额（0 元表示直通已支付）。 */
const settledAmount = computed(() => settleResult.value?.bill?.totalAmount ?? 0)
const settledIsFree = computed(() => settleResult.value?.status === 'PAID')

function formatYuan(v) {
  return `¥${formatMoney(v)}`
}

/* ---------------- 停止计时 ---------------- */

/**
 * 停止计时。
 *
 * <p>⚠️ <b>先停轮询再发 settle</b>。反过来的话，settle 期间的 preview
 * 会返回 409，而那是个预期内的错误（订单已转待支付、本页使命结束），
 * 却会因为轮询还在跑被当成真错误弹出来。
 */
async function onStop() {
  if (stopping.value) return
  stopping.value = true
  stop() // 先停轮询
  try {
    const resp = await settleOrder(orderId)
    settleResult.value = resp.data

    if (resp.data.status === 'PAID') {
      // 0 元的单直通已支付，不需要走支付流程
      toastSuccess('已结清，欢迎下次光临')
      router.replace(`/orders/${orderId}`)
      return
    }

    phase.value = 'settled'
  } catch (err) {
    toastError(errorMessage(err, '结算失败，请稍后重试'))
    // 结算没成功，恢复轮询 —— 订单还在计费中
    refresh()
    start(orderId)
  } finally {
    stopping.value = false
  }
}

/* ---------------- 支付 ---------------- */

/** 支付面板确认到账后跳到订单详情 —— 金额与状态一律以服务端记录为准。 */
function onPaid() {
  toastSuccess('支付成功')
  router.replace(`/orders/${orderId}`)
}

onMounted(() => {
  start(orderId)
})

onUnmounted(() => {
  stop()
})
</script>

<template>
  <div class="page">
    <NavBar title="结账" />

    <!-- ========== 阶段一：计费中 ========== -->
    <template v-if="phase === 'preview'">
      <div class="card settle__hero">
        <p class="settle__label">当前费用</p>
        <div class="settle__amount">{{ payableText }}</div>
        <p class="settle__meta">在店 {{ elapsedText }}</p>
        <p v-if="showCountdown" class="settle__next">
          还有 {{ formatCountdown(countdownSeconds) }} {{ preview?.nextChangeText }}
        </p>
        <p v-else-if="preview?.nextChangeText" class="settle__next">
          {{ preview.nextChangeText }}
        </p>
      </div>

      <div class="card">
        <div class="card-title">账单明细</div>
        <BillSegmentList
          :bill="preview?.bill"
          :stay-minutes="preview?.stayMinutes ?? null"
          :free-by-booking="preview?.freeByBooking"
        />
      </div>

      <p v-if="preview?.cappedNow" class="settle__note">
        当前已到封顶价（跨入日场 / 夜场边界后会开始新的一段，重新计价）
      </p>

      <button class="btn btn-primary settle__action" :disabled="stopping" @click="onStop">
        {{ stopping ? '处理中…' : '停止计时并结账' }}
      </button>
      <p class="settle__warn">
        停止后计时立即定格，无法恢复；想继续玩需要重新开门并重新计费
      </p>
    </template>

    <!-- ========== 阶段二：已定格，去支付 ========== -->
    <template v-else>
      <div class="card settle__hero">
        <p class="settle__label">应付金额</p>
        <div class="settle__amount">¥{{ formatMoney(settledAmount) }}</div>
        <p class="settle__meta">计时已定格</p>
      </div>

      <div class="card">
        <div class="card-title">账单明细</div>
        <BillSegmentList
          :bill="settleResult?.bill"
          :stay-minutes="settleResult?.stayMinutes ?? null"
          :free-by-booking="settleResult?.freeByBooking"
        />
      </div>

      <!-- 支付面板自带走通道选择、发起支付、模拟收银台与查单确认 -->
      <PaymentPanel
        v-if="!settledIsFree"
        target-type="ORDER"
        :target-id="orderId"
        :amount="settledAmount"
        @paid="onPaid"
      />
    </template>

    <LoadingMask :loading="stopping" text="正在结算…" />
  </div>
</template>

<style scoped>
.settle__hero {
  margin-top: var(--sp-4);
  text-align: center;
  padding: var(--sp-5) var(--sp-4);
}

.settle__label {
  font-size: 13px;
  color: var(--c-text-sub);
}

.settle__amount {
  margin: var(--sp-2) 0;
  font-size: 36px;
  font-weight: 600;
  color: var(--c-primary);
  font-variant-numeric: tabular-nums;
}

.settle__meta {
  font-size: 13px;
  color: var(--c-text-sub);
}

.settle__next {
  margin-top: var(--sp-2);
  font-size: 12px;
  color: var(--c-warning);
}

.settle__note {
  margin: var(--sp-3) 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-muted);
  text-align: center;
}

.settle__action {
  margin-top: var(--sp-4);
}

.settle__warn {
  margin-top: var(--sp-3);
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-text-muted);
  text-align: center;
}
</style>
