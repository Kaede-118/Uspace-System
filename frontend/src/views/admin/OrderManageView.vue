<script setup>
/**
 * 订单流水（运营后台）：<b>计时订单</b>与<b>商品订单</b>两条流水。
 *
 * <p><b>2026-10-16 合并成一页</b>：商品订单原先是「商品」页的第二个 tab，
 * 现在搬到这里与计时订单并列 —— 两者回答的是同一个问题「谁、什么时候、花了多少」，
 * 管理员比对两条流水时不必在两个页面之间来回切；商品页从此只管货架维护。
 *
 * <p><b>为什么是两个 tab、而不是合成一张表</b>：两条流水的字段几乎没有一个重合
 * （一边是「在店多久」，另一边是「买了什么 × 几件」），筛选能力也不一样 ——
 * 计时订单能按时间范围与「只看人工调整」筛，商品订单接口不支持这两种。
 * 硬凑成一张表，一半的列会永远空着，筛选栏还得为两套口径各解释一遍。
 *
 * <p><b>两个 tab 各有自己的筛选与分页</b>，互不影响：来回切换时各自停在上次
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
 * <p>⚠️ <b>两个接口的分页参数不一样</b>（后端如此，不是笔误）：
 * 计时订单用 {@code page} / {@code size}，商品订单用 {@code pageNum} / {@code pageSize}。
 * 传错不会报错，只会永远返回第一页 —— 表现为「翻页没反应」。
 *
 * <p>⚠️ <b>商品订单的卡片不跳详情</b>：它的「详情」是商品与数量，
 * 卡片上已经写全了；订单详情那一页讲的是时长与分段账单，对它是另一回事。
 * 所以只有计时订单的卡片可点。
 */
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listOrders, listAdminProductOrders, adjustOrder } from '@/api/admin'
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
  productOrderStatusCls
} from '@/utils/labels'
import AdminPager from '@/components/AdminPager.vue'
import AdminSheet from '@/components/AdminSheet.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

/** 每页条数。⚠️ 计时订单用 page + size，商品订单用 pageNum + pageSize（后端两套口径） */
const PAGE_SIZE = 10

const route = useRoute()
const router = useRouter()

/** 当前 tab：timing 计时订单 / product 商品订单 */
const type = ref('timing')

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
 * <p>商品订单是只读的、且多半不是管理员进来要看的那一条，所以<b>切过去时才拉</b>：
 * 打开页面就打两个请求，等于让每次看计时订单都白等一次商品订单的查询。
 *
 * @param {string} next timing / product
 */
function switchType(next) {
  type.value = next
  syncQuery()
  if (next === 'product' && !productLoaded.value) loadProductOrders()
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
 */
function syncQuery() {
  const q = {}
  if (type.value === 'product') q.type = 'product'

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

  if (str(q.type) === 'product') type.value = 'product'

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
onMounted(() => {
  initFromQuery()
  if (type.value === 'product') loadProductOrders()
  else loadTiming()
})
</script>

<template>
  <div class="page">
    <div class="card">
      <div class="card-title">订单流水</div>

      <!--
        两条流水做成两张卡片，不是一个下拉筛选 —— 它们是这一页的【两种内容】，
        而不是同一个列表的筛选条件。做成大按钮一眼看得出这一页有哪两条流水，
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
                「已调整」是「用户忘了点结束」的高发信号，值得定期过一眼；
                鼠标悬停能看到调整原因（那是审计信息，不摊在流水上）
              -->
              <span v-if="o.adjusted === 1" class="tag tag-warning" :title="o.adjustReason || ''">
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
      <template v-else>
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
    </div>

    <p class="om__hint">
      两条流水都在这一页：<b>计时订单</b>（用户端「开门计时」产生的）与<b>商品订单</b>（商城购买单）。
      <b>包场</b>在「包场」页、<b>月卡</b>在「用户」页的月卡弹层 —— 它们交付的是「权益」，
      要先经管理员复核，与这两条「提交即交付」的流水不是一回事。
      <b>点计时订单的任意一单可看订单详情</b>（与用户端订单详情是同一份账单）；
      金额上带「计价 / 实付」与减免明细，指的就是月度优惠、月卡与活动各自免了多少。
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
 * 两条流水的切换卡片。
 *
 * 两列等宽、每张卡片自带一行副标题说明口径：管理员不必点进去才知道
 * 「商品订单」里装的是购买单、也没有「在店时长」。
 * 选中态的配色与后台顶部导航的高亮一致（主色底 + 主色字），一眼认得出。
 */
.om__types {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
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

.orow__meta {
  margin-top: var(--sp-1);
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
