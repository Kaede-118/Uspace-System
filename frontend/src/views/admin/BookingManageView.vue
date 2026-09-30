<script setup>
/**
 * 包场排期（运营后台）。
 *
 * <p><b>这里只排期，不收款</b>：新建出来的场次是「待付款」—— 时段已被占住
 * （不会再排进别的包场），但<b>准入尚未生效</b>，散客照常可以进店。
 * 付款由包场人在用户端完成，付完才产生排他性、才生成邀请链接。
 *
 * <p>由此有两条只对「待付款」开放的规则：<b>改期与取消</b>。
 * 已付款的场次要改，得同时处理退款，那属于运营流程而不是一个「改期」接口。
 *
 * <p>⚠️ <b>时段冲突不做前端预判</b>：后端排期时会依次校验「结束晚于开始」
 * 「开始时刻在将来」「不与既有包场重叠」「不与停业区间重叠」四种情况，
 * 各自有明确的错误码与文案。前端再算一遍等于把判定规则抄成两份，
 * 两处一旦漂移，就会出现「前端说可以、后端说不行」这种最难解释的现象。
 * 这里只负责把后端的文案原样显示出来。
 *
 * <p>⚠️ <b>改期不校验过去的时间，新建校验</b> —— 这不是笔误：
 * 新建一场已经开始的包场没有意义（邀请链接刚生成就只剩一半可用），
 * 而把一场已经开始的场次往后挪是合理诉求。所以只有新建表单上有 {@code min}。
 */
import { ref, computed, onMounted } from 'vue'
import {
  listBookings,
  createBooking,
  updateBooking,
  deleteBooking,
  revokeBooking,
  listUsers,
  getUser
} from '@/api/admin'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import {
  formatDate,
  formatDateTime,
  formatMoney,
  formatTime,
  parseDateTime,
  toInputDateTime,
  fromInputDateTime
} from '@/utils/format'
import { bookingStatusOf } from '@/utils/labels'
import AdminPager from '@/components/AdminPager.vue'
import AdminSheet from '@/components/AdminSheet.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

/** 每页条数。⚠️ 本接口的分页参数是 page + size（商品那边才是 pageNum + pageSize） */
const PAGE_SIZE = 10

/**
 * 包场时段挡位。
 *
 * <p><b>「日场 / 夜场」的划分沿用计费规则那一套</b>（日场 10:00–22:00，
 * 夜场 22:00–次日 10:00），半场各取一半：日场半场到 16:00，夜场半场到次日 04:00。
 * 这样包场的边界与计费的边界是同一套，账单上「包场时段不计费」剪出来的区间
 * 才不会一半落在日场、一半落在夜场。
 *
 * <p>⚠️ <b>挡位只是前端的输入便利，不是领域概念</b>：接口收的仍然是
 * {@code startAt} / {@code endAt} 两个时刻，后端不知道「日场半场」这回事。
 * 把它做进数据库的话，将来改挡位（比如夜场改成 22:00–次日 08:00）
 * 就得连历史数据一起改，而历史订单要的是当时那个时段。
 *
 * <p>{@code startHour} 是当天的起始钟点，{@code hours} 是时长 ——
 * 结束时刻由两者算出来，跨零点由 Date 自己进位，不必手写「次日」的加法。
 */
const BOOKING_PRESETS = [
  { value: 'DAY_HALF', label: '日场半场', startHour: 10, hours: 6, hint: '10:00 – 16:00' },
  { value: 'DAY_FULL', label: '日场全场', startHour: 10, hours: 12, hint: '10:00 – 22:00' },
  { value: 'NIGHT_HALF', label: '夜场半场', startHour: 22, hours: 6, hint: '22:00 – 次日 4:00' },
  { value: 'NIGHT_FULL', label: '夜场全场', startHour: 22, hours: 12, hint: '22:00 – 次日 10:00' },
  { value: 'CUSTOM', label: '自由时段', startHour: null, hours: null, hint: '自己填起止时刻' }
]

/** 自由时段那一档的 value，多处要用，抽出来免得写错字面量。 */
const CUSTOM_PRESET = 'CUSTOM'

