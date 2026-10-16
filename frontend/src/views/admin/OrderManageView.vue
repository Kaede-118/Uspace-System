<script setup>
/**
 * 订单流水（运营后台）：<b>计时订单</b>、<b>商品订单</b>、<b>月卡</b>与<b>包场</b>四条流水。
 *
 * <p><b>2026-10-16 合并成一页</b>：商品订单原先是「商品」页的第二个 tab，
 * 现在搬到这里与计时订单并列 —— 两者回答的是同一个问题「谁、什么时候、花了多少」，
 * 管理员比对两条流水时不必在两个页面之间来回切；商品页从此只管货架维护。
 *
 * <p><b>同一天又加了「月卡」与「包场」两条</b>：它们原先分别散在「用户」页的月卡弹层
 * 与「包场」页 —— 那两处回答的是「这份权益现在怎么样」，而不是「这笔钱什么时候收的」。
 * 收款流水凑在一页，对账时不必开四个页面。
 * <b>它们只有列表、没有导出</b>：导出类型（TIMING_ORDER / PRODUCT_ORDER / ALL_ORDERS）
 * 是后端按单据形状定制的，月卡与包场还没有对应的导出实现 —— 宁可没有按钮，
 * 也不给一个点了导出却是别的东西的按钮。
 *
 * <p><b>为什么是几个 tab、而不是合成一张表</b>：几条流水的字段几乎没有一个重合
 * （一边是「在店多久」，一边是「买了什么 × 几件」，一边是「哪张卡、付没付成」），
 * 筛选能力也不一样 —— 计时订单能按时间范围与「只看人工调整」筛，商品订单只有状态与用户，
 * 包场只有后端定义的两档视图。硬凑成一张表，一半的列会永远空着，
 * 筛选栏还得为几套口径各解释一遍。
 *
 * <p><b>每个 tab 各有自己的筛选与分页</b>，互不影响：来回切换时各自停在上次
 * 看到的位置，来回比对不必重填一遍条件。
 *
 * <p><b>「包场单」标记为什么值得显示</b>：命中包场的订单会剪掉那段计费时长
 * （账单上「时长」明显短于「在店时长」），流水上标出来，管理员才不必
 * 逐单点进详情去查「为什么这单这么便宜 / 时长这么短」。
 *
 * <p><b>金额上带「计价 → 实付」与减免明细</b>（2026-10-16 加）——
 * 有优惠的单子会多出一行「计价 ¥40.00 实付 ¥35.00」与
 * 「月度优惠 / 月卡抵扣 / 活动减免」各是多少。原先流水上只有一个实收金额，
 * 管理员看到月卡用户的 ¥0.00 或 ¥3.50 只能靠猜：是月卡免的、活动免的，
 * 还是月度优惠价？三个口径在后端是三列，在这里就摊成三行。
 *
 * <p><b>点任意一单进订单详情</b>（2026-10-16 加）——
 * {@code /admin/orders/:id}，展示主体与用户端订单详情页是同一个组件
 * （{@code OrderDetailContent}），管理员看到的账单结构与顾客看到的逐字一致。
 *
 * <p><b>卡片上印用户 ID，而且能点</b>（2026-10-16 加）—— 筛选栏是按「用户 ID」查的，
 * 而管理员背不下每个人的 ID、昵称又可能重名；点那个 ID 就地按他筛一遍，
 * 省掉「看一眼、回来手抄一串数字」那一步。
 *
 * <p><b>筛选与页码会写进 URL 的 query</b>（2026-10-16 加）——
 * 从详情页返回时按原条件复原，连看几单不必每次重填筛选。
 * 顺带得到两个好处：刷新不丢筛选、把 URL 发给同事等于把「我看的那一屏」发过去。
 *
 * <p><b>人工调整时长的入口在计时订单每一行的右侧</b>（2026-10-16 加）——
 * 只给未了结的订单（使用中 / 待支付 / 凭证未通过）显示：已支付的单调整必然改金额，
 * 改完要牵出退款，后端会拒（{@code ORDER_NOT_ADJUSTABLE}）。
 *
 * <p>⚠️ <b>各接口的分页参数不一样</b>（后端如此，不是笔误）：
 * 计时订单与包场用 {@code page} / {@code size}，
 * 商品订单与月卡购买单用 {@code pageNum} / {@code pageSize}。
 * 传错不会报错，只会永远返回第一页 —— 表现为「翻页没反应」。
 *
 * <p>⚠️ <b>商品、月卡、包场三张卡片都不跳详情</b>：订单详情那一页讲的是时长与分段账单，
 * 对它们不是一回事；这几张卡片上的字段已经写全了。
 *
 * <p>⚠️ <b>包场流水没有「按用户筛」</b>：{@code GET /api/admin/bookings} 只支持
 * {@code scope} 这一个筛选参数（后端如此），卡片上那枚用户 ID 因此<b>不可点</b> ——
 * 点了也没有接口能按它筛，做成能点的反而误导。
 */
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  listOrders,
  listAdminProductOrders,
  listCardOrders,
  listBookings,
  adjustOrder,
  createExport,
  downloadExport,
  getFinanceSummary
} from '@/api/admin'
import { toastError, toastSuccess } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import {
  formatMoney,
  formatDate,
  formatTime,
  formatDateTime,
  formatDuration,
  parseDateTime,
  toInputDateTime,
  fromInputDateTime
} from '@/utils/format'
import {
  ORDER_STATUS,
  orderStatusCls,
  PRODUCT_ORDER_STATUS,
  productOrderStatusCls,
  MONTHLY_CARD_ORDER_STATUS,
  cardOrderStatusCls,
  MONTHLY_CARD_TYPES,
  BOOKING_STATUS
} from '@/utils/labels'
import AdminPager from '@/components/AdminPager.vue'
import AdminSheet from '@/components/AdminSheet.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

/** 每页条数。⚠️ 计时订单用 page + size，商品订单用 pageNum + pageSize（后端两套口径） */
const PAGE_SIZE = 10

const route = useRoute()
const router = useRouter()

/**
 * 当前 tab：timing 计时订单 / product 商品订单 / card 月卡 / booking 包场。
 *
 * <p>四个值同时也是 URL 上 {@code type} 参数的取值（计时订单不写，保持默认 URL 干净）。
 */
const type = ref('timing')

/**
 * 包场的 {@code scope} 筛选选项。
 *
 * <p><b>后端只给两档视图、不给逐状态筛选</b>（{@code GET /api/admin/bookings} 的
 * {@code scope} 要么 active、要么 void），所以这里照它的口径列两项。
 * 中文由前端写：它不是「某个状态的中文名」，而是后端两个视图的说明
 *（{@code active} = 已生效 + 待付款，{@code void} = 已取消 + 已退款）——
 * 后端没有对应的字典接口，而选项必须齐全，不能靠数据里恰好出现过哪几种推导。
 */
const BOOKING_SCOPES = [
  { value: 'active', label: '已生效与待付款' },
  { value: 'void', label: '已取消与已退款' }
]

/* ==================== tab 一：计时订单 ==================== */

const page = ref(1)
const total = ref(0)
const records = ref([])
const loading = ref(true)

/**
 * 计时订单的筛选条件。
 *
 * <p>⚠️ 空串一律<b>不发给后端</b>（组装参数时整个字段不传），而不是发空串：
 * 后端对 {@code status} 这类字段按值判断，发空串会被当成一个
 * 「值为空」的筛选条件，查出一片空白。
 */
const filters = ref({
  status: '',
  userId: '',
  from: '',
  to: '',
  adjustedOnly: false
})

/**
 * 拉取计时订单的当前页。
 *
 * <p>页码可能越界（改完筛选条件后停在了一个不存在的页上），那时列表空白
 * 却没有任何报错 —— 与「本来就没有数据」长得一样。所以拿到总数后夹一次。
 */
async function loadTiming() {
  loading.value = true
  // URL 上的 query 与「正在查的条件」保持一致，见 syncQuery 的说明
  syncQuery()
  try {
    const params = { page: page.value, size: PAGE_SIZE }
    const f = filters.value
    if (f.status) params.status = f.status
    if (f.userId.trim()) params.userId = f.userId.trim()
    // 时间控件给的是 "2026-10-10T19:00"，后端只认 "yyyy-MM-dd HH:mm:ss"
    if (f.from) {
      const from = fromInputDateTime(f.from)
      if (from) params.from = from
    }
    if (f.to) {
      const to = fromInputDateTime(f.to)
      if (to) params.to = to
    }
    if (f.adjustedOnly) params.adjusted = 1

    const resp = await listOrders(params)
    const count = resp.data?.total || 0
    const maxPage = Math.max(1, Math.ceil(count / PAGE_SIZE))
    if (page.value > maxPage) {
      page.value = maxPage
      return loadTiming()
    }
    records.value = resp.data?.records || []
    total.value = count
  } catch (err) {
    toastError(errorMessage(err, '加载订单失败'))
  } finally {
    loading.value = false
  }
}

