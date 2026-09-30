/**
 * 格式化工具：时间、金额、时长。
 *
 * <p>这里集中了前端最容易踩的两个坑，两个都【不报任何错】，只在特定环境下显示错误：
 *
 * <p><b>坑一：不要对后端返回的时间字符串直接 {@code new Date()}</b>。
 * 后端给的是 {@code "2026-09-30 19:00:00"}（不带时区的本地时间串），
 * 而 {@code new Date("2026-09-30 19:00:00")} 在 Safari / iOS 上返回 {@code Invalid Date},
 * Chrome 却按本地时区正确解析 —— 同一份代码在安卓上正常、在 iPhone 上白屏。
 * 本项目要做真机调试，iOS 必然会碰到。所以一律【字符串切分后手工构造 Date】。
 *
 * <p><b>坑二：金额不要用 {@code parseFloat().toFixed()}</b>。
 * 那会引入浮点误差（{@code 0.1 + 0.2 = 0.30000000000000004}），
 * 而钱是差一分都要说清楚的。这里只做【字符串层面的补零与截断】，
 * 全程不把金额变成浮点数。
 */

/**
 * 把后端的本地时间串解析成 Date 对象（按浏览器本地时区）。
 *
 * @param {string} str 形如 "2026-09-30 19:00:00" 或 "2026-09-30T19:00:00"
 * @returns {Date|null} 解析失败返回 null（不抛异常 —— 调用方多半只是想展示，
 *                      为了一个畸形字符串把整个页面搞崩不值得）
 */
export function parseDateTime(str) {
  if (!str) return null
  const m = String(str).match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?/)
  if (!m) return null
  return new Date(
    Number(m[1]),
    Number(m[2]) - 1, // ⚠️ 月份从 0 开始，减 1 是必须的
    Number(m[3]),
    Number(m[4]),
    Number(m[5]),
    Number(m[6] || 0)
  )
}

/** 两位补零。 */
function pad2(n) {
  return String(n).padStart(2, '0')
}

/**
 * 转成后端那套格式（"2026-09-30 19:00:00"）。
 *
 * @param {Date|string} value 日期对象或已是该格式的字符串
 * @returns {string} 空值返回空串
 */
export function formatDateTime(value) {
  if (!value) return ''
  if (typeof value === 'string') return value
  return (
    `${value.getFullYear()}-${pad2(value.getMonth() + 1)}-${pad2(value.getDate())} ` +
    `${pad2(value.getHours())}:${pad2(value.getMinutes())}:${pad2(value.getSeconds())}`
  )
}

/**
 * 后端时间串 → {@code <input type="datetime-local">} 的 value。
 *
 * <p>用于把一条已有记录回填进表单（如包场改期）。
 *
 * @param {string} str 形如 "2026-09-30 14:00:00"
 * @returns {string} 形如 "2026-09-30T14:00"；无法解析时返回空串
 */
export function toInputDateTime(str) {
  if (!str) return ''
  const m = String(str).match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/)
  if (!m) return ''
  return `${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}`
}

/**
 * {@code <input type="datetime-local">} 的 value → 后端要的时间串。
 *
 * <p>⚠️ <b>这一对函数不是「顺手加的」</b>：后端 {@code JacksonConfig} 里
 * {@code LocalDateTime} 的反序列化只认 {@code yyyy-MM-dd HH:mm:ss} 一种格式，
 * 而浏览器控件给出的是带 {@code T} 分隔、<b>且不带秒</b>的
 * {@code 2026-09-30T14:00} —— 直接提交会 400，而返回的报错文案只会说
 * 「参数格式不正确」，不会告诉你差在哪。本项目此前只展示时间、从不发送时间，
 * 后台的包场排期是**第一处由前端发出时间**的地方。
 *
 * @param {string} str 形如 "2026-09-30T14:00"（带秒也行）
 * @returns {string} 形如 "2026-09-30 14:00:00"；无法解析时返回空串
 */
export function fromInputDateTime(str) {
  if (!str) return ''
  const m = String(str).match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?/)
  if (!m) return ''
  return `${m[1]}-${m[2]}-${m[3]} ${m[4]}:${m[5]}:${m[6] || '00'}`
}

/**
 * 只取日期部分（"2026-09-30"）。
 *
 * @param {string} str 后端的时间串
 * @returns {string} 空值返回空串
 */