/** 一天的毫秒数。算结束时刻用。 */
const DAY_MS = 24 * 3600 * 1000

const page = ref(1)
const total = ref(0)
const records = ref([])
const loading = ref(true)

/**
 * 包场人昵称缓存：{ [userId]: 昵称 | null }。
 *
 * <p>⚠️ {@code BookingVo} <b>只有 hostUserId，没有昵称</b>，接口文档里写明了
 * 「需要的话前端按 ID 自行查用户」。这里就按 ID 查，并且：
 * <ul>
 *   <li>去重 —— 同一页里同一个人排两场只查一次</li>
 *   <li>并发查、互不牵连（{@code allSettled}）</li>
 *   <li><b>查不到也要记下来（存 null）</b>：不记的话，翻回这一页会再查一遍，
 *       而一直查不到的人会一直重查，页面上还一串报错</li>
 *   <li>失败<b>不弹提示</b>：一页十条就是十个错误提示，而昵称只是辅助信息，
 *       显示成 {@code #12} 完全能用</li>
 * </ul>
 */
const hostNames = ref({})

/* ---------------- 表单弹层 ---------------- */

const formVisible = ref(false)
/** 正在改期的那场；为 null 表示「新建」 */
const editing = ref(null)
/**
 * 表单。
 *
 * <p>{@code preset} + {@code date} 是「挡位模式」下的输入（只选开始日期），
 * {@code startAt} / {@code endAt} 是「自由时段」下的输入。两组字段都留着，
 * 切换挡位时不至于把已经填好的时刻丢掉。
 */
const form = ref({
  hostUserId: null,
  hostName: '',
  preset: 'DAY_HALF',
  date: '',
  startAt: '',
  endAt: '',
  price: '',
  remark: ''
})
const formError = ref('')
const submitting = ref(false)

/** 弹层里的第二层「视图」：选包场人。⚠️ 换内容而不是再叠一个弹层 */
const pickingHost = ref(false)

/* ---------------- 包场人选择器 ---------------- */

const userKeyword = ref('')
const users = ref([])
const userLoading = ref(false)

/* ---------------- 取消确认（待付款） ---------------- */

const removing = ref(null)
const removeError = ref('')

/* ---------------- 撤销退款（已付款） ---------------- */

/** 正在撤销的那场；为 null 表示弹层没开 */
const revoking = ref(null)
/** 选中的退款方式：MANUAL / ONLINE */
const refundMode = ref('ONLINE')
const revokeError = ref('')

/** 新建时「开始时刻」不能早于现在。用本地时间拼，不做时区换算（前后端都在同一台机器上）。 */
const nowInput = computed(() => toInputDateTime(formatDateTime(new Date())))

/** 日期选择框的最早可选值：今天。同一天里过了钟点的情况交给后端判（40911），前端不重算一遍。 */
const todayInput = computed(() => formatDate(formatDateTime(new Date())))

/**
 * 按当前表单算出实际的起止时刻。
 *
 * <p>两条路径：挡位模式由「开始日期 + 挡位」算出来（跨零点交给 {@code Date} 进位），
 * 自由时段模式直接用填的两个时刻。
 *
 * @returns {{startAt: string, endAt: string}|null} 后端格式的两个时刻；填不全时返回 null
 */
function resolveRange() {
  if (form.value.preset === CUSTOM_PRESET) {
    const startAt = fromInputDateTime(form.value.startAt)
    const endAt = fromInputDateTime(form.value.endAt)
    return startAt && endAt ? { startAt, endAt } : null
  }

  const preset = BOOKING_PRESETS.find((p) => p.value === form.value.preset)
  const m = String(form.value.date).match(/^(\d{4})-(\d{2})-(\d{2})$/)
  if (!preset || !m) return null

  const start = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), preset.startHour, 0, 0)
  const end = new Date(start.getTime() + preset.hours * 3600 * 1000)
  return { startAt: formatDateTime(start), endAt: formatDateTime(end) }
}

/**
 * 时段预览：「2026-10-01 10:00 ~ 16:00（6 小时）」。
 *
 * <p>挡位模式下这一行是必须的 —— 管理员只选了「日场半场 + 10 月 1 日」，
 * 得让他当场看到这到底是哪六个小时，尤其是跨零点的那两档。
 */