/** 改筛选条件后回到第一页再查 —— 停在第 3 页上换条件，多半会直接越界 */
function applyTimingFilters() {
  page.value = 1
  loadTiming()
}

/** 清空筛选 */
function resetTimingFilters() {
  filters.value = { status: '', userId: '', from: '', to: '', adjustedOnly: false }
  applyTimingFilters()
}

/* ==================== tab 二：商品订单 ==================== */

const productPage = ref(1)
const productTotal = ref(0)
const productOrders = ref([])
const productLoading = ref(false)
/** 是否已经拉过 —— 切到那个 tab 时才第一次拉，打开页面时不必打两个请求 */
const productLoaded = ref(false)

/** 商品订单的筛选条件。⚠️ 这个接口不支持时间范围，「只看人工调整」也没有 */
const productFilters = ref({ status: '', userId: '' })

/**
 * 拉取商品订单的当前页。
 *
 * <p>⚠️ <b>分页参数是 {@code pageNum} / {@code pageSize}</b>，与上面那条不是一套
 * （后端如此）。传成 {@code page} 不会报错，只会永远返回第一页。
 */
async function loadProductOrders() {
  productLoading.value = true
  // 商品 tab 的筛选（与页码）同样写进 URL，切回这一页时不丢
  syncQuery()
  try {
    const params = { pageNum: productPage.value, pageSize: PAGE_SIZE }
    if (productFilters.value.status) params.status = productFilters.value.status
    if (productFilters.value.userId.trim()) params.userId = productFilters.value.userId.trim()

    const resp = await listAdminProductOrders(params)
    const count = resp.data?.total || 0
    const maxPage = Math.max(1, Math.ceil(count / PAGE_SIZE))
    if (productPage.value > maxPage) {
      productPage.value = maxPage
      return loadProductOrders()
    }
    productOrders.value = resp.data?.records || []
    productTotal.value = count
    productLoaded.value = true
  } catch (err) {
    toastError(errorMessage(err, '加载商品订单失败'))
  } finally {
    productLoading.value = false
  }
}

/**
 * 切 tab。
 *
 * <p>后三条流水都是只读的、且多半不是管理员进来要看的那一条，所以<b>切过去时才拉</b>：
 * 打开页面就打四个请求，等于让每次看计时订单都白等三次别的查询。
 *
 * <p>不拉数据的那两个分支照样往下走 —— 它们只把 URL 改掉，查询交给
 * {@code onMounted} 的那套分发（刷新页面与点 tab 因此是同一条路）。
 *
 * @param {string} next timing / product / card / booking
 */
function switchType(next) {
  type.value = next
  syncQuery()
  if (next === 'product' && !productLoaded.value) loadProductOrders()
  if (next === 'card' && !cardLoaded.value) loadCardOrders()
  if (next === 'booking' && !bookingLoaded.value) loadBookings()
}

/** 改商品订单的筛选：回第一页再查 */
function applyProductFilters() {
  productPage.value = 1
  loadProductOrders()
}

/** 清空商品订单的筛选 */
function resetProductFilters() {
  productFilters.value = { status: '', userId: '' }
  applyProductFilters()
}

/* ==================== tab 三：月卡购买单 ==================== */

const cardPage = ref(1)
const cardTotal = ref(0)
const cardOrders = ref([])
const cardLoading = ref(false)
/** 是否已经拉过 —— 切到那个 tab 时才第一次拉，打开页面时不必打四个请求 */
const cardLoaded = ref(false)

/** 月卡购买单的筛选。⚠️ 这个接口同样不支持时间范围与「只看人工调整」 */
const cardFilters = ref({ status: '', cardType: '', userId: '' })

/**
 * 拉取月卡购买单的当前页。
 *
 * <p>⚠️ <b>分页参数是 pageNum / pageSize</b>（与月卡列表、商品列表同一套惯例），
 * 不是计时订单那套 page / size。传错不会报错，只会永远返回第一页。
 *
 * <p>状态与卡种的中文都不在这里映射：行内用后端给的 statusLabel / cardTypeLabel，
 * 本页只从 labels.js 取配色与下拉选项（「后端给了的一律用后端的」那条规矩）。
 */
async function loadCardOrders() {
  cardLoading.value = true
  // 筛选与页码写进 URL，切回这一页时不丢（见 syncQuery 的说明）
  syncQuery()
  try {
    const params = { pageNum: cardPage.value, pageSize: PAGE_SIZE }
    if (cardFilters.value.status) params.status = cardFilters.value.status
    if (cardFilters.value.cardType) params.cardType = cardFilters.value.cardType
    if (cardFilters.value.userId.trim()) params.userId = cardFilters.value.userId.trim()

    const resp = await listCardOrders(params)
    const count = resp.data?.total || 0
    const maxPage = Math.max(1, Math.ceil(count / PAGE_SIZE))
    if (cardPage.value > maxPage) {
      cardPage.value = maxPage
      return loadCardOrders()
    }
    cardOrders.value = resp.data?.records || []
    cardTotal.value = count
    cardLoaded.value = true
  } catch (err) {
    toastError(errorMessage(err, '加载月卡流水失败'))
  } finally {
    cardLoading.value = false
  }
}

/** 改月卡流水的筛选：回第一页再查 */
function applyCardFilters() {
  cardPage.value = 1
  loadCardOrders()
}

/** 清空月卡流水的筛选 */
function resetCardFilters() {
  cardFilters.value = { status: '', cardType: '', userId: '' }
  applyCardFilters()
}

/**
 * 点月卡卡片上的用户 ID：就地按这个购买人筛一遍。
 *
 * <p>与计时订单、商品订单是同一条路（回第 1 页、重查、条件写进 URL）——
 * 看到「这笔单不太对」之后紧接着的动作就是「把他这阵子的购卡记录都拉出来」。
 *
 * @param {number} userId 用户 ID
 */
function filterCardByUser(userId) {
  cardFilters.value.userId = String(userId)
  applyCardFilters()
}

/**
 * 卡种标签的配色（中文用后端给的 cardTypeLabel）。
 *
 * @param {string} cardType 卡种 code
 * @returns {string} 形如 "tag tag-success"
 */
function cardTypeCls(cardType) {
  const item = MONTHLY_CARD_TYPES.find((c) => c.value === cardType)
  return item ? item.cls : 'tag'
}

/* ==================== tab 四：包场 ==================== */

const bookingPage = ref(1)
const bookingTotal = ref(0)
const bookings = ref([])
const bookingLoading = ref(false)
const bookingLoaded = ref(false)

/** 包场的筛选。⚠️ 这个接口只支持 scope 一个参数，没有「按用户筛」 */
const bookingFilters = ref({ scope: '' })

/**
 * 拉取包场流水的当前页。
 *
 * <p>⚠️ <b>分页参数是 page / size</b>（包场接口的惯例），不是月卡那条的
 * pageNum / pageSize。
 *
 * <p>状态中文用后端给的 statusLabel（BookingVo 2026-10-16 起带上它），
 * 本页只从 labels.js 取配色。
 */
async function loadBookings() {
  bookingLoading.value = true
  syncQuery()
  try {
    const params = { page: bookingPage.value, size: PAGE_SIZE }
    if (bookingFilters.value.scope) params.scope = bookingFilters.value.scope

    const resp = await listBookings(params)
    const count = resp.data?.total || 0
    const maxPage = Math.max(1, Math.ceil(count / PAGE_SIZE))
    if (bookingPage.value > maxPage) {
      bookingPage.value = maxPage
      return loadBookings()
    }
    bookings.value = resp.data?.records || []
    bookingTotal.value = count
    bookingLoaded.value = true
  } catch (err) {
    toastError(errorMessage(err, '加载包场流水失败'))
  } finally {
    bookingLoading.value = false
  }
}

/** 改包场流水的筛选：回第一页再查 */
function applyBookingFilters() {
  bookingPage.value = 1
  loadBookings()
}

/** 清空包场流水的筛选 */
function resetBookingFilters() {
  bookingFilters.value = { scope: '' }
  applyBookingFilters()
}

