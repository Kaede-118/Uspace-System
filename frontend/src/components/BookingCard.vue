<script setup>
/**
 * 包场列表里的一张卡片。
 *
 * <p>⚠️ {@code BookingVo} <b>没有 {@code statusText}</b>（订单有，包场没有），
 * 所以状态中文要映射 —— 表在 {@code utils/labels.js}，
 * 与运营后台的包场页共用同一份（两处各写一份的话，改文案时必然只改一处）。
 */
import { computed } from 'vue'
import { formatDateTime, formatTime, formatMoney } from '@/utils/format'
import { bookingStatusOf } from '@/utils/labels'

const props = defineProps({
  /** BookingVo */
  booking: { type: Object, required: true },
  /** 是否显示价格（「我参与的」列表里通常不关心价格，由调用方决定） */
  showPrice: { type: Boolean, default: true }
})

defineEmits(['click'])

/** 包场状态的中文与配色，来自共用的映射表。 */
const status = computed(() => bookingStatusOf(props.booking.status))

/** 时段：「9/30 14:00 – 18:00」（同一天时省略后半段的日期）。 */
const timeRange = computed(() => {
  const { startAt, endAt } = props.booking
  if (!startAt || !endAt) return '—'
  const sameDay = String(startAt).slice(0, 10) === String(endAt).slice(0, 10)
  return sameDay
    ? `${formatDateTime(startAt)} – ${formatTime(endAt)}`
    : `${formatDateTime(startAt)} – ${formatDateTime(endAt)}`
})

/** 是否还在等待付款 —— 只有它需要包场人去操作。 */
const needsPayment = computed(() => props.booking.status === 'PENDING_PAYMENT')
</script>

<template>
  <button class="booking-card" @click="$emit('click')">
    <div class="booking-card__head">
      <span class="booking-card__no">{{ booking.bookingNo }}</span>
      <span :class="status.cls">{{ status.label }}</span>
    </div>

    <div class="booking-card__time">📅 {{ timeRange }}</div>

    <div v-if="showPrice" class="booking-card__foot">
      <span class="booking-card__price">¥{{ formatMoney(booking.price) }}</span>
      <span v-if="needsPayment" class="booking-card__action">去付款 ›</span>
      <span v-else class="booking-card__action">查看 ›</span>
    </div>

    <p v-if="booking.remark" class="booking-card__remark">{{ booking.remark }}</p>
  </button>
</template>

<style scoped>
.booking-card {
  display: block;
  width: 100%;
  padding: var(--sp-4);
  border-radius: var(--r-card);
  background: var(--c-card);
  text-align: left;
}

.booking-card + .booking-card {
  margin-top: var(--sp-3);
}

.booking-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  margin-bottom: var(--sp-2);
}

.booking-card__no {
  font-size: 12px;
  color: var(--c-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.booking-card__time {
  font-size: 13px;
  color: var(--c-text);
}

.booking-card__foot {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-top: var(--sp-2);
}

.booking-card__price {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-primary);
}

.booking-card__action {
  font-size: 12px;
  color: var(--c-text-sub);
}

.booking-card__remark {
  margin-top: var(--sp-2);
  font-size: 11px;
  color: var(--c-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
