/**
 * 支付接口（模块 8 的一部分）。
 *
 * <p>路径前缀 {@code /api/payments}，需登录。
 *
 * <p><b>四类收款共用这一个入口</b>，只是 {@code targetType} 不同：
 * 房间时长 {@code ORDER}、包场 {@code BOOKING}、月卡 {@code MONTHLY_CARD}、
 * 实体商品 {@code PRODUCT}。这是「把入口收在『支付』这个动作上」那个设计的兑现 ——
 * 加一类收款不必新开路径，回调、验签、幂等一行都不用改。
 */
import http from './http'

/** 收款目标类型。 */
export const TargetType = {
  ORDER: 'ORDER',
  BOOKING: 'BOOKING',
  MONTHLY_CARD: 'MONTHLY_CARD',
  PRODUCT: 'PRODUCT'
}

/** 支付通道。 */
export const Channel = {
  /** 微信内置浏览器：JSAPI */
  WXPAY_JSAPI: 'WXPAY_JSAPI',
  /** 微信外的手机浏览器：H5，拉起微信 App */
  WXPAY_H5: 'WXPAY_H5',
  /** 支付宝：手机网站支付（WAP） */
  ALIPAY_WAP: 'ALIPAY_WAP',
  /** 上传凭证 + 人工核销（降级路径，月卡与商品不受理此通道） */
  QR_UPLOAD: 'QR_UPLOAD'
}

/**
 * 发起支付。
 *
 * <p>⚠️ <b>不要在订单创建时就调它</b>，应当在用户点「去支付」时才调：
 * JSAPI 的 {@code prepay_id} 有效期 2 小时，H5 的 {@code h5_url} 只有 5 分钟。
 * 提前下单会拿到一个已经过期的支付参数。
 *
 * @param {object} params
 * @param {string} params.targetType 见 {@link TargetType}
 * @param {number|string} params.targetId 目标 ID（订单 ID / 包场 ID / 购买单 ID）
 * @param {string} params.channel 见 {@link Channel}
 * @returns {Promise<{data:{outTradeNo, channel, mockPayUrl, jsapiParams, h5Url, formHtml, ...}}>}
 *          返回哪个字段取决于通道，见 {@link pickPayAction}
 */
export function createPayment({ targetType, targetId, channel }) {
  return http.post('/api/payments', { targetType, targetId, channel })
}

/**
 * 查支付结果（主动查单）。
 *
 * <p>⚠️ <b>必须主动查，不能只依赖回调</b>。后端的
 * {@code uspace.payment.mock.notify-drop-rate} 会按概率<b>故意丢掉回调</b>，
 * 这是刻意造的 —— 真实支付平台的回调确实会丢。
 *
 * <p>依赖回调的后果很严重：用户付了钱、回调丢了、前端一直显示「支付中」，
 * 用户以为没付成功又付一次 —— <b>重复扣款</b>。
 *
 * @param {string} outTradeNo 商户订单号（发起支付时返回的）
 * @param {string} channel    支付通道。<b>必传</b> —— 后端签名上标着可选，
 *                            但不同通道的查单实现不同，不传会查不准。
 *                            建议后端把接口文档里这个参数从「可选」改成「必填」，
 *                            让契约与实现一致
 * @returns {Promise<{data:{outTradeNo, status, paid}}>} status 为 SUCCESS 表示已支付
 */
export function getPaymentStatus(outTradeNo, channel) {
  return http.get(`/api/payments/${outTradeNo}`, { params: { channel } })
}

/**
 * 模拟支付（仅 mock provider 下存在）。
 *
 * <p>这是「模拟用户在微信/支付宝里完成了支付」的那个动作。
 * 演示时在页面上点它，等价于顾客在收银台付了钱。
 *
 * <p>⚠️ <b>它返回成功 ≠ 支付成功</b>。它只是「把支付模拟提交了」，
 * 真正的确认在于后端的回调处理有没有走到，而那取决于回调有没有被丢。
 * 所以调完它之后仍要 {@link getPaymentStatus} 轮询确认。
 *
 * @param {string} outTradeNo 商户订单号
 * @returns {Promise}
 */
export function mockPay(outTradeNo) {
  return http.post('/api/payments/mock/pay', { outTradeNo })
}

/**
 * 从发起支付的响应里挑出该执行的动作。
 *
 * <p>⚠️ <b>判断顺序不能颠倒：先 {@code mockPayUrl}，再 {@code jsapiParams}，
 * 最后 {@code h5Url}</b>。mock provider 下后端为了让流程逼真，
 * 可能让多个字段同时有值；先判 {@code h5Url} 并跳过去，
 * 会跳到一个真实支付网关的沙箱地址（或 404）。
 *
 * @param {object} payInfo 发起支付返回的 data
 * @returns {{type:string, payload:*}} type 取 {@code mock} / {@code jsapi} / {@code h5} / {@code form} / {@code none}
 */
export function pickPayAction(payInfo) {
  if (!payInfo) return { type: 'none', payload: null }
  if (payInfo.mockPayUrl) return { type: 'mock', payload: payInfo.mockPayUrl }
  if (payInfo.jsapiParams) return { type: 'jsapi', payload: payInfo.jsapiParams }
  if (payInfo.h5Url) return { type: 'h5', payload: payInfo.h5Url }
  if (payInfo.formHtml) return { type: 'form', payload: payInfo.formHtml }
  return { type: 'none', payload: null }
}
