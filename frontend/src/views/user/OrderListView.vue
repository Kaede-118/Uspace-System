<script setup>
/**
 * 订单 —— 房间时长订单与商品订单合一页，用顶部 tab 分。
 *
 * <p><b>为什么合一页</b>：两者本质是同一件事（我在店里花了什么钱）。
 * 用户想知道「我还有哪笔没处理」时，不该翻两个页面才发现漏了一笔。
 *
 * <p><b>两个路由指向本组件</b>（{@code /orders} 与 {@code /product-orders}），
 * 由路由的 {@code meta.tab} 决定初始落在哪一栏 —— 这样完成了合并，
 * 又不必去改散在各处的跳转链接（首页那条驳回提醒、QQ 机器人给的地址）。
 *
 * <p>⚠️ <b>两边的分页参数名不一样</b>：订单是 {@code page}/{@code size}，
 * 商品是 {@code pageNum}/{@code pageSize}。传错**不会报错**，
 * 只会永远返回第一页 —— 所以下面的 {@code load} 按 tab 分派参数名，
 * 别图省事合并成一套。
 *
 * <p>⚠️ <b>两边的状态取值也不完全一样</b>：房间订单有「使用中」，
 * 商品没有；所以筛选项是按 tab 给的两套，切 tab 时要把筛选重置回「全部」——
 * 留着一个对方没有的筛选值，会查出一片空列表。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listMyOrders } from '@/api/order'
import { listMyProductOrders, cancelProductOrder } from '@/api/product'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatMoney, formatDateTime } from '@/utils/format'
import NavBar from '@/components/NavBar.vue'
import OrderCard from '@/components/OrderCard.vue'
import PaySheet from '@/components/PaySheet.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

const route = useRoute()
const router = useRouter()

const PAGE_SIZE = 10

/**
 * 当前看的是哪一栏。
 *
 * <p>初始值取自路由 —— {@code /product-orders} 进来就直接落在商品那一栏。
 * 之后它只是组件内部状态，<b>不再动 URL</b>：切一次 tab 改一次地址，
 * 会让浏览器的「返回」变成在 tab 之间来回跳，而用户期望的是回到上一页。
 */
const tab = ref(route.meta.tab === 'product' ? 'product' : 'order')

/** 两套数据分开存：切回原来那一栏时不必重新拉一遍 */
const orders = ref([])
const productOrders = ref([])

const total = ref(0)
const page = ref(1)
const loading = ref(false)

/** 当前这一栏的列表 */
const list = computed(() => (tab.value === 'order' ? orders.value : productOrders.value))
const hasMore = computed(() => list.value.length < total.value)

const payVisible = ref(false)
const payingOrder = ref(null)

/**
 * 正在付的这一笔是不是「凭证被驳回」的那一笔。
 *
 * <p>决定弹层里只留凭证那条路：那一刻用户能做的只有重新上传一张截图，
 * 而<b>后端也不允许再发起支付</b>（{@code loadForPay} 只认待支付状态）。
 * 少了它，弹层会摆一排支付通道、按钮写着「去支付」——
 * 用户既找不到重传的入口，又可能真的再付一笔。
 */
const payingRejected = computed(() => payingOrder.value?.status === 'REJECTED')

/**
 * 拉当前这一栏的数据。
 *
 * @param {boolean} reset 是否从头拉（切 tab、切筛选时用）
 */
async function load(reset = false) {
  if (loading.value) return
  loading.value = true
  try {
    const isOrder = tab.value === 'order'
    if (reset) {
      page.value = 1
      if (isOrder) {
        orders.value = []
      } else {
        productOrders.value = []
      }
    }

    // ⚠️ 参数名两边不同，见类注释。写成同一套不会报错，只会永远返回第一页
    const params = isOrder
      ? { page: page.value, size: PAGE_SIZE }
      : { pageNum: page.value, pageSize: PAGE_SIZE }

    const resp = isOrder ? await listMyOrders(params) : await listMyProductOrders(params)
    const data = resp.data
    total.value = data.total || 0
    const records = data.records || []

    if (isOrder) {
      orders.value = reset ? records : [...orders.value, ...records]
    } else {
      productOrders.value = reset ? records : [...productOrders.value, ...records]
    }
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    loading.value = false
  }
}

/**
 * 切栏。
 *
 * <p>⚠️ <b>这里刻意不做状态筛选</b>（2026-10-04 去掉）：单子本来就按时间倒序排，
 * 想找别的状态往下拉就是了 —— 而那一排筛选按钮占的是首屏最要紧的位置，
 * 用户来这一页通常只是想确认「还有哪笔没处理」。
 *
 * <p>它同时消掉了一个隐患：两边的状态取值不同（房间订单有「使用中」、
 * 商品没有），带着对方没有的筛选值切栏会得到一片空列表，
 * 而用户看不出为什么空。
 */
function onTab(next) {
  if (tab.value === next) return
  tab.value = next
  load(true)
}

function onLoadMore() {
  page.value += 1
  load(false)
}

