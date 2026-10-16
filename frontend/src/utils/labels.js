/**
 * 状态枚举的中文与配色映射。
 *
 * <p><b>为什么要有这个文件</b>：同一种状态在用户端与运营后台都要画成一个小胶囊标签，
 * 两处各写一份映射表的话，改文案时必然只改一处 —— 而两处不一致【不会有任何报错】，
 * 只是同一个「维护中」在顾客手机上叫一个名字、在后台叫另一个。
 *
 * <p>⚠️ <b>中文名有两个来源，用的时候分清楚</b>：
 * <ul>
 *   <li><b>后端给了的，一律用后端的</b> —— 机台的 {@code statusLabel}、
 *       商品购买单的 {@code statusLabel}。这是本项目的既定做法：
 *       中文对照表只维护一份，放在枚举旁边（{@code DeviceStatus#labelOf}）</li>
 *   <li><b>后端没给的，才用这里的 {@code label}</b> —— 只有两种情况：
 *       包场状态（{@code BookingVo} 压根没有 statusLabel 字段），
 *       以及机台三态/商品单三态的【下拉选项列表】（后端没有「全部状态」的字典接口，
 *       而选项要三个都齐全，不能靠数据里恰好出现过哪几种来推导）</li>
 * </ul>
 * 也就是说：<b>这里的 {@code label} 只用于「构造选项」，行内展示优先用后端字段</b>。
 * 改了后端枚举里的中文，记得回来看一眼这里的三张表。
 *
 * <p>配色没有第二个来源，全在这里 —— 后端的 VO 不带颜色，也不该带。
 */
import { parseDateTime } from './format'

/**
 * 某段时间此刻处于哪个阶段：未开始 / 进行中 / 已结束。
 *
 * <p>用在同一件事的三个地方：运营后台「门店」页的停业与免费时段列表，
 * 以及用户端首页的「免费活动」卡。
 *
 * <p>⚠️ <b>它只是展示，不是判定</b>：真正的「此刻免不免单」在服务端的计费侧，
 * 客户端时钟不可信 —— 这里算出来的东西不参与任何金额计算，
 * 也不该被拿去决定「要不要收钱」。
 *
 * <p>包场时间表的 {@code ongoing} 之所以由后端给，是因为它叠了一条业务规则
 *（比包场开始早 15 分钟就亮，好让散客别在开场前进来打不完一局）——
 * 那是运营口径，前端算不了；而本函数的判定就是纯粹的「起止之间」，
 * 没有第二处定义，也就不会再出现「两个地方各有一套规矩」的问题。
 *
 * <p>区间是<b>半开区间</b> {@code [start, end)}，与后端同一口径：
 * 结束那一刻就恢复收费，标签也要跟着翻。
 *
 * @param {string} startAt 开始时刻，后端格式 yyyy-MM-dd HH:mm:ss
 * @param {string} endAt   结束时刻，同上
 * @returns {{label: string, cls: string, ongoing: boolean}}
 *          阶段名、配色、以及「是否正在进行」。带最后那个布尔是因为它最常被单独用到
 *          （首页与计费规则页都拿它决定「显示进行中徽章还是显示日期」），
 *          调用方写 {@code .ongoing} 比每次都去比一次字符串可靠。
 *          时间不可解析时返回全空，{@code ongoing} 为 false。
 */
export function periodPhaseOf(startAt, endAt) {
  const start = parseDateTime(startAt)
  const end = parseDateTime(endAt)
  // 认不出时间就给一个空标签，不猜 —— 猜错的表现是一个言之凿凿的错标签
  if (!start || !end) return { label: '', cls: '', ongoing: false }
  const now = Date.now()
  if (now < start.getTime()) return { label: '未开始', cls: 'tag', ongoing: false }
  if (now < end.getTime()) return { label: '进行中', cls: 'tag tag-success', ongoing: true }
  return { label: '已结束', cls: 'tag', ongoing: false }
}

