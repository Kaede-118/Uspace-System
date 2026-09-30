<script setup>
/**
 * 首页。
 *
 * <p>本轮把这一页做成完整的，是刻意的：它把
 * 「axios 拦截器 → 路由 → store → 组件 → 设计 token」整条链路跑一遍。
 * 首页通了，剩下的页面就只是重复这个模式。
 *
 * <p>页面结构自上而下：欢迎语 → 门店状态 → 进行中订单 → 未付款提醒 →
 * 公告 → 包场时间表 → 快捷入口。
 *
 * <p><b>公告与包场时间表是两张不同的卡片，不合并</b>：公告记「已经发生了什么」，
 * 时间表回答「接下来哪些时段进不去」。混在一条流里，用户分不清哪条是通知、
 * 哪条是安排。
 *
 * <p><b>进行中卡片与未付款提醒也是两件事，可以同时出现</b>：
 * 前者只对 {@code IN_USE} 生效、显示计时与金额；后者只说「你欠着费」，
 * 不显示计时（待支付不是进行中）。
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { getStoreStatus, getNotices, getBookingSchedule } from '@/api/store'
import { getCurrentOrder, createOrder, listMyOrders, getPasscode } from '@/api/order'
import { usePreviewPolling } from '@/composables/usePreviewPolling'
import { toastSuccess, toastError, toastInfo } from '@/composables/useToast'
import { errorMessage, isCode, ErrorCode } from '@/utils/error'
import { displayName } from '@/stores/user'
import { formatDuration, formatCountdown, formatTime, formatShortDate, formatYuan } from '@/utils/format'
import NoticeItem from '@/components/NoticeItem.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

/* ---------------- 只读数据 ---------------- */

const storeStatus = ref(null)
const notices = ref([])
const schedule = ref([])
const currentOrder = ref(null)
const unpaidCount = ref(0)
const loading = ref(true)
const opening = ref(false)

/* ---------------- 进行中订单的预览 ---------------- */

/**
 * 首页的轮询间隔比结账页长（30 秒 vs 10 秒）：首页只是「扫一眼」，
 * 而结账页的金额是用户即将确认的数字，需要更实时。
 */
const {
  preview,
  elapsedSeconds,
  countdownSeconds,
  start: startPreview,
  stop: stopPreview
} = usePreviewPolling({ interval: 30000 })

/* ---------------- 派生展示值 ---------------- */

/** 门店状态点的颜色。 */
const statusDotClass = computed(() => {
  switch (storeStatus.value?.status) {
    case 'OPEN':
      return 'dot--open'
    case 'BOOKED':
      return 'dot--booked'
    case 'CLOSED':
      return 'dot--closed'
    default:
      return ''
  }
})

const elapsedText = computed(() => formatDuration(Math.floor(elapsedSeconds.value / 60)))

/** 应付金额。⚠️ 一律用 payableAmount —— 它是唯一权威的「要付多少」。 */
const payableText = computed(() => {
  if (!preview.value) return '—'
  return formatYuan(preview.value.bill?.totalAmount ?? 0)
})

/** 是否显示倒计时：后端给了秒数才显示（已封顶、或时段内不再变化时不给）。 */
const showCountdown = computed(() => countdownSeconds.value !== null)

/** 跳档预告文案，由后端给（前端只负责拼时间）。 */
const nextChangeText = computed(() => preview.value?.nextChangeText || '')

/* ---------------- 数据加载 ---------------- */

/**
 * 并发拉取首页需要的五路数据。
 *
 * <p>用 {@code allSettled} 而不是 {@code all}：公告接口挂了不该让整个首页白屏，
 * 门店状态、进行中订单这些更要紧的东西仍然要显示出来。
 *
 * <p>⚠️ 公告、包场时间表、门店状态三个接口<b>匿名可访问</b>；
 * 进行中订单与未付款查询需要登录 —— 但本页本来就在登录守卫之内。
 */
async function loadAll() {
  const [statusRes, noticesRes, scheduleRes, currentRes, unpaidRes] = await Promise.allSettled([
    getStoreStatus(),
    getNotices(5),
    getBookingSchedule(5),
    getCurrentOrder(),
    listMyOrders({ status: 'PENDING_PAYMENT', size: 1 })
  ])

  if (statusRes.status === 'fulfilled') storeStatus.value = statusRes.value.data
  if (noticesRes.status === 'fulfilled') notices.value = noticesRes.value.data || []
  if (scheduleRes.status === 'fulfilled') schedule.value = scheduleRes.value.data || []
  if (currentRes.status === 'fulfilled') currentOrder.value = currentRes.value.data || null
  if (unpaidRes.status === 'fulfilled') unpaidCount.value = unpaidRes.value.data?.total || 0

  // 只有真有进行中的订单时才启动预览轮询 —— 无单时不轮询，别白白打接口
  if (currentOrder.value?.id) {
    await startPreview(currentOrder.value.id)
  } else {
    stopPreview()
  }
}

