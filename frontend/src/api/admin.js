/**
 * 运营后台接口。
 *
 * <p>路径前缀 {@code /api/admin/**}，<b>全部要求 ADMIN 角色</b>
 * （服务端 `@PreAuthorize("hasRole('ADMIN')")`）。
 *
 * <p>⚠️ <b>前端路由守卫不是安全边界</b> —— 改前端代码就能进 `/admin` 页面，
 * 但页面里的每个请求都会被服务端挡回 403，拿不到任何数据。
 * 真正的隔离在服务端，前端守卫只是省掉一次「进去看到一片空白」的坏体验。
 *
 * <p>⚠️ <b>分页参数在这个文件里不统一</b>（后端如此，不是笔误）：
 * 用户 / 门店停业 / 包场 / 公告 / 订单 / 进出记录用 {@code page} + {@code size}，
 * 而 <b>月卡与商品用 {@code pageNum} + {@code pageSize}</b>。
 * 传错不会报错，只会永远返回第一页 —— 每个函数的注释里都标了。
 */
import http from './http'
import { uploadImage } from './upload'

/* ==================== 用户管理 ==================== */

/**
 * 分页查用户，支持筛选与排序。
 *
 * <p>每一条记录的 `cardType`（生效中的月卡类型）与 `totalStayMinutes`
 * （累计在店分钟数）都是后端**算出来的**，不是用户表上的列。
 *
 * <p>⚠️ 排序靠 `sortBy` + `desc`，而 `sortBy` 的取值只认三个：
 * `createdAt` / `totalPaid` / `stayMinutes`。传别的后端会**回落到默认排序**
 * 而不是报错 —— 所以排序「没生效」时先检查拼写，别以为是接口坏了。
 *
 * @param {object} [params]
 * @param {string} [params.keyword]  匹配登录名 / 昵称 / QQ 号
 * @param {string} [params.role]     USER / ADMIN
 * @param {number} [params.status]   1=正常 0=禁用
 * @param {boolean} [params.hasCard] 只看有（true）/ 没有（false）生效月卡的用户
 * @param {string} [params.cardType] ALL_DAY / NIGHT；**只在 hasCard=true 时有意义**
 * @param {string} [params.sortBy]   排序键，见上
 * @param {boolean} [params.desc]    是否降序
 * @param {number} [params.page]     页码（注意：这里用 page，不是 pageNum）
 * @param {number} [params.size]     每页条数
 * @returns {Promise<{data:{total, current, size, records}}>}
 */
export function listUsers(params = {}) {
  return http.get('/api/admin/users', { params })
}

/**
 * 修改用户资料（管理员侧）。
 *
 * <p><b>这是全站唯一能改 QQ 号的入口</b> —— 用户自己在「编辑资料」里改不了，
 * 后端会返回 40937「QQ 号需要联系管理员修改」。
 *
 * <p>⚠️ <b>它是全量替换语义</b>：没传的字段等于清空。页面必须先把当前值
 * 回填、提交时一起带上，否则会把手机号、偏好一起抹掉。
 *
 * <p>`id` 与登录名不在请求体里 —— 前者由路径指定，后者干脆不可改
 * （登录名是登录凭据的一半，改了用户会在毫不知情的情况下登不进去）。
 *
 * @param {number|string} id 目标用户 ID
 * @param {object} data
 * @param {string} [data.nickname]   昵称，传 null 清空（会按 QQ → 登录名兜底）
 * @param {string} [data.phone]      手机号，传 null 清空
 * @param {string} [data.qq]         QQ 号，传 null **解绑**
 * @param {string} [data.preference] 游玩偏好，传 null 清空
 * @returns {Promise<{data:object}>} 更新后的用户详情
 */
export function updateUser(id, data) {
  return http.put(`/api/admin/users/${id}`, data)
}

/**
 * 查用户详情。
 *
 * @param {number|string} id 用户 ID
 * @returns {Promise<{data:{...}}>}
 */
export function getUser(id) {
  return http.get(`/api/admin/users/${id}`)
}

/**
 * 启用 / 禁用用户。
 *
 * <p>禁用会升 {@code token_version}，<b>该用户所有在线凭证立即失效</b>。
 *
 * @param {number|string} id 用户 ID
 * @param {number} status 状态：<b>1 正常 / 0 禁用</b>（是数字，不是 'ENABLED' 这类字符串）
 * @returns {Promise}
 */
export function updateUserStatus(id, status) {
  return http.put(`/api/admin/users/${id}/status`, { status })
}