/**
 * 包场状态（后端 {@code BookingStatus}）。
 *
 * <p>⚠️ <b>2026-10-16 起后端 {@code BookingVo} 也带 {@code statusLabel} 了</b>
 * （订单流水页的「包场」tab 用它，与月卡、商品一样「后端给的一律用后端的」）。
 * 本表留下的用途有两个：<b>构造筛选下拉的选项</b>（后端没有状态字典接口），
 * 以及包场管理页 / 用户端包场列表的<b>配色</b> —— 那两处的行内中文仍是本表的
 * {@code label}，文案与 {@code BookingStatus#labelOf} 逐字一致，改动时要一起改。
 *
 * <p>五种状态：待付款 / 已付款 / 已取消 / <b>已退款</b> / 已结束。
 * 「已付款」与「已结束」都是业务上的正常终态，不是异常。
 *
 * <p>⚠️ <b>「已取消」与「已退款」是两个状态，不要合并</b>：
 * 前者是「这单没成」（还没付钱就取消了），后者是「钱收过又退回去了」。
 * 在账上这是两回事 —— 合并成一个的话，对账时分不清那场有没有产生过资金流水。
 */
export const BOOKING_STATUS = {
  PENDING_PAYMENT: { label: '待付款', cls: 'tag tag-warning' },
  PAID: { label: '已付款', cls: 'tag tag-success' },
  CANCELLED: { label: '已取消', cls: 'tag' },
  REFUNDED: { label: '已退款', cls: 'tag tag-danger' },
  CLOSED: { label: '已结束', cls: 'tag' }
}

/**
 * 取包场状态的中文与配色。
 *
 * <p>认不出的状态名<b>原样显示</b>（与后端 {@code labelOf} 的取向一致）——
 * 伪装成一个正常标签反而会把异常数据藏起来。
 *
 * @param {string} status 状态名
 * @returns {{label: string, cls: string}}
 */
export function bookingStatusOf(status) {
  return BOOKING_STATUS[status] || { label: status || '—', cls: 'tag' }
}

/**
 * 机台状况（后端 {@code DeviceStatus}），三态。
 *
 * <p>用数组而不是对象：后台的新增/编辑表单要直接拿它渲染下拉选项，
 * 顺序即「从好到坏」，与后端枚举的声明顺序一致。
 *
 * <p>⚠️ <b>行内展示优先用后端给的 {@code statusLabel}</b>，这里的 {@code label}
 * 是给下拉选项用的（见文件头那段说明）。
 * 三态按「顾客还能不能玩」划分：「待维护」是「还能玩，但有小毛病」，
 * 「维护中」是「别碰」—— 合并成一个「异常」态，顾客就不知道能不能上手了。
 */
export const DEVICE_STATUS = [
  { value: 'NORMAL', label: '良好', cls: 'tag tag-success' },
  { value: 'NEEDS_REPAIR', label: '待维护', cls: 'tag tag-warning' },
  { value: 'MAINTAINING', label: '维护中', cls: 'tag tag-danger' }
]

/**
 * 取机台状况的标签配色。
 *
 * <p>认不出的取值退回默认灰标签，不抛异常 —— 状况列是 {@code VARCHAR}，
 * 手工改过库的数据可能落在枚举之外，那种数据应该显示出来而不是让页面崩掉。
 *
 * @param {string} status 状况名
 * @returns {string} 形如 "tag tag-success"
 */
export function deviceStatusCls(status) {
  const item = DEVICE_STATUS.find((s) => s.value === status)
  return item ? item.cls : 'tag'
}

/**
 * 卡类型（后端 {@code MonthlyCardType}），三项。
 *
 * <p>用户端在店名册那份数据由后端直接给出中文（{@code cardTypeLabel}），
 * 而运营后台的用户列表只给 code —— 那一列要参与筛选与排序，多传一个
 * 纯展示字段会让「哪些是数据、哪些是渲染」变得含混。所以后台这一侧由前端映射。
 *
 * <p>⚠️ <b>STAFF 卡不在商城的在售列表里</b>（后端 {@code cardTypes()} 过滤掉了它），
 * 但本表是「所有卡种的中文名」，所以三项都要有 —— 后台的用户筛选、
 * 卡包、后台开卡弹层都要能显示STAFF 卡。
 */
