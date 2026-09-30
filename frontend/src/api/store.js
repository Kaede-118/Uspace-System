/**
 * 门店、公告、包场时间表、在店名册。
 *
 * <p>路径前缀 {@code /api/store}，但<b>权限并不一致</b>，用的时候要留意：
 *
 * <ul>
 *   <li>{@code /status}、{@code /notices}、{@code /bookings} —— <b>匿名可访问</b>。
 *       它们是「店门口挂的牌子」：门店名、此刻营不营业、有什么通知、接下来哪些
 *       时段进不去。要求先注册才能看，等于让第一次来的人白跑一趟</li>
 *   <li>{@code /instore} —— <b>需要登录</b>。「此刻店里有谁」是顾客之间才看得见的
 *       名册，与店门口的牌子是两回事</li>
 * </ul>
 */
import http from './http'

/**
 * 查门店营业状态。
 *
 * <p>⚠️ 营业状态是枚举字符串 {@code status}，取值 <b>OPEN / BOOKED / CLOSED</b>，
 * 不是布尔值：{@code BOOKED} 是「被包场」——它比包场开始时间早 15 分钟就亮，
 * 好让散客提前知道「再进来就打不完整局了」。
 *
 * <p>响应体里<b>不含</b>停业原因与包场人是谁 —— 那是门槛更高的信息，
 * 边界定在 {@code StoreStatusVo} 上。
 *
 * @returns {Promise<{data:{name, address, description, status, statusText, ongoingBooking}}>}
 */
export function getStoreStatus() {
  return http.get('/api/store/status')
}

/**
 * 查公告（消息流）。
 *
 * <p><b>公告是一条消息，不是一份状态</b>：自动公告只增不改，机台修好不会把
 * 「转为维护中」那条改掉，而是再产生一条「转为良好」。所以首页读起来是一段历史，
 * 而不是当前状态 —— 这与「状态投影」式的设计是反的，别按惯性去「去重」。
 *
 * @param {number} [limit] 取最近几条，1–20
 * @returns {Promise<{data:Array<{id, title, content, publishMode, createdAt}>}>}
 */
export function getNotices(limit) {
  return http.get('/api/store/notices', { params: { limit } })
}

/**
 * 查包场时间表。
 *
 * <p>与公告是两张不同的卡片，<b>不要合并</b>：公告记「已经发生了什么」，
 * 时间表回答「接下来哪些时段进不去」。混在一条流里，用户分不清哪条是通知、
 * 哪条是安排。
 *
 * <p>返回体里只有时段，没有包场人是谁。
 *
 * @param {number} [limit] 取最近几场
 * @returns {Promise<{data:Array<{startAt, endAt, ongoing}>}>}
 *          {@code ongoing} 由后端算好（客户端时钟不可信，且口径只能有一处定义）
 */
export function getBookingSchedule(limit) {
  return http.get('/api/store/bookings', { params: { limit } })
}

/**
 * 查在店名册（需登录）。
 *
 * <p>返回门店当前全部在店顾客，按进店时刻升序，<b>不含任何金额</b>。
 *
 * <p>⚠️ {@code preference} 是逗号分隔的 code（如 {@code "PAIPAI"}），
 * <b>没有中文名</b> —— 中文名在 {@code /api/devices/types} 里，
 * 前端用它做映射。后端不给是刻意的：偏好中文名躺在 device 包，
 * 为一个翻译新开一条 {@code order → device} 依赖边不划算。
 *
 * @returns {Promise<{data:Array<{userId, nickname, avatar, banner, preference, cardType, cardTypeLabel, startTime, stayMinutes}>}>}
 *          {@code cardType} 区分全天（ALL_DAY）与夜间（NIGHT），可为 null
 */
export function getInstoreUsers() {
  return http.get('/api/store/instore')
}
