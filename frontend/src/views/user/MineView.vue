<script setup>
/**
 * 「我的」页面。
 *
 * <p>上半部分是用户卡片（与「在店用户」列表共用 `UserCard` 组件，
 * 只是右侧信息不同：那边显示进店时间与时长，这边显示消费与时长统计）。
 *
 * <p>⚠️ <b>三个「累计」是三个不同的口径，标签绝不能混用</b>：
 * <ul>
 *   <li><b>累计实付消费</b>（{@code totalPaid}）—— 终生累计，<b>含</b>月卡卡费</li>
 *   <li><b>当月累计消费</b>（{@code monthSpent}）—— 由订单聚合得出，<b>不含</b>卡费。
 *       它是月度优惠的门槛判定依据</li>
 *   <li><b>累计时长</b>（{@code totalMinutes}）—— 在店时长，含包场时段与宽限那 5 分钟，
 *       与「计费时长」不是一回事</li>
 * </ul>
 */
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { getProfile } from '@/api/user'
import { logout } from '@/api/auth'
import { getMyStats, getMonthSpent } from '@/api/order'
import { getMyCards } from '@/api/card'
import { listEquipmentTypes } from '@/api/device'
import { userState, setUser, clear } from '@/stores/user'
import { toastError, toastInfo } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatMoney, formatDuration } from '@/utils/format'
import UserCard from '@/components/UserCard.vue'

const router = useRouter()

const stats = ref(null)
const monthSpent = ref(null)
const activeCard = ref(null)
const loading = ref(true)
/** 设备类型字典，用来把偏好 code 翻成中文名。 */
const typeMap = ref({})

/** 当前用户资料。优先用 store 里的，刷新接口返回后整体替换。 */
const user = computed(() => userState.user || {})

/**
 * 我的偏好标签（与月卡并排显示）。
 *
 * <p>偏好存的是逗号分隔的 code，中文名要用设备类型字典翻 ——
 * 后端给在店名册的响应里刻意不带中文名（那会新开一条 order → device 依赖边），
 * 所以两边都靠这张字典。
 */
const myTags = computed(() => {
  const pref = user.value.preference
  if (!pref) return []
  return pref
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((code) => typeMap.value[code] || code)
})

const totalPaidText = computed(() => formatMoney(user.value.totalPaid ?? 0))
const totalDurationText = computed(() =>
  stats.value ? formatDuration(stats.value.totalMinutes) : '—'
)
const monthDurationText = computed(() =>
  stats.value ? formatDuration(stats.value.monthMinutes) : '—'
)
const monthSpentText = computed(() => formatMoney(monthSpent.value?.monthSpent ?? 0))

/**
 * 优惠进度的文案。
 *
 * <p>两个都不能想当然：
 * <ul>
 *   <li>字段名是 <b>{@code discounted}</b>（本月是否已享有优惠价），
 *       不是 {@code reached} —— 实测核对时才发现的，猜错不会有任何报错，
 *       只会让这一行永远显示「再消费 X 即可享优惠」</li>
 *   <li>门槛值一律用后端返回的 {@code threshold}，不要在前端写死 200 ——
 *       它是配置项（{@code uspace.billing.*}），改了配置前端写死就会显示错误的目标</li>
 * </ul>
 */
const discountText = computed(() => {
  if (!monthSpent.value) return ''
  const { discounted, remaining, threshold } = monthSpent.value
  return discounted
    ? '本月已享有优惠价'
    : `再消费 ¥${formatMoney(remaining)} 即可享优惠（门槛 ¥${formatMoney(threshold)}）`
})

/** 菜单项。 */
const menus = [
  { key: 'orders', icon: '📋', label: '我的订单' },
  { key: 'productOrders', icon: '🛍', label: '我的商品订单' },
  { key: 'cards', icon: '🎫', label: '我的月卡' },
  { key: 'bookings', icon: '📅', label: '我发起的包场' },
  { key: 'joined', icon: '👥', label: '我参与的包场' },
  { key: 'profile', icon: '✏️', label: '修改个人资料' },
  { key: 'preference', icon: '🎯', label: '设置游玩偏好' }
]

