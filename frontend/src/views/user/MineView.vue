<script setup>
/**
 * 「我的」页面。
 *
 * <p>上半部分是用户卡片（与「在店用户」列表共用 `UserCard` 组件，
 * 只是第三行信息不同：那边显示进店时间与时长，这边显示消费与时长统计）。
 *
 * <p>⚠️ 页面在电脑上也是一条窄列（内容 480px，见 base.css 的 --page-max）——
 * 卡片、统计卡、菜单同宽。三种宽度混在一起时，卡片会像「缩在中间的一块」，
 * 与下面两块的边缘对不齐（实机截图报过）。
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
import { getBillingRules } from '@/api/billing'
import { preferenceLabels } from '@/utils/labels'
import { userState, setUser, clear, isAdmin } from '@/stores/user'
import { toastError, toastInfo } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatMoney, formatDuration } from '@/utils/format'
import UserCard from '@/components/UserCard.vue'

const router = useRouter()

const stats = ref(null)
const monthSpent = ref(null)
const activeCard = ref(null)
/**
 * 价目表（`GET /api/billing/rules`）。
 *
 * <p>只用来把达标文案里的「降到多少」写具体 —— 那两个数字是配置项，
 * 前端写死的话，调价之后这里会一直挂着旧价格，<b>且不报任何错</b>。
 */
const rules = ref(null)
const loading = ref(true)
/** 设备类型字典，用来把偏好 code 翻成中文名。 */
const typeMap = ref({})

/** 当前用户资料。优先用 store 里的，刷新接口返回后整体替换。 */
const user = computed(() => userState.user || {})

/**
 * 我的偏好标签（卡片第四行的中文胶囊）。
 *
 * <p>偏好存的是逗号分隔的 code，中文名与「认不出的 code 怎么兜底」都在
 * {@code utils/labels.js} 的 {@code preferenceLabels} 里 ——
 * 与「在店用户」名册共用同一份。两处各写一份的话，改了一处另一处不会跟着变，
 * 而那种不一致不会有任何报错，只是同一个偏好在两个页面显示成两种样子。
 */
const myTags = computed(() => preferenceLabels(user.value.preference, typeMap.value))

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
  if (!discounted) {
    return `再消费 ¥${formatMoney(remaining)} 即可享优惠（门槛 ¥${formatMoney(threshold)}）`
  }
  /*
   * 达标这一支要说清【降到多少】—— 原先只写「本月已享有优惠价」，
   * 等于告诉顾客「你便宜了」却不说是多少钱。
   *
   * ⚠️ 价格一律取自接口：写死的话调价之后这里会一直挂着旧数字，
   * 而那种不一致不会有任何报错。
   * ⚠️ 价目表没拉到时退回原来那句 —— 宁可少说，也不能说错。
   */
  if (!rules.value) return '本月已享有优惠价'
  const day = formatMoney(rules.value.discountDayPricePerHour)
  const night = formatMoney(rules.value.discountNightPricePerHour)
  // 分两行写：一行排下来在窄屏上会折在「元/小时」这种地方，很难读。
  // ⚠️ 换行靠样式里的 white-space: pre-line，见 .mine__discount
  return `本月消费已满 ¥${formatMoney(threshold)}\n价格下降至 日 ${day} 元/小时、夜 ${night} 元/小时`
})

/**
 * 菜单项。
 *
 * <p><b>管理员多一条「运营后台」入口</b>：后台不在底部 TabBar 上，
 * 而登录时的角色分流只在「没带 redirect 参数」时才生效 ——
 * 管理员从用户端这一路逛过来，没有这条入口就再也进不去后台了。
 * 判断依据是本地存的角色，真正的权限仍然由服务端把关。
 */
const menus = computed(() => {
  const list = [
    { key: 'orders', icon: '📋', label: '我的订单' },
    { key: 'productOrders', icon: '🛍', label: '我的商品订单' },
    { key: 'cards', icon: '🎫', label: '我的月卡' },
    { key: 'bookings', icon: '📅', label: '我发起的包场' },
    { key: 'joined', icon: '👥', label: '我参与的包场' },
    { key: 'profile', icon: '✏️', label: '修改个人资料' },
    { key: 'preference', icon: '🎯', label: '设置游玩偏好' },
    { key: 'pricing', icon: '💰', label: '计费规则' }
  ]
  if (isAdmin.value) {
    list.push({ key: 'admin', icon: '⚙️', label: '运营后台' })
  }
  return list
})

