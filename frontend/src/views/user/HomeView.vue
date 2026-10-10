<script setup>
/**
 * 首页。
 *
 * <p>本轮把这一页做成完整的，是刻意的：它把
 * 「axios 拦截器 → 路由 → store → 组件 → 设计 token」整条链路跑一遍。
 * 首页通了，剩下的页面就只是重复这个模式。
 *
 * <p>页面结构自上而下：欢迎语 → 营业状态与进行中订单（同一张卡片）→ 未付款提醒 →
 * 快捷入口 → 公告 → 包场时间表。⚠️ 快捷入口在公告【之前】：顾客进门是带着
 * 目的来的，先给「要做什么」，再给「发生了什么」（2026-10-03 调整）。
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
import { getStoreStatus, getNotices, getBookingSchedule, getFreePeriods } from '@/api/store'
import { getCurrentOrder, createOrder, listMyOrders, getPasscode } from '@/api/order'
import { listRejectedProofs } from '@/api/payment'
import { usePreviewPolling } from '@/composables/usePreviewPolling'
import { toastSuccess, toastError, toastInfo } from '@/composables/useToast'
import { errorMessage, isCode, ErrorCode } from '@/utils/error'
import { displayName } from '@/stores/user'
import { formatDuration, formatCountdown, formatTime, formatShortDate, formatYuan } from '@/utils/format'
import { periodPhaseOf } from '@/utils/labels'
import NoticeItem from '@/components/NoticeItem.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

/* ---------------- 只读数据 ---------------- */

const storeStatus = ref(null)
const notices = ref([])

/**
 * 首页公告栏显示几条。
 *
 * <p>只显示前几条（含置顶的），完整的走「查看全部 ›」——
 * 公告是只增不减的消息流，全摊在首页会把下面几张卡片全挤下去。
 */
const HOME_NOTICE_LIMIT = 4
const schedule = ref([])

/**
 * 近期免费活动（含正在进行的那一场）。没有时整张卡不出现 —— 与包场安排同理：
 * 一张只会说「没事发生」的空卡片不值得占首页一块显眼的位置。
 */
const freePeriods = ref([])
const currentOrder = ref(null)
const unpaidCount = ref(0)
/**
 * 第一笔未付款订单，用来给「去支付」当跳转目标。
 *
 * <p>与 {@link unpaidCount} 是同一次请求的两个字段（列表接口的
 * {@code size: 1} 只要一条，{@code total} 是总数）——
 * 页面只显示数量，但要能跳，所以那条记录也得留着。
 */
const unpaidOrder = ref(null)

/**
 * 被管理员驳回、还没重交的付款凭证。
 *
 * <p><b>这是「先交付后复核」那道缺口的补法</b>：订单与商品提交凭证即落账，
 * 管理员事后驳回<b>不回退订单状态</b>，所以订单页上它仍然显示「已支付」。
 * 少了这条提醒，用户会一直以为这笔账已经结了 —— 而管理员那边早就判它不成立。
 *
 * <p>用户重交之后它会自动从列表里消失（重交把状态翻回待复核），
 * 所以前端不需要做任何「已处理」的本地标记。
 */
const rejectedProofs = ref([])
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
 * 并发拉取首页需要的六路数据。
 *
 * <p>用 {@code allSettled} 而不是 {@code all}：公告接口挂了不该让整个首页白屏，
 * 门店状态、进行中订单这些更要紧的东西仍然要显示出来。
 *
 * <p>⚠️ 公告、包场时间表、免费活动、门店状态四个接口<b>匿名可访问</b>；
 * 进行中订单与未付款查询需要登录 —— 但本页本来就在登录守卫之内。
 */
