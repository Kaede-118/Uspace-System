<script setup>
/**
 * 订单详情（运营后台）。
 *
 * <p>2026-10-16 新增：订单流水上点任意一单进这里。展示主体是
 * {@code OrderDetailContent} —— <b>与用户端订单详情页是同一个组件</b>，
 * 管理员在后台看到的账单结构（逐段「X 档 × 单价」、封顶标记、月度优惠 /
 * 月卡抵扣 / 活动减免三行说明）与顾客看到的逐字一致。
 * 后台多一行「下单用户」（{@code show-user}）：流水点进来第一句要问的就是
 * 「这是谁的单」。
 *
 * <p>{@code PaymentPanel}（去支付）与「去结账」按钮<b>刻意不在这一页</b>：
 * 那是顾客的动作，后台替顾客付款、替顾客停表都会把账搅乱 ——
 * 后台能做的只有<b>调整时长</b>，那个入口在流水列表的每一行上。
 *
 * <p>⚠️ <b>走的是后台自己的接口</b>（{@code GET /api/admin/orders/{id}}）而不是
 * 用户端的 {@code /api/orders/{id}}：后者会校验订单归属，管理员点别人的单会被拒。
 */
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAdminOrder } from '@/api/admin'
import { toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import OrderDetailContent from '@/components/OrderDetailContent.vue'
import LoadingMask from '@/components/LoadingMask.vue'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const orderId = route.params.id

const order = ref(null)
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    const resp = await getAdminOrder(orderId)
    order.value = resp.data
  } catch (err) {
    toastError(errorMessage(err, '订单加载失败'))
  } finally {
    loading.value = false
  }
}

/**
 * 回到订单流水。
 *
 * <p><b>把跳进来时带上的 query 原样带回去</b> —— 那是列表页的筛选条件与页码
 * （见 {@code OrderManageView} 的 {@code syncQuery}）。用 {@code push} 而不是
 * {@code back()}：管理员可能是从收藏、消息里点开这条链接直接进来的，
 * 那时没有「上一条历史」可退，{@code back()} 会把他弹出后台。
 */
function goBack() {
  router.push({ path: '/admin/orders', query: route.query })
}

onMounted(load)
</script>

<template>
  <div class="page">
    <!--
      后台没有 NavBar（那是用户端的返回条），所以自己给一条：
      没有它，管理员从列表点进来之后只能靠浏览器的后退键回去。
    -->
    <div class="od__bar">
      <button class="od__back" @click="goBack">← 订单流水</button>
      <span class="od__title">订单详情</span>
    </div>

    <LoadingMask :loading="loading" />

    <OrderDetailContent v-if="order" :order="order" show-user />

    <EmptyState
      v-else-if="!loading"
      icon="🧾"
      text="订单不存在"
      hint="它可能已被删除，或链接里的订单号不对"
    />
  </div>
</template>

<style scoped>
.od__bar {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-bottom: var(--sp-2);
}

/*
 * 返回入口用文字按钮而不是图标：后台是 PC 上的工具，
 * 一行字比一个箭头更容易被看懂（箭头在后台里没有固定含义）。
 */
.od__back {
  flex-shrink: 0;
  height: 30px;
  padding: 0 var(--sp-3);
  border-radius: var(--r-pill);
  background: var(--c-card);
  color: var(--c-primary);
  font-size: 13px;
}

.od__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text-sub);
}
</style>