/**
 * 包场状态的标签配色（中文用后端给的 statusLabel）。
 *
 * <p>认不出的状态名退回默认灰标签，与其余几处同一取向 ——
 * 状态列是 VARCHAR，手工改过库的数据可能落在枚举之外。
 *
 * @param {string} status 状态名
 * @returns {string} 形如 "tag tag-warning"
 */
function bookingStatusCls(status) {
  return BOOKING_STATUS[status] ? BOOKING_STATUS[status].cls : 'tag'
}

/**
 * 包场时段的展示文本。
 *
 * <p>与订单的起止时刻同一套写法：同一天只写一次日期、去掉秒（流水只需要
 * 「一眼看出大概是哪一段」）。包场是整段排好的，所以不会出现「→ 进行中」。
 *
 * @param {object} b 包场行
 * @returns {string} 形如 "2026-10-20 14:00 → 18:00"
 */
function bookingRangeText(b) {
  if (!b.startAt) return '—'
  const from = formatDate(b.startAt) + ' ' + formatTime(b.startAt)
  if (!b.endAt) return from
  const to =
    formatDate(b.startAt) === formatDate(b.endAt)
      ? formatTime(b.endAt)
      : formatDate(b.endAt) + ' ' + formatTime(b.endAt)
  return from + ' → ' + to
}

/* ==================== 筛选状态与 URL 的同步 ==================== */

/**
 * 把当前 tab、筛选条件与页码写进 URL 的 query。
 *
 * <p><b>为什么值得这么麻烦</b>：点一单进详情（{@code /admin/orders/:id}）再返回时，
 * 这个组件会被重新创建 —— 不写进 URL 的话筛选全丢，管理员筛出某个用户后
 * 连看几单就得重填几次。写进去之后「返回」只要把 query 原样带回即可
 * （见 {@code OrderDetailView} 的 {@code goBack}）。
 *
 * <p>顺带两个好处：刷新不丢筛选；把 URL 发给同事，等于把「我看的那一屏」发过去。
 *
 * <p>⚠️ <b>用 {@code replace} 不用 {@code push}</b>：筛选条件是「同一屏的不同看法」，
 * 每改一次都压一条历史的话，管理员按后退键要按很多次才能退出这一页。
 * 也正因如此，这里<b>不监听 {@code route.query}</b>（监听 + replace 容易写成死循环），
 * query 只在页面挂载时读一次。
 *
 * <p>空值一概不写进 URL（{@code ?status=} 与不写是同义的），页码 1 也不写 ——
 * 让「默认状态」的 URL 保持干净。
 *
 * <p>⚠️ <b>四套筛选各带前缀，互不共用键名</b>：计时订单无前缀，商品订单 {@code p}，
 * 月卡 {@code c}，包场 {@code b}。混用（比如两个月卡字段写成同一个键）不会报错，
 * 只会让「切回某个 tab 看到的是别人的筛选」。
 */
function syncQuery() {
  const q = {}
  if (type.value === 'product') q.type = 'product'
  else if (type.value === 'card') q.type = 'card'
  else if (type.value === 'booking') q.type = 'booking'

  const f = filters.value
  if (f.status) q.status = f.status
  if (f.userId.trim()) q.userId = f.userId.trim()
  if (f.from) q.from = f.from
  if (f.to) q.to = f.to
  if (f.adjustedOnly) q.adjusted = '1'
  if (page.value > 1) q.page = String(page.value)

  const p = productFilters.value
  if (p.status) q.pStatus = p.status
  if (p.userId.trim()) q.pUserId = p.userId.trim()
  if (productPage.value > 1) q.pPage = String(productPage.value)

  // 月卡与包场各自带前缀（c / b）：四套筛选字段名互不相同，
  // 混用的话「切回某个 tab 看到的是别人的筛选」不会有任何报错
  const c = cardFilters.value
  if (c.status) q.cStatus = c.status
  if (c.cardType) q.cCardType = c.cardType
  if (c.userId.trim()) q.cUserId = c.userId.trim()
  if (cardPage.value > 1) q.cPage = String(cardPage.value)

  if (bookingFilters.value.scope) q.bScope = bookingFilters.value.scope
  if (bookingPage.value > 1) q.bPage = String(bookingPage.value)

  router.replace({ path: '/admin/orders', query: q })
}

/**
 * 从 URL 的 query 复原筛选状态（页面挂载时读一次）。
 *
 * <p>⚠️ 时间控件认的格式是 {@code 2026-10-10T19:00}（与写进 URL 的格式一致），
 * 所以这里不做任何转换 —— 中间转一道反而会让「刷新后时间筛选悄悄失效」。
 *
 * <p>页码只接受正整数：URL 是手可以改的，{@code ?page=abc} 进来时回落到第 1 页，
 * 而不是带着一个 NaN 去请求（那会拼出 {@code page=NaN}，后端直接 400）。
 */
function initFromQuery() {
  const q = route.query
  const str = (v) => (typeof v === 'string' ? v : '')

  // 只认这四个取值：URL 是手可以改的，写个别的进来时回落到计时订单，
  // 而不是让 type 停在一个没有对应模板的值上（那样整页会什么都不渲染）
  const nextType = str(q.type)
  if (nextType === 'product' || nextType === 'card' || nextType === 'booking') {
    type.value = nextType
  }

  filters.value.status = str(q.status)
  filters.value.userId = str(q.userId)
  filters.value.from = str(q.from)
  filters.value.to = str(q.to)
  filters.value.adjustedOnly = str(q.adjusted) === '1'
  const p = Number.parseInt(str(q.page), 10)
  if (Number.isInteger(p) && p > 1) page.value = p

  productFilters.value.status = str(q.pStatus)
  productFilters.value.userId = str(q.pUserId)
  const pp = Number.parseInt(str(q.pPage), 10)
  if (Number.isInteger(pp) && pp > 1) productPage.value = pp

  cardFilters.value.status = str(q.cStatus)
  cardFilters.value.cardType = str(q.cCardType)
  cardFilters.value.userId = str(q.cUserId)
  const cp = Number.parseInt(str(q.cPage), 10)
  if (Number.isInteger(cp) && cp > 1) cardPage.value = cp

  bookingFilters.value.scope = str(q.bScope)
  const bp = Number.parseInt(str(q.bPage), 10)
  if (Number.isInteger(bp) && bp > 1) bookingPage.value = bp
}

/**
 * 打开某一单的详情。
 *
 * <p>筛选条件（当前 query）一起带进详情页 —— 详情页的「返回订单流水」要按它复原。
 * 管理员若在详情页刷新，query 仍在 URL 上，返回按钮照样管用。
 *
 * @param {object} o 订单行
 */
function openDetail(o) {
  router.push({
    name: 'admin-order-detail',
    params: { id: o.id },
    query: route.query
  })
}

/* ==================== 优惠与计价 ==================== */

/**
 * 三项优惠之和（元）。
 *
 * <p>三种优惠并行存在且互不重叠（见后端 {@code BillingService} 的类注释）：
 * {@code discountAmount} 是月度累计优惠、{@code cardFreeAmount} 是月卡、
 * {@code activityFreeAmount} 是免费活动。合成一个数只用来判「有没有优惠」，
 * 摊开展示时仍要分开 —— 否则月卡用户看到的一行会说成「活动减免」。
 *
 * @param {object} o 订单行
 * @returns {number}
 */
function discountTotal(o) {
  return (
    Number(o.discountAmount || 0) +
    Number(o.cardFreeAmount || 0) +
    Number(o.activityFreeAmount || 0)
  )
}

/**
 * 这一单有没有优惠 —— 决定卡片上要不要多出「计价 / 实付」与那行减免明细。
 *
 * <p>使用中的订单没有金额（{@code payableAmount} 为 null），三项也全空，
 * 自然走「无优惠」那条路，卡片上仍旧只有一个「—」。
 *
 * @param {object} o 订单行
 * @returns {boolean}
 */
function hasDiscount(o) {
  return discountTotal(o) > 0
}

/**
 * 优惠前的计价（元）。
 *
 * <p>⚠️ <b>订单上没有「原价」这一列</b>：{@code totalAmount} 与
 * {@code payableAmount} 存的都是<b>实收</b>（已按分段封顶、已含月度优惠、
 * 已扣月卡与活动免除），那三项减免是说明性字段。所以原价只能反推：
 * <b>实收 + 三项优惠</b>。
 *
 * <p>有一处它并不精确、且刻意保留：<b>半场累计封顶</b>削掉的钱不算优惠
 * （那是同一半场的额度已经收满了，不是给这一单的减免），于是它会被算进
 * 「计价」里。这正是这个数字该有的口径 —— 「没享受到这些优惠与额度时要付多少」。
 * 逐段解释在详情页的账单里（{@code BillSegmentList} 会写明哪一段被削）。
 *
 * @param {object} o 订单行
 * @returns {number}
 */