export function formatDate(str) {
  if (!str) return ''
  return String(str).slice(0, 10)
}

/**
 * 只取时分（"19:00"）。
 *
 * @param {string} str 后端的时间串
 * @returns {string} 空值返回空串
 */
export function formatTime(str) {
  if (!str) return ''
  return String(str).slice(11, 16)
}

/**
 * 短日期（"10/5"），用于包场时间表这类空间紧凑的地方。
 *
 * @param {string} str 后端的时间串
 * @returns {string} 空值返回空串
 */
export function formatShortDate(str) {
  if (!str) return ''
  const s = String(str)
  const month = Number(s.slice(5, 7))
  const day = Number(s.slice(8, 10))
  return `${month}/${day}`
}

/**
 * 金额格式化：保留两位小数，全程不经过浮点数。
 *
 * @param {number|string} value 后端返回的金额（BigDecimal 序列化成 number 时会丢尾零，
 *                              例如 8.00 变成 8，所以展示前必须补回来）
 * @returns {string} 形如 "8.00"；空值返回 "0.00"
 */
export function formatMoney(value) {
  if (value === null || value === undefined || value === '') return '0.00'
  const s = String(value).trim()
  if (!/^-?\d+(\.\d+)?$/.test(s)) return '0.00'

  const negative = s.startsWith('-')
  const body = negative ? s.slice(1) : s
  const [intPart, fracPart = ''] = body.split('.')
  // 补零到两位，超过两位则截断（后端是 DECIMAL(10,2)，正常情况下不会有第三位）
  const frac = (fracPart + '00').slice(0, 2)
  return `${negative ? '-' : ''}${intPart}.${frac}`
}

/**
 * 带 ¥ 前缀的金额。
 *
 * @param {number|string} value 金额
 * @returns {string} 形如 "¥8.00"
 */
export function formatYuan(value) {
  return `¥${formatMoney(value)}`
}

/**
 * 时长格式化：分钟数转「X 小时 Y 分钟」。
 *
 * <p>用「分钟」而不是简写的「分」—— 简写在中文里也能读成「分数」，
 * 而这里到处都是时长场景（在店 4 分 / 计费 1 小时 12 分），歧义没必要留。
 *
 * @param {number} minutes 分钟数
 * @returns {string} 不足 1 小时只显示分钟，为 0 显示「0 分钟」
 */
export function formatDuration(minutes) {
  const m = Number(minutes) || 0
  if (m < 60) return `${m} 分钟`
  const h = Math.floor(m / 60)
  const rest = m % 60
  return rest === 0 ? `${h} 小时` : `${h} 小时 ${rest} 分钟`
}

/**
 * 倒计时格式化：秒数转 "mm:ss"（超过一小时转 "H:mm:ss"）。
 *
 * <p>用在「还有 12:30 进入下一档」。**秒数来自后端**而不是前端拿绝对时刻去减 ——
 * 绝对时刻会带上客户端与服务端的时钟偏差，用户手机时间不准时倒计时会算出荒谬的值。
 *
 * @param {number} seconds 剩余秒数
 * @returns {string} 形如 "12:30"
 */
export function formatCountdown(seconds) {
  const total = Math.max(0, Math.floor(Number(seconds) || 0))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  return h > 0 ? `${h}:${pad2(m)}:${pad2(s)}` : `${pad2(m)}:${pad2(s)}`
}

/**
 * 相对时间（「3 分钟前」「昨天 19:00」），用于公告流的每条消息。
 *
 * <p>判断基准是浏览器本地时间 —— 这里要的只是「读起来大概多久之前」，
 * 差几秒无所谓，与计费、倒计时那种要求精确的场景不同。
 *
 * @param {string} str 后端的时间串
 * @returns {string} 空值返回空串
 */
export function relativeTime(str) {
  const date = parseDateTime(str)
  if (!date) return ''
  const diffMs = Date.now() - date.getTime()
  const diffMin = Math.floor(diffMs / 60000)

  if (diffMin < 1) return '刚刚'
  if (diffMin < 60) return `${diffMin} 分钟前`

  const diffHour = Math.floor(diffMin / 60)
  if (diffHour < 24) return `${diffHour} 小时前`

  const diffDay = Math.floor(diffHour / 24)
  if (diffDay === 1) return `昨天 ${formatTime(str)}`
  if (diffDay < 7) return `${diffDay} 天前`

  return formatDate(str)
}