async function loadAll() {
  // 资料、统计、月累计、卡包四路并发。任一失败不影响其余 ——
  // 「我的」页少显示一个数字，也比整页白屏强
  const [profileRes, statsRes, spentRes, cardsRes, typesRes] = await Promise.allSettled([
    getProfile(),
    getMyStats(),
    getMonthSpent(),
    getMyCards(),
    listEquipmentTypes()
  ])

  if (profileRes.status === 'fulfilled') {
    setUser(profileRes.value.data)
  }
  if (statsRes.status === 'fulfilled') stats.value = statsRes.value.data
  if (spentRes.status === 'fulfilled') monthSpent.value = spentRes.value.data
  if (cardsRes.status === 'fulfilled') activeCard.value = cardsRes.value.data?.active || null
  if (typesRes.status === 'fulfilled') {
    // 字典拿不到只影响偏好标签显示成 code，不该让整页失败
    const map = {}
    for (const t of typesRes.value.data || []) {
      map[t.code] = t.name
    }
    typeMap.value = map
  }
}

/** 菜单 key → 路由。还没做的页面不在这张表里，点了给提示。 */
const MENU_ROUTES = {
  orders: '/orders',
  productOrders: '/product-orders',
  cards: '/cards',
  bookings: '/bookings/host',
  joined: '/bookings/joined',
  profile: '/profile',
  preference: '/preference'
}

/** 菜单跳转。已实现的直接跳，未实现的给一句提示（别静默无反应）。 */
function onMenu(key) {
  const path = MENU_ROUTES[key]
  if (path) {
    router.push(path)
    return
  }
  toastInfo('该页面正在开发中（下一步实现）')
}

/**
 * 退出登录。
 *
 * <p>后端的 logout 接口<b>什么也不做</b>（只记一条审计日志）——
 * JWT 无状态，没有会话可销毁。真正的登出是删掉本地的凭证。
 * 所以这里即使接口调用失败，也必须把本地清干净并跳走。
 */
async function onLogout() {
  try {
    await logout()
  } catch (err) {
    // 凭证已失效时接口会返回 401，那也意味着「已经登出了」，忽略即可
  }
  clear()
  router.replace('/login')
}

onMounted(async () => {
  try {
    await loadAll()
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="page page-with-tabbar">
    <UserCard
      :user="user"
      :card-type="activeCard?.cardType"
      :card-type-label="activeCard?.cardTypeLabel"
      :tags="myTags"
    >
      <template #info>
        <div>累计消费 ¥{{ totalPaidText }}</div>
        <div>累计时长 {{ totalDurationText }}</div>
      </template>
    </UserCard>

    <!-- 统计 -->
    <div class="card mine__stats">
      <div class="mine__stat">
        <div class="mine__stat-value">{{ monthSpentText }}</div>
        <div class="mine__stat-label">本月消费</div>
      </div>
      <div class="mine__stat">
        <div class="mine__stat-value">{{ monthDurationText }}</div>
        <div class="mine__stat-label">本月时长</div>
      </div>
      <div class="mine__stat">
        <div class="mine__stat-value">{{ totalDurationText }}</div>
        <div class="mine__stat-label">累计时长</div>
      </div>
    </div>

    <p v-if="discountText" class="mine__discount">{{ discountText }}</p>

    <!-- 菜单 -->
    <div class="card mine__menu">
      <button v-for="item in menus" :key="item.key" class="mine__item" @click="onMenu(item.key)">
        <span class="mine__item-icon">{{ item.icon }}</span>
        <span class="mine__item-label">{{ item.label }}</span>
        <span class="mine__item-arrow">›</span>
      </button>
    </div>

    <button class="btn btn-ghost mine__logout" @click="onLogout">退出登录</button>
  </div>
</template>

<style scoped>
.mine__stats {
  display: flex;
  margin-top: var(--sp-3);
}

.mine__stat {
  flex: 1;
  text-align: center;
}

.mine__stat-value {
  font-size: 17px;
  font-weight: 600;
  color: var(--c-primary);
}

.mine__stat-label {
  margin-top: var(--sp-1);
  font-size: 12px;
  color: var(--c-text-muted);
}

.mine__discount {
  margin: var(--sp-3) 0 0;
  padding: 0 var(--sp-1);
  font-size: 12px;
  color: var(--c-text-sub);
}

.mine__menu {
  margin-top: var(--sp-3);
  padding: var(--sp-1) var(--sp-4);
}

.mine__item {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  width: 100%;
  padding: var(--sp-3) 0;
  text-align: left;
}

.mine__item + .mine__item {
  border-top: 1px solid var(--c-border);
}

.mine__item-icon {
  font-size: 15px;
}

.mine__item-label {
  flex: 1;
  font-size: 14px;
  color: var(--c-text);
}

.mine__item-arrow {
  color: var(--c-text-muted);
  font-size: 18px;
  line-height: 1;
}

.mine__logout {
  margin-top: var(--sp-5);
}
</style>