function originAmount(o) {
  return Number(o.totalAmount || 0) + discountTotal(o)
}

/**
 * 下单人的展示文本。
 *
 * <p>昵称由后端批量补（{@code userNickname}）；用户已注销时回落成「用户 #ID」——
 * 这里不编一个名字，因为「查不到」与「叫这个名字」是两回事。
 *
 * @param {object} o 订单行
 * @returns {string}
 */
function userText(o) {
  return o.userNickname || `用户 #${o.userId}`
}

/**
 * 点卡片上的用户 ID：就地按这个用户筛一遍。
 *
 * <p><b>为什么卡片上要印 ID</b>：筛选栏是按「用户 ID」查的，而管理员既背不下
 * 每个人的 ID、昵称又可能重名（同名同姓在一个店里并不罕见）。
 *
 * <p><b>为什么印出来还不够、还得能点</b>：看到「这人这单不太对」之后紧接着的动作
 * 就是「把他这阵子的单都拉出来」。让管理员照着那一串数字手抄进输入框，
 * 等于把这个功能做成摆设。
 *
 * <p>走的是与「查询」按钮同一条路（{@link applyTimingFilters}）：回第 1 页、
 * 重查、条件照样写进 URL —— 于是「按他筛一遍」的结果可以刷新、可以发给同事。
 *
 * @param {number} userId 用户 ID
 */
function filterByUser(userId) {
  filters.value.userId = String(userId)
  applyTimingFilters()
}

/**
 * 商品订单那边的同款入口。
 *
 * <p>两个 tab 各有一套筛选（见文件头），所以这里<b>不切 tab</b>：
 * 在商品流水里点 ID 就把商品流水按他筛一遍，跳到计时流水反而违背了「我正在看什么」。
 *
 * @param {number} userId 用户 ID
 */
function filterProductByUser(userId) {
  productFilters.value.userId = String(userId)
  applyProductFilters()
}

/**
 * 金额的展示文本。
 *
 * <p>使用中的订单还没结算，{@code payableAmount} 为 null —— 显示「—」而不是
 * 「¥0.00」：后者看着像「这单免费」，而实际是「还在计费」。
 *
 * @param {object} o 订单行
 * @returns {string} 形如 "¥8.00" / "—"
 */
function amountText(o) {
  if (o.payableAmount == null) return '—'
  return `¥${formatMoney(o.payableAmount)}`
}

/**
 * 起止时刻的展示文本。
 *
 * <p>同一天只写一次日期（{@code 2026-10-10 14:03 → 18:41}）—— 流水上绝大多数
 * 订单是当天一进一出，把日期重复两遍只是让这一行更长。跨天了则两边都写全，
 * 否则「昨天 23:40 进来、今天 01:20 走」会被读成同一天的四个小时。
 *
 * <p>去掉秒：流水的用途是「一眼看出大概是什么时候」，秒级精度在这里是噪音。
 * 要精确到秒的是订单详情与账单，不是这一页。
 *
 * @param {object} o 订单行
 * @returns {string} 形如 "2026-10-10 14:03 → 18:41" / "… → 进行中"
 */
function rangeText(o) {
  if (!o.startTime) return '—'
  const from = `${formatDate(o.startTime)} ${formatTime(o.startTime)}`
  if (!o.endTime) return `${from} → 进行中`
  const to =
    formatDate(o.startTime) === formatDate(o.endTime)
      ? formatTime(o.endTime)
      : `${formatDate(o.endTime)} ${formatTime(o.endTime)}`
  return `${from} → ${to}`
}

/* ==================== 人工调整时长 ==================== */

/**
 * 允许人工调整的订单状态。
 *
 * <p>与后端 {@code OrderStatus.isUnpaid} 对齐：使用中 / 待支付 / 凭证未通过。
 * <b>已支付的订单不给按钮</b> —— 后端会返回 {@code ORDER_NOT_ADJUSTABLE}(40920)，
 * 而「点了才被拒」比「压根不给点」糟糕：管理员会以为是自己填错了什么。
 */
const ADJUSTABLE_STATUS = ['IN_USE', 'PENDING_PAYMENT', 'REJECTED']

/**
 * 这一单能不能人工调整。
 *
 * @param {object} o 订单行
 * @returns {boolean}
 */
function canAdjust(o) {
  return ADJUSTABLE_STATUS.includes(o.status)
}

/** 正在调整的那一单；为 null 表示弹层关着 */
const adjustTarget = ref(null)
const adjustVisible = ref(false)
const adjustForm = ref({ endTime: '', reason: '' })
const adjustError = ref('')
const adjusting = ref(false)

/**
 * 打开调整弹层。
 *
 * <p>离场时刻的预填分两种：<b>已有离场时刻的就填它</b>（待支付、凭证未通过的单
 * 都点过「结束使用」，管理员多半是在此基础上往前挪）；<b>还在使用中的填「现在」</b>
 * —— 核对完监控最常见的结论是「他刚走」，一进来就是对的、直接提交即可，
 * 而填错的风险由提交前那道「不能是未来」的校验兜住。
 *
 * @param {object} o 订单行
 */
function openAdjust(o) {
  adjustTarget.value = o
  adjustForm.value = {
    endTime: o.endTime ? toInputDateTime(o.endTime) : toInputDateTime(formatDateTime(new Date())),
    reason: ''
  }
  adjustError.value = ''
  adjustVisible.value = true
}

/**
 * 提交调整。
 *
 * <p>本地先挡四道（填了没有 / 晚于开门时刻 / 不是未来 / 原因非空），后端也各有一道。
 * 这几道不是为了替代后端，而是让管理员当场看到问题 —— 否则要提交一轮、
 * 等回一个 40918 再回来改。
 *
 * <p>⚠️ 调整会<b>重算账单并直接对顾客生效</b>：使用中的订单调整后自动转待支付
 * （不转的话，一条永远不会被点结束的订单就永远收不到钱）。所以这里没有
 * 「先试试看」的余地，提交成功之后必须重查列表 —— 金额、状态、「已调整」标记都变了。
 */
async function submitAdjust() {
  const o = adjustTarget.value
  if (!o) return

  // datetime-local 给的是 "2026-10-16T14:03"，后端只认 "yyyy-MM-dd HH:mm:ss"
  const endTime = fromInputDateTime(adjustForm.value.endTime)
  if (!endTime) {
    adjustError.value = '请填写核实后的离场时刻'
    return
  }
  const end = parseDateTime(endTime)
  const start = parseDateTime(o.startTime)
  if (!end || !start || end <= start) {
    adjustError.value = '离场时刻必须晚于开门时刻'
    return
  }
  if (end > new Date()) {
    adjustError.value = '离场时刻不能是未来时刻'
    return
  }
  const reason = adjustForm.value.reason.trim()
  if (!reason) {
    adjustError.value = '请填写调整原因 —— 它会永久留痕，事后要说得清依据'
    return
  }

  adjusting.value = true
  adjustError.value = ''
  try {
    const resp = await adjustOrder(o.id, { endTime, reason })
    adjustVisible.value = false
    const settle = resp.data
    const amount = settle?.bill ? `，本单合计 ¥${formatMoney(settle.bill.totalAmount)}` : ''
    toastSuccess(`已把离场时刻改为 ${formatDateTime(settle?.endTime)}${amount}`)
    await loadTiming()
  } catch (err) {
    // 错误走弹层的 error 属性，不用 toast —— toast 的层级比弹层低，会被遮罩压住
    adjustError.value = errorMessage(err, '调整失败')
  } finally {
    adjusting.value = false
  }
}

/**
 * 进页面：<b>先从 URL 复原条件，再查</b>。
 *
 * <p>从详情页返回时正是靠这一步把筛选与页码带回来（详情页把进来的 query
 * 原样推回这一页，见 {@code OrderDetailView#goBack}）。
 *
 * <p>⚠️ 商品 tab 也一样要复原：URL 上写着 {@code type=product} 时直接打开这一页
 * 就该落在商品流水上，而不是先闪一下计时订单、再去查商品。
 */
/* ==================== 收支概览与导出（2026-10-16 加） ==================== */

/**
 * 收支概览；加载失败时保持 null。
 *
 * <p>失败就整张卡不显示，而不是渲染一排空数字 —— 「取不到数」与「这个月是 0」
 * 在屏幕上是两回事。
 */
