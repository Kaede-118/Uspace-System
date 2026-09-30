<script setup>
/**
 * 我的商品订单。
 *
 * <p>⚠️ <b>分页参数是 {@code pageNum} / {@code pageSize}</b>，不是
 * {@code page} / {@code size} —— 本项目这两个模块用前者，
 * 传错不会报错，只会永远返回第一页。
 *
 * <p>商品<b>不做核销</b>：无人值守店里没有店员，付了钱自己取。
 * 代价是分不清货被拿了没有 —— 这是明知的取舍，不是遗漏。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listMyProductOrders, cancelProductOrder } from '@/api/product'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatMoney, formatDateTime } from '@/utils/format'
import NavBar from '@/components/NavBar.vue'
import PaySheet from '@/components/PaySheet.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

const router = useRouter()
const PAGE_SIZE = 10

const filters = [
  { label: '全部', value: null },
  { label: '待支付', value: 'PENDING_PAYMENT' },
  { label: '已支付', value: 'PAID' }
]
const activeFilter = ref(null)

const orders = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)

const payVisible = ref(false)
const payingOrder = ref(null)

const hasMore = computed(() => orders.value.length < total.value)

async function load(reset = false) {
  if (loading.value) return
  loading.value = true
  try {
    if (reset) {
      page.value = 1
      orders.value = []
    }
    const resp = await listMyProductOrders({
      pageNum: page.value,
      pageSize: PAGE_SIZE,
      status: activeFilter.value || undefined
    })
    const data = resp.data
    total.value = data.total || 0
    orders.value = reset ? data.records || [] : [...orders.value, ...(data.records || [])]
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    loading.value = false
  }
}

function onFilter(value) {
  if (activeFilter.value === value) return
  activeFilter.value = value
  load(true)
}

function onLoadMore() {
  page.value += 1
  load(false)
}

function onPay(order) {
  payingOrder.value = order
  payVisible.value = true
}

async function onPaid() {
  toastSuccess('支付成功，到店自取即可')
  payVisible.value = false
  await load(true)
}

/**
 * 取消待支付的单。
 *
 * <p>没有它，用户误下单之后只能干等超时，而超时<b>不关单</b>，
 * 界面上会一直挂着一笔「待支付」。
 */
async function onCancel(order) {
  try {
    await cancelProductOrder(order.id)
    toastSuccess('已取消')
    await load(true)
  } catch (err) {
    toastError(errorMessage(err, '取消失败'))
  }
}

onMounted(() => load(true))
</script>

<template>
  <div class="page">
    <NavBar title="我的商品订单" />

    <div class="filters">
      <button
        v-for="f in filters"
        :key="String(f.value)"
        class="filters__item"
        :class="{ 'filters__item--active': activeFilter === f.value }"
        @click="onFilter(f.value)"
      >
        {{ f.label }}
      </button>
    </div>

    <LoadingMask :loading="loading && !orders.length" />

    <div v-if="orders.length" class="list">
      <div v-for="o in orders" :key="o.id" class="order">
        <div class="order__head">
          <span class="order__name">{{ o.productName }}</span>
          <span class="order__status" :class="`order__status--${o.status.toLowerCase()}`">
            {{ o.statusLabel }}
          </span>
        </div>

        <div class="order__row">
          <span>¥{{ formatMoney(o.unitPrice) }} × {{ o.quantity }}</span>
          <span class="order__amount">¥{{ formatMoney(o.amount) }}</span>
        </div>

        <p class="order__no">{{ o.orderNo }}</p>
        <p class="order__time">{{ formatDateTime(o.createdAt) }}</p>

        <div v-if="o.status === 'PENDING_PAYMENT'" class="order__actions">
          <button class="btn btn-primary" @click="onPay(o)">去支付</button>
          <button class="btn btn-ghost" @click="onCancel(o)">取消</button>
        </div>

        <p v-else-if="o.status === 'PAID'" class="order__hint">
          已付款，到店自取即可
        </p>
      </div>

      <button v-if="hasMore" class="btn btn-ghost load-more" :disabled="loading" @click="onLoadMore">
        {{ loading ? '加载中…' : '加载更多' }}
      </button>
      <p v-else class="load-more__end">没有更多了</p>
    </div>

    <EmptyState
      v-else-if="!loading"
      icon="🛍"
      text="还没有商品订单"
      hint="去商城看看店里有什么"
    >
      <button class="btn btn-ghost go-mall" @click="router.push('/mall')">去商城</button>
    </EmptyState>

    <PaySheet
      v-model:visible="payVisible"
      title="商品订单"
      :order-no="payingOrder?.orderNo"
      :amount="payingOrder?.amount"
      target-type="PRODUCT"
      :target-id="payingOrder?.id"
      @paid="onPaid"
    />
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  gap: var(--sp-2);
  margin: var(--sp-4) 0;
}

.filters__item {
  padding: 6px var(--sp-4);
  border-radius: var(--r-pill);
  background: var(--c-card);
  color: var(--c-text-sub);
  font-size: 13px;
}

.filters__item--active {
  background: var(--c-primary);
  color: #fff;
}

.list {
  padding-bottom: var(--sp-4);
}

.order {
  padding: var(--sp-4);
  border-radius: var(--r-card);
  background: var(--c-card);
}

.order + .order {
  margin-top: var(--sp-3);
}

.order__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  margin-bottom: var(--sp-2);
}

.order__name {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order__status {
  flex-shrink: 0;
  font-size: 11px;
  padding: 2px var(--sp-2);
  border-radius: var(--r-pill);
}

/* 与 base.css 的 .tag 系列同一套实色 —— 半透明底色压在淡色卡片上看不清 */
.order__status--pending_payment {
  background: #fbe7c8;
  color: #9a6212;
}

.order__status--paid {
  background: #cdead9;
  color: #2b7d52;
}

.order__status--closed {
  background: #e4e0ec;
  color: #5d5476;
}

.order__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 13px;
  color: var(--c-text-sub);
}

.order__amount {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-primary);
}

.order__no,
.order__time {
  margin-top: 2px;
  font-size: 11px;
  color: var(--c-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.order__time {
  font-family: inherit;
}

.order__actions {
  display: flex;
  gap: var(--sp-3);
  margin-top: var(--sp-3);
}

.order__hint {
  margin-top: var(--sp-2);
  font-size: 11px;
  color: var(--c-text-muted);
}

.load-more {
  margin-top: var(--sp-4);
}

.load-more__end {
  margin-top: var(--sp-4);
  text-align: center;
  font-size: 12px;
  color: var(--c-text-muted);
}

.go-mall {
  margin-top: var(--sp-4);
  max-width: 200px;
}
</style>
