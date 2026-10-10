<script setup>
/**
 * 分段账单。
 *
 * <p>结账预览页、订单详情页、后台订单详情共用 —— 三处的账单是同一份数据，
 * 各写一份渲染迟早分岔（比如某处加了「月卡抵扣」另一处没加）。
 *
 * <p>三条不能省的东西，每条都对应一个「用户以为算错了」的场景：
 * <ul>
 *   <li><b>逐段显示单价与封顶</b> —— 日夜场不同价、优惠与否又不同价。
 *       只给总价的话，用户拿总时长去乘单价会对不上</li>
 *   <li><b>说明性金额（月度优惠 / 月卡抵扣 / 活动减免）单独列</b> —— 它们解释
 *       「为什么便宜了」，<b>不是「还要再减多少」</b>。
 *       有三项是因为三种优惠并行存在且互不重叠（见 BillingService 的类注释），
 *       合成一行的话，账单上就说不清那笔钱是谁免的</li>
 *   <li><b>包场减免要说一句</b> —— 否则包场用户看到「在店 3 小时、计费 1 小时」会问为什么</li>
 * </ul>
 *
 * <p><b>段的免费标记也是这一层的</b>：被月卡或活动覆盖的段实收是 0，
 * 而它的档数、单价、封顶值都还在（后端刻意保留）—— 不标一句「为什么 0 元」的话，
 * 那行看起来就像算错了。
 */
import { computed } from 'vue'
import { formatMoney, formatDuration } from '@/utils/format'

const props = defineProps({
  /** 账单对象：{ segments, totalMinutes, totalAmount, discountAmount, cardFreeAmount, activityFreeAmount } */
  bill: { type: Object, default: null },
  /** 实际应付金额（含各项优惠）。不传则显示账单合计 */
  payableAmount: { type: [Number, String], default: null },
  /** 本单优惠金额（说明性）。不传则取 {@code bill.discountAmount} */
  discountAmount: { type: [Number, String], default: null },
  /** 月卡为本单免掉的金额（说明性）。不传则取 {@code bill.cardFreeAmount} */
  cardFreeAmount: { type: [Number, String], default: null },
  /** 免费活动为本单免掉的金额（说明性）。不传则取 {@code bill.activityFreeAmount} */
  activityFreeAmount: { type: [Number, String], default: null },
  /** 是否因包场而减免了时长 */
  freeByBooking: { type: Boolean, default: false },
  /** 结算前的当月累计实付额 —— 用来解释「为什么走了/没走优惠价」 */
  monthSpentBefore: { type: [Number, String], default: null },
  /** 是否按月度优惠价计费 */
  discounted: { type: Boolean, default: false },
  /** 在店时长（分钟）。与 bill.totalMinutes（计费时长）不是一个口径 */
  stayMinutes: { type: Number, default: null }
})

const segments = computed(() => props.bill?.segments || [])
const totalMinutes = computed(() => props.bill?.totalMinutes || 0)
const totalAmount = computed(() => props.bill?.totalAmount || 0)

/** 实付：优先用调用方给的 payableAmount（那是唯一权威的「要付多少」）。 */
const finalPay = computed(() =>
  props.payableAmount === null || props.payableAmount === undefined
    ? totalAmount.value
    : props.payableAmount
)

/** 时段中文名。 */
function periodLabel(period) {
  return period === 'NIGHT' ? '夜场' : '日场'
}

/*
 * 三处说明性金额：优先用调用方显式传的 prop，没传就从 bill 上取 ——
 * BillingResult 本来就带这三个字段（后端算好的）。
 *
 * 回落是刻意的：调用方没传时，账单上少的那几行是【解释】而不是【金额】，
 * 少了它用户会以为「为什么便宜了」没有交代，而页面上不会有任何报错。
 * computed 的名字前缀 shown 是为了避开与 props 同名 —— 同名会让模板里
 * 那个标识符指向的东西变得说不清。
 */
const shownDiscount = computed(() => props.discountAmount ?? props.bill?.discountAmount)
const shownCardFree = computed(() => props.cardFreeAmount ?? props.bill?.cardFreeAmount)
const shownActivityFree = computed(() => props.activityFreeAmount ?? props.bill?.activityFreeAmount)

const hasDiscount = computed(() => Number(shownDiscount.value) > 0)
const hasCardFree = computed(() => Number(shownCardFree.value) > 0)
const hasActivityFree = computed(() => Number(shownActivityFree.value) > 0)
/** 实付与合计不同才需要单独列「应付」那一行。 */
const showFinalPay = computed(() => Number(finalPay.value) !== Number(totalAmount.value))

/*
 * 半场累计封顶的解释（2026-10-10）：封顶价按【半场】算 ——
 * 同一个半场（日场 10:00-22:00、夜场 22:00-次日 10:00）里，
 * 被包场剪开的几截、被活动 / 月卡切开的几段、乃至同一半场内的【其它订单】
 *（玩一段结算再开新单），实收合计不超过一个封顶价。
 *
 * 本段因「之前已经收过钱」而少收时，后端在段上留了
 * halfPeriodUsedBefore（本段之前已收）与 halfPeriodCutAmount（本段少收的）。
 * 不解释一句的话，那行 ¥0.00 看起来就像算错了 —— 用户实测问过这个
 *（「日场怎么也免费了」：其实是该半场内已付满封顶，不是月卡免的）。
 *
 * 口径：整单里找【第一个】被削的段取它的 usedBefore —— 它就是「该半场
 * 在本段之前的累计」，而账单按时间排列，第一个被削的段最能说明问题。
 */
const quotaCutSegment = computed(() =>
  segments.value.find((s) => Number(s.halfPeriodCutAmount) > 0) || null
)
</script>

