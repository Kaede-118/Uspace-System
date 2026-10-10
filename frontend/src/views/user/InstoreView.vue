<script setup>
/**
 * 在店用户名册。
 *
 * <p>返回门店当前全部在店顾客，按进店时刻升序，<b>不含任何金额</b> ——
 * 「此刻店里有谁」是顾客之间才看得见的，而谁花了多少钱不是。
 *
 * <p><b>卡片是半宽、一行两张（2026-10-03 改）</b>：一屏（18:9 全面屏）
 * 能看到 6 个人 —— 这是本页最主要的用途（扫一眼店里现在有谁），
 * 所以卡片从整行改成了栅格单元。栅格是<b>写死两列</b>：
 * 页面宽度已锚定手机竖屏（见 base.css 的 --page-max，内容 328px），
 * 每张卡片因此恒为 160px —— 与手机上严格一致，不随设备漂移。
 *
 * <p>⚠️ 偏好只有 code（如 {@code MAIMAI}），<b>没有中文名</b> ——
 * 后端刻意不给：偏好中文名躺在 device 包，为翻译一个 code 新开一条
 * {@code order → device} 依赖边不划算。中文名由本页调 {@code /api/devices/types}
 * 取回来，卡片第四行显示什么文案由 {@code utils/labels.js} 的
 * {@code preferenceLabels} 统一决定（含认不出 code 时的兜底）。
 */
import { ref, computed, onMounted } from 'vue'
import { getInstoreUsers } from '@/api/store'
import { listEquipmentTypes } from '@/api/device'
import { preferenceLabels } from '@/utils/labels'
import { toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatArrivalTime, formatDuration } from '@/utils/format'
import NavBar from '@/components/NavBar.vue'
import UserCard from '@/components/UserCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

const users = ref([])
const typeMap = ref({})
const loading = ref(true)

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
        卡片六行：banner / 月卡 / 头像·STAFF·昵称 / 偏好 / 到店 / 在店。
        到店时刻用 formatArrivalTime：跨天时自动带上「昨天 / 前天 / 几月几日」，
        免得昨晚进店的人在名册上看起来像今天凌晨来的。
      -->
      <UserCard
        v-for="u in users"
        :key="u.userId"
        :user="u"
        :card-type="u.cardType"
        :card-type-label="u.cardTypeLabel"
        :tags="preferenceLabels(u.preference, typeMap)"
      >
        <template #info>
          <span>{{ formatArrivalTime(u.startTime) }} 到店</span>
          <span>在店 {{ formatDuration(u.stayMinutes) }}</span>
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

/*
 * 卡片栅格。
 *
 * ⚠️ 写死两列（2026-10-03）：页面宽度已锚定手机竖屏（见 base.css 的 --page-max，
 * 内容 328px），每张卡片因此是 (328 − 8) ÷ 2 = **160px** —— 与手机上严格一致。
 * 用 auto-fill 的话，页面宽度一变它就会漂成三列，卡片跟着变窄、
 * 而卡片里的字号并不会跟着调（那就又回到「两套规则」了）。
 *
 * 更窄的 320px 老屏上卡片约 140px，仍然放得下 —— 那是可接受的边界，
 * 不是目标机型。
 */
.instore__list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  /* 卡片本身不高，间距比通用档收一级，一整屏排得更紧凑 */
  gap: var(--sp-2);
  padding-bottom: var(--sp-4);
}
</style>