const rangePreview = computed(() => {
  const range = resolveRange()
  if (!range) return ''
  const { startAt, endAt } = range
  const hours = Math.round((parseDateTime(endAt) - parseDateTime(startAt)) / 3600000)
  const sameDay = startAt.slice(0, 10) === endAt.slice(0, 10)
  return sameDay
    ? `${startAt.slice(0, 16)} ~ ${formatTime(endAt)}（${hours} 小时）`
    : `${startAt.slice(0, 16)} ~ ${endAt.slice(0, 16)}（${hours} 小时）`
})

/**
 * 从既有的起止时刻反推它属于哪个挡位（改期时回填用）。
 *
 * <p>反推不出来就退回「自由时段」—— 历史数据里什么时段都可能有，
 * 硬套一个挡位会让表单显示成与实际不符的时间。
 *
 * @param {string} startAt 后端格式的开始时刻
 * @param {string} endAt   后端格式的结束时刻
 * @returns {string} 挡位 value，认不出时为 {@link CUSTOM_PRESET}
 */
function detectPreset(startAt, endAt) {
  const start = parseDateTime(startAt)
  const end = parseDateTime(endAt)
  if (!start || !end) return CUSTOM_PRESET
  // 挡位都是整点开始；带分钟的时段一律按自由时段处理
  if (start.getMinutes() !== 0 || start.getSeconds() !== 0) return CUSTOM_PRESET

  const hours = Math.round((end.getTime() - start.getTime()) / 3600000)
  const hit = BOOKING_PRESETS.find((p) => p.startHour === start.getHours() && p.hours === hours)
  return hit ? hit.value : CUSTOM_PRESET
}

/** 当前页里哪些行可以改/取消（待付款的）。 */
function isEditable(row) {
  return row.status === 'PENDING_PAYMENT'
}

/** 哪些行可以撤销退款（已付款的）。 */
function isPaid(row) {
  return row.status === 'PAID'
}

/**
 * 支付通道的中文与「能不能原路退回」。
 *
 * <p>传截图人工核销（{@code QR_UPLOAD}）的钱根本没经过支付平台，
 * 没有可退的流水 —— 这两种情况下原路退回这个选项不该出现，
 * 让管理员选了再被后端拒，等于白跑一趟。
 */
const REFUND_CHANNELS = {
  WXPAY_JSAPI: { label: '微信内浏览器', online: true },
  WXPAY_H5: { label: '微信外手机浏览器', online: true },
  ALIPAY_WAP: { label: '支付宝手机网站支付', online: true },
  QR_UPLOAD: { label: '传截图人工核销', online: false }
}

/** 退款方式的中文。 */
const REFUND_MODE_TEXT = { MANUAL: '人工退款', ONLINE: '原路退回' }

/** 这场包场当初是不是走线上通道收的款（决定能不能原路退回）。 */
function paidOnline(row) {
  const channel = REFUND_CHANNELS[row.paymentMethod]
  return !!channel && channel.online
}

/**
 * 拉取列表，并顺带解析本页的包场人昵称。
 *
 * <p>页码越界（删掉最后一页最后一条）时夹回最后一页重拉 —— 与公告页同因：
 * 停在不存在的页码上，列表空白且不报错，与「本来就没数据」长得一样。
 */
async function load() {
  loading.value = true
  try {
    const resp = await listBookings({ page: page.value, size: PAGE_SIZE })
    const count = resp.data?.total || 0
    const maxPage = Math.max(1, Math.ceil(count / PAGE_SIZE))

    if (page.value > maxPage) {
      page.value = maxPage
      return load()
    }

    records.value = resp.data?.records || []
    total.value = count
    resolveHostNames(records.value)
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    loading.value = false
  }
}

/**
 * 解析本页记录的包场人昵称（并发、去重、失败静默）。
 *
 * @param {Array} rows 当前页的包场记录
 */
