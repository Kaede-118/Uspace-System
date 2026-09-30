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

/**
 * 包场状态（后端 {@code BookingStatus}）。
 *
 * <p>⚠️ {@code BookingVo} 只有状态名、<b>没有 statusLabel</b>（订单有，包场没有），
 * 所以包场的中文只能在前端映射。用户端的包场列表与后台的包场页共用这一份。
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
 * 商品购买单状态（后端 {@code ProductOrderStatus}），三态。
 *
 * <p>没有「已交付」—— 无人值守店里没有店员，付了钱自己取，这是明知的取舍。
 * 与包场同理：{@code label} 供筛选下拉使用，行内展示用后端的 {@code statusLabel}。
 */
export const PRODUCT_ORDER_STATUS = [
  { value: 'PENDING_PAYMENT', label: '待支付', cls: 'tag tag-warning' },
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