async function loadAll() {
  const [statusRes, noticesRes, scheduleRes, freeRes, currentRes, unpaidRes, rejectedRes] =
    await Promise.allSettled([
      getStoreStatus(),
      getNotices({ page: 1, size: HOME_NOTICE_LIMIT }),
      getBookingSchedule(5),
      getFreePeriods(3),
      getCurrentOrder(),
      listMyOrders({ status: 'PENDING_PAYMENT', size: 1 }),
      listRejectedProofs()
    ])

  if (statusRes.status === 'fulfilled') storeStatus.value = statusRes.value.data
  if (noticesRes.status === 'fulfilled') notices.value = noticesRes.value.data?.records || []
  if (scheduleRes.status === 'fulfilled') schedule.value = scheduleRes.value.data || []
  if (freeRes.status === 'fulfilled') freePeriods.value = freeRes.value.data || []
  if (currentRes.status === 'fulfilled') currentOrder.value = currentRes.value.data || null
  if (unpaidRes.status === 'fulfilled') {
    unpaidCount.value = unpaidRes.value.data?.total || 0
    unpaidOrder.value = unpaidRes.value.data?.records?.[0] || null
  }
  if (rejectedRes.status === 'fulfilled') rejectedProofs.value = rejectedRes.value.data || []

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

/**
 * 点了「未付款提醒」的卡片：跳到那笔订单去付款。
 *
 * <p>⚠️ <b>支付入口在订单详情页</b>（{@code /orders/:id}，那里挂着
 * {@code PaymentPanel}），<b>不在结账页</b> —— 结账页是「停止计时」的流程，
 * 只对进行中的订单有意义；欠着费的订单早已结算完，去那里没有东西可结。
 *
 * <p>多笔未付款时也跳第一笔：订单列表页没有「只看未付款」的筛选，
 * 跳过去反而要用户自己在列表里翻。付完一笔回到首页，提醒里的数字会少一
 *（首页每次进入都重新拉数据，不是缓存的）。
 */
function onGoPay() {
  if (unpaidOrder.value?.id) {
    router.push(`/orders/${unpaidOrder.value.id}`)
    return
  }
  // 拿不到具体订单（理论上只可能是别处刚把这笔付掉了）——
  // 给一句提示，而不是点了没反应
  toastInfo('订单状态已变化，请稍后刷新')
}

/**
 * 点了「凭证被驳回」的卡片：跳到那一笔去重新上传。
 *
 * <p>四类收款的落点各不相同，靠后端随凭证一起给的 {@code targetType} 分流
 *（{@code targetId} 也是后端给的）—— 只给一个类型名的话，用户还得自己去
 * 列表里翻是哪一笔。
 *
 * <p>用映射表而不是 {@code if/else} 链：加一类收款时漏改一处的表现是
 * 「点进去落在别处」，而那种错误不会有任何报错。认不出的类型回落到订单列表，
 * 而不是不跳 —— 卡片是能点的，点了没反应比跳到一个次优的页面更让人困惑。
 */
function onGoResubmit(proof) {
  const landings = {
    ORDER: `/orders/${proof.targetId}`,
    PRODUCT: '/product-orders',
    BOOKING: '/bookings/host',
    MONTHLY_CARD: '/cards'
  }
  router.push(landings[proof.targetType] || '/orders')
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
    <!-- 欢迎语 -->
    <header class="home__head">
      <h1 class="home__title">
        欢迎您，<span class="text-primary">{{ displayName }}</span>
      </h1>
      <p class="home__sub">今天想做点什么？</p>
    </header>

    <!--
      营业状态 + 进行中订单，共用一张卡片（2026-10-03 定）：
      左边是「此刻开没开门」，右边是「你正在花多少钱」——
      两件都是进门先要看的事，摆在一行里。
      ⚠️ 卡片【始终渲染】：没有进行中订单时它只剩左边的营业状态。
      营业状态若跟着订单一起藏起来，顾客在没单的时候反而看不到店开没开。
    -->
    <div
      class="card home__status"
      :class="{ 'home__status--tappable': currentOrder }"
      @click="currentOrder && onViewPasscode()"
    >
      <div class="home__status-row">
        <p v-if="storeStatus" class="home__status-line">
          <span class="dot" :class="statusDotClass" />
          <span>{{ storeStatus.statusText }}</span>
          <span v-if="storeStatus.statusEndAt" class="home__status-until">
            至 {{ formatTime(storeStatus.statusEndAt) }}
          </span>
        </p>

        <!-- 进行中订单：右端两行 —— 标签与金额一行、在店时长一行 -->
        <div v-if="currentOrder" class="home__running">
          <div class="home__running-line">
            <span class="home__running-label">⏱ 正在计费</span>
            <span class="home__amount">{{ payableText }}</span>
          </div>
          <div class="home__running-time">在店 {{ elapsedText }}</div>
        </div>
      </div>

      <!-- 跳档预告：只有进行中订单才有 -->
      <p v-if="currentOrder && (showCountdown || nextChangeText)" class="home__running-next">
        <template v-if="showCountdown">还有 {{ formatCountdown(countdownSeconds) }} </template>
        {{ nextChangeText }}
      </p>
    </div>

    <!--
      凭证被驳回的提醒。**放在未付款提醒之前** —— 它比「你欠着费」更要紧：
      欠费是用户知道的待办，而「管理员不认这笔钱」是他完全不知道的一件事
      （订单与商品先交付后复核，驳回不回退订单状态，订单页上它仍是「已支付」）。

      每条凭证一张卡：通常只有一条，但一次驳回多笔时要能逐条看到各自的原因。
    -->
    <div
      v-for="proof in rejectedProofs"
      :key="proof.proofId"
      class="card home__rejected"
      @click="onGoResubmit(proof)"
    >
      <div class="home__rejected-head">
        <span class="home__rejected-title">
          ⚠️ {{ proof.targetTypeLabel }}的付款凭证没通过复核
        </span>
        <span class="home__rejected-action">重新上传 ›</span>
      </div>
      <!-- ⚠️ formatYuan 自带 ¥ 前缀，不要再加一个（加过一次，页面上显示成「¥¥3.00」 -->
      <p class="home__rejected-reason">{{ formatYuan(proof.amount) }} · {{ proof.reason }}</p>
      <!--
        「不要重复支付」这半句不能省：驳回会把订单退回待支付，用户点进去
        看到的是「待支付 ¥8.00」—— 而他明明付过。不说这一句，他很可能再扫一次码。
      -->
      <p class="home__rejected-hint">重新上传一张付款截图即可；已经付过款的话，不要重复支付。</p>
    </div>

    <!--
      未付款提醒。整张卡都可点（不只是「去支付」四个字那一段）——
      那截小字在手机上只有几十像素宽，要求顾客点准它是为难人。
    -->
    <div v-if="unpaidCount > 0" class="card home__unpaid" @click="onGoPay">
      <span>💰 你有 {{ unpaidCount }} 笔未付款的订单</span>
      <span class="text-primary">去支付 ›</span>
    </div>

    <!--
      快捷入口。**放在公告之前**（2026-10-03 调整）：这四个是「要做什么」，
      公告是「发生了什么」—— 顾客进门是带着目的来的，先给动作、再给消息。
    -->
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

    <!--
      免费活动、包场时间表、门店公告三张卡片。它们是【三张不同的卡片】
      （一个说「这时段不收钱」、一个说「这时段进不去」、一个是已发生的事），
      **合并成一张才是设计错误** —— 并列摆放只是布局。
      ⚠️ 页面宽度锚定手机竖屏之后（见 base.css 的 --page-max，内容 328px），
      它们在任何设备上都是上下三张（栅格下限 300px 判定了这一点），
      不再有「宽屏并排」那一档。

      顺序（2026-10-04 调整）：免费活动 → 包场安排 → 公告。
      前两张是「接下来会怎样」的即时信息（好事优先、限制其次），
      公告是只增不减的消息流、内容可能很长，垫底不挡路。
    -->
    <div class="home__panels">
      <!--
        免费活动（正在进行 / 即将开始）。没有活动时【整张卡不出现】，与包场安排同理。
        ⚠️ 排在包场之前：它说的是「这些时段不收钱」，对顾客是好事，也该更显眼；
        包场说的是「这些时段进不去」，是限制信息。
      -->
      <div v-if="freePeriods.length" class="card">
        <div class="card-title">🎉 免费活动</div>
        <div v-for="item in freePeriods" :key="item.id" class="freebie">
          <span v-if="periodPhaseOf(item.startAt, item.endAt).ongoing" class="freebie__badge">
            进行中
          </span>
          <span v-else class="freebie__date">{{ formatShortDate(item.startAt) }}</span>
          <span class="freebie__time">
            {{ formatTime(item.startAt) }} – {{ formatTime(item.endAt) }}
          </span>
          <span v-if="item.reason" class="freebie__name">{{ item.reason }}</span>
        </div>
      </div>

      <!--
        包场时间表（日程）：没有安排时【整张卡不出现】，而不是留一句「近期没有包场安排」。
        那张空卡片会一直占着首页一块显眼的位置，说的却只是「没事发生」——
        公告栏也同理，但公告一般总有内容（机台一动就产生一条），所以留了占位。
        ⚠️ 排在公告之前（2026-10-04 调整）：它是「未来的安排」、有时效性，
        顾客出门前最该先看到；而公告是往后翻的消息流，垫底不影响它的作用。
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

      <!-- 公告（消息流）。只显示前几条，看全部去「全部公告」页 -->
      <div class="card">
        <div class="card-title">
          📢 门店公告
          <router-link v-if="notices.length" class="home__more" to="/notices">
            查看全部 ›
          </router-link>
        </div>
        <template v-if="notices.length">
          <NoticeItem v-for="item in notices" :key="item.id" :notice="item" />
        </template>
        <EmptyState v-else text="暂无公告" />
      </div>
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
        <!--
          包场参与者才看得到的一条规则：包场里「全体各自开门计时、各自离店结账」
          是核对实际到场人员的办法。散客没有这条规矩 —— 所以按后端的 inBooking
          条件渲染，而那个标记的判据是「此刻落在包场时段内」，
          不是「订单挂没挂包场」（提前一天到店的人订单也会挂上，但他此刻在普通消费）。
        -->
        <p v-if="passcodeInfo?.inBooking" class="modal__booking">
          包场参与者全员都要开门计时，离开时各自离店结账，以核对实际到场人员
        </p>
        <p class="modal__hint">进店后如需再次查看，点首页的「查看密码」</p>
        <button class="btn btn-primary" @click="passcodeVisible = false">知道了</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 「查看全部 ›」：贴在卡片标题行的右端 */
.home__more {
  margin-left: auto;
  font-size: 12px;
  font-weight: 400;
  color: var(--c-primary);
}

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

/*
 * 营业状态 + 正在计费，同一张卡片的左右两端。
 * 没订单时右端整个不渲染，左端自然靠左。
 *
 * ⚠️ 窄屏下这一行很容易挤爆：两端都是可折行的中文，
 * 而 flex 会一路把它们压到【单字宽度】才罢休 —— 表现就是
 * 「⏱ 正在计费」竖排下来（实测踩过）。
 * 兜底靠三处配合，缺一条就会退回竖排：
 *   ① 右端 flex-shrink: 0 —— 不参与压缩
 *   ② 左端 min-width     —— 压到一定程度就触发换行，而不是无限让位
 *   ③ wrap + margin-left: auto —— 真放不下时让右端整块掉到下一行，仍靠右
 */
.home__status-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}

/* 有进行中订单时整张卡可点（去看密码） */
.home__status--tappable {
  cursor: pointer;
}

/* 营业状态：卡片左端 */
.home__status-line {
  /*
   * ⚠️ 这里【刻意不用 flex】。用了的话圆点与「至 16:00」会各成一列，
   * 「至 16:00」按自己的宽度占位，把中间的正文挤到只剩几个字宽 ——
   * 实测 390px 下正文折成 3 行、320px 下折成 6 行，一行三四个字。
   * 改用普通文本流之后，正文能占满整行，时间跟在它后面自然折行。
   */
  /*
   * basis 取 0 而不是 auto：外层 flex 判断「这一行放不放得下」用的是 flex-basis，
   * 取 auto 的话左端拿整句话的宽度去参与计算，宽屏下也会被判为放不下、
   * 右端白白掉到第二行（实测过）。取 0 之后左端宽度由剩余空间决定。
   */
  flex: 1 1 0;
  /* 压到 8 个中文字宽为止 —— 再窄就宁可让右端换行，也不把这句话竖着排 */
  min-width: 8em;
  font-size: 13px;
  color: var(--c-text-sub);
}

.home__status-until {
  color: var(--c-text-muted);
  /* 「至 16:00」是一个整体，不从中间断开 */
  white-space: nowrap;
  /*
   * 与前面正文的间距靠 margin 而不是空格：Vue 模板编译器会把标签之间
   * 【含换行】的空白整个删掉（默认 whitespace: 'condense'），
   * 靠空格的话线上就没有间距了，而在模板里看起来一切正常。
   */
  margin-left: var(--sp-1);
}

/*
 * 营业状态的点。inline-block 而不是 flex item ——
 * 它要跟正文处在同一段文本流里，正文折行时才能占满整行
 * （理由见 .home__status-line 的注释）。
 */
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c-text-muted);
  margin-right: var(--sp-2);
  vertical-align: middle;
}