async function resolveHostNames(rows) {
  const ids = [
    ...new Set(
      rows.map((r) => r.hostUserId).filter((id) => id && !(id in hostNames.value))
    )
  ]

  await Promise.allSettled(
    ids.map(async (id) => {
      try {
        const resp = await getUser(id)
        hostNames.value[id] = resp.data?.nickname || resp.data?.username || null
      } catch (err) {
        // 查不到（用户被删、接口挂了）也记一笔，只是留空 —— 模板会回落显示 #id
        hostNames.value[id] = null
      }
    })
  )
}

/** 包场人的展示文案：有昵称就带昵称，没有就只显示 ID。 */
function hostLabel(userId) {
  const name = hostNames.value[userId]
  return name ? `${name}（#${userId}）` : `#${userId}`
}

/** 时段文案：同一天时省略后半段的日期。 */
function timeRange(row) {
  const { startAt, endAt } = row
  if (!startAt || !endAt) return '—'
  const sameDay = String(startAt).slice(0, 10) === String(endAt).slice(0, 10)
  return sameDay
    ? `${formatDateTime(startAt)} – ${formatTime(endAt)}`
    : `${formatDateTime(startAt)} – ${formatDateTime(endAt)}`
}

/** 翻页。 */
function onPageChange(target) {
  page.value = target
  load()
}

/** 打开「新建」表单。 */
function openCreate() {
  editing.value = null
  form.value = {
    hostUserId: null,
    hostName: '',
    preset: 'DAY_HALF',
    // 日期预填明天：今天的那几档多半已经开始了，预填未来一天能少一次 40911
    date: formatDate(formatDateTime(new Date(Date.now() + DAY_MS))),
    startAt: '',
    endAt: '',
    price: '',
    remark: ''
  }
  formError.value = ''
  pickingHost.value = false
  formVisible.value = true
}

/**
 * 打开「改期」表单。
 *
 * <p>⚠️ 逐字段取新对象，且时间要过一遍 {@link toInputDateTime} ——
 * 后端给的是 {@code 2026-09-30 14:00:00}，而 {@code datetime-local}
 * 只认带 {@code T} 的 {@code 2026-09-30T14:00}。格式不对时浏览器
 * <b>静默地把输入框置空</b>（不是报错），表现就是「打开表单，时间是空的」。
 */
function openEdit(row) {
  editing.value = row
  form.value = {
    hostUserId: row.hostUserId,
    hostName: hostLabel(row.hostUserId),
    // 能对上挡位就回填挡位（只改日期即可），对不上退回自由时段
    preset: detectPreset(row.startAt, row.endAt),
    date: String(row.startAt || '').slice(0, 10),
    startAt: toInputDateTime(row.startAt),
    endAt: toInputDateTime(row.endAt),
    price: String(row.price ?? ''),
    remark: row.remark || ''
  }
  formError.value = ''
  pickingHost.value = false
  formVisible.value = true
}

/** 打开包场人选择视图，并先列一页用户出来（多数时候不搜也能直接选）。 */
function openHostPicker() {
  pickingHost.value = true
  userKeyword.value = ''
  searchUsers()
}

/** 按关键字查用户。 */
async function searchUsers() {
  userLoading.value = true
  try {
    const resp = await listUsers({ keyword: userKeyword.value.trim(), page: 1, size: 10 })
    users.value = resp.data?.records || []
  } catch (err) {
    users.value = []
    toastError(errorMessage(err, '用户查询失败'))
  } finally {
    userLoading.value = false
  }
}

/** 选中一个包场人。 */
function pickHost(user) {
  form.value.hostUserId = user.id
  form.value.hostName = user.nickname || user.username
  pickingHost.value = false
}

/**
 * 提交（新建或改期）。
 *
 * <p>⚠️ 改期的请求体里<b>不含 hostUserId</b>（后端 DTO 里根本没这个字段）——
 * 换包场人等于换一单生意，正确做法是取消原场另建一场。
 */
