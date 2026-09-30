<script setup>
/**
 * 我的月卡。
 *
 * <p>后端一次给出三样：{@code active}（生效中）/ {@code pending}（待支付）/ {@code history}（历史）。
 * <b>三者互不重叠</b>，按字段名分渲染即可 —— 不要自己拿状态和日期去判断某张卡该放哪边，
 * 那等于在客户端再实现一遍免单判定的口径，两边迟早分岔。
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getMyCards, cancelCardPurchase } from '@/api/card'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatMoney, formatDate, formatDateTime } from '@/utils/format'
import NavBar from '@/components/NavBar.vue'
import PaySheet from '@/components/PaySheet.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

const router = useRouter()

const active = ref(null)
const pending = ref(null)
const history = ref([])
const loading = ref(true)

const payVisible = ref(false)

async function load() {
  loading.value = true
  try {
    const resp = await getMyCards()
    const data = resp.data || {}
    active.value = data.active || null
    pending.value = data.pending || null
    history.value = data.history || []
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    loading.value = false
  }
}

/** 支付那张待付款的购买单。 */
function onPay() {
  if (!pending.value) return
  payVisible.value = true
}

async function onPaid() {
  toastSuccess('月卡已生效')
  payVisible.value = false
  await load()
}

/**
 * 取消待支付的购买单。
 *
 * <p>⚠️ <b>这不是退货</b>：那笔钱从来没付过。取消只是让单子不再挡着用户 ——
 * 月卡是「一人一卡」，未关闭的旧单会让他想买第二张时买不了。
 */
async function onCancel() {
  if (!pending.value) return
  try {
    await cancelCardPurchase(pending.value.id)
    toastSuccess('已取消')
    await load()
  } catch (err) {
    toastError(errorMessage(err, '取消失败'))
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <NavBar title="我的月卡" />

    <LoadingMask :loading="loading" />

    <!-- 生效中 -->
    <div v-if="active" class="card wallet__active">
      <span class="tag tag-success">{{ active.statusLabel }}</span>
      <p class="wallet__type">{{ active.cardTypeLabel }}</p>
      <p class="wallet__desc">{{ active.coverageLabel }} · {{ active.periodText }}</p>
      <div class="wallet__dates">
        {{ formatDate(active.startDate) }} 至 {{ formatDate(active.endDate) }}
        <span class="wallet__remaining">剩余 {{ active.remainingDays }} 天</span>
      </div>
      <p class="wallet__no">{{ active.cardNo }}</p>
    </div>

    <!-- 待支付 -->
    <div v-else-if="pending" class="card wallet__pending">
      <div class="card-title">待支付的月卡</div>
      <p class="wallet__pending-info">
        {{ pending.cardTypeLabel }} · ¥{{ formatMoney(pending.price) }}
      </p>
      <p class="wallet__no">{{ pending.orderNo }}</p>
      <div class="wallet__pending-actions">
        <button class="btn btn-primary" @click="onPay">去支付</button>
        <button class="btn btn-ghost" @click="onCancel">取消</button>
      </div>
    </div>

    <!-- 没有卡也没待支付的单 -->
    <EmptyState
      v-else-if="!loading"
      icon="🎫"
      text="还没有月卡"
      hint="去商城看看，全天与夜间两种可选"
    >
      <button class="btn btn-ghost wallet__go" @click="router.push('/mall')">去商城</button>
    </EmptyState>

    <!-- 历史 -->
    <div v-if="history.length" class="card">
      <div class="card-title">历史月卡</div>
      <div v-for="c in history" :key="c.id" class="wallet__history-item">
        <div class="wallet__history-main">
          <span class="wallet__history-type">{{ c.cardTypeLabel }}</span>
          <span class="text-sm text-muted">
            {{ formatDate(c.startDate) }} 至 {{ formatDate(c.endDate) }}
          </span>
        </div>
        <span class="tag">{{ c.statusLabel }}</span>
      </div>
    </div>

    <PaySheet
      v-model:visible="payVisible"
      title="月卡购买"
      :order-no="pending?.orderNo"
      :amount="pending?.price"
      target-type="MONTHLY_CARD"
      :target-id="pending?.id"
      @paid="onPaid"
    />
  </div>
</template>

<style scoped>
.wallet__active {
  margin-top: var(--sp-4);
}

.wallet__type {
  margin: var(--sp-2) 0 var(--sp-1);
  font-size: 20px;
  font-weight: 600;
  color: var(--c-primary);
}

.wallet__desc {
  font-size: 13px;
  color: var(--c-text-sub);
}

.wallet__dates {
  margin-top: var(--sp-3);
  font-size: 13px;
  color: var(--c-text);
}

.wallet__remaining {
  margin-left: var(--sp-2);
  font-size: 12px;
  color: var(--c-success);
}

.wallet__no {
  margin-top: var(--sp-2);
  font-size: 11px;
  color: var(--c-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.wallet__pending-info {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-primary);
}

.wallet__pending-actions {
  display: flex;
  gap: var(--sp-3);
  margin-top: var(--sp-3);
}

.wallet__go {
  margin-top: var(--sp-4);
  max-width: 200px;
}

.wallet__history-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-2) 0;
}

.wallet__history-item + .wallet__history-item {
  border-top: 1px solid var(--c-border);
}

.wallet__history-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.wallet__history-type {
  font-size: 14px;
  color: var(--c-text);
}
</style>