/*
 * 窄屏：这两块各占一行，不并排。
 *
 * 手机上「包场提示」和「正在计费」各自都需要接近整行的宽度 ——
 * 硬塞在一行里，左边那句会折成三五个字一行（实测 320px 下折了 6 行）。
 * 并排只在宽屏（平板、桌面）保留，那里确实放得下。
 * 两块之间的间距由 .home__status-row 的 row-gap 给（flex 换行后 gap 自动变行距）。
 */
@media (max-width: 480px) {
  .home__status-line,
  .home__running {
    flex: 1 1 100%;
  }
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

/* 正在计费：卡片右端，两行（标签 + 金额 / 在店时长） */
.home__running {
  text-align: right;
  /* 不参与压缩 —— 少了这条，窄屏下「⏱ 正在计费」会被挤成一字一行 */
  flex-shrink: 0;
  /* 只有右端渲染时（没营业状态）也靠右，不被 space-between 甩到左边 */
  margin-left: auto;
}

/* 第一行：标签 + 金额，靠右排 */
.home__running-line {
  display: flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: var(--sp-2);
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

/* 第二行：在店时长，与上面那行的金额同处右端 */
.home__running-time {
  margin-top: 2px;
  font-size: 13px;
  color: var(--c-text-sub);
}

/* 第二行：跳档预告 */
.home__running-next {
  margin-top: var(--sp-1);
  font-size: 12px;
  color: var(--c-warning);
}

/* 凭证被驳回 —— 比「未付款」更该被看见：前者是用户不知道的事 */
.home__rejected {
  border-left: 3px solid var(--c-danger);
  cursor: pointer;
}

.home__rejected-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  font-size: 13px;
}

.home__rejected-title {
  font-weight: 600;
}

.home__rejected-action {
  flex: none;
  color: var(--c-danger);
}

.home__rejected-reason {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--c-text-muted);
}

