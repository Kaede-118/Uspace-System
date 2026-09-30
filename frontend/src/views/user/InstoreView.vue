<script setup>
/**
 * 在店用户名册。
 *
 * <p>返回门店当前全部在店顾客，按进店时刻升序，<b>不含任何金额</b> ——
 * 「此刻店里有谁」是顾客之间才看得见的，而谁花了多少钱不是。
 *
 * <p>⚠️ 偏好只有 code（如 {@code PAIPAI}），<b>没有中文名</b> ——
 * 后端刻意不给：偏好中文名躺在 device 包，为翻译一个 code 新开一条
 * {@code order → device} 依赖边不划算。中文名由本页调 {@code /api/devices/types}
 * 取回来自己映射。
 */
import { ref, computed, onMounted } from 'vue'
import { getInstoreUsers } from '@/api/store'
import { listEquipmentTypes } from '@/api/device'
import { toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatDuration, formatTime } from '@/utils/format'
import NavBar from '@/components/NavBar.vue'
import UserCard from '@/components/UserCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

const users = ref([])
const typeMap = ref({})
const loading = ref(true)

/**
 * 把逗号分隔的偏好 code 翻成中文名数组（每个偏好占一个标签）。
 *
 * @param {string} preference 形如 "PAIPAI,TAISHOU"
 * @returns {string[]} 形如 ["拍拍机", "抬手乐"]；没设偏好时返回空数组
 */
function preferenceTags(preference) {
  if (!preference) return []
  return preference
    .split(',')
    .map((code) => code.trim())
    .filter(Boolean)
    .map((code) => typeMap.value[code] || code)
}

/** 总人数，用于标题上的一行小字。 */
const countText = computed(() =>
  users.value.length ? `共 ${users.value.length} 人` : ''
)

async function load() {
  loading.value = true
  try {
    // 名册与类型字典并发拉；类型字典拿不到只影响偏好显示，不该让整页失败
    const [usersRes, typesRes] = await Promise.allSettled([
      getInstoreUsers(),
      listEquipmentTypes()
    ])
    if (usersRes.status === 'fulfilled') users.value = usersRes.value.data || []
    if (typesRes.status === 'fulfilled') {
      const map = {}
      for (const t of typesRes.value.data || []) {
        map[t.code] = t.name
      }
      typeMap.value = map
    }
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <NavBar title="在店用户" />

    <p v-if="countText" class="instore__count">{{ countText }}</p>

    <LoadingMask :loading="loading" />

    <div v-if="users.length" class="instore__list">
      <!--
        月卡与偏好作为同一行标签（[全天月卡][拍拍机][抬手乐]），
        右侧只留进店时刻与在店时长 —— 偏好是「倾向」，与「他什么时候来的」
        不是一类信息，跟月卡放一起读起来更顺。
      -->
      <UserCard
        v-for="u in users"
        :key="u.userId"
        :user="u"
        :card-type="u.cardType"
        :card-type-label="u.cardTypeLabel"
        :tags="preferenceTags(u.preference)"
      >
        <template #info>
          <div>{{ formatTime(u.startTime) }} 进店</div>
          <div>在店 {{ formatDuration(u.stayMinutes) }}</div>
        </template>
      </UserCard>
    </div>

    <EmptyState
      v-else-if="!loading"
      icon="👥"
      text="店里目前没有人"
      hint="有人开门进店后会出现在这里"
    />
  </div>
</template>

<style scoped>
.instore__count {
  margin: var(--sp-4) 0 var(--sp-3);
  font-size: 12px;
  color: var(--c-text-muted);
}

.instore__list {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding-bottom: var(--sp-4);
}
</style>