/**
 * 改用户角色。
 *
 * <p>⚠️ 与禁用不同，<b>改角色不升 {@code token_version}</b> ——
 * 权限判断每请求都从库里读 {@code sys_user.role}，所以改角色立即生效，
 * 且不必把用户踢下线。这是刻意的设计，不是遗漏。
 *
 * @param {number|string} id 用户 ID
 * @param {string} role USER / ADMIN
 * @returns {Promise}
 */
export function updateUserRole(id, role) {
  return http.put(`/api/admin/users/${id}/role`, { role })
}

/* ==================== 门店与停业 ==================== */

/**
 * 查门店信息。
 *
 * @returns {Promise<{data:{id, name, address, description}}>}
 */
export function getStore() {
  return http.get('/api/admin/store')
}

/**
 * 改门店信息（全量替换）。
 *
 * @param {object} data
 * @param {string} data.name 门店名称
 * @param {string} data.address 地址
 * @param {string} [data.description] 简介
 * @returns {Promise}
 */
export function updateStore(data) {
  return http.put('/api/admin/store', data)
}

/**
 * 分页查停业时段。
 *
 * @param {object} [params]
 * @param {number} [params.page] 页码（用 page）
 * @param {number} [params.size] 每页条数
 * @returns {Promise<{data:{total, current, size, records}}>}
 */
export function listClosures(params = {}) {
  return http.get('/api/admin/store/closures', { params })
}

/**
 * 新增停业时段。
 *
 * <p>时段一律<b>半开区间 {@code [startAt, endAt)}</b>：10:00–12:00 与 12:00–14:00
 * 可以相邻而不算重叠。
 *
 * @param {object} data
 * @param {string} data.startAt 开始时刻，格式 {@code yyyy-MM-dd HH:mm:ss}
 * @param {string} data.endAt   结束时刻
 * @param {string} [data.reason] 停业原因
 * @returns {Promise}
 */
export function createClosure(data) {
  return http.post('/api/admin/store/closures', data)
}

/**
 * 改停业时段。
 *
 * @param {number|string} id 停业记录 ID
 * @param {object} data 同 {@link createClosure}
 * @returns {Promise}
 */
export function updateClosure(id, data) {
  return http.put(`/api/admin/store/closures/${id}`, data)
}

/**
 * 删停业时段。
 *
 * @param {number|string} id 停业记录 ID
 * @returns {Promise}
 */
export function deleteClosure(id) {
  return http.delete(`/api/admin/store/closures/${id}`)
}

/* ==================== 免费时段（活动） ==================== */

/**
 * 分页查免费活动。
 *
 * <p>活动区间内所有订单实收为 0 —— 店里照常营业、门照开，只是账单不计费。
 * 与停业（拒绝新订单）不是一回事，两者在后台是「门店」页的两个 tab。
 *
 * @param {object} [params]
 * @param {number} [params.page] 页码（与停业同一套，用 page/size）
 * @param {number} [params.size] 每页条数
 * @returns {Promise<{data:{total, current, size, records}}>}
 */
export function listFreePeriods(params = {}) {
  return http.get('/api/admin/store/free-periods', { params })
}

/**
 * 新增免费活动。
 *
 * <p>与已有活动重叠会被拒绝（40945）—— 首尾相接的两场不算重叠。
 *
 * @param {object} data
 * @param {string} data.startAt 开始时刻，格式 {@code yyyy-MM-dd HH:mm:ss}
 * @param {string} data.endAt   结束时刻
 * @param {string} [data.reason] 活动名称，如「跨年活动」
 * @returns {Promise}
 */
export function createFreePeriod(data) {
  return http.post('/api/admin/store/free-periods', data)
}

/**
 * 改免费活动。
 *
 * @param {number|string} id 活动 ID
 * @param {object} data 同 {@link createFreePeriod}
 * @returns {Promise}
 */
export function updateFreePeriod(id, data) {
  return http.put(`/api/admin/store/free-periods/${id}`, data)
}

/**
 * 删免费活动（逻辑删除）。
 *
 * <p>删完立刻不再免单，已发生的订单不受影响（金额在结算时就已落库）。
 *
 * @param {number|string} id 活动 ID
 * @returns {Promise}
 */
export function deleteFreePeriod(id) {
  return http.delete(`/api/admin/store/free-periods/${id}`)
}

/* ==================== 包场排期 ==================== */

/**
 * 分页查包场。
 *
 * @param {object} [params]
 * @param {number} [params.page] 页码（用 page）
 * @param {number} [params.size] 每页条数
 * @returns {Promise<{data:{total, current, size, records}}>}
 */
