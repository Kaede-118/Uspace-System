/**
 * 支付流程封装。
 *
 * <p>四类收款（订单 / 包场 / 月卡 / 商品）共用这一个 —— 它们的差别只有
 * {@code targetType} 一个参数，回调、验签、幂等都在后端同一段逻辑里。
 *
 * <p>三个坑写在下面各自的注释里，每个都对应一种「钱付了但页面说没付」的故障。
 */
import { ref } from 'vue'
import { createPayment, getPaymentStatus, mockPay, pickPayAction } from '@/api/payment'
import { errorMessage } from '@/utils/error'

/** 轮询查单的次数与间隔：15 次 × 2 秒 = 最多等 30 秒。 */
const POLL_TIMES = 15
const POLL_INTERVAL = 2000

/**
 * 发起支付并处理返回的支付参数。
 *
 * @returns {object} 支付流程的状态与操作
 */
export function usePayment() {
  /** 正在发起支付（调 POST /api/payments）。 */
  const loading = ref(false)
  /** 发起支付返回的数据，含 outTradeNo 与各通道参数。 */
  const payInfo = ref(null)
  /** 是否显示模拟收银台。 */
  const cashierVisible = ref(false)
  /** 是否已确认支付成功。 */
  const paid = ref(false)
  /** 给用户看的状态说明。 */
  const statusMessage = ref('')
  /** 正在轮询查单。 */
  const polling = ref(false)

  /**
   * 发起支付。
   *
   * <p>⚠️ <b>应当在用户点「去支付」时才调</b>，不要在结算完就调：
   * JSAPI 的凭据有效期 2 小时、H5 的链接更是只有 5 分钟，
   * 提前下单只会让它在用户还没决定付款时就过期。
   *
   * @param {object} params
   * @param {string} params.targetType ORDER / BOOKING / MONTHLY_CARD / PRODUCT
   * @param {number|string} params.targetId 目标 ID
   * @param {string} params.channel 支付通道
   * @returns {Promise<object|null>} 支付参数；失败返回 null
   */
  async function start({ targetType, targetId, channel }) {
    loading.value = true
    paid.value = false
    statusMessage.value = ''
    try {
      const resp = await createPayment({ targetType, targetId, channel })
      payInfo.value = resp.data

      /*
       * ⚠️ 判断顺序不能颠倒：先 mockPayUrl，再 jsapiParams，最后 h5Url。
       * mock provider 下后端为了让流程逼真，可能让多个字段同时有值 ——
       * 先判 h5Url 并跳过去，会跳到一个真实支付网关的沙箱地址（或 404）。
       */
      const action = pickPayAction(resp.data)

      if (action.type === 'mock') {
        // 模拟模式：弹我们自己的收银台，由它去调 mock/pay。
        // 绝不动 location.href —— 那个地址是假的，跳过去只会 404
        cashierVisible.value = true
      } else if (action.type === 'jsapi') {
        await invokeWechatJsapi(action.payload)
        await pollUntilPaid()
      } else if (action.type === 'h5') {
        // ⚠️ h5Url 只有 5 分钟有效期，且严禁篡改、拆分或截断 ——
        // 拿到后立刻跳，不要缓存、不要等待
        window.location.href = action.payload
      } else if (action.type === 'form') {
        submitFormHtml(action.payload)
      }

      return resp.data
    } catch (err) {
      statusMessage.value = errorMessage(err, '发起支付失败')
      return null
    } finally {
      loading.value = false
    }
  }

  /**
   * 模拟收银台上点「完成支付」。
   *
   * <p>⚠️ <b>它返回成功 ≠ 支付成功</b>。后端那个接口模拟的是「用户在收银台点了确认」，
   * 触发的是完整回调链路；而后端配置里有 {@code notify-drop-rate}，
   * 会按概率<b>故意丢掉回调</b>（这是刻意造的，真实平台的回调确实会丢）。
   * 所以无论它返回什么，都要去查单确认。
   *
   * @returns {Promise<boolean>} 最终是否确认已支付
   */
  async function confirmMockPay() {
    if (!payInfo.value) return false
    const { outTradeNo, channel } = payInfo.value

    loading.value = true
    try {
      const resp = await mockPay(outTradeNo)
      // 回调没投递成功时后端会说明，但仍要走查单补偿 ——
      // 查单接口在查到平台已收款而本地未更新时，会走与回调相同的处理逻辑
      if (resp.data?.notified === false) {
        statusMessage.value = '回调未送达，正在主动查单确认…'
      }

      const ok = await pollUntilPaid()
      cashierVisible.value = false
      return ok
    } catch (err) {
      statusMessage.value = errorMessage(err, '支付确认失败')
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * 轮询查单，直到确认为止。
   *
   * <p>⚠️ <b>不能只依赖回调</b>。回调丢失在生产环境是必然事件；
   * 依赖它的后果是用户付了钱、页面一直显示「支付中」，
   * 用户以为没付成功又付一次 —— <b>重复扣款</b>。
   *
   * @returns {Promise<boolean>} 是否已支付
   */
  async function pollUntilPaid() {
    if (!payInfo.value) return false
    const { outTradeNo, channel } = payInfo.value
    polling.value = true
    try {
      for (let i = 0; i < POLL_TIMES; i++) {
        const resp = await getPaymentStatus(outTradeNo, channel)
        const data = resp.data
        if (data?.message) statusMessage.value = data.message
        if (data?.paid) {
          paid.value = true
          return true
        }
        await sleep(POLL_INTERVAL)
      }
      statusMessage.value = '尚未确认到账。若你已完成付款，请稍后刷新页面查看'
      return false
    } catch (err) {
      statusMessage.value = errorMessage(err, '查询支付状态失败')
      return false
    } finally {
      polling.value = false
    }
  }

  /** 复位，供下次支付复用。 */
  function reset() {
    payInfo.value = null
    paid.value = false
    statusMessage.value = ''
    cashierVisible.value = false
  }

  return {
    loading,
    polling,
    paid,
    payInfo,
    cashierVisible,
    statusMessage,
    start,
    confirmMockPay,
    pollUntilPaid,
    reset
  }
}

/**
 * 调起微信 JSAPI 支付。
 *
 * @param {object} params 后端返回的 jsapiParams
 * @returns {Promise<void>} 用户完成支付时 resolve，取消或失败时 reject
 */
function invokeWechatJsapi(params) {
  return new Promise((resolve, reject) => {
    if (typeof window.WeixinJSBridge === 'undefined') {
      reject(new Error('未检测到微信支付环境，请从微信中打开页面'))
      return
    }
    window.WeixinJSBridge.invoke('getBrandWCPayRequest', params, (res) => {
      if (res.err_msg === 'get_brand_wcpay_request:ok') {
        resolve()
      } else if (res.err_msg === 'get_brand_wcpay_request:cancel') {
        reject(new Error('已取消支付'))
      } else {
        reject(new Error('微信支付未完成'))
      }
    })
  })
}

/**
 * 提交支付宝返回的表单 HTML。
 *
 * <p>后端给的是「一段自动提交的 form」，插进页面即会拉起支付宝 App。
 * 不要试图解析它的字段自己拼表单 —— 签名与字段顺序都是支付宝定的。
 *
 * @param {string} html 表单 HTML
 */
function submitFormHtml(html) {
  const holder = document.createElement('div')
  holder.style.display = 'none'
  holder.innerHTML = html
  document.body.appendChild(holder)
  const form = holder.querySelector('form')
  if (form) {
    form.submit()
  }
}

/**
 * 延时。
 *
 * @param {number} ms 毫秒
 * @returns {Promise<void>}
 */
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
