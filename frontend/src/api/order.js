/**
 * 订单接口（模块 8）。
 *
 * <p>路径前缀 {@code /api/orders}，全部需登录。
 *
 * <p><b>状态机只有两跳</b>：{@code IN_USE → PENDING_PAYMENT → PAID}，
 * 没有 CREATED 中转态，也因此<b>没有「取消订单」接口</b> ——
 * 「点击开门」是一步到位的：点一次同时完成「创建订单 + 下发密码 + 开始计费」。
 * 误点一下不想进店，点「结束使用」即可，5 分钟内落在免费档、0 元自动结清。
 */
import http from './http'

/**
 * 点击开门：创建订单 + 下发密码 + 开始计费。
 *
 * <p>⚠️ <b>调用前先查一次 {@link getCurrentOrder}}</b>。后端有防连点与欠费拦截，
 * 但直接调的话用户会先看到一个错误提示，而他的真实处境可能是「我已经开过门了」。
 * 先查一次能把一个错误提示变成一次正常的页面跳转。
 *
 * <p>可能返回的两个「拦下来」的码，<b>处置方式完全不同</b>：
 * <ul>
 *   <li>{@code ORDER_ALREADY_ACTIVE}(40919) —— 人还在店里玩，引到「查看密码」</li>
 *   <li>{@code ORDER_UNPAID_EXISTS}(40931) —— 玩完了账还欠着，引到「去支付」</li>
 * </ul>
 *
 * @param {string} [inviteToken] 邀请令牌。它已<b>降级为兜底路径</b>：
 *                               正常流程是落地页自动加入参与者表，准入认表；
 *                               令牌只在「那一次自动加入失败」时兜底
 * @returns {Promise<{data:{id, orderNo, passcode, startTime, status}}>}
 */
export function createOrder(inviteToken) {
  return http.post('/api/orders', { inviteToken: inviteToken || null })
}

/**
 * 查当前进行中的订单。
 *
 * <p>⚠️ <b>只返回 {@code IN_USE}，不返回待支付的单</b>。这不是遗漏：
 * 待支付的单已经结算、密码已经撤销，返回它会让首页给出一个点不动的「查看密码」。
 * 所以判断「有没有欠费」要用 {@link listMyOrders} 单独查。
 *
 * @returns {Promise<{data:object|null}>} 没有进行中的订单时 {@code data} 为 null
 */
export function getCurrentOrder() {
  return http.get('/api/orders/current')
}

/**
 * 取门锁密码。
 *
 * <p>密码在<b>订单创建时就已下发到锁上</b>，这个接口只是把它显示出来 ——
 * 不是「点一次发一次」。这样设计是为了省门锁云额度（30,000 次/月是硬约束），
 * 也避免密码在锁上越积越多。
 *
 * <p>密码过期时会自动续期：{@code changePasscode} 推后有效期，
 * <b>密码数字不变</b>，锁上也不累积。
 *
 * @param {number|string} id 订单 ID
 * @returns {Promise<{data:{passcode, expireAt}}>}
 */
export function getPasscode(id) {
  return http.get(`/api/orders/${id}/passcode`)
}

/**
 * 结账预览（纯查询、零副作用）。
 *
 * <p>⚠️ <b>它不撤销密码、不改状态、计时照走</b>。这一点很要紧：
 * 若误用结算接口来做预览，用户点一下「结账」看看多少钱就进不去门了，
 * 且每次都要消耗一次门锁云额度。
 *
 * <p>它走的是与结算<b>同一个 {@code calculateBill}</b>，所以预览价必然等于实际价。
 *
 * <p>返回体里的 {@code nextChangeInSeconds} / {@code nextChangeText} 是跳档预告，
 * <b>必须用后端给的秒数</b>，不要让前端拿绝对时刻去减 —— 客户端时钟不可信。
 *
 * <p>返回体里的 {@code bill} 是三处说明性金额的来源（{@code discountAmount} /
 * {@code cardFreeAmount} / {@code activityFreeAmount}），
 * 账单组件拿它来解释「为什么便宜了」。
 *
 * @param {number|string} id 订单 ID
 * @returns {Promise<{data:{bill:{segments, totalMinutes, totalAmount, discountAmount,
 *          cardFreeAmount, activityFreeAmount}, stayMinutes, freeByBooking, cappedNow,
 *          nextChangeInSeconds, nextChangeText, previewAt}}>}
 */