const finance = ref(null)

/** 导出进行中：生成一次要查全库，连点会堆请求，用户也会以为没反应 */
const exporting = ref(false)

async function loadFinance() {
  try {
    finance.value = (await getFinanceSummary()).data
  } catch (err) {
    // 概览失败不该挡住流水本身：列表照常可用，这里只在控制台留一声
    console.warn('[订单流水] 收支概览加载失败', err)
  }
}

/** 计时订单的导出参数：与 loadTiming 的筛选口径逐条对齐。 */
function timingExportParams() {
  const params = { type: 'TIMING_ORDER' }
  const f = filters.value
  if (f.status) params.status = f.status
  if (f.userId.trim()) params.userId = f.userId.trim()
  if (f.from) {
    const from = fromInputDateTime(f.from)
    if (from) params.from = from
  }
  if (f.to) {
    const to = fromInputDateTime(f.to)
    if (to) params.to = to
  }
  if (f.adjustedOnly) params.adjusted = 1
  return params
}

/** 商品订单的导出参数（那个接口同样不支持时间范围与「只看人工调整」）。 */
function productExportParams() {
  const params = { type: 'PRODUCT_ORDER' }
  if (productFilters.value.status) params.status = productFilters.value.status
  if (productFilters.value.userId.trim()) params.userId = productFilters.value.userId.trim()
  return params
}

/**
 * 导出当前筛选条件下的<b>全量</b>流水为 xlsx。
 *
 * <p>⚠️ 导的是整份筛选结果，不是当前页：筛了「只看人工调整」就导出所有被调整过的单，
 * 哪怕列表上只显示 10 条 —— 这正是「拿出去做流水计算」需要的东西。
 *
 * <p>⚠️ 后端到行数上限时会回 {@code truncated}，必须提示用户：
 * 一份看起来完整、实际少了一截的流水，比直接导出失败更危险。
 */
async function onExport() {
  await runExport(type.value === 'product' ? productExportParams() : timingExportParams())
}

/**
 * 导出「总流水」：计时订单与商品单合并成一张表。
 *
 * <p>⚠️ 它<b>不传筛选条件</b> —— 那两张表能筛的维度不一样（计时单有时间范围与
 * 「只看人工调整」，商品单只有状态），混着传会让「这次导的到底是什么口径」说不清。
 */
async function onExportAll() {
  await runExport({ type: 'ALL_ORDERS' })
}

/**
 * 生成并下载一份导出文件；两个入口（单表 / 总流水）共用这一段。
 *
 * <p>⚠️ 后端到行数上限时会回 {@code truncated}，必须提示用户：
 * 一份看起来完整、实际少了一截的流水，比直接导出失败更危险。
 */
async function runExport(params) {
  if (exporting.value) return
  exporting.value = true
  try {
    const resp = await createExport(params)
    const { token, fileName, rows, truncated } = resp.data
    await downloadExport(token, fileName)
    if (truncated) {
      toastError(`只导出了前 ${rows} 行（已达上限），请缩小时间范围再导一次`)
    } else {
      toastSuccess(`已导出 ${rows} 行`)
    }
  } catch (err) {
    toastError(errorMessage(err, '导出失败'))
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  initFromQuery()
  // 四个 tab 各查各的：URL 上写着哪个 type 就先落在哪一条流水上
  if (type.value === 'product') loadProductOrders()
  else if (type.value === 'card') loadCardOrders()
  else if (type.value === 'booking') loadBookings()
  else loadTiming()
  // 概览与列表分开拉：它慢一点不该拖着列表不出来
  loadFinance()
})
</script>

