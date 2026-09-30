/**
 * 结账预览轮询 + 本地秒表。
 *
 * <p>首页的「进行中」卡片与结账页共用这一个 —— 两处显示的是<b>同一份</b>
 * {@code calculateBill} 的结果，所以首页金额必然等于结账页金额。
 * 差别只在轮询间隔（首页 30 秒、结账页 10 秒）。
 *
 * <p>三个坑，每个都不是理论问题，见下面各自的注释。
 */
import { ref, computed, watch, onUnmounted } from 'vue'
import { getPreview } from '@/api/order'
import { ApiError } from '@/utils/error'

/**
 * 创建一个预览轮询器。
 *
 * @param {object} [options]
 * @param {number} [options.interval] 轮询间隔（毫秒），默认 10 秒
 * @returns {object} 轮询器
 */
export function usePreviewPolling(options = {}) {
  const interval = options.interval ?? 10000

  /** 最新一次预览结果。 */
  const preview = ref(null)
  /** 是否正在请求。 */
  const loading = ref(false)

  /** 当前轮询的订单 ID。 */
  let orderId = null
  /** 定时器句柄。 */
  let timer = null
  /**
   * 已渲染数据的计算时刻。
   *
   * <p>⚠️ 用响应里的 {@code previewAt} 而不是本地时间戳：
   * 本地时钟可能被用户改过，用它比较会得出错误的先后关系。
   */
  let renderedPreviewAt = ''

  /* ---------------- 本地秒表 ---------------- */

  /**
   * 距上次拿到预览数据过去了多少秒。
   *
   * <p>为什么不直接用本地时钟减订单开始时刻？那样算出来的是「页面时钟的差」，
   * 而后端给的是「服务端算的时长」—— 用户手机时间不准时两者会对不上。
   * 这里以后端数值为锚点，本地只负责在两帧之间走字。
   */
  const tick = ref(0)
  let tickTimer = null

  /** 预览刷新时把秒表归零 —— 新的锚点到了。 */
  watch(preview, () => {
    tick.value = 0
  })

  /** 在店时长（秒）：后端给的分钟数 + 本地走过的秒数。 */
  const elapsedSeconds = computed(() => (preview.value?.stayMinutes || 0) * 60 + tick.value)

  /**
   * 距下一次账单变化的剩余秒数。
   *
   * <p>{@code null} 表示后端没有给预告（已封顶、或时段内不再变化），
   * 此时前端<b>只显示文字、不显示倒计时</b>。
   */
  const countdownSeconds = computed(() => {
    const total = preview.value?.nextChangeInSeconds
    if (total == null) return null
    return Math.max(0, total - tick.value)
  })

  function startTick() {
    stopTick()
    tickTimer = setInterval(() => {
      tick.value += 1
      // 倒计时归零说明跨过了档位边界，立刻拉一次新预览。
      // 秒表先归零，免得在下一份数据到达前反复触发刷新
      if (countdownSeconds.value === 0) {
        tick.value = 0
        fetchOnce()
      }
    }, 1000)
  }

  function stopTick() {
    if (tickTimer !== null) {
      clearInterval(tickTimer)
      tickTimer = null
    }
  }

  /* ---------------- 轮询 ---------------- */

  /**
   * 【坑一：乱序响应】
   *
   * <p>网络抖动时，第 20 秒发出的请求可能比第 10 秒的先回来，
   * 页面会先显示新数据再<b>倒退</b>（金额变小），用户以为系统算错了。
   *
   * <p>用 {@code previewAt} 做单调性检查：比已渲染的更早则整个丢弃。
   * 时间串是 {@code yyyy-MM-dd HH:mm:ss} 定长格式，字典序即时间序，
   * 直接字符串比较即可。
   *
   * @param {object} data 后端返回的预览数据
   * @returns {boolean} 是否应当采用
   */
  function accept(data) {
    if (!data) return false
    const at = data.previewAt || ''
    return at >= renderedPreviewAt
  }

  /** 拉一次预览。 */
  async function fetchOnce() {
    if (!orderId) return
    const requestedId = orderId
    loading.value = true
    try {
      const resp = await getPreview(requestedId)
      /*
       * 【坑二：停止后仍在途的响应】
       *
       * <p>用户点了「停止计时」，上一个 preview 还在路上 ——
       * 它回来会把页面从「已结束」刷回「计时中」，用户以为没停成功又点一次。
       * 所以响应回来后先确认轮询还是同一个订单：对不上就直接丢弃。
       */
      if (!orderId || orderId !== requestedId) return
      if (accept(resp.data)) {
        preview.value = resp.data
        renderedPreviewAt = resp.data.previewAt || ''
      }
    } catch (err) {
      /**
       * 【坑三：停止之后的 409 不是错误】
       *
       * <p>订单转成 {@code PENDING_PAYMENT} 之后再请求 preview 会返回 409，
       * 这是<b>预期内</b>的 —— 那个 409 的含义是「本页使命结束」，
       * 不是故障。在这里静默停止轮询即可，绝不能弹提示吓用户一跳。
       */
      if (err instanceof ApiError && err.status === 409) {
        stop()
        return
      }
      // 其余错误（网络抖动等）静默忽略，下一轮会重试 ——
      // 轮询过程中的偶发失败不该打断用户正在看的页面
    } finally {
      loading.value = false
    }
  }

  /** 排下一轮。 */
  function schedule() {
    clearTimer()
    /*
     * ⚠️ 用递归 setTimeout 而不是 setInterval：
     * 某次请求耗时 3 秒时，setInterval 会在它还没返回时又发一个，
     * 于是并发请求越堆越多，页面金额开始跳来跳去。
     */
    timer = setTimeout(async () => {
      await fetchOnce()
      // timer 为 null 说明期间调用了 stop()，不要再排下一轮
      if (timer !== null) schedule()
    }, interval)
  }

  function clearTimer() {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  /**
   * 开始轮询。
   *
   * @param {number|string} id 订单 ID
   */
  async function start(id) {
    stop()
    orderId = id
    renderedPreviewAt = ''
    await fetchOnce()
    if (orderId) {
      schedule()
      startTick()
    }
  }

  /**
   * 停止轮询。
   *
   * <p>⚠️ <b>点「停止计时」前必须先调它</b>：反过来的话，
   * settle 期间的 preview 会返回 409，而那是个预期内的错误，
   * 却会因为轮询还没停被当成真错误弹出来。
   */
  function stop() {
    clearTimer()
    stopTick()
    orderId = null
  }

  /** 立即刷新一次。 */
  function refresh() {
    return fetchOnce()
  }

  // 离开页面时一定要清掉定时器，否则组件销毁后它还在跑
  onUnmounted(stop)

  return {
    preview,
    loading,
    tick,
    elapsedSeconds,
    countdownSeconds,
    start,
    stop,
    refresh
  }
}