export function listBookings(params = {}) {
  return http.get('/api/admin/bookings', { params })
}

/**
 * 排一场包场。
 *
 * <p>创建出来是 {@code PENDING_PAYMENT} —— <b>包场只做排期，收款归模块 8</b>。
 * 付款后才生成邀请令牌、才产生排他性。
 *
 * <p>⚠️ <b>开始时刻必须是将来</b>（后端校验，返回 {@code BOOKING_START_IN_PAST} 40911）——
 * 排一场已经开始的包场没有意义，邀请链接刚生成就只剩一半可用。
 * 注意这与「改期」不同：{@link updateBooking} <b>刻意不校验过去的时间</b>，
 * 把一场已经开始的场次往后挪是合理诉求。所以 {@code datetime-local} 上的
 * {@code min} 只给新建表单加，别顺手加到改期表单上。
 *
 * @param {object} data
 * @param {number|string} data.hostUserId 包场人用户 ID
 * @param {string} data.startAt 开始时刻（格式 {@code yyyy-MM-dd HH:mm:ss}，见 utils/format.js）
 * @param {string} data.endAt   结束时刻
 * @param {number|string} data.price 包场费
 * @param {string} [data.remark] 备注
 * @returns {Promise}
 * @throws 40911 开始时刻在过去；40912 与既有包场重叠；40913 与停业区间重叠
 */
export function createBooking(data) {
  return http.post('/api/admin/bookings', data)
}

/**
 * 改包场排期。
 *
 * <p>⚠️ <b>只受理待付款的场次</b>。已付款的场次改期会让「已经发出去的邀请链接」
 * 指向一个不存在的时间，所以不受理。
 *
 * @param {number|string} id 包场 ID
 * @param {object} data 同 {@link createBooking}（不含 hostUserId）
 * @returns {Promise}
 */
export function updateBooking(id, data) {
  return http.put(`/api/admin/bookings/${id}`, data)
}

/**
 * 取消包场（**只有待付款的可以取消**，没有钱的事）。
 *
 * <p>已付款的要撤销退款，走 {@link revokeBooking} —— 两个动作的后果不同，
 * 是两个端点、两个错误码。
 *
 * @param {number|string} id 包场 ID
 * @returns {Promise}
 */
export function deleteBooking(id) {
  return http.delete(`/api/admin/bookings/${id}`)
}

/**
 * 撤销已付款的包场并退款（**只有已付款的可以撤**）。
 *
 * <p>退款金额固定为全额（包场是一口价，撤销就是整场作废），由后端自己取，
 * 前端传不了金额 —— 能传就能传错。
 *
 * <p>服务端是「先占位、再退钱」：先把状态原子地翻成已退款（并发时只有一个能成功，
 * 这是防「同一笔钱被退两次」的关键），再去调支付平台；
 * <b>平台失败会把状态回滚成已付款并返回 502</b>，钱与单子始终对得上。
 *
 * @param {number|string} id 包场 ID
 * @param {string} refundMode 退款方式：**MANUAL** 管理员线下退 / **ONLINE** 原路退回
 * @returns {Promise<{data:{id, bookingNo, status, refundMode, refundAmount, refundedAt, refundedBy, refundNo}}>}
 * @throws 40934 该场不是已付款状态（含已被别人撤销过）
 * @throws 50201 原路退回失败，状态已回滚
 */
export function revokeBooking(id, refundMode) {
  return http.post(`/api/admin/bookings/${id}/revoke`, { refundMode })
}

/* ==================== 设备 ==================== */

/**
 * 查机台台账（按类型分组，含停用类型）。
 *
 * @returns {Promise<{data:Array}>}
 */
export function listAdminDevices() {
  return http.get('/api/admin/devices')
}

/**
 * 新增机台。会自动产生一条公告（「新增机台 X」）。
 *
 * @param {object} data
 * @param {string} data.name     机台名称
 * @param {string} data.deviceNo 机台编号
 * @param {number|string} data.typeId 类型 ID
 * @param {string} [data.location] 摆放位置
 * @param {string} [data.status] 状况：<b>NORMAL</b> 良好 / <b>NEEDS_REPAIR</b> 待维护 / <b>MAINTAINING</b> 维护中。
 *   「待维护」与「维护中」刻意分开：前者是「还能玩，但有小毛病」，后者是「别碰」
 * @param {number} [data.sort] 排序值
 * @param {string} [data.remark] 运营备注（**不会**出现在公告里）
 * @returns {Promise}
 */
export function createDevice(data) {
  return http.post('/api/admin/devices', data)
}