<template>
  <div class="page">
    <!-- ---------------- 收支概览（2026-10-16 加） ---------------- -->
    <div v-if="finance" class="card">
      <div class="card-title">收支概览</div>
      <!--
        收入取「到账」事件而不是订单金额之和：顾客 8/31 进店、9/1 离场、9/2 付款，
        这笔钱 9 月才到手 —— 统计的是钱什么时候到的，不是服务什么时候消费的
      -->
      <p class="fin__source">
        收入 = 交易流水里的「到账」事件，按到账时刻归月；不含提交凭证与取消（那两类没有钱发生）
      </p>

      <div class="fin__group-title">收入</div>
      <div class="fin__grid">
        <div class="fin__cell">
          <div class="fin__label">本月 · {{ finance.thisMonthLabel }}</div>
          <div class="fin__value">¥{{ formatMoney(finance.thisMonthIncome) }}</div>
        </div>
        <div class="fin__cell">
          <div class="fin__label">上月 · {{ finance.lastMonthLabel }}</div>
          <div class="fin__value">¥{{ formatMoney(finance.lastMonthIncome) }}</div>
        </div>
        <div class="fin__cell">
          <div class="fin__label">累计</div>
          <div class="fin__value">¥{{ formatMoney(finance.totalIncome) }}</div>
        </div>
      </div>

      <div class="fin__group-title fin__group-title--gap">支出</div>
      <div class="fin__grid">
        <div class="fin__cell">
          <div class="fin__label">本月</div>
          <div class="fin__value fin__value--none">—</div>
        </div>
        <div class="fin__cell">
          <div class="fin__label">上月</div>
          <div class="fin__value fin__value--none">—</div>
        </div>
        <div class="fin__cell">
          <div class="fin__label">累计</div>
          <div class="fin__value fin__value--none">—</div>
        </div>
      </div>
      <p class="fin__note">
        系统还没做支出记账（没有对应的表），所以这里显示「—」而不是 ¥0.00 ——
        0 会被读成「本月没有支出」，而事实是「不知道」。将来做账时补一张支出表即可。
      </p>
    </div>

    <div class="card">
      <div class="card-title om__title">
        <span>订单流水</span>
        <!--
          「导出总流水」：把计时订单与商品单拼成一张表（2026-10-16 加）。
          它不吃任何筛选条件 —— 那两张表能筛的维度不一样（计时单有时间范围与
          「只看人工调整」，商品单只有状态），混着传会让「这次导的是什么口径」说不清。
          因此它属于页面级、不属于任何一个 tab
        -->
        <button class="btn btn-ghost om__btn" :disabled="exporting" @click="onExportAll">
          {{ exporting ? '导出中…' : '导出总流水' }}
        </button>
      </div>

      <!--
        四条流水做成四张卡片，不是一个下拉筛选 —— 它们是这一页的【四种内容】，
        而不是同一个列表的筛选条件。做成大按钮一眼看得出这一页有哪四条流水，
        副标题顺手写清各自的字段口径，省得挨个点进去试
      -->
      <div class="om__types">
        <button
          class="om__type"
          :class="{ 'om__type--on': type === 'timing' }"
          @click="switchType('timing')"
        >
          <span class="om__type-name">计时订单</span>
          <span class="om__type-desc">开门计时 · 在店时长与金额</span>
        </button>
        <button
          class="om__type"
          :class="{ 'om__type--on': type === 'product' }"
          @click="switchType('product')"
        >
          <span class="om__type-name">商品订单</span>
          <span class="om__type-desc">商城购买单 · 商品与数量</span>
        </button>
        <button
          class="om__type"
          :class="{ 'om__type--on': type === 'card' }"
          @click="switchType('card')"
        >
          <span class="om__type-name">月卡</span>
          <span class="om__type-desc">购卡单 · 卡种与支付状态</span>
        </button>
        <button
          class="om__type"
          :class="{ 'om__type--on': type === 'booking' }"
          @click="switchType('booking')"
        >
          <span class="om__type-name">包场</span>
          <span class="om__type-desc">排期场次 · 时段与包场费</span>
        </button>
      </div>

      <!-- ============ tab 一：计时订单 ============ -->
      <template v-if="type === 'timing'">
        <!-- ---------------- 筛选栏 ---------------- -->
        <div class="om__filters">
          <select v-model="filters.status" class="field-input om__sel" @change="applyTimingFilters">
            <option value="">全部状态</option>
            <option v-for="s in ORDER_STATUS" :key="s.value" :value="s.value">
              {{ s.label }}
            </option>
          </select>
          <input
            v-model="filters.userId"
            class="field-input om__uid"
            type="text"
            inputmode="numeric"
            placeholder="用户 ID"
            @keyup.enter="applyTimingFilters"
          />
          <label class="om__time">
            <span class="om__time-label">起</span>
            <input v-model="filters.from" class="field-input om__dt" type="datetime-local" />
          </label>
          <label class="om__time">
            <span class="om__time-label">止</span>
            <input v-model="filters.to" class="field-input om__dt" type="datetime-local" />
          </label>
          <label class="om__check">
            <input v-model="filters.adjustedOnly" type="checkbox" @change="applyTimingFilters" />
            <span>只看人工调整</span>
          </label>
          <button class="btn btn-ghost om__btn" @click="applyTimingFilters">查询</button>
          <button class="btn btn-ghost om__btn" @click="resetTimingFilters">重置</button>
          <!--
            导出的是「当前筛选下的全量」，不是这一页 —— 所以它贴着筛选栏，
            而不是放在分页器旁边（放那儿会被读成「导出当前页」）
          -->
          <button class="btn btn-ghost om__btn" :disabled="exporting" @click="onExport">
            {{ exporting ? '导出中…' : '导出 xlsx' }}
          </button>
        </div>

        <LoadingMask :loading="loading" />

        <div v-if="records.length" class="om__list">
          <!--
            整张卡片可点，进这一单的详情（/admin/orders/:id）。
            键盘也给一条路（tabindex + 回车）—— 后台是电脑上的工具，
            只用键盘翻流水的管理员不该在这里被卡住
          -->
          <div
            v-for="o in records"
            :key="o.id"
            class="orow orow--link"
            tabindex="0"
            @click="openDetail(o)"
            @keyup.enter="openDetail(o)"
          >
            <div class="orow__head">
              <span class="orow__no">{{ o.orderNo }}</span>
              <!-- 状态中文用后端给的 statusText，只有配色取自 labels.js -->
              <span :class="orderStatusCls(o.status)">{{ o.statusText }}</span>
              <!--
                「已调整」是「用户忘了点结束」的高发信号，值得定期过一眼。
                原因 / 处理人 / 调整时刻不藏在这里，直接印在卡片底部（见下面那段）
              -->
              <span v-if="o.adjusted === 1" class="tag tag-warning">
                已调整
              </span>
              <span v-if="o.bookingId" class="tag">包场单</span>
              <!--
                已支付的单不给这个按钮：调整必然改金额，改完要牵出退款。
                ⚠️ click 必须 stop：整张卡片本身是可点的（进详情），
                不拦住的话点「调整时长」会顺带跳走一页
              -->
              <button v-if="canAdjust(o)" class="om__op" @click.stop="openAdjust(o)">
                调整时长
              </button>
            </div>

            <!--
              时长与金额并排放在右侧（2026-10-16 改）：它们回答的是同一个问题
              「这单玩了多久、收了多少」，原先一个在第二行、一个在第三行，看的人
              还得自己对齐。昵称留在左边，一行之内交代完「谁 · 多久 · 多少」
            -->
            <div class="orow__line">
              <span class="orow__left">
                {{ userText(o) }}
                <!--
                  用户 ID 与昵称并排（2026-10-16 加）：筛选栏要按「用户 ID」查，
                  而管理员既背不下每个人的 ID、昵称又可能重名。
                  **点它直接按这个用户筛一遍** —— 那正是看到 ID 之后想做的事；
                  不拦冒泡的话会顺带跳进订单详情（整张卡片是可点的）
                -->
                <span
                  class="orow__uid"
                  title="只看该用户的订单"
                  @click.stop="filterByUser(o.userId)"
                >#{{ o.userId }}</span>
              </span>
              <span class="orow__right">
                <span v-if="o.stayMinutes != null" class="orow__stay">
                  在店 {{ formatDuration(o.stayMinutes) }}
                </span>
                <!--
                  「计价 → 实付」：只有真有优惠的单子才给这个对比。
                  每行都写一遍「计价」的话，没优惠的九成订单上那两个字纯属噪音，
                  而它们恰恰是流水里最不需要解释的那一批
                -->
                <span v-if="hasDiscount(o)" class="orow__origin">
                  计价 ¥{{ formatMoney(originAmount(o)) }}
                </span>
                <span class="orow__amount">
                  {{ hasDiscount(o) ? '实付 ' : '' }}{{ amountText(o) }}
                </span>
              </span>
            </div>

            <p class="orow__meta">{{ rangeText(o) }}</p>

            <!--
              减免明细：三种优惠各有各的出资方（月度累计优惠 / 月卡 / 活动），
              摊成三项而不是合成一个数 —— 合成之后，月卡用户省下的钱会被记到
              活动头上，而那种错不会有任何报错
            -->
            <div v-if="hasDiscount(o)" class="orow__cuts">
              <span v-if="Number(o.discountAmount) > 0" class="orow__cut">
                月度优惠 -¥{{ formatMoney(o.discountAmount) }}
              </span>
              <span v-if="Number(o.cardFreeAmount) > 0" class="orow__cut">
                月卡抵扣 -¥{{ formatMoney(o.cardFreeAmount) }}
              </span>
              <span v-if="Number(o.activityFreeAmount) > 0" class="orow__cut orow__cut--activity">
                活动减免 -¥{{ formatMoney(o.activityFreeAmount) }}
              </span>
              <span class="orow__cut orow__cut--sum">共减 ¥{{ formatMoney(discountTotal(o)) }}</span>
            </div>

            <!--
              人工调整的三项留痕（原因 / 处理人 / 时刻）直接印在卡片上（2026-10-16 改）：
              它们原先只挂在「已调整」标签的 tooltip 里 —— 而悬停既不好读、也复制不走，
              偏偏这条正是要「定期过一眼」的那类信息，审计的东西不该藏在鼠标后面
            -->
            <p v-if="o.adjusted === 1" class="orow__adjust">
              <span class="orow__adjust-tag">人工调整</span>
              {{ o.adjustReason || '（未填写原因）' }}
              <span v-if="o.adjustedByName"> · {{ o.adjustedByName }}</span>
              <span v-if="o.adjustedAt"> · {{ formatDateTime(o.adjustedAt) }}</span>
            </p>
          </div>
        </div>

        <EmptyState
          v-else-if="!loading"
          icon="🧾"
          text="没有符合条件的订单"
          hint="换个筛选条件，或等顾客开单后再来看"
        />

        <AdminPager
          v-model:page="page"
          :size="PAGE_SIZE"
          :total="total"
          @update:page="loadTiming"
        />
      </template>

      <!-- ============ tab 二：商品订单 ============ -->
      <template v-else-if="type === 'product'">
        <div class="om__filters">
          <select
            v-model="productFilters.status"
            class="field-input om__sel"
            @change="applyProductFilters"
          >
            <option value="">全部状态</option>
            <option v-for="s in PRODUCT_ORDER_STATUS" :key="s.value" :value="s.value">
              {{ s.label }}
            </option>
          </select>
          <input
            v-model="productFilters.userId"
            class="field-input om__uid"
            type="text"
            inputmode="numeric"
            placeholder="用户 ID"
            @keyup.enter="applyProductFilters"
          />
          <button class="btn btn-ghost om__btn" @click="applyProductFilters">查询</button>
          <button class="btn btn-ghost om__btn" @click="resetProductFilters">重置</button>
          <button class="btn btn-ghost om__btn" :disabled="exporting" @click="onExport">
            {{ exporting ? '导出中…' : '导出 xlsx' }}
          </button>
        </div>

        <LoadingMask :loading="productLoading" />

        <div v-if="productOrders.length" class="om__list">
          <div v-for="o in productOrders" :key="o.id" class="orow">
            <div class="orow__head">
              <span class="orow__no">{{ o.orderNo }}</span>
              <!-- 状态中文用后端给的 statusLabel，只有配色取自 labels.js -->
              <span :class="productOrderStatusCls(o.status)">{{ o.statusLabel }}</span>
            </div>

            <!-- 与计时订单那边同一种排布：左边买的是什么、右边多少钱 -->
            <div class="orow__line">
              <span class="orow__left">{{ o.productName }} × {{ o.quantity }}</span>
              <span class="orow__right">
                <span class="orow__amount">¥{{ formatMoney(o.amount) }}</span>
              </span>
            </div>

            <!--
              购买人放最前 —— 后台看流水最先要答的是「这是谁买的」。
              昵称由后端批量补（userNickname）；用户已注销时回落成「用户 #ID」，
              不编一个名字，因为「查不到」与「叫这个名字」是两回事
            -->
            <p class="orow__meta">
              购买人 {{ userText(o) }}
              <span class="orow__uid" title="只看该用户的商品订单" @click="filterProductByUser(o.userId)"
                >#{{ o.userId }}</span
              >
              · 单价 ¥{{ formatMoney(o.unitPrice) }} · 下单于
              {{ formatDateTime(o.createdAt) }}
              <template v-if="o.paidAt"> · 付款于 {{ formatDateTime(o.paidAt) }}</template>
            </p>
          </div>
        </div>

        <EmptyState
          v-else-if="!productLoading"
          icon="🛍"
          text="还没有商品订单"
          hint="顾客在商城里下单后，这里就能看到"
        />

        <AdminPager
          v-model:page="productPage"
          :size="PAGE_SIZE"
          :total="productTotal"
          @update:page="loadProductOrders"
        />
      </template>

      <!-- ============ tab 三：月卡购买单 ============ -->
      <template v-else-if="type === 'card'">
        <div class="om__filters">
          <select
            v-model="cardFilters.status"
            class="field-input om__sel"
            @change="applyCardFilters"
          >
            <option value="">全部状态</option>
            <option v-for="s in MONTHLY_CARD_ORDER_STATUS" :key="s.value" :value="s.value">
              {{ s.label }}
            </option>
          </select>
          <select
            v-model="cardFilters.cardType"
            class="field-input om__sel"
            @change="applyCardFilters"
          >
            <option value="">全部卡种</option>
            <option v-for="t in MONTHLY_CARD_TYPES" :key="t.value" :value="t.value">
              {{ t.label }}
            </option>
          </select>
          <input
            v-model="cardFilters.userId"
            class="field-input om__uid"
            type="text"
            inputmode="numeric"
            placeholder="用户 ID"
            @keyup.enter="applyCardFilters"
          />
          <button class="btn btn-ghost om__btn" @click="applyCardFilters">查询</button>
          <button class="btn btn-ghost om__btn" @click="resetCardFilters">重置</button>
          <!-- 这一条没有导出实现，所以这里没有导出按钮（见文件头那段说明） -->
        </div>

        <LoadingMask :loading="cardLoading" />

        <div v-if="cardOrders.length" class="om__list">
          <div v-for="o in cardOrders" :key="o.id" class="orow">
            <div class="orow__head">
              <span class="orow__no">{{ o.orderNo }}</span>
              <!-- 状态中文用后端给的 statusLabel，只有配色取自 labels.js -->
              <span :class="cardOrderStatusCls(o.status)">{{ o.statusLabel }}</span>
            </div>

            <!-- 与另两条同一种排布：左边买的是什么卡、右边多少钱 -->
            <div class="orow__line">
              <span class="orow__left">
                <span :class="cardTypeCls(o.cardType)">{{ o.cardTypeLabel }}</span>
              </span>
              <span class="orow__right">
                <span class="orow__amount">¥{{ formatMoney(o.price) }}</span>
              </span>
            </div>

            <!--
              购买人放最前 —— 后台看流水最先要答的是「这是谁买的」。昵称由后端批量补
              （userNickname，昵称优先、回落登录名）；用户已注销时回落成「用户 #ID」，
              不编一个名字，因为「查不到」与「叫这个名字」是两回事
            -->
            <p class="orow__meta">
              购买人 {{ userText(o) }}
              <span
                class="orow__uid"
                title="只看该用户的购卡记录"
                @click="filterCardByUser(o.userId)"
                >#{{ o.userId }}</span
              >
              · 下单于 {{ formatDateTime(o.createdAt) }}
              <template v-if="o.paidAt"> · 付款于 {{ formatDateTime(o.paidAt) }}</template>
              <template v-if="o.paymentMethod"> · 通道 {{ o.paymentMethod }}</template>
            </p>
          </div>
        </div>

        <EmptyState
          v-else-if="!cardLoading"
          icon="💳"
          text="还没有月卡购买单"
          hint="顾客在商城买卡后这里就会有 —— 未付款与已关闭的单也在其中"
        />

        <AdminPager
          v-model:page="cardPage"
          :size="PAGE_SIZE"
          :total="cardTotal"
          @update:page="loadCardOrders"
        />
      </template>

      <!-- ============ tab 四：包场 ============ -->
      <template v-else>
        <div class="om__filters">
          <select
            v-model="bookingFilters.scope"
            class="field-input om__sel"
            @change="applyBookingFilters"
          >
            <option value="">全部场次</option>
            <option v-for="s in BOOKING_SCOPES" :key="s.value" :value="s.value">
              {{ s.label }}
            </option>
          </select>
          <button class="btn btn-ghost om__btn" @click="applyBookingFilters">查询</button>
          <button class="btn btn-ghost om__btn" @click="resetBookingFilters">重置</button>
          <!-- 这一条同样没有导出实现 -->
        </div>

        <LoadingMask :loading="bookingLoading" />

        <div v-if="bookings.length" class="om__list">
          <div v-for="b in bookings" :key="b.id" class="orow">
            <div class="orow__head">
              <span class="orow__no">{{ b.bookingNo }}</span>
              <!-- 状态中文用后端给的 statusLabel（BookingVo 2026-10-16 起带上它） -->
              <span :class="bookingStatusCls(b.status)">{{ b.statusLabel }}</span>
            </div>

            <!-- 左边是包场时段（这一条流水的主信息）、右边是包场费 -->
            <div class="orow__line">
              <span class="orow__left">{{ bookingRangeText(b) }}</span>
              <span class="orow__right">
                <span class="orow__amount">¥{{ formatMoney(b.price) }}</span>
              </span>
            </div>

            <!--
              包场人只有 ID、没有昵称（BookingVo 不带），而接口也不支持按用户筛 ——
              所以这里只把 ID 印出来供核对，不像其余几条那样做成可点的
            -->
            <p class="orow__meta">
              包场人 用户 #{{ b.hostUserId }}
              · 排期于 {{ formatDateTime(b.createdAt) }}
              <template v-if="b.paidAt"> · 付款于 {{ formatDateTime(b.paidAt) }}</template>
              <template v-if="b.refundAmount != null">
                · 已退 ¥{{ formatMoney(b.refundAmount) }}
              </template>
              <template v-if="b.remark"> · {{ b.remark }}</template>
            </p>
          </div>
        </div>

        <EmptyState
          v-else-if="!bookingLoading"
          icon="🎫"
          text="还没有包场场次"
          hint="在「包场」页排一场，或等顾客付款后回这里看"
        />

        <AdminPager
          v-model:page="bookingPage"
          :size="PAGE_SIZE"
          :total="bookingTotal"
          @update:page="loadBookings"
        />
      </template>
    </div>

    <p class="om__hint">
      <b>四条流水都在这一页</b>：<b>计时订单</b>（用户端「开门计时」产生的）、
      <b>商品订单</b>（商城购买单）、<b>月卡</b>（购卡单，未付款与已关闭的也在其中）与
      <b>包场</b>（排期场次，含退款记录）。开卡、停卡、排期、取消这些<b>动作</b>仍在
      「用户」页的月卡弹层与「包场」页里 —— 这一页只管「谁、什么时候、花了多少」。
      <b>点计时订单的任意一单可看订单详情</b>（与用户端订单详情是同一份账单）；
      金额上带「计价 / 实付」与减免明细，指的就是月度优惠、月卡与活动各自免了多少。
      <b>月卡与包场没有导出按钮</b>：后端还没有这两类单据的导出实现（见文件头那段说明）。
    </p>

    <!-- ---------------- 人工调整时长 ---------------- -->
    <AdminSheet v-model:visible="adjustVisible" title="调整订单时长" :error="adjustError">
      <div class="field">
        <label class="field-label">订单</label>
        <p class="om__adj-order">
          {{ adjustTarget?.orderNo }}<br />
          {{ adjustTarget ? userText(adjustTarget) : '' }} · 开门于
          {{ formatDateTime(adjustTarget?.startTime) }}
        </p>
      </div>

      <div class="field">
        <label class="field-label" for="om-adjust-end">核实后的离场时刻</label>
        <input
          id="om-adjust-end"
          v-model="adjustForm.endTime"
          class="field-input"
          type="datetime-local"
        />
      </div>

      <div class="field">
        <label class="field-label" for="om-adjust-reason">调整原因</label>
        <textarea
          id="om-adjust-reason"
          v-model="adjustForm.reason"
          class="field-input"
          rows="3"
          maxlength="255"
          placeholder="如：用户忘记结束，监控核实 21:30 已离场"
        ></textarea>
      </div>

      <p class="om__adj-hint">
        调整会按新的离场时刻<b>重算账单</b>并直接对顾客生效；使用中的订单调整后转为待支付。
        原因会永久留痕，请写清依据。
      </p>

      <template #footer>
        <button class="btn btn-ghost" @click="adjustVisible = false">取消</button>
        <button class="btn btn-primary" :disabled="adjusting" @click="submitAdjust">
          {{ adjusting ? '提交中…' : '确定调整' }}
        </button>
      </template>
    </AdminSheet>
  </div>
