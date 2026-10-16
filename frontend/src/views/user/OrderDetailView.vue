<script setup>
/**
 * 订单详情（用户端）。
 *
 * <p><b>展示主体全在 {@code OrderDetailContent} 里</b>（2026-10-16 抽出）——
 * 运营后台的订单详情用的是同一个组件，两处「这一单怎么算的」因此不会分岔。
 * 这一页只负责<b>用户端独有的三件事</b>：
 * <ul>
 *   <li>拉自己的订单（{@code GET /api/orders/{id}}，后端会校验归属）</li>
 *   <li>查这一笔的付款凭证有没有被驳回，并把提示卡插进详情中间</li>
 *   <li>两个动作：待支付时给 {@code PaymentPanel}，使用中时给「去结账」</li>
 * </ul>
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
import NavBar from '@/components/NavBar.vue'
import OrderDetailContent from '@/components/OrderDetailContent.vue'
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

// 「已支付才显示支付信息」那条判定跟着展示主体一起搬进了 OrderDetailContent，
// 这里只留用户端自己用得上的三个状态
const isUsing = computed(() => order.value?.status === 'IN_USE')
const isUnpaid = computed(() => order.value?.status === 'PENDING_PAYMENT')

/**
 * 付款凭证被管理员驳回了，等着他重新传一张。
 *
 * <p><b>与「待支付」刻意分开</b>：那个的处置是「去付款」，这个的处置是
 * 「重新上传一张截图」（钱他多半已经付过了）。后端为它单开了一个状态，
 * 也是同一个理由 —— 混在一起的话，用户看到「待支付 ¥8.00」会再付一次钱。
 */
const isRejected = computed(() => order.value?.status === 'REJECTED')

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
      <OrderDetailContent :order="order">
        <!--
          ⚠️ 凭证被驳回 —— 这一块与 isPaid 无关，必须独立成卡。

          驳回会把订单退回待支付（见 PaymentProofService#reject），
          所以此刻 isPaid 是 false、详情里那张支付信息卡根本不显示；
          而用户光看下面的「待支付」是不知道为什么的 —— 他明明付过。
          原因必须写在这里，否则他会以为系统把他的付款弄丢了。
        -->
        <template #extra>
          <div v-if="rejectedProof" class="card detail__rejected">
            <div class="card-title">⚠️ 付款凭证没通过复核</div>
            <p class="detail__rejected-reason">{{ rejectedProof.reason }}</p>
            <p class="detail__rejected-hint">
              重新上传一张付款截图即可；<b>已经付过款的话，不要重复支付</b>。
            </p>
          </div>
        </template>
      </OrderDetailContent>

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

.detail__action {
  margin-top: var(--sp-4);
}
</style>