/**
 * 改机台（全量替换）。
 *
 * <p>⚠️ {@code status} 传 {@code null} 时<b>保持原值</b> —— 这是全量替换语义
 * 唯一的例外。设备状况是管理员正盯着看的事实，不能被一次普通改名顺手改回「良好」。
 *
 * <p>⚠️ 改 {@code status} 非 null 时会<b>触发公告</b>（「X 由 A 转为 B」），
 * 状况没变时不记 —— 反复点几下会把公告栏刷满。
 *
 * @param {number|string} id 机台 ID
 * @param {object} data 同 {@link createDevice}
 * @returns {Promise}
 */
export function updateDevice(id, data) {
  return http.put(`/api/admin/devices/${id}`, data)
}

/**
 * 单独改机台状况。
 *
 * <p>运营里最高频的动作，只更新一列。走全量替换的话，前端要先读整条记录
 * 再传回去，中间若有别人改了名称会<b>静默覆盖</b>。
 *
 * @param {number|string} id 机台 ID
 * @param {string} status NORMAL / NEEDS_REPAIR / MAINTAINING
 * @returns {Promise}
 */
export function updateDeviceStatus(id, status) {
  return http.put(`/api/admin/devices/${id}/status`, { status })
}

/**
 * 机台退役（逻辑删除）。会记一条「机台 X 已退役」的公告。
 *
 * @param {number|string} id 机台 ID
 * @returns {Promise}
 */
export function deleteDevice(id) {
  return http.delete(`/api/admin/devices/${id}`)
}

/* ==================== 公告 ==================== */

/**
 * 分页查公告（含自动公告）。
 *
 * @param {object} [params]
 * @param {number} [params.page] 页码（用 page）
 * @param {number} [params.size] 每页条数
 * @param {string} [params.publishMode] AUTO / MANUAL
 * @returns {Promise<{data:{total, current, size, records}}>}
 */
export function listNotices(params = {}) {
  return http.get('/api/admin/notices', { params })
}

/**
 * 发手写公告。
 *
 * <p>⚠️ <b>没有 {@code publishMode} 入参</b> —— 服务端方法体里硬编码 MANUAL，
 * 且 {@code source_*} 无条件置 null。别在前端传这两个字段，传了也不会生效。
 *
 * @param {object} data
 * @param {string} data.title   标题
 * @param {string} [data.content] 正文
 * @returns {Promise}
 */
export function createNotice(data) {
  return http.post('/api/admin/notices', data)
}

/**
 * 改公告（只允许改 title / content）。
 *
 * <p>⚠️ <b>自动公告改不了</b>，返回 {@code NOTICE_AUTO_READONLY}(40929)。
 * 理由不是权限而是语义：自动公告是「已发生的事实」的记录，改写等于篡改历史；
 * 而手写公告是「运营现在想说的话」，说错了该能收回来。
 *
 * @param {number|string} id 公告 ID
 * @param {object} data { title, content }
 * @returns {Promise}
 */
export function updateNotice(id, data) {
  return http.put(`/api/admin/notices/${id}`, data)
}

/**
 * 删公告（逻辑删除）。同样只对手写公告有效。
 *
 * @param {number|string} id 公告 ID
 * @returns {Promise}
 */
export function deleteNotice(id) {
  return http.delete(`/api/admin/notices/${id}`)
}

/* ==================== 订单 ==================== */

/**
 * 分页查订单。
 *
 * @param {object} [params]
 * @param {number} [params.page] 页码（用 page）
 * @param {number} [params.size] 每页条数
 * @param {number|string} [params.userId] 按用户筛
 * @param {string} [params.status] IN_USE / PENDING_PAYMENT / PAID
 * @param {string} [params.from] 开始时刻，格式 {@code yyyy-MM-dd HH:mm:ss}
 * @param {string} [params.to]   结束时刻（这个接口的 from/to 显式标了格式，能收带空格的串）
 * @param {number} [params.adjusted] 只看人工调整过的：1 是、0 否
 * @returns {Promise<{data:{total, current, size, records}}>}
 */
export function listOrders(params = {}) {
  return http.get('/api/admin/orders', { params })
}

/**
 * 查订单详情。
 *
 * @param {number|string} id 订单 ID
 * @returns {Promise}
 */
export function getAdminOrder(id) {
  return http.get(`/api/admin/orders/${id}`)
}

