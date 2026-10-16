<script setup>
/**
 * 订单详情的展示主体：金额与状态、订单信息、费用明细、支付信息、人工调整说明。
 *
 * <p><b>用户端订单详情页与运营后台订单详情页共用它</b>（2026-10-16 抽出）——
 * 两处要回答的是同一个问题「这一单是怎么算出来的」，各写一份迟早分岔：
 * 某天在用户端加了「活动减免」那一行、后台没加，而这种分岔不会有任何报错，
 * 只表现为管理员看到的总价与顾客看到的总价对不上。
 *
 * <p><b>两处不一样的部分留在调用方</b>，不塞进这里：
 * <ul>
 *   <li>用户端的 {@code PaymentPanel}（去支付 / 重传凭证）与「去结账」按钮 ——
 *       那是顾客的动作，后台不该有</li>
 *   <li>后台的付款凭证驳回提示与「调整时长」—— 后台特有，走 {@code #extra} 插槽
 *       （插在支付信息与人工调整说明之间，与用户端原来的顺序一致）</li>
 *   <li>「下单用户」这一行只有后台要（走 {@code showUser}）：顾客看自己的订单，
 *       再把名字印一遍是废话；而后台流水点进来第一句就是要问「这是谁的单」</li>
 * </ul>
 *
 * <p>费用明细分两种形态：<b>有分段账单（{@code order.bill}）时按段展示
 * 「X 档 × 单价」</b> —— 与结账页共用 {@code BillSegmentList}；没有时回落到
 * 日场/夜场汇总行。账单从哪来是后端的事，这里只消费：结算时算出的那一份会
 * 序列化成快照存进订单，详情直接读；快照机制之前结算的老订单由后端按当前规则
 * 重算，且只有金额与落库<b>完全一致</b>才会给到这里。所以「没有 bill」不是缺陷，
 * 多半是后端刻意保守的结果 —— 使用中的订单也没有账单，那时的费用去结账页看。
 *
 * <p>⚠️ <b>金额一律以 {@code payableAmount} 为准</b> —— 它是唯一权威的
 * 「要付多少」，月度优惠、月卡抵扣、活动减免都已含在其中
 * （后端的 {@code totalAmount} 同样是实收，那三列只是说明性字段），不要再减第二次。
 */
import { computed } from 'vue'
import { formatMoney, formatDuration, formatDateTime } from '@/utils/format'
import { orderStatusCls } from '@/utils/labels'
import BillSegmentList from '@/components/BillSegmentList.vue'

const props = defineProps({
  /** 订单视图（后台取 {@code /api/admin/orders/{id}}，用户端取 {@code /api/orders/{id}}，同一个 OrderVo） */
  order: { type: Object, required: true },
  /** 是否显示「下单用户」一行，见文件头 */
  showUser: { type: Boolean, default: false }
})

/**
 * 实付金额。
 *
 * <p>{@code payableAmount} 为 null 时回落 0：使用中的订单还没结算，金额无从谈起
 * （要估算得去结账预览接口）。这里<b>刻意不显示「—」</b>，
 * 与用户端订单详情页保持同一副面孔 —— 同一个组件在两处给出不同的字，
 * 管理员与顾客对同一单的描述就会不一致。
 */
const payableText = computed(() => formatMoney(props.order.payableAmount ?? 0))

/** 状态标签的配色。口径在 labels.js，与订单卡片、后台订单流水共用一份。 */
const statusClass = computed(() => orderStatusCls(props.order.status))

/**
 * 下单人展示文本。
 *
 * <p>昵称由后端补（用户端那条路不带它，所以用户端看不到这一行）；
 * 查不到用户（已注销）时回落成「用户 #ID」—— 不编一个名字，
 * 因为「查不到」与「叫这个名字」是两回事。
 */
const userText = computed(() =>
  props.order.userNickname || `用户 #${props.order.userId}`
)
</script>

<template>
  <div class="dcard">
    <!-- 金额与状态 -->
    <div class="card detail__hero">
      <span :class="statusClass">{{ order.statusText }}</span>
      <div class="detail__amount">¥{{ payableText }}</div>
      <p class="detail__order-no">{{ order.orderNo }}</p>
    </div>

    <!-- 订单信息 -->
    <div class="card">
      <div class="card-title">订单信息</div>
      <div v-if="showUser" class="detail__row">
        <span>下单用户</span>
        <span>{{ userText }}<template v-if="order.userId"> · #{{ order.userId }}</template></span>
      </div>
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
    <div v-if="order.status === 'PAID'" class="card">
      <div class="card-title">支付信息</div>
      <div class="detail__row">
        <span>支付方式</span><span>{{ order.paymentMethodLabel || '—' }}</span>
      </div>
      <div class="detail__row">
        <span>支付时间</span><span>{{ formatDateTime(order.paidAt) }}</span>
      </div>
    </div>

    <!--
      调用方要插在「支付信息」与「人工调整说明」之间的卡片 ——
      用户端放付款凭证驳回提示，后台放凭证 / 调整入口一类的东西。
    -->
    <slot name="extra" />

    <!-- 人工调整说明 -->
    <div v-if="order.adjusted === 1" class="card detail__adjusted">
      <div class="card-title">时长经人工调整</div>
      <p class="text-sm text-sub">{{ order.adjustReason || '（未填写原因）' }}</p>
      <!--
        处理人与调整时刻：只有后台那两条查询会填（用户端的订单接口不给，
        顾客不必知道是哪个管理员改的），所以这一行在用户端自然不出现
      -->
      <p v-if="order.adjustedByName || order.adjustedAt" class="detail__adjust-meta">
        处理人：{{ order.adjustedByName || '—' }}
        <template v-if="order.adjustedAt"> · 调整时间：{{ formatDateTime(order.adjustedAt) }}</template>
      </p>
    </div>
  </div>
</template>

<style scoped>
/*
 * 本组件不产生额外布局，只把几张卡竖着排开 —— 卡片之间的间距由 .card 自己带着，
 * 这里只负责「它们是一组」。
 */
.dcard {
  display: flex;
  flex-direction: column;
}

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

/* 处理人与调整时刻：比原因弱一档 —— 原因是要读的，这两项是要核对的 */
.detail__adjust-meta {
  margin-top: var(--sp-2);
  font-size: 11px;
  color: var(--c-text-muted);
}

.detail__adjusted {
  border-left: 3px solid var(--c-warning);
}
</style>
