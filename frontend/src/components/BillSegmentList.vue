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
 *   <li><b>说明性金额（优惠 / 月卡抵扣）单独列</b> —— 它们解释「为什么便宜了」，
 *       <b>不是「还要再减多少」</b>。实付一律以 {@code payableAmount} 为准</li>
 *   <li><b>包场减免要说一句</b> —— 否则包场用户看到「在店 3 小时、计费 1 小时」会问为什么</li>
 * </ul>
 */
import { computed } from 'vue'
import { formatMoney, formatDuration } from '@/utils/format'

const props = defineProps({
  /** 账单对象：{ segments, totalMinutes, totalAmount } */
  bill: { type: Object, default: null },
  /** 实际应付金额（含优惠与月卡抵扣）。不传则显示账单合计 */
  payableAmount: { type: [Number, String], default: null },
  /** 本单优惠金额（说明性） */
  discountAmount: { type: [Number, String], default: null },
  /** 月卡为本单免掉的金额（说明性） */
  cardFreeAmount: { type: [Number, String], default: null },
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

const hasDiscount = computed(() => Number(props.discountAmount) > 0)
const hasCardFree = computed(() => Number(props.cardFreeAmount) > 0)
/** 实付与合计不同才需要单独列「应付」那一行。 */
const showFinalPay = computed(() => Number(finalPay.value) !== Number(totalAmount.value))
</script>

<template>
  <div class="bill">
    <!-- 逐段 -->
    <div v-for="(seg, i) in segments" :key="i" class="bill__seg">
      <div class="bill__seg-head">
        <span class="bill__period">{{ periodLabel(seg.period) }}</span>
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
      <span>月度优惠{{ discounted ? '' : '' }}</span>
      <span>-¥{{ formatMoney(discountAmount) }}</span>
    </div>
    <div v-if="hasCardFree" class="bill__row bill__row--note">
      <span>月卡抵扣</span>
      <span>-¥{{ formatMoney(cardFreeAmount) }}</span>
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

.bill__period {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
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
