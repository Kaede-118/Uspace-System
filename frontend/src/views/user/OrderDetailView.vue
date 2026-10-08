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
import { listRejectedProofs } from '@/api/payment'
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

/**
 * 这一笔的付款凭证被管理员驳回了的话，是那一条。
 *
 * <p>订单自己的 {@code status} 仍是「已支付」—— 订单与商品是「先交付后复核」，
 * 驳回<b>刻意不回退订单状态</b>。所以「这笔钱认不认」这个结论得单独查一次，
 * 否则页面上看起来一切正常，而管理员那边早就判它不成立。
 */
const rejectedProof = ref(null)

const isUsing = computed(() => order.value?.status === 'IN_USE')
const isUnpaid = computed(() => order.value?.status === 'PENDING_PAYMENT')
const isPaid = computed(() => order.value?.status === 'PAID')

/**
 * 付款凭证被管理员驳回了，等着他重新传一张。
 *
 * <p><b>与「待支付」刻意分开</b>：那个的处置是「去付款」，这个的处置是
 * 「重新上传一张截图」（钱他多半已经付过了）。后端为它单开了一个状态，
 * 也是同一个理由 —— 混在一起的话，用户看到「待支付 ¥8.00」会再付一次钱。
 */
const isRejected = computed(() => order.value?.status === 'REJECTED')

/** 实付金额。已支付时就是订单金额，未支付时是应付额 —— 两者是同一个数。 */
const payableText = computed(() => formatMoney(order.value?.payableAmount ?? 0))

/** 状态标签的配色。 */
const statusClass = computed(() => {
  if (isUsing.value) return 'tag'
  if (isRejected.value) return 'tag tag-danger'
  if (isUnpaid.value) return 'tag tag-warning'
  return 'tag tag-success'
})

async function load() {
  loading.value = true
  try {
    const resp = await getOrder(orderId)
    order.value = resp.data
    await loadRejected()
  } catch (err) {
    toastError(errorMessage(err, '订单加载失败'))
  } finally {
    loading.value = false
  }
}

/**
 * 查这一笔的凭证有没有被驳回。
 *
 * <p>复用「我被打回的凭证」那个接口，而不是让订单接口多带一个字段：
 * 「哪些算被驳回」这条口径在后端只有一处，订单侧再判一次迟早漂移。
 *
 * <p>⚠️ <b>查失败只当作「没有」</b>：它是个提示，为它让整个订单页报错不划算。
 * 首页那条提醒条覆盖的是同一件事，这里漏了不会让人错过什么。
 *
 * <p>{@code targetId} 是数字而 {@code orderId} 来自路由参数（字符串），
 * 所以两边都转字符串再比 —— 用 {@code ===} 直接比会永远不相等，
 * 而表现只是「这块提示永远不出现」，不报任何错。
 */
async function loadRejected() {
  try {
    const resp = await listRejectedProofs()
    rejectedProof.value = (resp.data || []).find(
      (p) => p.targetType === 'ORDER' && String(p.targetId) === String(orderId)
    ) || null
  } catch {
    rejectedProof.value = null
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

      <!--
        ⚠️ 凭证被驳回 —— 这一块与 isPaid 无关，必须独立成卡。

        驳回会把订单退回待支付（见 PaymentProofService#reject），
        所以此刻 isPaid 是 false、上面那张支付信息卡根本不显示；
        而用户光看下面的「待支付」是不知道为什么的 —— 他明明付过。
        原因必须写在这里，否则他会以为系统把他的付款弄丢了。
      -->
      <div v-if="rejectedProof" class="card detail__rejected">
        <div class="card-title">⚠️ 付款凭证没通过复核</div>
        <p class="detail__rejected-reason">{{ rejectedProof.reason }}</p>
        <p class="detail__rejected-hint">
          重新上传一张付款截图即可；<b>已经付过款的话，不要重复支付</b>。
        </p>
      </div>

      <!-- 人工调整说明 -->
      <div v-if="order.adjusted === 1" class="card detail__adjusted">
        <div class="card-title">时长经人工调整</div>
        <p class="text-sm text-sub">{{ order.adjustReason || '（未填写原因）' }}</p>
      </div>

      <!--
        待支付：就地支付。
        凭证被驳回时也给它，但只留凭证那条路 —— 那一刻用户能做的只有
        重新上传一张截图（后端也不允许再发起支付），所以按钮文案与通道选择
        都要跟着变，否则入口看着像「再付一次钱」。

        ⚠️ 条件里那个 rejectedProof 是给**历史数据**兜底的：交付回退机制
        上线之前被驳回的单子，订单状态还停在「已支付」上（rejectedProof 有值、
        但 isRejected 为 false）。新流程不会产生那种组合，可它一旦存在，
        用户就既看不到重传入口、也点不动任何东西。
      -->
      <PaymentPanel
        v-if="isUnpaid || isRejected || rejectedProof"
        target-type="ORDER"
        :target-id="orderId"
        :amount="order.payableAmount"
        :proof-only="isRejected || !!rejectedProof"
        :pay-text="isRejected || rejectedProof ? '重新上传付款截图' : '去支付'"
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

/* 凭证被驳回：订单已被退回待支付，所以这块与 isPaid 无关，是独立的一张卡 */
.detail__rejected {
  border-left: 3px solid var(--c-danger);
}

.detail__rejected-reason {
  font-size: 13px;
  line-height: 1.6;
  color: var(--c-text-sub);
}

.detail__rejected-hint {
  margin-top: var(--sp-2);
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
