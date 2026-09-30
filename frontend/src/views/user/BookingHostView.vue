<script setup>
/**
 * 我发起的包场。
 *
 * <p>⚠️ 这个列表按 {@code biz_booking.host_user_id} 查（<b>含待付款的场次</b>），
 * 而不是按参与者表查 —— 参与者表里的 {@code HOST} 行是<b>付款成功那一刻</b>
 * 才写入的，改读它就会漏掉所有待付款的场次，包场人也就点不到付款入口。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listHostBookings } from '@/api/booking'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import NavBar from '@/components/NavBar.vue'
import BookingCard from '@/components/BookingCard.vue'
import BookingDetailSheet from '@/components/BookingDetailSheet.vue'
import PaySheet from '@/components/PaySheet.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

const router = useRouter()
const PAGE_SIZE = 10

const bookings = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)

const detailVisible = ref(false)
const selected = ref(null)

const payVisible = ref(false)
const payingBooking = ref(null)

const hasMore = computed(() => bookings.value.length < total.value)

async function load(reset = false) {
  if (loading.value) return
  loading.value = true
  try {
    if (reset) {
      page.value = 1
      bookings.value = []
    }
    const resp = await listHostBookings({ page: page.value, size: PAGE_SIZE })
    const data = resp.data
    total.value = data.total || 0
    bookings.value = reset ? data.records || [] : [...bookings.value, ...(data.records || [])]
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    loading.value = false
  }
}

function onOpen(booking) {
  selected.value = booking
  detailVisible.value = true
}

/** 从详情弹层里点「去付款」。 */
function onPay(booking) {
  detailVisible.value = false
  payingBooking.value = booking
  payVisible.value = true
}

async function onPaid() {
  toastSuccess('付款成功，可以邀请朋友了')
  payVisible.value = false
  await load(true)
}

function onLoadMore() {
  page.value += 1
  load(false)
}

onMounted(() => load(true))
</script>

<template>
  <div class="page">
    <NavBar title="我发起的包场" />

    <LoadingMask :loading="loading && !bookings.length" />

    <div v-if="bookings.length" class="list">
      <BookingCard
        v-for="b in bookings"
        :key="b.id"
        :booking="b"
        @click="onOpen(b)"
      />

      <button v-if="hasMore" class="btn btn-ghost load-more" :disabled="loading" @click="onLoadMore">
        {{ loading ? '加载中…' : '加载更多' }}
      </button>
      <p v-else class="load-more__end">没有更多了</p>
    </div>

    <EmptyState
      v-else-if="!loading"
      icon="📅"
      text="还没有发起过包场"
      hint="包场由管理员排期，排好后会出现在这里"
    />

    <button class="btn btn-ghost back-btn" @click="router.push('/mine')">返回「我的」</button>

    <BookingDetailSheet
      v-model:visible="detailVisible"
      :booking="selected"
      is-host
      @pay="onPay"
    />

    <PaySheet
      v-model:visible="payVisible"
      title="包场费用"
      :order-no="payingBooking?.bookingNo"
      :amount="payingBooking?.price"
      target-type="BOOKING"
      :target-id="payingBooking?.id"
      @paid="onPaid"
    />
  </div>
</template>

<style scoped>
.list {
  padding-bottom: var(--sp-4);
}

.load-more {
  margin-top: var(--sp-4);
}

.load-more__end {
  margin-top: var(--sp-4);
  text-align: center;
  font-size: 12px;
  color: var(--c-text-muted);
}

.back-btn {
  margin-top: var(--sp-2);
}
</style>