async function submit() {
  const range = resolveRange()
  if (!range) {
    formError.value =
      form.value.preset === CUSTOM_PRESET
        ? '请填写包场的开始与结束时刻'
        : '请选择包场的开始日期'
    return
  }
  const price = Number(form.value.price)
  if (form.value.price === '' || !Number.isFinite(price) || price < 0) {
    formError.value = '包场费不能为空，且不能为负数'
    return
  }
  if (!editing.value && !form.value.hostUserId) {
    formError.value = '请选择包场人'
    return
  }

  submitting.value = true
  formError.value = ''
  try {
    const body = { startAt: range.startAt, endAt: range.endAt, price, remark: form.value.remark.trim() }
    if (editing.value) {
      await updateBooking(editing.value.id, body)
      toastSuccess('已改期')
    } else {
      await createBooking({ ...body, hostUserId: form.value.hostUserId })
      toastSuccess('已排期，等包场人付款后生效')
    }
    formVisible.value = false
    await load()
  } catch (err) {
    // 时段冲突（40912）、与停业重叠（40913）、开始时刻在过去（40911）都从这里出来，
    // 文案由后端给，前端不做二次解释
    formError.value = errorMessage(err, '保存失败')
  } finally {
    submitting.value = false
  }
}

/**
 * 打开撤销退款弹层。
 *
 * <p>退款方式给一个默认值：<b>当初走线上通道付的，默认原路退回</b>
 * （那是正经那条路，不用管理员垫钱）；人工核销的只能人工退，
 * 连选项都不显示 —— 让管理员选了再被后端拒，等于白跑一趟。
 */
function openRevoke(row) {
  revokeError.value = ''
  refundMode.value = paidOnline(row) ? 'ONLINE' : 'MANUAL'
  revoking.value = row
}

/**
 * 确认撤销并退款。
 *
 * <p>失败有两种，都要显示在弹层里（toast 的层级低于弹层，会被遮住）：
 * 40934 说明这场已经不是可退款状态了（多半是别人刚撤过），
 * 50201 说明原路退回失败、状态已回滚 —— 后者尤其要说清楚，
 * 因为「钱没退成」与「钱退了但没记账」是两件完全的事。
 */
async function confirmRevoke() {
  submitting.value = true
  revokeError.value = ''
  try {
    await revokeBooking(revoking.value.id, refundMode.value)
    toastSuccess(refundMode.value === 'ONLINE' ? '已撤销，款已原路退回' : '已撤销，请确认线下已退款')
    revoking.value = null
    await load()
  } catch (err) {
    revokeError.value = errorMessage(err, '撤销失败')
  } finally {
    submitting.value = false
  }
}