export const MONTHLY_CARD_TYPES = [
  { value: 'ALL_DAY', label: '全天月卡', cls: 'tag tag-success' },
  { value: 'NIGHT', label: '夜间月卡', cls: 'tag' },
  { value: 'STAFF', label: 'STAFF 卡', cls: 'tag tag-warning' }
]

/**
 * 月卡类型的中文名。
 *
 * @param {string} cardType 卡种 code；为空表示没有生效卡
 * @returns {string} 「全天月卡」/「夜间月卡」/「STAFF 卡」/「无」
 */
export function cardTypeLabel(cardType) {
  const item = MONTHLY_CARD_TYPES.find((c) => c.value === cardType)
  return item ? item.label : '无'
}

/**
 * 月卡状态的标签配色（后端 {@code MonthlyCardStatus}）。
 *
 * <p>中文名不在这里 —— 后端 {@code MonthlyCardVo} 已给 {@code statusLabel}，
 * 按「后端给了的一律用后端的」那条规矩走（见文件头）。这里只管配色。
 *
 * @param {string} status 状态名 ACTIVE / EXPIRED / REFUNDED / REVOKED
 * @returns {string} 形如 "tag tag-success"
 */
export function cardStatusCls(status) {
  switch (status) {
    case 'ACTIVE':
      return 'tag tag-success'
    case 'REFUNDED':
    case 'REVOKED':
      return 'tag tag-danger'
    default:
      return 'tag'
  }
}

/**
 * 月卡购买单状态（后端 {@code CardOrderStatus}），三态。
 *
 * <p>⚠️ <b>它与 {@link cardStatusCls} 不是一回事</b>：那条讲的是「卡的状态」
 * （生效中 / 已过期 / 已退款 / 已停用），这条讲的是「购买单的状态」
 * （待支付 / 已支付 / 已关闭）—— 未付款的意向还不是卡。
 * 拿卡状态去翻购买单状态不会有任何报错，只会把 PENDING_PAYMENT 原样印在界面上。
 *
 * <p>与商品购买单同一条规矩：{@code label} 供筛选下拉使用，
 * 行内展示用后端 {@code MonthlyCardOrderVo} 给的 {@code statusLabel}
 *（「后端给了的一律用后端的」那条规矩）。
 */
export const MONTHLY_CARD_ORDER_STATUS = [
  { value: 'PENDING_PAYMENT', label: '待支付', cls: 'tag tag-warning' },
  { value: 'PAID', label: '已支付', cls: 'tag tag-success' },
  { value: 'CLOSED', label: '已关闭', cls: 'tag' }
]

/**
 * 取月卡购买单状态的标签配色。
 *
 * <p>认不出的取值退回默认灰标签，不抛异常（与其他几个 {@code xxxCls} 同一条规矩）——
 * 状态列是 VARCHAR，手工改过库的数据可能落在枚举之外。
 *
 * @param {string} status 状态名
 * @returns {string} 形如 "tag tag-success"
 */
export function cardOrderStatusCls(status) {
  const item = MONTHLY_CARD_ORDER_STATUS.find((s) => s.value === status)
  return item ? item.cls : 'tag'
}

/**
 * 订单状态（后端 {@code OrderStatus}），四态。
 *
 * <p>⚠️ <b>2026-10-10 从三处局部实现里收拢来的</b>：用户端的订单卡片与订单详情页
 * 各有一份 {@code statusClass}，而后台订单列表还要第三份 —— 三份迟早分岔，
 * 且分岔不报错（同一个 REJECTED 在一处红、另一处绿）。这正是本文件存在的理由。
 *
 * <p>顺带修掉一个真实的分岔：{@code OrderCard} 原来只判 IN_USE / PENDING_PAYMENT，
 * 其余一律绿色 —— 于是「凭证未通过」在列表里是绿的、点进详情是红的。
 *
 * <p>{@code label} 供后台筛选下拉使用，行内展示用后端给的 {@code statusText}
 *（「后端给了的一律用后端的」那条规矩）。
 */
