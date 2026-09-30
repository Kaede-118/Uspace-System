<script setup>
/**
 * 全部公告。
 *
 * <p>首页公告栏只放最近 4 条（含置顶），看不完的到这里接着往下翻 ——
 * 公告是只增不减的消息流（机台每变一次状况就多一条），
 * 只给最近几条的话，更早的内容就永远看不到了。
 *
 * <p>⚠️ <b>顺序由后端定</b>（{@code ORDER BY pinned DESC, id DESC}）：
 * 置顶的在最前，其余按时间倒序。前端不要再排一遍 —— 那会把这个次序打乱，
 * 而且「哪条该在前面」的规则就变成了两份。
 */
import { ref, onMounted } from 'vue'
import { getNotices } from '@/api/store'
import { toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import NavBar from '@/components/NavBar.vue'
import NoticeItem from '@/components/NoticeItem.vue'
import AdminPager from '@/components/AdminPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

/** 每页条数。与后端 {@code MAX_USER_PAGE_SIZE}（20）保持一致，别超 */
const PAGE_SIZE = 10

const page = ref(1)
const total = ref(0)
const notices = ref([])
const loading = ref(true)

/**
 * 拉取当前页。
 *
 * <p>页码越界（理论上不会，但翻页时后端数据可能变少）时夹回最后一页重拉 ——
 * 停在不存在的页码上，列表空白且不报错，与「本来就没有数据」长得一样。
 */
async function load() {
  loading.value = true
  try {
    const resp = await getNotices({ page: page.value, size: PAGE_SIZE })
    const count = resp.data?.total || 0
    const maxPage = Math.max(1, Math.ceil(count / PAGE_SIZE))

    if (page.value > maxPage) {
      page.value = maxPage
      return load()
    }

    notices.value = resp.data?.records || []
    total.value = count
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    loading.value = false
  }
}

/** 翻页。 */
function onPageChange(target) {
  page.value = target
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <NavBar title="全部公告" />

    <LoadingMask :loading="loading" />

    <div v-if="notices.length || loading" class="card">
      <NoticeItem v-for="item in notices" :key="item.id" :notice="item" />

      <!-- 一页装得下就不显示分页条了（AdminPager 自己在 total 为 0 时也不显示） -->
      <AdminPager :page="page" :size="PAGE_SIZE" :total="total" @update:page="onPageChange" />
    </div>

    <EmptyState
      v-else
      icon="📢"
      text="还没有公告"
      hint="机台状况变化、管理员发布通知，都会出现在这里"
    />
  </div>
</template>

<style scoped>
.card {
  margin-top: var(--sp-4);
  padding: var(--sp-3) var(--sp-4);
}
</style>