/** 执行取消（逻辑删除，只有待付款的能取消）。 */
async function confirmRemove() {
  submitting.value = true
  removeError.value = ''
  try {
    await deleteBooking(removing.value.id)
    toastSuccess('已取消')
    removing.value = null
    await load()
  } catch (err) {
    removeError.value = errorMessage(err, '取消失败')
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="card">
      <div class="card-title">
        包场排期
        <span class="card-sub">{{ total }} 场</span>
        <button class="btn btn-primary booking__new" @click="openCreate">排一场</button>
      </div>

      <LoadingMask :loading="loading" />

      <div v-if="records.length" class="booking__list">
        <div v-for="row in records" :key="row.id" class="booking__item">
          <div class="booking__head">
            <span class="booking__no">{{ row.bookingNo }}</span>
            <span :class="bookingStatusOf(row.status).cls">
              {{ bookingStatusOf(row.status).label }}
            </span>
          </div>

          <div class="booking__time">📅 {{ timeRange(row) }}</div>

          <div class="booking__meta">
            <span>包场人 {{ hostLabel(row.hostUserId) }}</span>
            <span class="booking__price">¥{{ formatMoney(row.price) }}</span>
          </div>

          <p v-if="row.remark" class="booking__remark">{{ row.remark }}</p>

          <!--
            退款信息。已撤销的场次要把钱的事写清楚 ——
            「已取消」与「已退款」在账上是两回事，对账时要一眼看得出来
          -->
          <p v-if="row.status === 'REFUNDED'" class="booking__refund">
            已退款 ¥{{ formatMoney(row.refundAmount) }} ·
            {{ REFUND_MODE_TEXT[row.refundMode] || row.refundMode }} ·
            {{ formatDateTime(row.refundedAt) }}
            <template v-if="row.refundNo"> · 退款单号 {{ row.refundNo }}</template>
          </p>

          <div class="booking__foot">
            <span class="booking__meta">
              {{ row.paidAt ? `付款于 ${formatDateTime(row.paidAt)}` : '尚未付款' }}
            </span>
            <!--
              待付款的可以改期与取消（没有钱的事）；
              已付款的只能撤销退款 —— 改期要同时处理退款，那属于另一件事
            -->
            <span v-if="isEditable(row)" class="booking__ops">
              <button class="booking__op" @click="openEdit(row)">改期</button>
              <button class="booking__op booking__op--danger" @click="removing = row">取消</button>
            </span>
            <span v-else-if="isPaid(row)" class="booking__ops">
              <button class="booking__op booking__op--danger" @click="openRevoke(row)">撤销退款</button>
            </span>
          </div>
        </div>
      </div>

      <EmptyState
        v-else-if="!loading"
        icon="📅"
        text="还没有包场"
        hint="排一场之后，把邀请链接发给包场人付款即可生效"
      />

      <AdminPager :page="page" :size="PAGE_SIZE" :total="total" @update:page="onPageChange" />
    </div>

    <p class="booking__hint">
      付款前散客照常可以进店 —— 没付款的包场不该把顾客挡在门外。
      包场人与被邀请者要等到付款后拿到邀请链接，提前 15 分钟起才进得来。
    </p>

    <!-- 新建 / 改期 -->
    <AdminSheet
      v-model:visible="formVisible"
      :title="pickingHost ? '选择包场人' : editing ? '包场改期' : '包场排期'"
      :error="pickingHost ? '' : formError"
    >
      <!-- 视图二：选包场人。同一层弹层里换内容，不再叠一个弹层 -->
      <template v-if="pickingHost">
        <div class="booking__search">
          <input
            v-model="userKeyword"
            class="field-input"
            placeholder="搜用户名 / 昵称 / QQ 号"
            @keyup.enter="searchUsers"
          />
          <button class="btn btn-ghost booking__search-btn" @click="searchUsers">搜索</button>
        </div>

        <LoadingMask :loading="userLoading" />

        <div class="booking__users">
          <button
            v-for="u in users"
            :key="u.id"
            class="booking__user"
            @click="pickHost(u)"
          >
            <span class="booking__user-name">{{ u.nickname || u.username }}</span>
            <span class="booking__user-sub">{{ u.username }} · #{{ u.id }}</span>
            <!--
              被禁用的用户要标出来：后端建单时只校验「用户存在」，不校验状态，
              选了他单子建得出来，但他登不上去付款 —— 这场包场会永远停在待付款
            -->
            <span v-if="u.status === 0" class="tag tag-danger">已禁用</span>
          </button>

          <p v-if="!userLoading && !users.length" class="booking__hint">没有匹配的用户</p>
        </div>
      </template>

      <!-- 视图一：排期表单 -->
      <template v-else>
        <div class="field">
          <label class="field-label">包场人</label>
          <button class="booking__picker" @click="openHostPicker">
            <span v-if="form.hostName">{{ form.hostName }}（#{{ form.hostUserId }}）</span>
            <span v-else class="booking__picker-empty">点击选择用户</span>
          </button>
          <p v-if="editing" class="booking__hint">改期不能换包场人 —— 换人请取消原场另排一场。</p>
        </div>

        <div class="field">
          <label class="field-label">时段</label>
          <div class="booking__presets">
            <button
              v-for="p in BOOKING_PRESETS"
              :key="p.value"
              class="booking__preset"
              :class="{ 'booking__preset--on': form.preset === p.value }"
              @click="form.preset = p.value"
            >
              <span class="booking__preset-name">{{ p.label }}</span>
              <span class="booking__preset-hint">{{ p.hint }}</span>
            </button>
          </div>
        </div>

        <!--
          选了挡位就只需要挑开始日期，时刻由挡位算出来；
          自由时段才回到手填起止时刻 —— 两者是互斥的输入方式，同一个时段
        -->
        <div v-if="form.preset !== CUSTOM_PRESET" class="field">
          <label class="field-label" for="b-date">开始日期</label>
          <input
            id="b-date"
            v-model="form.date"
            class="field-input"
            type="date"
            :min="editing ? undefined : todayInput"
          />
        </div>

        <template v-else>
          <div class="field">
            <label class="field-label" for="b-start">开始时刻</label>
            <input
              id="b-start"
              v-model="form.startAt"
              class="field-input"
              type="datetime-local"
              :min="editing ? undefined : nowInput"
            />
          </div>

          <div class="field">
            <label class="field-label" for="b-end">结束时刻</label>
            <input id="b-end" v-model="form.endAt" class="field-input" type="datetime-local" />
          </div>
        </template>

        <!-- 算出来的到底是哪一段，当场给出来 —— 跨零点的那两档尤其要看一眼 -->
        <p v-if="rangePreview" class="booking__range">{{ rangePreview }}</p>

        <div class="field">
          <label class="field-label" for="b-price">包场费（元）</label>
          <input id="b-price" v-model="form.price" class="field-input" type="number" min="0" step="0.01" placeholder="一口价，0 元也可以" />
        </div>

        <div class="field">
          <label class="field-label" for="b-remark">备注（选填）</label>
          <textarea id="b-remark" v-model="form.remark" class="field-input" rows="2" maxlength="255" placeholder="如：老顾客生日场" />
        </div>

        <p class="booking__hint">
          时段与既有包场或停业区间重叠时会被后端拒绝，提示语会显示在这里。
        </p>
      </template>

      <template #footer>
        <template v-if="pickingHost">
          <button class="btn btn-ghost" @click="pickingHost = false">返回</button>
        </template>
        <template v-else>
          <button class="btn btn-ghost" @click="formVisible = false">取消</button>
          <button class="btn btn-primary" :disabled="submitting" @click="submit">
            {{ submitting ? '提交中…' : '确定' }}
          </button>
        </template>
      </template>
    </AdminSheet>

    <!-- 取消确认 -->
    <AdminSheet
      :visible="!!removing"
      title="取消包场"
      :error="removeError"
      mask-closable
      @update:visible="removing = null"
    >
      <p class="booking__confirm">确定取消「{{ removing?.bookingNo }}」这场包场吗？</p>
      <p class="booking__hint">取消后这个时段会重新开放，已付款的场次需要先退款，因此不在这里受理。</p>

      <template #footer>
        <button class="btn btn-ghost" @click="removing = null">再想想</button>
        <button class="btn btn-danger" :disabled="submitting" @click="confirmRemove">
          {{ submitting ? '处理中…' : '确定取消' }}
        </button>
      </template>
    </AdminSheet>

    <!-- 撤销退款（已付款） -->
    <AdminSheet
      :visible="!!revoking"
      title="撤销包场并退款"
      :error="revokeError"
      @update:visible="revoking = null"
    >
      <p class="booking__confirm">{{ revoking?.bookingNo }}</p>

      <div class="booking__refund-info">
        <span>{{ revoking ? timeRange(revoking) : '' }}</span>
        <span class="booking__refund-amount">
          全额退款 ¥{{ formatMoney(revoking?.price) }}
        </span>
      </div>

      <div class="field">
        <label class="field-label">退款方式</label>
        <div class="booking__refund-modes">
          <!--
            原路退回只在「当初走线上通道付的款」时才给选 ——
            人工核销的钱根本没经过支付平台，没有可退的流水，
            摆出来让管理员选了再被后端拒，等于白跑一趟
          -->
          <button
            v-if="revoking && paidOnline(revoking)"
            class="booking__refund-mode"
            :class="{ 'booking__refund-mode--on': refundMode === 'ONLINE' }"
            @click="refundMode = 'ONLINE'"
          >
            <span class="booking__preset-name">原路退回</span>
            <span class="booking__preset-hint">调支付平台退回顾客原账户，平台侧留有凭证</span>
          </button>

          <button
            class="booking__refund-mode"
            :class="{ 'booking__refund-mode--on': refundMode === 'MANUAL' }"
            @click="refundMode = 'MANUAL'"
          >
            <span class="booking__preset-name">人工退款</span>
            <span class="booking__preset-hint">你已经在线下把钱退给顾客了，这里只是登记</span>
          </button>
        </div>
      </div>

      <p class="booking__hint">
        撤销后这个时段重新开放、邀请链接随之失效，顾客那边会看到「已退款」。
        原路退回失败时状态会自动回滚，这场仍然是已付款，可以稍后重试或改走人工退款。
      </p>

      <template #footer>
        <button class="btn btn-ghost" @click="revoking = null">再想想</button>
        <button class="btn btn-danger" :disabled="submitting" @click="confirmRevoke">
          {{ submitting ? '处理中…' : '确定撤销并退款' }}
        </button>
      </template>
    </AdminSheet>
  </div>
</template>

<style scoped>
.booking__new {
  width: auto;
  height: 32px;
  margin-left: auto;
  padding: 0 var(--sp-4);
  font-size: 13px;
}

.booking__item {
  padding: var(--sp-3) 0;
}

.booking__item + .booking__item {
  border-top: 1px solid var(--c-border);
}

.booking__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
}

.booking__no {
  font-size: 12px;
  color: var(--c-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.booking__time {
  margin-top: var(--sp-1);
  font-size: 13px;
  color: var(--c-text);
}

.booking__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-top: 2px;
  font-size: 11px;
  color: var(--c-text-muted);
}

.booking__price {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-primary);
}

.booking__remark {
  margin-top: 2px;
  font-size: 11px;
  color: var(--c-text-sub);
}

.booking__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-top: var(--sp-2);
}

