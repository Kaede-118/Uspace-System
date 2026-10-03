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
import { uploadImage } from './upload'

/** 收款目标类型。 */
export const TargetType = {
  ORDER: 'ORDER',
  BOOKING: 'BOOKING',
  MONTHLY_CARD: 'MONTHLY_CARD',
  PRODUCT: 'PRODUCT'
}

/**
 * 支付通道。
 *
 * <p>⚠️ <b>这只是取值表，不是「有哪些通道可选」的清单。</b>
 * 某次支付能用哪些通道由 {@link getChannels} 从后端取 ——
 * 12 月投产时线上三条全部关闭，只剩 {@code QR_UPLOAD}，
 * 而本机演示时四条都能用。写死清单会让页面与部署配置脱节。
 */
export const Channel = {
  /** 微信内置浏览器：JSAPI */
  WXPAY_JSAPI: 'WXPAY_JSAPI',
  /** 微信外的手机浏览器：H5，拉起微信 App */
  WXPAY_H5: 'WXPAY_H5',
  /** 支付宝：手机网站支付（WAP） */
  ALIPAY_WAP: 'ALIPAY_WAP',
  /** 扫码转账：展示店内收款码，用户付款后上传截图，管理员核对 */
  QR_UPLOAD: 'QR_UPLOAD'
}

/**
 * 列出某类收款当前可用的支付通道。
 *
 * <p><b>收银台的选项由这个接口给，不要在页面里写死</b>：哪些通道开放是部署配置
 * （{@code uspace.payment.enabled-channels}），哪类收款受理哪些通道是业务规则 ——
 * 两者都只有服务端知道。前端自己维护一份清单，会出现「配置改了页面没跟着变」，
 * 以及更糟的「选项在那里、点了却报错」。
 *
 * <p>后端算这份列表用的判断与 {@link createPayment} 的准入校验是同一组，
 * 所以<b>页面上选得到 ⟺ 点下去不报错</b>。
 *
 * @param {string} targetType 见 {@link TargetType}，决定滤掉哪些不受理的通道
 * @returns {Promise<{data:Array<{channel:string, label:string, needProof:boolean, online:boolean}>}>}
 *          {@code label} 是用户口径的中文名（「微信支付」「扫码转账」），直接渲染；
 *          {@code needProof} 为 true 表示这条通道要走上传付款凭证的收银台
 */
export function getChannels(targetType) {
  return http.get('/api/payments/channels', { params: { targetType } })
}

/**
 * 列出当前门店启用中的收款码。
 *
 * <p>扫码转账那条通道的收银台要展示它们：用户看到二维码 → 扫码付款 →
 * 回来上传付款截图。多张码（微信、支付宝各一）时并排展示，靠 {@code name} 区分。
 *
 * <p><b>与 {@link getChannels} 是两个接口而不是一个</b>：通道管「店收不收这种钱」
 * （部署配置决定，改一次要重启），收款码管「钱扫到哪张图上」（运营在后台随时改）。
 * 两者的更新时机完全不同，合并会让前者的缓存被后者拖累。
 *
 * <p>一张码都没配时返回<b>空数组</b>：那是真实的运营状态（刚部署完还没配），
 * 页面要提示「请联系管理员配置收款方式」而不是白屏。
 *
 * @returns {Promise<{data:Array<{id,channel,channelLabel,name,imageUrl}>}>}
 *          {@code imageUrl} 直接给 {@code <img :src>} 用；
 *          {@code id} 提交付款凭证时原样回传，记在凭证上供对账追溯
 */
export function listPayQrs() {
  return http.get('/api/payments/qr')
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
 * 上传付款截图。
 *
 * <p>扫码转账那条通道的收银台用它：用户扫码付完款 → 选择/拍摄付款详情页的截图 →
 * 上传拿到路径 → 连同流水号一起提交。
 *
 * <p><b>上传与提交是两个接口</b>：上传只把文件存下来、返回路径，
 * 提交才写进凭证表。因为用户传完图还要核对流水号、可能还要改一遍再交 ——
 * 上传那一刻就落库，等于把「还没确认的东西」记成了一条凭证。
 *
 * <p>⚠️ 图片处理参数走 {@code utils/image.js} 的 {@code proof} 处理器
 * （不裁剪、长边 1600、质量 0.92）—— 那些参数直接决定 OCR 认不认得出流水号，
 * 由 {@code ImageUploader} 按 kind 自动选，调用方不必关心。
 *
 * <p><b>返回体里还可能带上三个识别结果</b>（{@code ocrPaymentNo} / {@code ocrAmount} /
 * {@code ocrText}）：后端在上传时顺手把截图识别了一遍。它们可能为空 ——
 * 没配识别引擎（{@code uspace.ocr.provider=mock}）时恒为空，
 * 装了引擎也可能认不出这张图。<b>三个字段都要原样带回提交接口</b>，
 * 见 {@link submitProof}。
 *
 * @param {File} file 处理后的图片文件
 * @returns {Promise<{data:{proofUrl:string, ocrPaymentNo:?string,
 *          ocrAmount:?number, ocrText:?string}}>}
 */
export function uploadProofImage(file) {
  return uploadImage('/api/payment-proofs/image', file)
}

/**
 * 提交付款凭证。
 *
 * <p><b>四类收款共用这一个入口</b>（订单 / 包场 / 月卡 / 商品），
 * 靠 {@code targetType} + {@code targetId} 指明是哪一笔。
 *
 * <p>提交之后会发生什么<b>取决于收款类型</b>，看返回体里的 {@code delivered}：
 * 订单与商品是「提交即交付」（当场结清、库存当场扣），包场与月卡要等管理员复核。
 * ⚠️ <b>不要按 {@code targetType} 自己判一遍</b> —— 哪一类走哪条路由是后端
 * 声明的，前端再判一次就会漂移，而漂移的表现是「页面说已结清、实际还在待复核」。
 *
 * <p>三个 {@code ocr*} 参数是上传接口返回的那三个，<b>原样回传、不要在中间加工</b>：
 * 服务端把它们落进凭证表，后台复核时拿识别出的金额与单号跟用户提交的比对。
 * 它们只在上传那一刻识别一次（云端按次计费），提交时不再重新识别。
 *
 * @param {object} params
 * @param {string} params.targetType 见 {@link TargetType}
 * @param {number|string} params.targetId 目标 ID
 * @param {string} params.proofUrl 上传接口返回的截图路径
 * @param {string} [params.paymentNo] 交易流水号，可空（有些收款方式没有）
 * @param {number} [params.payQrId] 扫的是哪张收款码，可空
 * @param {?string} [params.ocrPaymentNo] 识别出的交易单号，可空
 * @param {?number} [params.ocrAmount] 识别出的金额，可空
 * @param {?string} [params.ocrText] 识别原文，可空
 * @returns {Promise<{data:{targetType, targetId, verifyStatus, verifyStatusLabel,
 *          delivered, message}}>}
 */
export function submitProof({ targetType, targetId, proofUrl, paymentNo, payQrId,
                              ocrPaymentNo, ocrAmount, ocrText }) {
  return http.post('/api/payment-proofs', {
    targetType,
    targetId,
    proofUrl,
    paymentNo,
    payQrId,
    ocrPaymentNo,
    ocrAmount,
    ocrText
  })
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
