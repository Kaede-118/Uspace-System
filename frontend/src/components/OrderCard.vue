<script setup>
/**
 * 订单列表里的一张卡片。
 *
 * <p>金额显示的是 {@code payableAmount}（应付 / 实付）—— 未支付的显示「应付」、
 * 已支付的显示「实付」，是同一个字段，只是文案不同。
 */
import { computed } from 'vue'
import { formatMoney, formatDateTime, formatDuration } from '@/utils/format'

const props = defineProps({
  /** OrderVo */
  order: { type: Object, required: true }
})

/** 状态标签配色。 */
const statusClass = computed(() => {
  switch (props.order.status) {
    case 'IN_USE':
      return 'tag' /* 主色，表示正在计费 */
    case 'PENDING_PAYMENT':
      return 'tag tag-warning'
    default:
      return 'tag tag-success'
  }
})

/** 金额前缀：没付的叫「应付」，付过的叫「实付」。 */
const amountLabel = computed(() => (props.order.status === 'PAID' ? '实付' : '应付'))

const amountText = computed(() => formatMoney(props.order.payableAmount ?? 0))

/** 副标题：进行中显示开始时刻，已结束显示结束时刻。 */
const timeText = computed(() => {
  if (props.order.status === 'IN_USE') {
    return `${formatDateTime(props.order.startTime)} 开始`
  }
  return formatDateTime(props.order.endTime || props.order.startTime)
})

/** 时长：优先用在店时长；老订单没有这个值就退回计费时长。 */
const durationText = computed(() => {
  const o = props.order
  const minutes =
    o.stayMinutes !== null && o.stayMinutes !== undefined
      ? o.stayMinutes
      : (o.dayMinutes || 0) + (o.nightMinutes || 0)
  return minutes > 0 ? formatDuration(minutes) : ''
})
</script>

<template>
  <button class="order-card" @click="$emit('click')">
    <div class="order-card__head">
      <span class="order-card__no">{{ order.orderNo }}</span>
      <span :class="statusClass">{{ order.statusText }}</span>
    </div>

    <div class="order-card__body">
      <div class="order-card__meta">
        <span>{{ timeText }}</span>
        <span v-if="durationText" class="order-card__duration">{{ durationText }}</span>
      </div>
      <div class="order-card__amount">
        <span class="order-card__amount-label">{{ amountLabel }}</span>
        <span class="order-card__amount-value">¥{{ amountText }}</span>
      </div>
    </div>

    <!-- 人工调整过的订单标出来：那是「用户忘记点结束」的高发信号 -->
    <p v-if="order.adjusted === 1" class="order-card__adjusted">
      时长经人工调整{{ order.adjustReason ? `：${order.adjustReason}` : '' }}
    </p>
  </button>
</template>

<style scoped>
.order-card {
  display: block;
  width: 100%;
  padding: var(--sp-4);
  border-radius: var(--r-card);
  background: var(--c-card);
  text-align: left;
}

.order-card + .order-card {
  margin-top: var(--sp-3);
}

.order-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  margin-bottom: var(--sp-2);
}

.order-card__no {
  font-size: 12px;
  color: var(--c-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-card__body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
}

.order-card__meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
  color: var(--c-text-sub);
}

.order-card__duration {
  color: var(--c-text-muted);
}

.order-card__amount {
  text-align: right;
  flex-shrink: 0;
}

.order-card__amount-label {
  font-size: 11px;
  color: var(--c-text-muted);
  margin-right: var(--sp-1);
}

.order-card__amount-value {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.order-card__adjusted {
  margin-top: var(--sp-2);
  font-size: 11px;
  color: var(--c-warning);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
