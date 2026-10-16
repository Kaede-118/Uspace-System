<script setup>
/**
 * 月卡。
 *
 * <p>后端一次给出三样：{@code active}（生效中）/ {@code pending}（待支付）/ {@code history}（历史）。
 * <b>三者互不重叠</b>，按字段名分渲染即可 —— 不要自己拿状态和日期去判断某张卡该放哪边，
 * 那等于在客户端再实现一遍免单判定的口径，两边迟早分岔。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getMyCards, cancelCardPurchase } from '@/api/card'
import { getProofStatus } from '@/api/payment'
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

/**
 * 待支付那张单的凭证状态。
 *
 * <p>⚠️ 月卡「不是即时生效」：扫码转账交的是付款截图，复核通过才发卡（后端
 * {@code deliverOnSubmit=false}）。而购买单在复核前一直是 {@code PENDING_PAYMENT}，
 * 页面若照旧显示「待支付 + 去支付」，用户很可能再去转一次账 ——
 * 所以要单独问一次凭证状态，见 {@code getProofStatus}。
 *
 * <p>null 表示「还没查」或「还没交过图」，两者显示相同（都是待支付）。
 */
const proofStatus = ref(null)

/** 付款凭证已提交、正在等管理员复核 */
const underReview = computed(() => proofStatus.value?.verifyStatus === 'SUBMITTED')

/** 付款凭证被驳回 —— 那时按钮要变成「重新上传付款截图」 */
const proofRejected = computed(() => proofStatus.value?.verifyStatus === 'REJECTED')

/** 那张单此刻该叫什么，三态各有各的说法 */
const pendingTitle = computed(() => {
  if (underReview.value) return '待复核的月卡'
  if (proofRejected.value) return '付款凭证未通过的月卡'
  return '待支付的月卡'
})

/**
 * 一张卡的有效期文案。
 *
 * <p>⚠️ <b>STAFF 卡按卡种显示「长期有效」，不要摆出 2099-12-31</b> ——
 * 那是后端表达「无到期日」的方式（远期日期），对顾客来说是个实现细节。
 * 按卡种判而不是按日期大小判（后者会在 2099 年之后静默失效）。
 *
 * <p>STAFF 卡也没有剩余天数（后端返回 null）—— 模板里的「剩余 N 天」
 * 因此必须判 null 再显示，否则会出现「剩余 null 天」。
 *
 * @param {object} card 卡视图
 * @returns {string} 形如 "2026-10-10 至 2026-11-08" / "长期有效"
 */
function cardPeriodText(card) {
  if (!card) return ''
  if (card.cardType === 'STAFF') return '长期有效'
  return `${formatDate(card.startDate)} 至 ${formatDate(card.endDate)}`
}

async function load() {
  loading.value = true
  try {
    const resp = await getMyCards()
    const data = resp.data || {}
    active.value = data.active || null
    pending.value = data.pending || null
    history.value = data.history || []
    proofStatus.value = pending.value ? await loadProofStatus(pending.value.id) : null
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    loading.value = false
  }
}

/**
 * 查待支付那张单的凭证状态。
 *
 * <p>查询失败一律按「没交过图」处理（返回 null）—— 它只影响这一个提示，
 * 弹一句错误提示反而会盖住页面上真正的内容；最坏的结果只是多显示一个支付按钮。
 *
 * @param {number|string} orderId 月卡购买单 ID
 * @returns {Promise<object|null>} 凭证状态；查不到时为 null
 */
async function loadProofStatus(orderId) {
  try {
    const resp = await getProofStatus({ targetType: 'MONTHLY_CARD', targetId: orderId })
    return resp.data || null
  } catch {
    return null
  }
}

/** 支付那张待付款的购买单。 */
function onPay() {
  if (!pending.value) return
  payVisible.value = true
}

/**
 * 付款完成（或凭证已提交）。
 *
 * <p>⚠️ <b>两条路的提示必须分开</b>（2026-10-10 修）：扫码转账提交的是付款截图，
 * 月卡要等管理员复核通过才发卡（后端 {@code deliverOnSubmit=false}）——
 * 那时说一句「月卡已生效」，用户刷新看不到卡就会以为钱丢了。
 * 判据用返回体里的 {@code delivered}，<b>不按 targetType 自己猜</b>。
 *
 * @param {object} [info] 凭证提交的返回体；线上通道那条路没有（即为已交付）
 */