/**
 * 人工调整时长（顾客忘了点「结束使用」时的兜底）。
 *
 * <p>典型场景写在 {@code adjusted} 系列字段的注释里：「用户忘记结束，
 * 监控核实 21:30 已离场」。调整会重新结算并落 {@code adjusted} 审计字段。
 *
 * @param {number|string} id 订单 ID
 * @param {object} data
 * @param {string} data.endTime 修正后的离场时刻
 * @param {string} data.reason  调整原因（会进审计）
 * @returns {Promise}
 */
export function adjustOrder(id, data) {
  return http.post(`/api/admin/orders/${id}/adjust`, data)
}

// 说明：这里曾有一个 confirmPayment（订单专属的人工核销接口）。
// 2026-09-30 起付款凭证由下面的 confirmProof 统一复核 ——
// 后台列出来的每一条【就是】凭证本身，不再需要「先查目标有没有凭证」那一步。

/* ==================== 商品 ==================== */

/**
 * 分页查商品。
 *
 * @param {object} [params]
 * @param {number} [params.pageNum]  页码（⚠️ 这里用 pageNum，不是 page）
 * @param {number} [params.pageSize] 每页条数
 * @param {string} [params.keyword]  按名称搜索
 * @param {number} [params.enabled]  1 上架 / 0 下架
 * @param {boolean} [params.stockAsc] 按「库存从少到多」排，补货优先。
 *   ⚠️ <b>排序必须交给后端</b>：结果是分页的，前端只能排当前这一页，
 *   第二页可能藏着比本页更少的库存
 * @returns {Promise<{data:{total, current, size, records}}>}
 */
export function listAdminProducts(params = {}) {
  return http.get('/api/admin/products', { params })
}

/**
 * 新增商品。
 *
 * @param {object} data
 * @param {string} data.name 名称
 * @param {string} [data.cover] 封面图路径，由 {@link uploadProductCover} 上传得到
 * @param {string} [data.description] 描述
 * @param {number|string} data.price 售价
 * @param {number} data.stock 库存
 * @param {number} [data.enabled] 1 上架 / 0 下架
 * @param {number} [data.sortNo] 排序值，越小越靠前
 * @returns {Promise}
 */
export function createProduct(data) {
  return http.post('/api/admin/products', data)
}

/**
 * 改商品（全量替换：不传即清空）。
 *
 * <p>⚠️ {@code enabled} 是<b>唯一的例外</b>：不传时保持原值。
 * 改个名字顺手把商品下架了、而管理员毫无察觉，比「多写一个字段」严重得多。
 *
 * @param {number|string} id 商品 ID
 * @param {object} data 同 {@link createProduct}
 * @returns {Promise}
 */
export function updateProduct(id, data) {
  return http.put(`/api/admin/products/${id}`, data)
}

/**
 * 删商品（逻辑删除）。
 *
 * @param {number|string} id 商品 ID
 * @returns {Promise}
 */
export function deleteProduct(id) {
  return http.delete(`/api/admin/products/${id}`)
}

/**
 * 上传商品封面图（multipart）。
 *
 * <p><b>只返回图片路径，不写任何数据库</b> —— 把返回的 `cover` 填进表单，
 * 随新增 / 修改商品一起提交。三条理由：新增商品时还没有 ID 可写、
 * 表单是「填一半可以取消」的语义、传错可以反复换一张。
 *
 * @param {File} file 图片文件。后端按【文件头】判类型（JPG / PNG / WebP），
 *                    不看 Content-Type；上限 2MB
 * @returns {Promise<{data:{cover:string}}>} `data.cover` 是站内相对路径
 */
export function uploadProductCover(file) {
  return uploadImage('/api/admin/products/cover', file)
}

/**
 * 分页查商品订单。
 *
 * @param {object} [params]
 * @param {number} [params.pageNum]  页码（用 pageNum）
 * @param {number} [params.pageSize] 每页条数
 * @param {number|string} [params.userId] 按用户筛
 * @param {string} [params.status] PENDING_PAYMENT / PAID / CLOSED
 * @returns {Promise<{data:{total, current, size, records}}>}
 */
export function listAdminProductOrders(params = {}) {
  return http.get('/api/admin/products/orders', { params })
}

/* ==================== 月卡 ==================== */

/**
 * 分页查月卡（本期只读，无退款接口）。
 *
 * @param {object} [params]
 * @param {number} [params.pageNum]  页码（⚠️ 这里用 pageNum，不是 page）
 * @param {number} [params.pageSize] 每页条数
 * @param {number|string} [params.userId] 按用户筛
 * @param {string} [params.status] ACTIVE / EXPIRED
 * @param {string} [params.cardType] ALL_DAY / NIGHT
 * @returns {Promise<{data:{total, current, size, records}}>}
 */