export const ORDER_STATUS = [
  { value: 'IN_USE', label: '使用中', cls: 'tag' },
  { value: 'PENDING_PAYMENT', label: '待支付', cls: 'tag tag-warning' },
  { value: 'PAID', label: '已支付', cls: 'tag tag-success' },
  { value: 'REJECTED', label: '凭证未通过', cls: 'tag tag-danger' }
]

/**
 * 取订单状态的标签配色。
 *
 * <p>认不出的取值退回默认灰标签，不抛异常（与其他几个 {@code xxxCls} 同一条规矩）——
 * 状态列是 VARCHAR，可能存着枚举之外的字符串，显示出来好过整页白屏。
 *
 * @param {string} status 状态名
 * @returns {string} 形如 "tag tag-warning"
 */
export function orderStatusCls(status) {
  const item = ORDER_STATUS.find((s) => s.value === status)
  return item ? item.cls : 'tag'
}

/**
 * 商品购买单状态（后端 {@code ProductOrderStatus}），三态。
 *
 * <p>没有「已交付」—— 无人值守店里没有店员，付了钱自己取，这是明知的取舍。
 * 与包场同理：{@code label} 供筛选下拉使用，行内展示用后端的 {@code statusLabel}。
 */
export const PRODUCT_ORDER_STATUS = [
  { value: 'PENDING_PAYMENT', label: '待支付', cls: 'tag tag-warning' },
  { value: 'REJECTED', label: '凭证未通过', cls: 'tag tag-danger' },
  { value: 'PAID', label: '已支付', cls: 'tag tag-success' },
  { value: 'CLOSED', label: '已关闭', cls: 'tag' }
]

/**
 * 取商品购买单状态的标签配色。
 *
 * @param {string} status 状态名
 * @returns {string} 形如 "tag tag-success"
 */
export function productOrderStatusCls(status) {
  const item = PRODUCT_ORDER_STATUS.find((s) => s.value === status)
  return item ? item.cls : 'tag'
}

/**
 * 收款账号渠道（后端 {@code PayQrChannel}），两项。
 *
 * <p>⚠️ <b>这不是支付通道</b>。支付通道是 {@code api/payment.js} 的 {@code Channel}
 *（微信内 JSAPI / 微信外 H5 / 支付宝 WAP / 扫码转账），描述的是「这笔钱走哪条路」；
 * 这里描述的是「这笔钱进了哪个收款账号」。两者刻意用两套取值 ——
 * 「支付宝收款码」与「支付宝手机网站支付」压根不是一回事：前者只能靠用户
 * 上传的截图对账（也正是本系统 12 月投产的唯一方式），后者有平台流水可查。
 *
 * <p>与 {@code DEVICE_STATUS} 一样，本表<b>只用于构造后台的下拉选项</b>；
 * 列表里展示渠道名用后端给的 {@code channelLabel} —— 那是
 * 「后端给了的一律用后端的」那条规矩。
 */
export const PAY_QR_CHANNELS = [
  { value: 'WXPAY', label: '微信' },
  { value: 'ALIPAY', label: '支付宝' }
]

/**
 * 对账差异类型（后端 {@code ReconcileDiffType}），六类。
 *
 * <p><b>数组顺序即优先级</b>，与后端的声明顺序、
 * 以及列表 SQL 里 {@code ORDER BY FIELD(diff_type, ...)} 的字面量三处对齐。
 * 这里只用于构造筛选下拉，列表本身的排序由后端给 ——
 * 但下拉里的先后跟着走一遍，管理员看到的顺序才与列表一致。
 *
 * <p>配色的分档不是随口定的：<b>两类红、两类黄、两类灰</b>。
 * 红的是「系统当前的结论与事实相反」与「疑似作弊」，黄的是「两边对不上、要查」，
 * 灰的（账单无对应凭证 / 未填流水号）多半只是顾客没交凭证或没填单号 ——
 * 常见、不必紧张，但不该被藏起来。
 *
 * <p>标签与说明都由后端给（{@code diffTypeLabel} / {@code diffTypeHint}），
 * 本表只提供筛选下拉的选项与配色。
 */
