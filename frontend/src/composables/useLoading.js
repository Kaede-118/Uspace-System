/**
 * 全局加载遮罩的状态。
 *
 * <p>三个调用方：拦截器（`api/http.js`）在每个请求前后调
 * {@link startLoading} / {@link finishLoading}；路由（`router/index.js`）
 * 在每次跳转前后调 {@link startNavigation} / {@link endNavigation}；
 * `App.vue` 把 {@link loadingVisible} 接到 {@code LoadingMask} 上。
 *
 * <p><b>为什么需要「跳转中」这个来源</b>：目标页面是懒加载的，
 * 点下按钮之后要先【下载它的 chunk】才轮得到发请求 —— 经隧道那一段一秒多，
 * 光靠「请求计数」的话，用户点完只有「什么都没发生」，然后才突然转圈。
 * 那正是「点了按钮感觉卡一下」的来源。
 *
 * <p><b>为什么用计数而不是布尔值</b>：一次点击常并发好几个请求
 * （结算 = 提交结算 + 刷新账单），布尔值会被先回来的那个提前关掉，
 * 剩下的请求就没人管了。
 *
 * <p><b>刻意不做延迟显示</b>：曾经取过 250ms（想避开快请求的闪烁），
 * 但本系统经隧道时每个请求都在 1 秒上下，那个延迟只是让遮罩迟到 ——
 * 点下按钮到框弹出来之间的空当，用户感觉就是「卡了一下」。
 * 而快请求本来也不会闪：亮与灭落在同一帧之内，Vue 的批处理让它一次都不渲染。
 *
 * <p>状态放在模块级（与 {@code useToast} 同一个理由）：遮罩是<b>全局唯一</b>的
 * 一层，任何页面的请求都该点亮同一个遮罩。
 */
import { computed, readonly, ref, watch } from 'vue'

/** 超过这个秒数后，文案补上「已等待 N 秒」。 */
const HINT_AFTER_SECONDS = 2

/** 在途请求数。 */
const pending = ref(0)

/** 是否正在路由跳转。 */
const navigating = ref(false)

/** 跳转的兜底定时器句柄。 */
let navTimer = null

/** 遮罩已显示的秒数。 */
const waitedSeconds = ref(0)

/** 秒表定时器句柄。 */
let tickTimer = null

/**
 * 遮罩是否可见：有在途请求，**或**正在跳转。
 *
 * <p>两个来源是并列关系（或），不是相加 —— 它们覆盖的是同一段等待的
 * 不同阶段：跳转时还没有请求，请求发出后跳转已经结束。
 */
const visible = computed(() => pending.value > 0 || navigating.value)

/**
 * 秒表：遮罩一亮就从头走，收起即归零。
 *
 * <p>「已等待 N 秒」是在慢网络下唯一能区分「在等」与「卡死」的东西 ——
 * 一个不断增长的数字比一句静止的「加载中」让人踏实得多。
 */
watch(visible, (on) => {
  if (tickTimer !== null) {
    clearInterval(tickTimer)
    tickTimer = null
  }
  waitedSeconds.value = 0
  if (!on) return
  tickTimer = setInterval(() => {
    waitedSeconds.value += 1
  }, 1000)
})

/**
 * 一个请求开始了：计数 +1。
 *
 * <p>⚠️ {@code config.silent = true} 的请求不会走到这里 —— 那类请求在
 * 拦截器里就被跳过了（轮询弹遮罩会把用户正在看的页面反复盖住）。
 */
export function startLoading() {
  pending.value += 1
}

/**
 * 一个请求结束了：计数 -1。
 *
 * <p>⚠️ <b>成功与失败都必须调</b>。只在成功分支调的话，一次失败的请求
 * 就会让遮罩永远盖在屏幕上 —— 而失败恰恰是最常见的路径（欠费、重复下单）。
 */
export function finishLoading() {
  pending.value = Math.max(0, pending.value - 1)
}

/**
 * 路由跳转开始：立刻亮起遮罩。
 *
 * <p>⭐ <b>这是「点击按钮当下就有反馈」的关键。</b>
 *
 * <p>兜底 10 秒：导航若被守卫拦下（未登录被重定向、非管理员回首页，
 * 那几种情况 {@code afterEach} 不会触发），别让遮罩一直挂在屏幕上。
 */
export function startNavigation() {
  navigating.value = true
  if (navTimer !== null) clearTimeout(navTimer)
  navTimer = setTimeout(() => {
    navTimer = null
    navigating.value = false
  }, 10000)
}

/**
 * 路由跳转结束：把控制权交还给「在途请求」。
 *
 * <p>⚠️ 调用方要等一拍（{@code nextTick}）再调 —— 目标页面的
 * {@code onMounted} 与它的首个请求都落在那之后，那时请求计数已经接上，
 * 遮罩不会「灭一下又亮起来」。
 */
export function endNavigation() {
  if (navTimer !== null) {
    clearTimeout(navTimer)
    navTimer = null
  }
  navigating.value = false
}

/**
 * 静默模式：为 true 时**新发起**的请求不弹遮罩。
 *
 * <p>供 {@code useKeepAliveRefresh} 用 —— keep-alive 页面返回时的后台刷新
 * 不该盖住用户已经在看的缓存内容：那会把「秒开」变成「又转一次圈」，
 * 白费了 keep-alive。
 *
 * <p>⚠️ 只在**发起阶段**生效。一个请求到底计没计过数，由 `http.js` 在发起时
 * 记在它自己的 config 上，响应回来据此配对 —— 光靠这个开关判断的话，
 * 发起时静默、回来时已关闭的那种请求会多减一次，把别人的遮罩提前关掉。
 */
let silentMode = false

/**
 * 开 / 关静默模式。
 *
 * @param {boolean} on 是否静默
 */
export function setSilentMode(on) {
  silentMode = !!on
}

/**
 * 当前是否处于静默模式。
 *
 * @returns {boolean} 静默中返回 true
 */
export function isSilentMode() {
  return silentMode
}

/**
 * 遮罩上显示的文案。
 *
 * <p>刚发出去时说「加载中…」；超过几秒还没回来就报出已等待时长。
 */
export const loadingText = computed(() => {
  if (waitedSeconds.value >= HINT_AFTER_SECONDS) {
    return `加载中… 已等待 ${waitedSeconds.value} 秒`
  }
  return '加载中…'
})

/**
 * 组合式用法。
 *
 * @returns {object} 遮罩状态，供 {@code App.vue} 绑定到 {@code LoadingMask}
 */
export function useLoading() {
  return {
    loadingVisible: readonly(visible),
    loadingText
  }
}