</template>

<style scoped>
/*
 * 四条流水的切换卡片。
 *
 * 列宽自适应（每列至少 150px）：窄屏两列、宽屏四列 —— 写死四列在手机上会把
 * 「计时订单」压成两三个字一行，而这一页的手机使用场景是真实存在的。
 * 每张卡片自带一行副标题说明口径：管理员不必点进去才知道
 * 「商品订单」里装的是购买单、也没有「在店时长」。
 * 选中态的配色与后台顶部导航的高亮一致（主色底 + 主色字），一眼认得出。
 */
/* 标题行：左边标题、右边「导出总流水」—— 它不属于任何一个 tab，所以放页面级 */
.om__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--sp-2);
}

.om__types {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}

.om__type {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  min-height: 52px;
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r-btn);
  background: #fff;
  text-align: left;
}

.om__type--on {
  border-color: var(--c-primary);
  background: var(--c-primary-pale);
}

.om__type-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text-sub);
}

.om__type--on .om__type-name {
  color: var(--c-primary);
}

.om__type-desc {
  font-size: 11px;
  color: var(--c-text-muted);
}

.om__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}

/* ⚠️ .field-input 是 width:100%（给整行的表单字段用的），在 flex 行里要显式收窄 */
.om__filters .field-input {
  width: auto;
  height: 38px;
  font-size: 13px;
}

