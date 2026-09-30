/**
 * 包场接口（模块 3 的用户端部分）。
 *
 * <p>路径前缀 {@code /api/bookings}，全部需登录。
 *
 * <p><b>两个列表不要合并成一个</b>：{@code /host} 是「我发起的」，
 * {@code /joined} 是「我参与的」。它们的数据来源不同 ——
 * {@code /host} 按 {@code biz_booking.host_user_id} 查（<b>含待付款的场次</b>，
 * 付款入口就在那个列表上）；{@code /joined} 按参与者表的 {@code role='PARTICIPANT'} 查。
 */
import http from './http'

/**
 * 查我发起的包场（含待付款）。
 *
 * @param {object} [params]
 * @param {number} [params.page] 页码
 * @param {number} [params.size] 每页条数
 * @returns {Promise<{data:{total, current, size, records}}>}
 */
export function listHostBookings(params = {}) {
  return http.get('/api/bookings/host', { params })
}

/**
 * 查我参与的包场（别人发起、邀请了我）。
 *
 * @param {object} [params]
 * @param {number} [params.page] 页码
 * @param {number} [params.size] 每页条数
 * @returns {Promise<{data:{total, current, size, records}}>}
 */
export function listJoinedBookings(params = {}) {
  return http.get('/api/bookings/joined', { params })
}

/**
 * 取邀请链接（仅包场人本人）。
 *
 * <p>⚠️ 未付款时返回 {@code BOOKING_NOT_PAID}(40930) ——
 * 包场创建出来是 {@code PENDING_PAYMENT}，<b>付款后才生成邀请令牌、
 * 才产生排他性</b>。这不是「状态不对不能改」那种错误，
 * 用户的可行动作是「去付款」。
 *
 * @param {number|string} id 包场 ID
 * @returns {Promise<{data:{bookingId, bookingNo, inviteToken, path, url, startAt, endAt}}>}
 *          ⚠️ 前端<b>优先用 {@code location.origin + path} 自己拼</b>完整链接 ——
 *          开发期后端配的 {@code uspace.web.base-url} 是 {@code localhost:8080}，
 *          发到微信里打不开
 */
export function getInviteLink(id) {
  return http.get(`/api/bookings/${id}/invite-link`)
}

/**
 * 凭令牌查包场信息与参与者名单（只读）。
 *
 * <p>⚠️ 这个 GET <b>保持只读</b>是刻意的：浏览器预取、用户刷新、爬虫都会重复
 * 请求 GET，把它做成「点开即加入」会有副作用。落地页加载后由前端<b>另外</b>
 * 发一次 {@link joinByInvite}，用户感受完全一样（点开链接就进去了）。
 *
 * @param {string} token 邀请令牌
 * @returns {Promise<{data:{booking, startAt, endAt, participants}}>}
 *          participants 每项含 {@code userId, nickname, avatar, role, roleText}
 */
export function getInviteInfo(token) {
  return http.get(`/api/bookings/invite/${token}`)
}

/**
 * 加入包场（落地页自动调用）。
 *
 * <p><b>「重复加入」不是错误</b>：刷新页面、从聊天记录里再点一次都会走到这里。
 * 后端靠唯一键冲突识别，返回 {@code alreadyJoined: true} —— 那是答案，不是异常。
 *
 * @param {string} token 邀请令牌
 * @returns {Promise<{data:{joined, alreadyJoined, participantCount}}>}
 */
export function joinByInvite(token) {
  return http.post(`/api/bookings/invite/${token}/join`)
}