async function loadAll() {
  // 六路并发：资料、统计、月累计、卡包、偏好字典、价目表。
  // 任一失败不影响其余 ——「我的」页少显示一个数字，也比整页白屏强
  const [profileRes, statsRes, spentRes, cardsRes, typesRes, rulesRes] = await Promise.allSettled([
    getProfile(),
    getMyStats(),
    getMonthSpent(),
    getMyCards(),
    listEquipmentTypes(),
    getBillingRules()
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
  // 价目表拿不到只影响优惠文案的具体程度（退回「本月已享有优惠价」）
  if (rulesRes.status === 'fulfilled') rules.value = rulesRes.value.data
}

/** 菜单 key → 路由。还没做的页面不在这张表里，点了给提示。 */
const MENU_ROUTES = {
  orders: '/orders',
  productOrders: '/product-orders',
  cards: '/cards',
  bookings: '/bookings/host',
  joined: '/bookings/joined',
  profile: '/profile',
  preference: '/preference',
  pricing: '/pricing',
  admin: '/admin'
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
      size="roomy"
    >
      <template #info>
        <span>累计消费 ¥{{ totalPaidText }}</span>
        <span>累计时长 {{ totalDurationText }}</span>
      </template>
    </UserCard>

    <!--
      统计。只有两项 ——「累计消费 / 累计时长」在上面那张用户卡片里已经有了，
      统计栏再放一遍是重复。⚠️ 少一格还顺带解决了折行：三格平分 296px 时
      每格只有约 99px，最长的「67 小时 56 分钟」放不下；两格每格约 148px。
      ⚠️ 说明文字在数字【上方】（2026-10-03 调整）：先知道这是什么，再读数字。
    -->
    <div class="card mine__stats">
      <div class="mine__stat">
        <div class="mine__stat-label">本月消费</div>
        <div class="mine__stat-value">{{ monthSpentText }}</div>
      </div>
      <div class="mine__stat">
        <div class="mine__stat-label">本月时长</div>
        <div class="mine__stat-value">{{ monthDurationText }}</div>
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
/*
 * ⚠️ 这里原先有一条 .mine { max-width: 512px }（只把本页收窄到一列），
 * 已随「用户端整体按手机宽度走」一并删除（2026-10-03）——
 * 现在页面宽度由 base.css 的 --page-max 统一决定，本页不需要特殊处理。
 *
 * 当时那条规则要解决的问题仍然值得记住：本页有三种宽度的东西
 *（用户卡片、统计卡、菜单），若只把卡片单独限窄，它会像「缩在中间的一块」，
 * 与下面两块的边缘对不齐（实机截图报过）。**要么一起宽、要么一起窄。**
 */

.mine__stats {
  display: flex;
  margin-top: var(--sp-3);
}

.mine__stat {
  flex: 1;
  text-align: center;
}

.mine__stat-value {
  /* 标签在上之后，间距加在这一侧（原来在 label 的 margin-top 上） */
  margin-top: var(--sp-1);
  /*
   * 字号保持 17px：统计栏只剩两格，每格约 148px（296 ÷ 2）——
   * 最长的那种值「67 小时 56 分钟」（挂了几天没结算的单）约 127px，放得下。
   * （三格时每格只有 99px，那一版曾为此把字号缩到 14px 都不够；
   * 删掉重复的那一格之后就不必缩了。）
   */
  font-size: 17px;
  font-weight: 600;
  color: var(--c-primary);
}

.mine__stat-label {
  font-size: 12px;
  color: var(--c-text-muted);
}

.mine__discount {
  margin: var(--sp-3) 0 0;
  padding: 0 var(--sp-1);
  font-size: 12px;
  color: var(--c-text-sub);
  /*
   * 保留文案里的换行（达标那支是两行：「本月消费已满 ¥200」/「价格下降至 …」）。
   * ⚠️ 少了这一条，那个 \n 会被当成普通空白折叠掉，两行又挤回一行 ——
   * 而挤成一行后在窄屏上会折在「元/小时」这种地方，很难读。
   */
  white-space: pre-line;
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