.om__sel {
  flex: 0 0 auto;
  min-width: 108px;
  padding: 0 var(--sp-2);
}

.om__uid {
  flex: 0 1 110px;
  min-width: 88px;
}

.om__time {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 0 1 auto;
}

.om__time-label {
  font-size: 12px;
  color: var(--c-text-sub);
}

.om__dt {
  flex: 0 1 auto;
  min-width: 168px;
}

.om__check {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--c-text-sub);
  white-space: nowrap;
}

.om__btn {
  width: auto;
  height: 38px;
  padding: 0 var(--sp-4);
  font-size: 13px;
  flex-shrink: 0;
}

.om__list {
  display: flex;
  flex-direction: column;
}

.orow {
  padding: var(--sp-3) 0;
}

.orow + .orow {
  border-top: 1px solid var(--c-border);
}

/*
 * 可点的订单卡片（整卡进详情）。
 *
 * 负边距 + 等量内边距：hover 的底色要比文字宽一点，看起来才像一整块可点的区域；
 * 完全不加的话，底色紧贴文字边缘，像是文字被选中了。
 * 溢出的部分落在卡片的 padding 里，不会顶到卡片外。
 */
.orow--link {
  cursor: pointer;
  margin-left: calc(-1 * var(--sp-2));
  margin-right: calc(-1 * var(--sp-2));
  padding-left: var(--sp-2);
  padding-right: var(--sp-2);
  border-radius: var(--r-btn);
}

.orow--link:hover {
  background: var(--c-card);
}

/*
 * 键盘焦点也要看得出来：只有 hover 的话，用 Tab 翻流水的管理员
 * 根本不知道光标停在哪一行上
 */
.orow--link:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: -2px;
}

.orow__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
}

/*
 * 「调整时长」推到这一行的最右：它属于「这一行可以做的动作」，
 * 与左边那串状态标记不是一类东西 —— 挨着状态放，看的人会以为它也是个标记。
 */
.om__op {
  flex-shrink: 0;
  margin-left: auto;
  height: 28px;
  padding: 0 var(--sp-3);
  border-radius: var(--r-pill);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-size: 12px;
}

.orow__no {
  font-size: 12px;
  color: var(--c-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.orow__line {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
}

.orow__left {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
  /* 昵称/商品名过长时换行，不要把右边的金额挤出这一行 */
  overflow-wrap: anywhere;
}

/*
 * 卡片上的用户 ID（2026-10-16 加）。
 *
 * 与昵称并排、压小一号、用等宽字体：它是拿来核对与点击的，不该跟昵称抢注意力，
 * 但要能和昵称一眼分开。给一点淡主色底 —— 它是个可点的东西，不是标记。
 */
.orow__uid {
  margin-left: var(--sp-2);
  padding: 0 5px;
  border-radius: var(--r-pill);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-size: 11px;
  font-weight: 400;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  cursor: pointer;
  white-space: nowrap;
}

.orow__uid:hover {
  text-decoration: underline;
}

/* 时长与金额同住右侧：两者是「这单多久、多少」，读的时候本就是一体的 */
.orow__right {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  flex-shrink: 0;
}

/* 时长比金额轻一档 —— 金额是这一行最该被看到的数字 */
.orow__stay {
  font-size: 12px;
  color: var(--c-text-sub);
}

/*
 * 优惠前的计价。比实付轻一档：它回答的是「本来要付多少」，
 * 是实付那个数字的注解，不该跟实付抢注意力
 */
.orow__origin {
  font-size: 12px;
  color: var(--c-text-muted);
  font-variant-numeric: tabular-nums;
}

.orow__amount {
  font-size: 15px;
  font-weight: 600;
  color: var(--c-primary);
  /* 等宽数字：翻页时数字不会因字形宽窄而左右跳动 */
  font-variant-numeric: tabular-nums;
}

/*
 * 减免明细。
 *
 * 摊成一个个小标签而不是一句「共减 ¥23.50」：三种优惠的出资方不是同一个
 * （月度累计优惠 / 月卡 / 活动），管理员要能一眼看出这一单便宜在哪儿 ——
 * 合成一句的话，月卡免掉的钱会被读成活动送的
 */
.orow__cuts {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-1) var(--sp-2);
  margin-top: var(--sp-1);
}

.orow__cut {
  padding: 1px var(--sp-2);
  border-radius: var(--r-pill);
  background: var(--c-card);
  color: var(--c-text-sub);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

/*
 * 活动减免用主色 —— 与账单里的口径一致：活动是「此刻正在发生的优惠」，
 * 比一张长期持有的月卡更值得被看见（月卡保持中性灰）
 */
.orow__cut--activity {
  background: var(--c-primary-pale);
  color: var(--c-primary);
}

/* 合计那一枚只留文字，不加底色：它是前几项的和，不是第四种优惠 */
.orow__cut--sum {
  padding-left: 0;
  padding-right: 0;
  background: transparent;
  font-weight: 600;
}

/*
 * 人工调整的留痕（原因 / 处理人 / 时刻）：2026-10-16 从 tooltip 挪到卡片上。
 * 左侧那道黄边与头部的「已调整」标签同一套配色 —— 两者说的本来就是同一件事，
 * 一处在头部当标记、一处在底部当说明
 */
.orow__adjust {
  margin-top: var(--sp-2);
  padding-left: var(--sp-2);
  border-left: 2px solid var(--c-warning);
  font-size: 11px;
  line-height: 1.7;
  color: var(--c-text-sub);
}

.orow__adjust-tag {
  margin-right: var(--sp-1);
  font-weight: 600;
  color: var(--c-warning);
}

.orow__meta {
  margin-top: var(--sp-1);
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-text-muted);
}

/* ---------- 收支概览（2026-10-16 加） ---------- */
.fin__source {
  margin-bottom: var(--sp-3);
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-text-muted);
}

.fin__group-title {
  margin-bottom: var(--sp-2);
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text-sub);
}

.fin__group-title--gap {
  margin-top: var(--sp-4);
}

.fin__grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--sp-2);
}

.fin__cell {
  padding: var(--sp-3);
  border-radius: var(--r-btn);
  background: var(--c-card);
}

.fin__label {
  font-size: 11px;
  color: var(--c-text-muted);
}

.fin__value {
  margin-top: 2px;
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

/* 支出：做成一眼看得出的「无数据」，而不是一个像真数据的数字 */
.fin__value--none {
  color: var(--c-text-muted);
}

.fin__note {
  margin-top: var(--sp-2);
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-text-muted);
}

.om__hint {
  margin-top: var(--sp-2);
  font-size: 11px;
  color: var(--c-text-muted);
  line-height: 1.6;
}

/* 调整弹层里那段「这是哪一单」的只读信息 */
.om__adj-order {
  font-size: 13px;
  line-height: 1.7;
  color: var(--c-text);
  overflow-wrap: anywhere;
}

/* 弹层底部的后果说明：调整会重算账单且直接对顾客生效，不能只靠一句标题交代 */
.om__adj-hint {
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-muted);
}
</style>