/* ---------------- 开门 ---------------- */

const passcodeVisible = ref(false)
const passcodeInfo = ref(null)
/** 遮罩上的文案。开门与查密码共用同一个遮罩，文案要跟着变。 */
const busyText = ref('')

/**
 * 点击「开门计时」。
 *
 * <p>⚠️ <b>点击前先查一次 {@code current}</b>。后端有防连点与欠费拦截，
 * 但直接调的话用户会先看到一个错误提示，而他的真实处境可能是
 * 「我已经开过门了」。先查一次能把一个错误提示变成一次正常的页面跳转。
 */
async function onOpenDoor() {
  opening.value = true
  busyText.value = '正在开门…'
  try {
    // 第一道：先问一次「我在不在店里」
    const currentResp = await getCurrentOrder()
    if (currentResp.data) {
      currentOrder.value = currentResp.data
      toastInfo('你已有进行中的订单')
      return
    }

    const resp = await createOrder()
    passcodeInfo.value = resp.data
    passcodeVisible.value = true
    toastSuccess(resp.data?.message || '开门成功，请在门锁上输入密码')
    await loadAll()
  } catch (err) {
    /*
     * 两个「拦下来」的码，处置完全不同：
     *   40919 人还在店里玩 → 刷新出进行中卡片，让用户点「查看密码」
     *   40931 玩完了账还欠着 → 引导去支付
     * 合并成一句「操作失败」的话，用户不知道自己该点哪个。
     */
    if (isCode(err, ErrorCode.ORDER_ALREADY_ACTIVE)) {
      toastInfo(errorMessage(err))
      await loadAll()
    } else if (isCode(err, ErrorCode.ORDER_UNPAID_EXISTS)) {
      toastError(errorMessage(err))
      await loadAll()
    } else {
      toastError(errorMessage(err, '开门失败，请稍后重试'))
    }
  } finally {
    opening.value = false
  }
}

/**
 * 查看已开订单的门锁密码。
 *
 * <p>⚠️ 这个接口在密码过期时<b>会自动续期</b>：后端调 {@code changePasscode}
 * 把有效期往后推，<b>密码数字不变</b>、锁上也不会累积密码。
 * 所以「重新看一次密码」既安全又不花额外额度（只在真的过期时才调一次云端）。
 */
async function onViewPasscode() {
  if (!currentOrder.value) return
  opening.value = true
  busyText.value = '正在获取密码…'
  try {
    const resp = await getPasscode(currentOrder.value.id)
    passcodeInfo.value = resp.data
    passcodeVisible.value = true
    // 后端在本次续期了的话，提示一句，让用户知道旧的有效期已经更新
    if (resp.data?.renewed) {
      toastInfo('密码有效期已顺延')
    }
  } catch (err) {
    toastError(errorMessage(err, '获取密码失败，请稍后重试'))
  } finally {
    opening.value = false
  }
}

const router = useRouter()

/** 快捷入口点击。 */
function onQuickAction(action) {
  if (action === 'settle') {
    if (currentOrder.value) {
      router.push(`/orders/${currentOrder.value.id}/settle`)
    } else {
      // 没有进行中的订单时点「离店结账」多半是误点，给一句说明就好，
      // 不跳页面 —— 跳到结账页也没东西可结
      toastInfo('当前没有进行中的订单')
    }
    return
  }
  if (action === 'instore') {
    router.push('/instore')
    return
  }
  if (action === 'devices') {
    router.push('/devices')
    return
  }
}

onMounted(async () => {
  try {
    await loadAll()
  } finally {
    loading.value = false
  }
})

onUnmounted(() => {
  stopPreview()
})
</script>