export function listAdminCards(params = {}) {
  return http.get('/api/admin/cards', { params })
}

/* ==================== 门锁（应急） ==================== */

/**
 * 下发限时密码。
 *
 * <p>面向应急场景：顾客手机没电、临时给维修人员开门。
 * 也是演示时下发密码的入口。
 *
 * @param {object} data
 * @param {number|string} data.lockId 锁 ID
 * @param {string} data.keyboardPwd  密码（自定义密码只有三代锁支持，且只能设为限时密码）
 * @param {string} [data.keyboardPwdName] 密码名称
 * @param {string} data.startTime 生效时刻
 * @param {string} data.endTime   失效时刻
 * @returns {Promise}
 */
export function addPasscode(data) {
  return http.post('/api/admin/lock/passcodes', data)
}

/**
 * 撤销密码（幂等）。
 *
 * @param {object} data
 * @param {number|string} data.lockId 锁 ID
 * @param {string} data.keyboardPwd 密码
 * @returns {Promise}
 */
export function revokePasscode(data) {
  return http.post('/api/admin/lock/passcodes/revoke', data)
}

/**
 * 查门锁在线状态。
 *
 * @param {number|string} lockId 锁 ID（**必填**，无默认值）
 * @returns {Promise<{data:{lockId, status}}>} status 为 ONLINE / OFFLINE / UNKNOWN
 */
export function getLockStatus(lockId) {
  return http.get('/api/admin/lock/status', { params: { lockId } })
}

/* ==================== 进出记录 ==================== */

/**
 * 分页查开门记录。
 *
 * @param {object} [params]
 * @param {number} [params.page] 页码（用 page）
 * @param {number} [params.size] 每页条数
 * @param {number|string} [params.userId] 按用户筛
 * @param {string} [params.from] 开始时刻
 * @param {string} [params.to]   结束时刻
 * @returns {Promise<{data:{total, current, size, records}}>}
 */
export function listAccessRecords(params = {}) {
  return http.get('/api/admin/access-records', { params })
}

/**
 * 同步门锁开门记录（手动触发）。
 *
 * <p><b>不做轮询</b>：门锁云额度 30,000 次/月是硬约束，单次同步消耗 1 次调用
 * （拉取为空时再 +1 次探活）。
 *
 * @param {object} data
 * @param {number|string} data.lockId 锁 ID
 * @param {string} data.from 起始时刻
 * @param {string} data.to   截止时刻（区间跨度 ≤ 31 天）
 * @returns {Promise}
 */
export function syncAccessRecords(data) {
  return http.post('/api/admin/access-records/sync', data)
}

/**
 * 补录开门记录。
 *
 * @param {object} data
 * @param {number|string} data.lockId 锁 ID
 * @param {string} data.openTime 开门时刻
 * @param {number|string} [data.userId] 用户 ID
 * @param {number|string} [data.orderId] 订单 ID
 * @param {string} [data.passcode] 所用密码
 * @returns {Promise}
 */
export function createAccessRecord(data) {
  return http.post('/api/admin/access-records', data)
}

/**
 * 模拟开门（仅 mock 门锁 provider 下存在）。
 *
 * <p>演示用：等价于顾客在门口输入密码开了门。
 *
 * @param {object} data
 * @param {number|string} data.lockId 锁 ID
 * @param {string} data.passcode 密码
 * @param {number|string} [data.userId] 用户 ID
 * @param {number|string} [data.orderId] 订单 ID
 * @returns {Promise}
 */
export function mockOpen(data) {
  return http.post('/api/admin/access-records/mock-open', data)
}

/* ==================== 收款码 ==================== */

/**
 * 列出全部收款码（含停用的）。
 *
 * <p>{@code enabled} 为 0 的也会返回 —— 后台必须看得到停用的码，
 * 否则停用之后就再也找不回它了，而「临时停用、过阵子再开」正是最常见的用法。
 * 用户端能看到的只有启用中的，那是另一个接口（{@code api/payment.js} 的
 * {@code listPayQrs}）。
 *
 * @returns {Promise<{data:Array<{id,channel,channelLabel,name,imageUrl,enabled,sort,updatedAt}>}>}
 */
export function listAdminPayQrs() {
  return http.get('/api/admin/pay-qrs')
}