/** 点一张房间订单：进行中的直接进结账页，那是他此刻要做的事。 */
function onOpen(order) {
  if (order.status === 'IN_USE') {
    router.push(`/orders/${order.id}/settle`)
  } else {
    router.push(`/orders/${order.id}`)
  }
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
 * 取消待支付的商品单。
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
    <NavBar title="订单" />

    <!-- 两类单子的切换。「我的订单」这个名字太窄了 —— 商品也是订单 -->
    <div class="tabs">
      <button
        class="tabs__item"
        :class="{ 'tabs__item--active': tab === 'order' }"
        @click="onTab('order')"
      >
        房间时长
      </button>
      <button
        class="tabs__item"
        :class="{ 'tabs__item--active': tab === 'product' }"
        @click="onTab('product')"
      >
        商品
      </button>
    </div>

    <LoadingMask :loading="loading && !list.length" />

    <!-- 房间时长订单 -->
    <div v-if="list.length && tab === 'order'" class="order-list">
      <OrderCard v-for="o in orders" :key="o.id" :order="o" @click="onOpen(o)" />
    </div>

    <!-- 商品订单 -->
    <div v-else-if="list.length" class="order-list">
      <div v-for="o in productOrders" :key="o.id" class="goods">
        <div class="goods__head">
          <span class="goods__name">{{ o.productName }}</span>
          <span class="goods__status" :class="`goods__status--${o.status.toLowerCase()}`">
            {{ o.statusLabel }}
          </span>
        </div>

        <div class="goods__row">
          <span>¥{{ formatMoney(o.unitPrice) }} × {{ o.quantity }}</span>
          <span class="goods__amount">¥{{ formatMoney(o.amount) }}</span>
        </div>

        <p class="goods__no">{{ o.orderNo }}</p>
        <p class="goods__time">{{ formatDateTime(o.createdAt) }}</p>

        <div v-if="o.status === 'PENDING_PAYMENT'" class="goods__actions">
          <button class="btn btn-primary goods__btn" @click="onPay(o)">去支付</button>
          <button class="btn btn-ghost goods__btn" @click="onCancel(o)">取消</button>
        </div>

        <!--
          凭证被驳回：与「待支付」分成两支，虽然两者打开的都是同一个 PaySheet
          （选扫码转账 → 传图那条路）。分开的是**文案** ——
          「去支付」与「重新上传」对用户是两件事，
          用一个按钮说一件不对的事，他就会再付一遍钱。

          提示在左、按钮在右：这一行是要用户做个动作，按钮该落在他视线收尾的地方。
          文案也从「重新上传付款截图」缩到四个字 —— 卡片本来就在讲哪一笔商品，
          再说一遍「付款截图」是废话，而长了会把提示挤下去。
        -->
        <div v-else-if="o.status === 'REJECTED'" class="goods__actions goods__actions--split">
          <span class="goods__actions-hint">已经付过款的话，不要重复支付</span>
          <button class="btn btn-primary goods__btn" @click="onPay(o)">重新上传</button>
        </div>

        <p v-else-if="o.status === 'PAID'" class="goods__hint">已付款，到店自取即可</p>
      </div>
    </div>

    <template v-if="list.length">
      <button v-if="hasMore" class="btn btn-ghost load-more" :disabled="loading" @click="onLoadMore">
        {{ loading ? '加载中…' : '加载更多' }}
      </button>
      <p v-else class="load-more__end">没有更多了</p>
    </template>

    <EmptyState
      v-else-if="!loading && tab === 'order'"
      icon="📋"
      text="还没有订单"
      hint="在首页点「开门计时」即可开始使用"
    />

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
      :title="payingRejected ? '付款凭证未通过' : '商品订单'"
      :order-no="payingOrder?.orderNo"
      :amount="payingOrder?.amount"
      target-type="PRODUCT"
      :target-id="payingOrder?.id"
      :proof-only="payingRejected"
      :pay-text="payingRejected ? '重新上传付款截图' : '去支付'"
      @paid="onPaid"
    />
  </div>
</template>

<style scoped>
/* ---------- 两类单子的切换（比状态筛选重一档，用色块区分） ---------- */
.tabs {
  display: flex;
  gap: var(--sp-2);
  margin: var(--sp-4) 0;
}

.tabs__item {
  flex: 1;
  padding: 10px 0;
  border-radius: var(--r-btn);
  background: var(--c-card);
  color: var(--c-text-sub);
  font-size: 14px;
}

.tabs__item--active {
  background: var(--c-icon-bg);
  color: var(--c-text);
  font-weight: 600;
}

.order-list {
  padding-bottom: var(--sp-4);
}

/* ---------- 商品订单的一条 ---------- */
.goods {
  margin-bottom: var(--sp-3);
  padding: var(--sp-4);
  border-radius: var(--r-card);
  background: var(--c-card);
}

.goods__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
}

.goods__name {
  font-size: 15px;
  font-weight: 600;
}

.goods__status {
  flex: none;
  font-size: 12px;
  color: var(--c-text-sub);
}

.goods__status--pending_payment {
  color: var(--c-warning);
}

.goods__status--rejected {
  color: var(--c-danger);
}

.goods__status--paid {
  color: var(--c-success);
}

.goods__row {
  display: flex;
  justify-content: space-between;
  margin-top: var(--sp-2);
  font-size: 13px;
  color: var(--c-text-sub);
}

.goods__amount {
  font-weight: 600;
  color: var(--c-text);
}

.goods__no,
.goods__time {
  margin-top: 4px;
  font-size: 11px;
  color: var(--c-text-muted);
}

.goods__no {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.goods__actions {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-top: var(--sp-3);
}

/* 提示在左、动作在右（只给「凭证未通过」那一行用） */
.goods__actions--split {
  justify-content: space-between;
}

.goods__actions-hint {
  font-size: 12px;
  line-height: 1.4;
  color: var(--c-text-muted);
}

.goods__actions .btn {
  width: auto;
  flex: none;
}

/*
  卡片里的按钮比页面主按钮小一档：它们是这一条卡片上的动作，
  用页面主按钮的体量会把整张卡片的层级压乱。
*/
.goods__btn {
  padding: 6px var(--sp-4);
  font-size: 13px;
}

.goods__hint {
  margin-top: var(--sp-3);
  font-size: 12px;
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
  margin-top: var(--sp-3);
  width: auto;
}
</style>