<template>
  <div class="bill">
    <!-- 逐段 -->
    <div v-for="(seg, i) in segments" :key="i" class="bill__seg">
      <div class="bill__seg-head">
        <span class="bill__period-head">
          <span class="bill__period">{{ periodLabel(seg.period) }}</span>
          <!--
            这一段的实收为什么是 0。两者互斥（见 BillingService）：被月卡覆盖的段
            只记月卡 —— 月卡用户本来就免费，活动并没有为他省下什么
          -->
          <span v-if="seg.freeByActivity" class="bill__free bill__free--activity">活动免费</span>
          <span v-else-if="seg.freeByCard" class="bill__free">月卡免费</span>
          <!--
            因半场累计而少收（不是免费）：与上面两种互斥 ——
            被月卡 / 活动免掉的段 cutAmount 是 0（它们本来就收 0）
          -->
          <span v-if="Number(seg.halfPeriodCutAmount) > 0" class="bill__free bill__free--quota">
            本时段已收满
          </span>
        </span>
        <span class="bill__duration">{{ formatDuration(seg.minutes) }}</span>
      </div>
      <div class="bill__seg-body">
        <span class="bill__calc">
          {{ seg.units }} 档 × ¥{{ formatMoney(seg.unitPrice) }}
          <span v-if="seg.capped" class="bill__capped">已封顶 ¥{{ formatMoney(seg.capAmount) }}</span>
        </span>
        <span class="bill__amount">¥{{ formatMoney(seg.amount) }}</span>
      </div>
    </div>

    <p v-if="!segments.length" class="bill__empty text-muted">本单没有产生计费时长</p>

    <div class="divider" />

    <!-- 合计 -->
    <div class="bill__row">
      <span>计费时长</span>
      <span>{{ formatDuration(totalMinutes) }}</span>
    </div>
    <div v-if="stayMinutes !== null" class="bill__row">
      <span>在店时长</span>
      <span>{{ formatDuration(stayMinutes) }}</span>
    </div>

    <div class="bill__row bill__row--total">
      <span>合计</span>
      <span>¥{{ formatMoney(totalAmount) }}</span>
    </div>

    <!-- 说明性金额：解释「为什么便宜了」，不是「再减多少」 -->
    <div v-if="hasDiscount" class="bill__row bill__row--note">
      <span>月度优惠</span>
      <span>-¥{{ formatMoney(shownDiscount) }}</span>
    </div>
    <div v-if="hasCardFree" class="bill__row bill__row--note">
      <span>月卡抵扣</span>
      <span>-¥{{ formatMoney(shownCardFree) }}</span>
    </div>
    <div v-if="hasActivityFree" class="bill__row bill__row--note">
      <span>活动减免</span>
      <span>-¥{{ formatMoney(shownActivityFree) }}</span>
    </div>

    <div v-if="showFinalPay" class="bill__row bill__row--total">
      <span>应付</span>
      <span class="text-primary">¥{{ formatMoney(finalPay) }}</span>
    </div>

    <!-- 两个口径不一样时说明一句，免得用户以为算错 -->
    <p v-if="freeByBooking" class="bill__hint">
      包场时段不计费，所以计费时长短于在店时长
    </p>
    <p v-if="monthSpentBefore !== null && hasDiscount" class="bill__hint">
      结算前本月已消费 ¥{{ formatMoney(monthSpentBefore) }}，本单按优惠价计费
    </p>
    <p v-if="quotaCutSegment" class="bill__hint">
      同一时段（日场 / 夜场）内的消费合计只收一次封顶价。
      本单之前该时段内已收 ¥{{ formatMoney(quotaCutSegment.halfPeriodUsedBefore) }}，
      额度用尽的部分不再计费
    </p>
  </div>
</template>

<style scoped>
.bill__seg + .bill__seg {
  margin-top: var(--sp-3);
  padding-top: var(--sp-3);
  border-top: 1px dashed var(--c-border);
}

.bill__seg-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: var(--sp-1);
}

/* 时段名与它的免费标记并排 —— 两者在视觉上是一件事（这一段属于哪儿、为什么免费） */
.bill__period-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.bill__period {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

/*
 * 免费标记。活动用主色、月卡用中性灰 ——
 * 活动是「此刻正在发生的优惠」，比一张长期持有的卡更值得被看见。
 */
.bill__free {
  padding: 1px var(--sp-2);
  border-radius: var(--r-pill);
  background: var(--c-card);
  color: var(--c-text-sub);
  font-size: 11px;
  font-weight: 400;
}

.bill__free--activity {
  background: var(--c-primary-pale);
  color: var(--c-primary);
}

/*
 * 「本时段已收满」用警告色 —— 它解释的是一段明显反常的 0 元 / 小额，
 * 与上面两种「免费」不是一回事（用户没有获得好处，是额度早用掉了），
 * 用中性灰会被误读成又一处优惠。
 */
.bill__free--quota {
  color: var(--c-warning);
}

.bill__duration {
  font-size: 12px;
  color: var(--c-text-sub);
}

.bill__seg-body {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-2);
}

.bill__calc {
  font-size: 12px;
  color: var(--c-text-muted);
}

.bill__capped {
  margin-left: var(--sp-2);
  color: var(--c-warning);
}

.bill__amount {
  font-size: 14px;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.bill__empty {
  font-size: 13px;
  padding: var(--sp-2) 0;
}

.bill__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 13px;
  color: var(--c-text-sub);
  padding: 3px 0;
}

.bill__row--note {
  font-size: 12px;
  color: var(--c-text-muted);
}

.bill__row--total {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
  margin-top: var(--sp-1);
}

.bill__hint {
  margin-top: var(--sp-2);
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-text-muted);
}
</style>