async function onPaid(info) {
  toastSuccess(info && info.delivered === false
    ? '付款凭证已提交，待管理员复核后开通'
    : '月卡已生效')
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
    <NavBar title="月卡" />

    <LoadingMask :loading="loading" />

    <!-- 生效中 -->
    <div v-if="active" class="card wallet__active">
      <span class="tag tag-success">{{ active.statusLabel }}</span>
      <p class="wallet__type">{{ active.cardTypeLabel }}</p>
      <p class="wallet__desc">{{ active.coverageLabel }} · {{ active.periodText }}</p>
      <div class="wallet__dates">
        {{ cardPeriodText(active) }}
        <!-- STAFF 卡没有剩余天数（后端返回 null，见 cardPeriodText 的说明） -->
        <span v-if="active.remainingDays != null" class="wallet__remaining">
          剩余 {{ active.remainingDays }} 天
        </span>
      </div>
      <p class="wallet__no">{{ active.cardNo }}</p>
    </div>

    <!-- 待支付 / 待复核（同一条单的三种样子，2026-10-10 加后两种） -->
    <div v-else-if="pending" class="card wallet__pending">
      <div class="card-title">{{ pendingTitle }}</div>
      <p class="wallet__pending-info">
        {{ pending.cardTypeLabel }} · ¥{{ formatMoney(pending.price) }}
      </p>
      <p class="wallet__no">{{ pending.orderNo }}</p>
      <!--
        ⚠️ 三态各有各的说法（2026-10-10 由用户要求加「待复核」）：
          · 已交图、等复核 —— 说清在等什么，并提醒别重复付款
          · 凭证被驳回   —— 带上管理员写的原因，指路重传
          · 还没交图     —— 原样保留那句「并非即时生效」
        都不写的话，用户看到的就是一个「待支付 + 去支付」，
        而他明明已经把钱转出去了 —— 转头就会再转一次
      -->
      <p v-if="underReview" class="wallet__pending-note">
        付款凭证已提交，等待管理员复核 —— 通过后自动开通，请勿重复付款。
      </p>
      <p v-else-if="proofRejected" class="wallet__pending-note wallet__pending-note--warn">
        付款凭证未通过：{{ proofStatus.rejectReason || '管理员未填写原因' }}。请重新上传一张付款截图。
      </p>
      <p v-else class="wallet__pending-note">
        月卡并非即时生效：付款后需等管理员复核，通过后才会开通。
      </p>
      <div class="wallet__pending-actions">
        <!-- 等复核时不摆支付按钮：那一笔已经付过了，再点一次只会重新传图 -->
        <button v-if="!underReview" class="btn btn-primary" @click="onPay">
          {{ proofRejected ? '重新上传付款截图' : '去支付' }}
        </button>
        <!-- 交过图之后也不给取消：钱可能已经转出去了，取消等于把一笔已付的账关掉 -->
        <button v-if="!underReview" class="btn btn-ghost" @click="onCancel">取消</button>
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
          <span class="text-sm text-muted">{{ cardPeriodText(c) }}</span>
        </div>
        <span class="tag">{{ c.statusLabel }}</span>
      </div>
    </div>

    <!-- 凭证被驳回时只走「上传付款截图」那条路，与订单页同一处置：
         那时后端也不允许再发起支付（loadForPay 只认待支付），
         摆一排通道给他选只会换来一句指错方向的拒绝 -->
    <PaySheet
      v-model:visible="payVisible"
      :title="proofRejected ? '重新上传付款凭证' : '月卡购买'"
      :order-no="pending?.orderNo"
      :amount="pending?.price"
      target-type="MONTHLY_CARD"
      :target-id="pending?.id"
      :proof-only="proofRejected"
      :pay-text="proofRejected ? '重新上传付款截图' : '去支付'"
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

.wallet__pending-note {
  margin-top: var(--sp-2);
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-sub);
}

/* 凭证被驳回那一句：与「还没交图」的提示必须一眼分得开 */
.wallet__pending-note--warn {
  color: var(--c-danger);
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