<template>
  <div class="page page-with-tabbar">
    <!--
      欢迎语 + 营业状态。
      营业状态原本单独占一张卡片，但它只是一行状态文字 ——
      为它撑起一整块卡片，等于把首页最值钱的那块位置花在了「此刻开着门」上。
      挪到欢迎语下面做一行小字，卡片留给真正有事要说的东西。
    -->
    <header class="home__head">
      <h1 class="home__title">
        欢迎您，<span class="text-primary">{{ displayName }}</span>
      </h1>
      <p v-if="storeStatus" class="home__status-line">
        <span class="dot" :class="statusDotClass" />
        <span>{{ storeStatus.statusText }}</span>
        <span v-if="storeStatus.statusEndAt" class="home__status-until">
          至 {{ formatTime(storeStatus.statusEndAt) }}
        </span>
      </p>
      <p class="home__sub">今天想做点什么？</p>
    </header>

    <!-- 进行中订单：压成两行 —— 第一行时长与金额，第二行跳档预告 -->
    <div v-if="currentOrder" class="card home__running" @click="onViewPasscode">
      <div class="home__running-line">
        <span class="home__running-label">⏱ 正在计费</span>
        <span class="home__amount">{{ payableText }}</span>
        <span class="home__running-time">在店 {{ elapsedText }}</span>
      </div>

      <p v-if="showCountdown || nextChangeText" class="home__running-next">
        <template v-if="showCountdown">还有 {{ formatCountdown(countdownSeconds) }} </template>
        {{ nextChangeText }}
      </p>
    </div>

    <!-- 未付款提醒 -->
    <div v-if="unpaidCount > 0" class="card home__unpaid">
      <span>💰 你有 {{ unpaidCount }} 笔未付款的订单</span>
      <span class="text-primary">去支付 ›</span>
    </div>

    <!--
      公告与包场时间表并排。
      两者仍是【两张不同的卡片】（一个是已发生的事、一个是接下来的安排），
      只是宽屏下摆在同一行 —— 变成两列是布局，合并成一张才是设计错误。
      窄屏（手机）下 auto-fit 会自动退回单列。
    -->
    <div class="home__panels">
      <!-- 公告（消息流） -->
      <div class="card">
        <div class="card-title">📢 门店公告</div>
        <template v-if="notices.length">
          <NoticeItem v-for="item in notices" :key="item.id" :notice="item" />
        </template>
        <EmptyState v-else text="暂无公告" />
      </div>

      <!--
        包场时间表（日程）：没有安排时【整张卡不出现】，而不是留一句「近期没有包场安排」。
        那张空卡片会一直占着首页一块显眼的位置，说的却只是「没事发生」——
        公告栏也同理，但公告一般总有内容（机台一动就产生一条），所以留了占位。
      -->
      <div v-if="schedule.length" class="card">
        <div class="card-title">📅 近期包场安排</div>
        <div v-for="(item, index) in schedule" :key="index" class="schedule">
          <span v-if="item.ongoing" class="schedule__badge">进行中</span>
          <span v-else class="schedule__date">{{ formatShortDate(item.startAt) }}</span>
          <span class="schedule__time">
            {{ formatTime(item.startAt) }} – {{ formatTime(item.endAt) }}
          </span>
        </div>
      </div>
    </div>

    <!-- 快捷入口 -->
    <div class="home__grid">
      <!--
        「开门计时」与「查看密码」是同一个按钮的两副面孔：有进行中订单时
        它变成后者。点击前先查一次 current 也是这个道理 ——
        让用户看到的是「你已经开过门了」，而不是一个错误提示。
      -->
      <button class="quick" @click="currentOrder ? onViewPasscode() : onOpenDoor()">
        <span class="quick__icon">🔑</span>
        <span class="quick__label">{{ currentOrder ? '查看密码' : '开门计时' }}</span>
      </button>
      <button class="quick" @click="onQuickAction('settle')">
        <span class="quick__icon">🛒</span>
        <span class="quick__label">离店结账</span>
      </button>
      <button class="quick" @click="onQuickAction('instore')">
        <span class="quick__icon">👥</span>
        <span class="quick__label">在店用户</span>
      </button>
      <button class="quick" @click="onQuickAction('devices')">
        <span class="quick__icon">📋</span>
        <span class="quick__label">设备列表</span>
      </button>
    </div>

    <LoadingMask :loading="opening" :text="busyText" />

    <!-- 密码弹层 -->
    <div v-if="passcodeVisible" class="modal" @click.self="passcodeVisible = false">
      <div class="modal__box">
        <p class="modal__title">请在门锁上输入</p>
        <div class="modal__passcode">{{ passcodeInfo?.passcode }}</div>
        <p class="text-sm text-sub">
          密码有效至 {{ formatTime(passcodeInfo?.passcodeEnd) }}
        </p>
        <p class="modal__hint">进店后如需再次查看，点首页的「查看密码」</p>
        <button class="btn btn-primary" @click="passcodeVisible = false">知道了</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.home__head {
  margin-bottom: var(--sp-4);
}

