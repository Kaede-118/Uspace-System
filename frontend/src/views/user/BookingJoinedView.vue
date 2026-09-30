<script setup>
/**
 * 我参与的包场（别人发起、邀请了我）。
 *
 * <p>按参与者表的 {@code role='PARTICIPANT'} 查 —— 与「我发起的」是两个数据源，
 * 所以自己发起的场次不会在两个列表里各出现一次。
 *
 * <p>只有被邀请者点开邀请链接并完成加入后，场次才会出现在这里。
 */
import { ref, computed, onMounted } from 'vue'
import { listJoinedBookings } from '@/api/booking'
import { toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import NavBar from '@/components/NavBar.vue'
import BookingCard from '@/components/BookingCard.vue'
import BookingDetailSheet from '@/components/BookingDetailSheet.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

const PAGE_SIZE = 10

const bookings = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)

const detailVisible = ref(false)
const selected = ref(null)

const hasMore = computed(() => bookings.value.length < total.value)

async function load(reset = false) {
  if (loading.value) return
  loading.value = true
  try {
    if (reset) {
      page.value = 1
      bookings.value = []
    }
    const resp = await listJoinedBookings({ page: page.value, size: PAGE_SIZE })
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

function onLoadMore() {
  page.value += 1
  load(false)
}

onMounted(() => load(true))
</script>

<template>
  <div class="page">
    <NavBar title="我参与的包场" />

    <LoadingMask :loading="loading && !bookings.length" />

    <div v-if="bookings.length" class="list">
      <!-- 被邀请者不关心价钱（那是包场人付的），所以不显示价格 -->
      <BookingCard
        v-for="b in bookings"
        :key="b.id"
        :booking="b"
        :show-price="false"
        @click="onOpen(b)"
      />

      <button v-if="hasMore" class="btn btn-ghost load-more" :disabled="loading" @click="onLoadMore">
        {{ loading ? '加载中…' : '加载更多' }}
      </button>
      <p v-else class="load-more__end">没有更多了</p>
    </div>

    <EmptyState
      v-else-if="!loading"
      icon="👥"
      text="还没有参与过包场"
      hint="朋友把邀请链接发给你，打开后就会出现在这里"
    />

    <BookingDetailSheet v-model:visible="detailVisible" :booking="selected" />
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
</style>
