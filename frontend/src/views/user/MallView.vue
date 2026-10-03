<script setup>
/**
 * 商城：实体商品 + 月卡两个分区，默认进实体商品。
 *
 * <p>两个分区的下单<b>复用同一个支付入口</b>（{@code POST /api/payments}），
 * 只是 {@code targetType} 不同：商品传 {@code PRODUCT}、月卡传 {@code MONTHLY_CARD}。
 * 回调、验签、幂等一行未改 —— 这是「把入口收在『支付』这个动作上」那个设计的兑现。
 *
 * <p>⚠️ <b>月卡的价格与时段文案一律用后端返回值</b>：
 * {@code periodText} 里的「22:00 – 次日 10:00」由后端的
 * {@code uspace.billing.day-start / day-end} 决定，写死会在改配置时对不上。
 */
import { ref, onMounted } from 'vue'
import { listProducts } from '@/api/product'
import { createProductOrder } from '@/api/product'
import { listCardTypes, purchaseCard, getMyCards } from '@/api/card'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage, isCode, ErrorCode } from '@/utils/error'
import { formatMoney, formatDate } from '@/utils/format'
import ProductDetailSheet from '@/components/ProductDetailSheet.vue'
import PaySheet from '@/components/PaySheet.vue'
import EmptyState from '@/components/EmptyState.vue'

const tab = ref('goods')
const tabs = [
  { key: 'goods', label: '实体商品' },
  { key: 'card', label: '月卡' }
]

const products = ref([])
const cardTypes = ref([])
const activeCard = ref(null)
const loading = ref(true)

/* ---------------- 下单 ---------------- */

/** 商品详情弹层的开关与当前商品。 */
const detailVisible = ref(false)
const selectedProduct = ref(null)
const submitting = ref(false)

/** 支付弹层的状态。商品与月卡共用。 */
const payVisible = ref(false)
const payOrder = ref(null)
const payTargetType = ref('PRODUCT')

function openProduct(product) {
  // 售罄的商品仍然可以点开看详情 —— 点不动会让人以为是页面坏了
  selectedProduct.value = product
  detailVisible.value = true
}

/**
 * 提交商品下单。
 *
 * @param {number} quantity 数量
 */
async function onSubmitProduct(quantity) {
  if (!selectedProduct.value) return
  submitting.value = true
  try {
    const resp = await createProductOrder({
      productId: selectedProduct.value.id,
      quantity
    })
    detailVisible.value = false
    payOrder.value = resp.data
    payTargetType.value = 'PRODUCT'
    payVisible.value = true
  } catch (err) {
    if (isCode(err, ErrorCode.PRODUCT_SOLD_OUT)) {
      toastError('商品已售罄')
      // 售罄了就把列表刷一遍，免得页面上还挂着「有货」的假象
      await loadProducts()
    } else {
      toastError(errorMessage(err, '下单失败'))
    }
  } finally {
    submitting.value = false
  }
}

/**
 * 购买月卡。
 *
 * <p>月卡是「一人一卡」：已有生效中的卡时后端会返回 40924，
 * 由后端判定而不是前端 —— 前端自己也拦一道的话，两边口径迟早分岔。
 *
 * @param {object} cardType 卡种
 */
async function onBuyCard(cardType) {
  try {
    const resp = await purchaseCard(cardType.cardType)
    payOrder.value = resp.data
    payTargetType.value = 'MONTHLY_CARD'
    payVisible.value = true
  } catch (err) {
    toastError(errorMessage(err, '下单失败'))
  }
}

/** 支付完成：刷新数据（商品库存变了、月卡可能生效了）。 */
async function onPaid() {
  toastSuccess('支付成功')
  payVisible.value = false
  await Promise.all([loadProducts(), loadCards()])
}

/* ---------------- 数据 ---------------- */

async function loadProducts() {
  try {
    const resp = await listProducts()
    products.value = resp.data || []
  } catch (err) {
    toastError(errorMessage(err, '商品加载失败'))
  }
}

async function loadCards() {
  const [typesRes, mineRes] = await Promise.allSettled([listCardTypes(), getMyCards()])
  if (typesRes.status === 'fulfilled') cardTypes.value = typesRes.value.data || []
  if (mineRes.status === 'fulfilled') activeCard.value = mineRes.value.data?.active || null
}

onMounted(async () => {
  await Promise.all([loadProducts(), loadCards()])
  loading.value = false
})
</script>