export function getPreview(id) {
  return http.get(`/api/orders/${id}/preview`)
}

/**
 * 结束使用（结算）。
 *
 * <p>⚠️ <b>先停轮询再调它</b>。反过来的话，结算期间的 preview 会返回 409，
 * 而那是个<b>预期内</b>的错误（订单已转 {@code PENDING_PAYMENT}，本页使命结束），
 * 却会因为停止标志还是 false 被当成真错误弹出来。
 *
 * <p>返回的金额与状态一律以此为准，不要再拿之前的预览值。
 * 0 元的单会<b>直通 {@code PAID}</b>，不需要走支付。
 *
 * @param {number|string} id 订单 ID
 * @returns {Promise<{data:{id, orderNo, status, payableAmount, ...}}>}
 */
export function settleOrder(id) {
  return http.post(`/api/orders/${id}/settle`)
}

// 说明：这里曾有一个 submitPaymentProof（订单专属的上传凭证接口）。
// 2026-09-30 起付款凭证由 api/payment.js 的 submitProof 统一受理 ——
// 四类收款（订单 / 包场 / 月卡 / 商品）共用一个入口，
// 挂在订单子路径下的那个端点已经删掉了。

/**
 * 查我的订单（分页）。
 *
 * <p>首页判断「有没有欠费」就是靠它带 {@code status: 'PENDING_PAYMENT'} 查一条。
 *
 * @param {object} [params]
 * @param {number} [params.page]   页码，从 1 开始
 * @param {number} [params.size]   每页条数，1–100
 * @param {string} [params.status] IN_USE / PENDING_PAYMENT / PAID
 * @returns {Promise<{data:{total, current, size, records}}>}
 */
export function listMyOrders(params = {}) {
  return http.get('/api/orders/me', { params })
}

/**
 * 查订单详情。
 *
 * <p>比其他订单接口多两个账单字段（列表接口刻意不带，见后端 OrderVo 的说明）：
 * <ul>
 *   <li>{@code bill} —— 分段账单（{@code {segments, totalMinutes, totalAmount, ...}}），
 *       结构与结算返回的 bill 完全一致，喂给 {@code BillSegmentList} 即可。
 *       <b>可能为 null</b>：使用中的订单、以及老订单重算金额与落库对不上时都没有 ——
 *       页面必须准备回落展示（日场/夜场汇总行），不能假定它一定在</li>
 *   <li>{@code freeByBooking} —— 是否因包场减免了计费时长（解释「计费时长 < 在店时长」）</li>
 * </ul>
 *
 * @param {number|string} id 订单 ID。不是本人的订单返回 404 而不是 403 ——
 *                           403 等于承认这个订单存在，可以靠状态码差异枚举单号
 * @returns {Promise<{data:{...}}>}
 */
export function getOrder(id) {
  return http.get(`/api/orders/${id}`)
}

/**
 * 查本月累计消费与优惠资格。
 *
 * <p>返回的 {@code threshold}（门槛）与 {@code remaining}（还差多少）
 * <b>必须用返回值</b>，不要在页面上写死 200 —— 门槛是配置项。
 *
 * <p>⚠️ 判断「有没有享优惠价」用的是 <b>{@code discounted}</b>，
 * 不是 {@code reached}（没有这个字段）。实测核对过。
 *
 * @returns {Promise<{data:{monthStart, monthSpent, threshold, discounted, remaining}}>}
 */
export function getMonthSpent() {
  return http.get('/api/orders/me/month-spent')
}

/**
 * 查累计时长统计。
 *
 * <p>只算 {@code PAID} 的订单，与消费口径一致 —— 把在店未结账的那一单算进去，
 * 数字每刷新一次就往上跳一次，而它还没定局。
 *
 * <p>⚠️ 与「累计消费」不是同一口径：这里是在店时长，
 * 含包场时段、含宽限那 5 分钟。两个数字在「我的」页并排显示，别混用。
 *
 * @returns {Promise<{data:{totalMinutes, monthMinutes, monthStart}}>}
 */
export function getMyStats() {
  return http.get('/api/orders/me/stats')
}
