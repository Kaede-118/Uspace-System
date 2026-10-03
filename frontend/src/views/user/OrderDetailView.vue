<script setup>
/**
 * 订单详情。
 *
 * <p>费用明细分两种形态：<b>有分段账单（{@code order.bill}）时按段展示
 * 「X 档 × 单价」</b> —— 与结账页共用 {@code BillSegmentList}；没有时回落到
 * 日场/夜场汇总行。
 *
 * <p>账单从哪来（那是后端的事，这一页只消费）：结算时算出的那一份会序列化成
 * 快照存进订单，详情直接读；快照机制（2026-10-03）之前结算的老订单由后端按
 * 当前规则重算，且只有金额与落库<b>完全一致</b>才会给到这里。
 * 所以「没有 bill」不是缺陷，多半是后端刻意保守的结果 ——
 * 使用中的订单也没有账单，那时的费用去结账页看。
 *
 * <p>金额一律以 {@code payableAmount} 为准 —— 它是唯一权威的「要付多少」，
 * 优惠与月卡抵扣都已含在其中，不要再减第二次。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getOrder } from '@/api/order'
import { toastError, toastSuccess } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatMoney, formatDuration, formatDateTime } from '@/utils/format'
import NavBar from '@/components/NavBar.vue'
import BillSegmentList from '@/components/BillSegmentList.vue'
import PaymentPanel from '@/components/PaymentPanel.vue'
import LoadingMask from '@/components/LoadingMask.vue'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const orderId = route.params.id

const order = ref(null)
const loading = ref(true)

const isUsing = computed(() => order.value?.status === 'IN_USE')
const isUnpaid = computed(() => order.value?.status === 'PENDING_PAYMENT')
const isPaid = computed(() => order.value?.status === 'PAID')

/** 实付金额。已支付时就是订单金额，未支付时是应付额 —— 两者是同一个数。 */
const payableText = computed(() => formatMoney(order.value?.payableAmount ?? 0))

/** 状态标签的配色。 */
const statusClass = computed(() => {
  if (isUsing.value) return 'tag'
  if (isUnpaid.value) return 'tag tag-warning'
  return 'tag tag-success'
})

async function load() {
  loading.value = true
  try {
    const resp = await getOrder(orderId)
    order.value = resp.data
  } catch (err) {
    toastError(errorMessage(err, '订单加载失败'))
  } finally {
    loading.value = false
  }
}