.booking__ops {
  display: flex;
  gap: var(--sp-4);
  flex-shrink: 0;
}

.booking__op {
  font-size: 12px;
  color: var(--c-primary);
}

.booking__op--danger {
  color: var(--c-danger);
}

.booking__picker {
  width: 100%;
  height: 46px;
  padding: 0 var(--sp-4);
  border: 1px solid var(--c-border);
  border-radius: var(--r-btn);
  background: #fff;
  text-align: left;
  font-size: 14px;
  color: var(--c-text);
}

.booking__picker-empty {
  color: var(--c-text-muted);
}

.booking__search {
  display: flex;
  gap: var(--sp-2);
}

.booking__search-btn {
  width: auto;
  height: 46px;
  padding: 0 var(--sp-4);
  font-size: 14px;
  flex-shrink: 0;
}

.booking__users {
  margin-top: var(--sp-2);
}

.booking__user {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  width: 100%;
  padding: var(--sp-3) 0;
  text-align: left;
}

.booking__user + .booking__user {
  border-top: 1px solid var(--c-border);
}

.booking__user-name {
  font-size: 14px;
  color: var(--c-text);
}

.booking__user-sub {
  flex: 1;
  font-size: 11px;
  color: var(--c-text-muted);
}

.booking__confirm {
  font-size: 14px;
  color: var(--c-text);
}

