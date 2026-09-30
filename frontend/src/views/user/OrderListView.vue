<script setup>
/**
 * 我的订单列表。
 *
 * <p>按状态筛选 + 分页加载。状态标签用后端给的 {@code statusText}，
 * 不在前端维护一份中文映射 —— 那样两边文案会分岔。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listMyOrders } from '@/api/order'
import { toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import NavBar from '@/components/NavBar.vue'
import OrderCard from '@/components/OrderCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

const router = useRouter()

const PAGE_SIZE = 10

/** 状态筛选。value 为 null 表示全部。 */
const filters = [
  { label: '全部', value: null },
  { label: '使用中', value: 'IN_USE' },
  { label: '待支付', value: 'PENDING_PAYMENT' },
  { label: '已支付', value: 'PAID' }
]
const activeFilter = ref(null)

const orders = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)

const hasMore = computed(() => orders.value.length < total.value)

async function load(reset = false) {
  if (loading.value) return
  loading.value = true
  try {
    if (reset) {
      page.value = 1
      orders.value = []
    }
    const resp = await listMyOrders({
      page: page.value,
      size: PAGE_SIZE,
      status: activeFilter.value || undefined
    })
    const data = resp.data
    total.value = data.total || 0
    orders.value = reset ? data.records || [] : [...orders.value, ...(data.records || [])]
  } catch (err) {
    toastError(errorMessage(err, '订单加载失败'))
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

function onOpen(order) {
  // 进行中的订单直接进结账页 —— 那才是用户此刻要做的事
  if (order.status === 'IN_USE') {
    router.push(`/orders/${order.id}/settle`)
  } else {
    router.push(`/orders/${order.id}`)
  }
}

onMounted(() => load(true))
</script>

<template>
  <div class="page">
    <NavBar title="我的订单" />

    <!-- 状态筛选 -->
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

    <div v-if="orders.length" class="order-list">
      <OrderCard v-for="o in orders" :key="o.id" :order="o" @click="onOpen(o)" />

      <button v-if="hasMore" class="btn btn-ghost load-more" :disabled="loading" @click="onLoadMore">
        {{ loading ? '加载中…' : '加载更多' }}
      </button>
      <p v-else class="load-more__end">没有更多了</p>
    </div>

    <EmptyState
      v-else-if="!loading"
      icon="📋"
      text="还没有订单"
      hint="在首页点「开门计时」即可开始使用"
    />
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  gap: var(--sp-2);
  margin: var(--sp-4) 0;
  /* 标签多时横向滚动，不换行挤成一团 */
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
}

.filters__item {
  flex-shrink: 0;
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

.order-list {
  padding-bottom: var(--sp-4);
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
</style>