<template>
  <div class="page page-with-tabbar">
    <header class="mall__head">
      <h1 class="mall__title">商城</h1>
      <p class="mall__sub">实体商品与月卡</p>
    </header>

    <!-- 分区切换 -->
    <div class="mall__tabs">
      <button
        v-for="item in tabs"
        :key="item.key"
        class="mall__tab"
        :class="{ 'mall__tab--active': tab === item.key }"
        @click="tab = item.key"
      >
        {{ item.label }}
      </button>
    </div>

    <!-- ========== 实体商品 ========== -->
    <template v-if="tab === 'goods'">
      <div v-if="products.length" class="goods">
        <button
          v-for="p in products"
          :key="p.id"
          class="goods__card"
          :class="{ 'goods__card--soldout': p.soldOut }"
          @click="openProduct(p)"
        >
          <div class="goods__cover">
            <img v-if="p.cover" :src="p.cover" :alt="p.name" />
            <span v-else class="goods__cover-fallback">🎁</span>
            <span v-if="p.soldOut" class="goods__soldout">已售罄</span>
          </div>
          <div class="goods__name">{{ p.name }}</div>
          <div class="goods__price">¥{{ formatMoney(p.price) }}</div>
        </button>
      </div>

      <EmptyState v-else-if="!loading" icon="🛒" text="暂无商品" hint="管理员上架后就能看到" />
    </template>

    <!-- ========== 月卡 ========== -->
    <template v-else>
      <!-- 已有生效中的卡时先把状态亮出来，免得用户买完才发现买重了 -->
      <div v-if="activeCard" class="card card-active">
        <div class="card-title">当前月卡</div>
        <p class="card-active__name">{{ activeCard.cardTypeLabel }}</p>
        <p class="text-sm text-sub">
          有效期至 {{ formatDate(activeCard.endDate) }}
        </p>
      </div>

      <div
        v-for="t in cardTypes"
        :key="t.cardType"
        class="card card-type"
      >
        <div class="card-type__head">
          <!-- ⚠️ 字段名是 label / cardType，不是 typeLabel / type（实测核对过） -->
          <span class="card-type__name">{{ t.label }}</span>
          <span class="card-type__price">¥{{ formatMoney(t.price) }}</span>
        </div>
        <p class="card-type__period">{{ t.coverageLabel }} · {{ t.periodText }}</p>
        <p class="card-type__desc">有效期 {{ t.validDays }} 天</p>

        <button
          class="btn btn-primary card-type__buy"
          :disabled="!!activeCard"
          @click="onBuyCard(t)"
        >
          {{ activeCard ? '已有生效中的月卡' : '购买' }}
        </button>
      </div>

      <EmptyState v-if="!cardTypes.length && !loading" icon="🎫" text="暂无可购买的月卡" />
    </template>

    <!-- 商品详情与下单 -->
    <ProductDetailSheet
      v-model:visible="detailVisible"
      :product="selectedProduct"
      :submitting="submitting"
      @submit="onSubmitProduct"
    />

    <!-- 支付 -->
    <PaySheet
      v-model:visible="payVisible"
      title="订单已创建"
      :order-no="payOrder?.orderNo"
      :amount="payOrder?.amount ?? payOrder?.price"
      :target-type="payTargetType"
      :target-id="payOrder?.id"
      @paid="onPaid"
    />
  </div>
</template>

<style scoped>
.mall__head {
  margin-bottom: var(--sp-4);
}

.mall__title {
  font-size: 22px;
  font-weight: 600;
}

.mall__sub {
  margin-top: var(--sp-1);
  font-size: 13px;
  color: var(--c-text-muted);
}

.mall__tabs {
  display: flex;
  gap: var(--sp-2);
  padding: var(--sp-1);
  border-radius: var(--r-pill);
  background: var(--c-card);
  margin-bottom: var(--sp-3);
  max-width: 320px;
}

.mall__tab {
  flex: 1;
  height: 34px;
  border-radius: var(--r-pill);
  font-size: 13px;
  color: var(--c-text-sub);
  transition: background 0.15s, color 0.15s;
}

.mall__tab--active {
  background: #fff;
  color: var(--c-primary);
  font-weight: 500;
}

/* ---- 商品网格 ---- */

/*
 * ⚠️ 写死两列（2026-10-03）：页面宽度已锚定手机竖屏（见 base.css 的 --page-max，
 * 内容 328px），每格因此是 (328 − 12) ÷ 2 = 158px —— 与手机上一致。
 * 用 auto-fill 的话，宽度一变它会漂成三列，商品图跟着变小，
 * 而卡片里的字号并不会跟着调。
 */
.goods {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--sp-3);
}

.goods__card {
  border-radius: var(--r-card);
  background: var(--c-card);
  overflow: hidden;
  text-align: left;
}

.goods__card--soldout {
  opacity: 0.6;
}

.goods__cover {
  position: relative;
  aspect-ratio: 1 / 1;
  background: var(--c-icon-bg);
}

.goods__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.goods__cover-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: 32px;
}

.goods__soldout {
  position: absolute;
  top: var(--sp-2);
  right: var(--sp-2);
  padding: 2px var(--sp-2);
  border-radius: var(--r-pill);
  background: rgba(43, 35, 64, 0.7);
  color: #fff;
  font-size: 11px;
}

.goods__name {
  padding: var(--sp-2) var(--sp-3) 0;
  font-size: 13px;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.goods__price {
  padding: 2px var(--sp-3) var(--sp-3);
  font-size: 15px;
  font-weight: 600;
  color: var(--c-primary);
}

/* ---- 月卡 ---- */

.card-active {
  margin-bottom: var(--sp-3);
}

.card-active__name {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-primary);
  margin-bottom: var(--sp-1);
}

.card-type__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: var(--sp-2);
}

.card-type__name {
  font-size: 16px;
  font-weight: 600;
}

.card-type__price {
  font-size: 20px;
  font-weight: 600;
  color: var(--c-primary);
}

.card-type__period {
  font-size: 12px;
  color: var(--c-text-sub);
  line-height: 1.6;
}

.card-type__desc {
  margin-top: var(--sp-1);
  font-size: 12px;
  color: var(--c-text-muted);
  line-height: 1.6;
}

.card-type__buy {
  margin-top: var(--sp-3);
}
</style>