/**
 * 新增一张收款码。
 *
 * @param {object} data
 * @param {string} data.channel  WXPAY 微信 / ALIPAY 支付宝
 * @param {string} data.name     显示名，如「微信收款码」
 * @param {string} data.imageUrl 图片路径，由 {@link uploadPayQrImage} 上传得到
 * @param {number} data.enabled  1 启用 / 0 停用。⚠️ 必传：漏传后端会返回 400，
 *                               而不是替你选一个默认值（两种默认都会出错事）
 * @param {number} [data.sort]   排序值，不传按 0
 * @returns {Promise}
 */
export function createPayQr(data) {
  return http.post('/api/admin/pay-qrs', data)
}

/**
 * 修改一张收款码（全量替换）。
 *
 * <p><b>没传的字段就是清空</b>，所以提交时要把整张表单带上（除了 {@code sort}，
 * 它不传按 0 处理）。这与商品、机台的 PUT 语义一致。
 *
 * @param {number|string} id 收款码 ID
 * @param {object} data 同上
 * @returns {Promise}
 */
export function updatePayQr(id, data) {
  return http.put(`/api/admin/pay-qrs/${id}`, data)
}

/**
 * 删除一张收款码（逻辑删除）。
 *
 * <p>只是「不再出现在任何列表里」，行还留在库中 ——
 * 历史付款凭证上记着它，物理删掉就说不清那笔钱当时扫的是哪张码了。
 *
 * @param {number|string} id 收款码 ID
 * @returns {Promise}
 */
export function deletePayQr(id) {
  return http.delete(`/api/admin/pay-qrs/${id}`)
}

/**
 * 上传收款码图片（multipart）。
 *
 * <p><b>只返回图片路径，不写任何数据库</b> —— 把返回的 `imageUrl` 填进表单，
 * 随新增 / 修改一起提交。与商品封面是同一套语义。
 *
 * <p>⚠️ 图片会由前端的 {@code processImage(file, 'payqr')} 处理成
 * <b>不裁剪、不缩放、PNG 格式</b> —— 二维码转成 JPEG 的话透明底会变黑，
 * 码就扫不出来了。
 *
 * @param {File} file 图片文件。后端按【文件头】判类型（JPG / PNG / WebP）；
 *                    上限 2MB
 * @returns {Promise<{data:{imageUrl:string}}>} `data.imageUrl` 是站内相对路径
 */
export function uploadPayQrImage(file) {
  return uploadImage('/api/admin/pay-qrs/image', file)
}

/* ==================== 付款凭证复核 ==================== */

/**
 * 分页列出付款凭证。
 *
 * <p>不传 `verifyStatus` 时返回全部，排序是「有风险的 → 待复核的 → 新的」——
 * 后台默认视图就靠它：一打开先看到的应该是最需要处理的那几条。
 *
 * @param {object} params
 * @param {number} [params.page] 页码，从 1 开始
 * @param {number} [params.size] 每页条数，最多 100
 * @param {string} [params.verifyStatus] SUBMITTED 待复核 / CONFIRMED 已核对 /
 *                                       REJECTED 未通过，不传则全部
 * @returns {Promise<{data:{records:Array, total:number, ...}}>}
 *          `records` 每项含 `proofUrl`（截图）、`amount`（应付额）、
 *          `delivered`（该笔是否已交付 —— 见 {@link rejectProof}）
 */
export function listAdminProofs({ page = 1, size = 10, verifyStatus = '' } = {}) {
  return http.get('/api/admin/payment-proofs', { params: { page, size, verifyStatus } })
}

/**
 * 复核通过。
 *
 * <p>对订单与商品而言这是**纯登记**（钱在用户提交那一刻就算收到了）；
 * 对包场与月卡而言**此刻才交付** —— 邀请令牌与月卡都产生在这一步。
 *
 * @param {number|string} id 凭证 ID
 * @returns {Promise}
 */
export function confirmProof(id) {
  return http.post(`/api/admin/payment-proofs/${id}/confirm`)
}

/**
 * 复核不通过。
 *
 * <p>⚠️ **驳回不会回退已经发生的交付**：订单与商品在用户提交那刻就落账了
 * （订单已支付、库存已扣、累计消费已加），系统不做自动回退，只能人工处置。
 * 所以列表里 `delivered` 为 true 的条目，驳回之后还有一步要做 ——
 * 后台用醒目标记提示这一点。
 *
 * @param {number|string} id     凭证 ID
 * @param {string}        reason 未通过原因（必填，会展示给用户）
 * @returns {Promise}
 */
export function rejectProof(id, reason) {
  return http.post(`/api/admin/payment-proofs/${id}/reject`, { reason })
}

/* ==================== 对账（支付改造 Phase 5） ==================== */