function onPaid() {
  toastSuccess('支付成功')
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <NavBar title="订单详情" />

    <LoadingMask :loading="loading" />

    <template v-if="order">
      <!-- 金额与状态 -->
      <div class="card detail__hero">
        <span :class="statusClass">{{ order.statusText }}</span>
        <div class="detail__amount">¥{{ payableText }}</div>
        <p class="detail__order-no">{{ order.orderNo }}</p>
      </div>

      <!-- 订单信息 -->
      <div class="card">
        <div class="card-title">订单信息</div>
        <div class="detail__row">
          <span>开始时间</span><span>{{ formatDateTime(order.startTime) }}</span>
        </div>
        <div class="detail__row">
          <span>结束时间</span>
          <span>{{ order.endTime ? formatDateTime(order.endTime) : '使用中' }}</span>
        </div>
        <div class="detail__row">
          <span>在店时长</span>
          <!--
            ⚠️ 在店时长与计费时长是两个口径：前者含包场时段与宽限那 5 分钟。
            加列之前的老订单没有这个值（列为 NULL），显示「—」而不是 0 —— 编不出来。
          -->
          <span>{{ order.stayMinutes === null ? '—' : formatDuration(order.stayMinutes) }}</span>
        </div>
        <div v-if="order.bookingId" class="detail__row">
          <span>关联包场</span><span>#{{ order.bookingId }}</span>
        </div>
        <div class="detail__row">
          <span>下单时间</span><span>{{ formatDateTime(order.createdAt) }}</span>
        </div>
      </div>

      <!--
        费用明细。
        有分段账单时与结账页共用 BillSegmentList（「X 档 × ¥单价」、封顶标记、
        各种减免的说明都在里面）；没有时回落到日场/夜场汇总行。
        ⚠️ 两者的金额字段是同一批（都来自订单上的落库列），只是展示粒度不同。
      -->
      <div class="card">
        <div class="card-title">费用明细</div>

        <BillSegmentList
          v-if="order.bill"
          :bill="order.bill"
          :stay-minutes="order.stayMinutes ?? null"
          :free-by-booking="order.freeByBooking"
        />

        <template v-else>
          <div v-if="order.dayMinutes" class="detail__row">
            <span>日场 {{ formatDuration(order.dayMinutes) }}</span>
            <span>¥{{ formatMoney(order.dayAmount) }}</span>
          </div>
          <div v-if="order.nightMinutes" class="detail__row">
            <span>夜场 {{ formatDuration(order.nightMinutes) }}</span>
            <span>¥{{ formatMoney(order.nightAmount) }}</span>
          </div>
          <div v-if="!order.dayMinutes && !order.nightMinutes" class="detail__row">
            <span class="text-muted">没有产生计费时长</span>
          </div>

          <div class="divider" />

          <div class="detail__row detail__row--total">
            <span>订单金额</span><span>¥{{ formatMoney(order.totalAmount) }}</span>
          </div>
          <div v-if="Number(order.discountAmount) > 0" class="detail__row detail__row--note">
            <span>月度优惠</span><span>-¥{{ formatMoney(order.discountAmount) }}</span>
          </div>
          <div v-if="Number(order.cardFreeAmount) > 0" class="detail__row detail__row--note">
            <span>月卡抵扣</span><span>-¥{{ formatMoney(order.cardFreeAmount) }}</span>
          </div>
          <div v-if="Number(order.activityFreeAmount) > 0" class="detail__row detail__row--note">
            <span>活动减免</span><span>-¥{{ formatMoney(order.activityFreeAmount) }}</span>
          </div>
          <div class="detail__row detail__row--total">
            <span>实付</span>
            <span class="text-primary">¥{{ payableText }}</span>
          </div>
        </template>
      </div>

      <!-- 支付信息 -->
      <div v-if="isPaid" class="card">
        <div class="card-title">支付信息</div>
        <div class="detail__row">
          <span>支付方式</span><span>{{ order.paymentMethodLabel || '—' }}</span>
        </div>
        <div class="detail__row">
          <span>支付时间</span><span>{{ formatDateTime(order.paidAt) }}</span>
        </div>
      </div>

      <!-- 人工调整说明 -->
      <div v-if="order.adjusted === 1" class="card detail__adjusted">
        <div class="card-title">时长经人工调整</div>
        <p class="text-sm text-sub">{{ order.adjustReason || '（未填写原因）' }}</p>
      </div>

      <!-- 待支付：就地支付 -->
      <PaymentPanel
        v-if="isUnpaid"
        target-type="ORDER"
        :target-id="orderId"
        :amount="order.payableAmount"
        @paid="onPaid"
      />

      <!-- 进行中：去结账 -->
      <button v-if="isUsing" class="btn btn-primary detail__action" @click="router.push(`/orders/${orderId}/settle`)">
        去结账
      </button>
    </template>

    <EmptyState v-else-if="!loading" text="订单不存在" hint="它可能不属于你，或已被删除" />
  </div>
</template>

<style scoped>
.detail__hero {
  margin-top: var(--sp-4);
  text-align: center;
  padding: var(--sp-4);
}

.detail__amount {
  margin: var(--sp-2) 0 var(--sp-1);
  font-size: 32px;
  font-weight: 600;
  color: var(--c-primary);
  font-variant-numeric: tabular-nums;
}

.detail__order-no {
  font-size: 12px;
  color: var(--c-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.detail__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  font-size: 13px;
  color: var(--c-text-sub);
  padding: 4px 0;
}

.detail__row--total {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}

.detail__row--note {
  font-size: 12px;
  color: var(--c-text-muted);
}

.detail__adjusted {
  border-left: 3px solid var(--c-warning);
}

.detail__action {
  margin-top: var(--sp-4);
}
</style>