export const RECONCILE_DIFF_TYPES = [
  { value: 'REJECTED_IN_BILL', label: '驳回后有款', cls: 'tag tag-danger' },
  { value: 'DUPLICATE_CLAIM', label: '一单多认领', cls: 'tag tag-danger' },
  { value: 'PROOF_ONLY', label: '凭证无对应账单', cls: 'tag tag-warning' },
  { value: 'AMOUNT_MISMATCH', label: '金额不符', cls: 'tag tag-warning' },
  { value: 'BILL_ONLY', label: '账单无对应凭证', cls: 'tag' },
  { value: 'NO_PAYMENT_NO', label: '未填流水号', cls: 'tag' }
]

/**
 * 取差异类型的标签配色。
 *
 * <p>与其余几个 {@code xxxCls} 同一条规矩：<b>认不出的取值退回默认配色，
 * 不抛异常</b> —— 库列是 VARCHAR，可能存着枚举之外的字符串，
 * 而「多一个灰标签」远好过整页白屏。
 *
 * @param {string} type 类型名
 * @returns {string} 形如 "tag tag-danger"
 */
export function reconcileDiffTypeCls(type) {
  const item = RECONCILE_DIFF_TYPES.find((t) => t.value === type)
  return item ? item.cls : 'tag'
}

/* ---------- 游玩偏好（设备类型）--------- */

/**
 * 把偏好的逗号分隔 code 串转成中文名数组。
 *
 * <p><b>中文名的唯一来源是设备类型字典</b>（{@code GET /api/devices/types}）——
 * 前端不再自建一份对照表：增删机台类型时只改后端一处，
 * 而前端若自己也存一张表，「字典加了新类型、前端那张忘了跟」不会有任何报错，
 * 只在那一种类型的用户卡片上静默显示成 code。
 *
 * <p>两级回退：字典里查得到就用中文名，查不到<b>原样显示 code</b> ——
 * 与 {@code bookingStatusOf}「认不出的取值原样显示」是同一条取向：
 * 回落到一个通用词等于把问题盖住，而露出 code 至少看得出「这里不对劲」。
 *
 * <p>这段逻辑原本在「在店用户」与「我的」两个页面各写了一份，
 * 抽到这里是因为<b>两处各写一份迟早分岔</b>，而分岔的表现只是两边文案不同，
 * 不会有任何报错。
 *
 * @param {string} preference 逗号分隔的偏好 code，如 "ONGEKI,MAIMAI"；可为空
 * @param {Object} typeMap    code → 中文名的字典（来自 GET /api/devices/types）
 * @returns {string[]} 中文名数组；没设偏好时返回空数组
 */
export function parsePreference(preference) {
  if (!preference) return []
  return String(preference)
    .split(',')
    .map((code) => code.trim())
    .filter(Boolean)
}

/**
 * 把 code 数组拼回逗号串；一个都没选时返回 {@code null}（= 清空该项）。
 *
 * <p>与 {@link parsePreference} 成对：两个页面的「读出来 → 改 → 存回去」
 * 都走这一对函数，免得一处按数组存、另一处按字符串存，接缝上静默丢数据
 * （{@code PUT} 是全量替换语义，传 null 即清空）。
 *
 * @param {string[]} codes 选中的 code 数组
 * @returns {string|null} 形如 "ONGEKI,MAIMAI"；空数组时为 null
 */
export function joinPreference(codes) {
  return Array.isArray(codes) && codes.length ? codes.join(',') : null
}

/**
 * 把偏好的逗号串翻译成中文名数组。
 *
 * <p>用 {@link parsePreference} 拆串，保证「拆」只有一处实现 ——
 * 三个调用点（在店名册、我的、后台用户列表）看到的切分规则必须一模一样。
 *
 * @param {string} preference 逗号分隔的偏好 code，如 "ONGEKI,MAIMAI"；可为空
 * @param {Object} typeMap    code → 中文名的字典（来自 GET /api/devices/types）
 * @returns {string[]} 中文名数组；没设偏好时返回空数组
 */
export function preferenceLabels(preference, typeMap = {}) {
  return parsePreference(preference).map((code) => typeMap[code] || code)
}