/* 时段挡位：两列的小卡片，名称与时段各占一行 */
.booking__presets {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--sp-2);
}

.booking__preset {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r-btn);
  background: #fff;
  text-align: left;
}

.booking__preset--on {
  border-color: var(--c-primary);
  background: var(--c-primary-pale);
}

.booking__preset-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text);
}

.booking__preset--on .booking__preset-name {
  color: var(--c-primary);
}

.booking__preset-hint {
  font-size: 11px;
  color: var(--c-text-muted);
}

/* 已撤销场次的退款信息 */
.booking__refund {
  margin-top: var(--sp-2);
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--r-btn);
  background: #f8d7d5;
  color: #a33a36;
  font-size: 11px;
  line-height: 1.6;
}

.booking__refund-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-bottom: var(--sp-3);
  font-size: 13px;
  color: var(--c-text-sub);
}

.booking__refund-amount {
  font-weight: 600;
  color: var(--c-danger);
}

.booking__refund-modes {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}

.booking__refund-mode {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--sp-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r-btn);
  background: #fff;
  text-align: left;
}

.booking__refund-mode--on {
  border-color: var(--c-primary);
  background: var(--c-primary-pale);
}

/* 「2026-10-01 10:00 ~ 16:00（6 小时）」 */
.booking__range {
  margin-bottom: var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--r-btn);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.booking__hint {
  margin-top: var(--sp-2);
  font-size: 11px;
  color: var(--c-text-muted);
  line-height: 1.6;
}
</style>