.home__title {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.35;
}

.home__sub {
  margin-top: var(--sp-1);
  font-size: 13px;
  color: var(--c-text-muted);
}

/* 营业状态：欢迎语下面的一行小字，不再是独立卡片 */
.home__status-line {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
  font-size: 13px;
  color: var(--c-text-sub);
}

.home__status-until {
  color: var(--c-text-muted);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c-text-muted);
  flex-shrink: 0;
}

.dot--open {
  background: var(--c-success);
}

.dot--booked {
  background: var(--c-warning);
}

.dot--closed {
  background: var(--c-danger);
}

/* 进行中 */
.home__running {
  cursor: pointer;
}

/* 第一行：状态 + 金额 + 在店时长，全部左对齐 */
.home__running-line {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  /* 窄屏放不下时让「在店 X 分钟」换到下一行，而不是把金额挤没 */
  flex-wrap: wrap;
}

.home__running-label {
  font-size: 13px;
  color: var(--c-text-sub);
}

.home__amount {
  font-size: 18px;
  font-weight: 600;
  color: var(--c-primary);
  /* 数字等宽：金额随时间变化时整行不会左右抖动 */
  font-variant-numeric: tabular-nums;
}

.home__running-time {
  font-size: 13px;
  color: var(--c-text-sub);
}

/* 第二行：跳档预告 */
.home__running-next {
  margin-top: var(--sp-1);
  font-size: 12px;
  color: var(--c-warning);
}

/* 未付款 */
.home__unpaid {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  cursor: pointer;
}

/* 包场时间表 */
.schedule {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-2) 0;
  font-size: 13px;
}

.schedule + .schedule {
  border-top: 1px solid var(--c-border);
}

.schedule__date {
  flex-shrink: 0;
  width: 44px;
  color: var(--c-text-sub);
}

.schedule__badge {
  flex-shrink: 0;
  padding: 1px var(--sp-2);
  border-radius: var(--r-pill);
  background: var(--c-warning);
  color: #fff;
  font-size: 11px;
}

.schedule__time {
  color: var(--c-text);
}

/* 公告与包场并排。auto-fit 在窄屏下自动退回单列，不必写媒体查询 */
.home__panels {
  display: grid;
  gap: var(--sp-3);
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  margin-top: var(--sp-3);
  /*
   * 用默认的 stretch 让两张卡片等高 —— 内容多少不一（公告常有几条、
   * 包场常为空）时，一高一矮看起来像没对齐。grid 的 gap 管间距，
   * 所以取消 .card + .card 那条通用规则里的 margin。
   */
}

.home__panels .card + .card {
  margin-top: 0;
}

/* 快捷入口 */
.home__grid {
  display: grid;
  /* 两列（手机）到四列（宽屏）之间自适应 */
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: var(--sp-3);
  margin-top: var(--sp-3);
}

.quick {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--sp-2);
  padding: var(--sp-5) var(--sp-3);
  border-radius: var(--r-card);
  background: var(--c-primary-pale);
  color: var(--c-primary);
}

.quick:disabled {
  opacity: 0.55;
}

.quick__icon {
  font-size: 22px;
}

.quick__label {
  font-size: 13px;
  font-weight: 500;
}

/* 密码弹层 */
.modal {
  position: fixed;
  inset: 0;
  z-index: 400;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-5);
  background: rgba(43, 35, 64, 0.45);
}

.modal__box {
  width: 100%;
  max-width: 320px;
  padding: var(--sp-5);
  border-radius: var(--r-card);
  background: #fff;
  text-align: center;
}

.modal__title {
  font-size: 14px;
  color: var(--c-text-sub);
}

.modal__passcode {
  margin: var(--sp-3) 0;
  font-size: 34px;
  font-weight: 600;
  letter-spacing: 6px;
  color: var(--c-primary);
  /* 数字等宽，避免不同数字宽度不一致时整串数字左右跳动 */
  font-variant-numeric: tabular-nums;
}

.modal__hint {
  margin: var(--sp-3) 0 var(--sp-4);
  font-size: 12px;
  color: var(--c-text-muted);
}
</style>