.home__rejected-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--c-text-sub);
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

/*
 * 免费活动。与包场时间表【同形】（都是「一行时段 + 一个可选徽章」），
 * 但徽章配色刻意相反：包场的「进行中」是橙色的警示（此刻进不去），
 * 这里用主色 —— 它是好消息（此刻进来不要钱）。
 * 两处各留一份样式而不是抽公共类：类名要表达的是「哪张卡的行」，
 * 硬合成一个名字反而看不出区别（见模板里那段「为什么排在包场之前」）。
 */
.freebie {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-2) 0;
  font-size: 13px;
}

.freebie + .freebie {
  border-top: 1px solid var(--c-border);
}

.freebie__date {
  flex-shrink: 0;
  width: 44px;
  color: var(--c-text-sub);
}

.freebie__badge {
  flex-shrink: 0;
  padding: 1px var(--sp-2);
  border-radius: var(--r-pill);
  background: var(--c-primary);
  color: #fff;
  font-size: 11px;
}

.freebie__time {
  color: var(--c-text);
}

/* 活动名称：占掉剩下的位置，长了就省略 —— 但绝不换行把卡片撑高 */
.freebie__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 12px;
  color: var(--c-text-sub);
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * 公告与包场。minmax 的 300px 下限表达的是「放得下才并排」这条规则 ——
 * 页面锚定手机竖屏（328px 内容宽）之后它恒为单列，但保留 auto-fit 比写死单列
 * 更贴近规则本身（见模板里那段注释）。
 */
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
  /*
   * ⚠️ 写死两列（2026-10-03）：页面宽度已锚定手机竖屏（见 base.css 的 --page-max），
   * 用 auto-fit 的话宽度一变就会漂成三列四列，与手机上看到的对不上。
   */
  grid-template-columns: repeat(2, 1fr);
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
/*
 * 包场参与者的额外提示。用主色（紫）而不是 hint 那种灰 ——
 * 它说的是一条「请你配合」的规则，不是说明文字，得让人一眼看到。
 */
.modal__booking {
  margin-top: var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--r-btn);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-size: 12px;
  line-height: 1.6;
  text-align: left;
}
</style>