/**
 * 上传账单并执行对账。
 *
 * <p>一次上传就是一个批次，<b>没有「先预览再确认」</b>：对账不改任何业务状态
 *（不碰订单、不碰复核结论），传错一份已经对过的账单会走「已被认领」那一支、
 * 零差异，所以不值得为它引入一个中间态。
 *
 * <p><b>不需要传渠道</b>：微信还是支付宝由表头认出来。让管理员多选一个东西，
 * 就多一个选错的机会，而选错的后果是数据错且不会报错。
 *
 * <p>响应里直接带着解析结果（账单多少笔、合计多少、覆盖哪段时间）——
 * 管理员据此就能看出自己有没有传错文件。
 *
 * @param {File} file 账单文件（微信 xlsx / 支付宝 CSV / 标准模板 CSV）
 * @returns {Promise<{data:object}>} data 是完整的批次视图
 */
export function uploadReconcileBill(file) {
  const form = new FormData()
  form.append('file', file)
  // ⚠️ 不要手写 Content-Type：axios 认出 FormData 会自己补 boundary，
  // 手写的那个字符串少了 boundary，后端解析不出任何字段
  return http.post('/api/admin/reconcile-batches', form)
}

/**
 * 分页查对账批次。
 *
 * @param {object} [params]
 * @param {number} [params.page] 页码（注意：这里用 page，不是 pageNum）
 * @param {number} [params.size] 每页条数
 * @returns {Promise<{data:{total, current, size, records}}>}
 *          `records` 每项含 `unhandledCount`（还有几条差异没处理）与
 *          `hasBillFile`（下载按钮可不可用）
 */
export function listReconcileBatches({ page = 1, size = 10 } = {}) {
  return http.get('/api/admin/reconcile-batches', { params: { page, size } })
}

/**
 * 取一个批次的详情。
 *
 * <p>比列表多一个 `diffTypeCounts`（各类差异各多少条）。
 * ⚠️ **某个类型一条都没有时那个键不存在**，取值要用 `?? 0`。
 *
 * @param {number|string} id 批次 ID
 * @returns {Promise<{data:object}>}
 */
export function getReconcileBatch(id) {
  return http.get(`/api/admin/reconcile-batches/${id}`)
}

/**
 * 分页查某个批次的差异明细。
 *
 * <p>排序由后端给：待处理的在前，同类里按紧急程度排。
 *
 * @param {number|string} batchId    批次 ID
 * @param {object}        [params]
 * @param {number}        [params.page]     页码
 * @param {number}        [params.size]     每页条数
 * @param {string}        [params.diffType] 类型筛选，空串表示不过滤
 * @param {number|null}   [params.handled]  处理状态（0 / 1），null 表示不过滤
 * @returns {Promise<{data:{total, current, size, records}}>}
 */
export function listReconcileDiffs(batchId, { page = 1, size = 20, diffType = '', handled = null } = {}) {
  return http.get(`/api/admin/reconcile-batches/${batchId}/diffs`, {
    params: { page, size, diffType, handled }
  })
}

/**
 * 标记一条差异已处理。
 *
 * <p><b>这个动作不改任何业务数据</b> —— 不改订单状态、不代提交凭证、不撤批次。
 * 差异只是给人看的线索，钱怎么处置由人决定。
 *
 * @param {number|string} id   差异 ID
 * @param {string}        note 处理备注，<b>选填</b>
 *        （它是管理员给自己的备忘，不是给顾客的结论，所以不强制填）
 * @returns {Promise}
 */
export function handleReconcileDiff(id, note) {
  return http.post(`/api/admin/reconcile-diffs/${id}/handle`, { note })
}

/**
 * 下载账单原文件。
 *
 * <p>⚠️ <b>不能用 `<a href="/api/admin/reconcile-batches/{id}/file">` 打开</b>：
 * 那样不会带上 `Authorization` 头，拿到的是 401。而且账单是敏感数据，
 * 服务端刻意把它放在公开的 `/uploads/` 之外，只认这个带鉴权的接口。
 *
 * <p>所以走 blob：拿到数据后在内存里拼一个临时地址触发下载，用完立刻释放 ——
 * 不释放的话每点一次下载就多占一份文件大小的内存，直到刷新页面才回收。
 *
 * @param {number|string} id       批次 ID
 * @param {string}        fileName 下载时的文件名（后端也给了，这里用列表里那条）
 * @returns {Promise<void>}
 */
export async function downloadReconcileBill(id, fileName = '账单') {
  const resp = await http.get(`/api/admin/reconcile-batches/${id}/file`, {
    responseType: 'blob'
  })
  const url = URL.createObjectURL(resp.data)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    link.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}
