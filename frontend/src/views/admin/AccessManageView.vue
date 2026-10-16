<script setup>
/**
 * 进出记录（运营后台）。
 *
 * <p><b>口径（2026-10-16 由使用者定死）：到店 = 开始计时，离店 = 停止计时。</b>
 * 因此这一页的主体数据是<b>订单</b>（{@code start_time} / {@code end_time}），
 * 不是门锁的开门记录 —— 封闭空间的出门是机械推杠、不产生门锁记录，
 * 拿门锁记录当「离店」会永远等不到那一条。
 *
 * <p><b>两个 tab 的分工，别混</b>：
 * <ul>
 *   <li><b>进出记录</b>（订单口径）—— 谁什么时候来、什么时候走，
 *       以及<b>当时店里还有谁</b>。这是复盘与对账时真正要看的那一份</li>
 *   <li><b>门锁开门记录</b>（门锁口径）—— 门到底被开过几次、用的哪串密码。
 *       它是<b>旁证</b>：证明有人在门口输对了密码，不证明谁进谁出</li>
 * </ul>
 *
 * <p><b>名册快照是这一页的核心价值</b>：单看一条记录只知道「某人走了」，
 * 而「他走的时候店里还剩谁」才是排班、纠纷复盘要用的事实。
 * 每行的到店 / 离店两端各带一份快照 —— 到店那份<b>含本人</b>，
 * 离店那份<b>不含本人</b>（半开区间：离店那一刻他就不在店里了）。
 *
 * <p>⚠️ <b>快照是推算的，不是当时存下来的</b>：它按订单的开始 / 结束时刻反推，
 * 好处是历史任意时刻都能还原（顶部「还原某一时刻」就是干这个的），
 * 代价是人工调整过离店时刻的单子，受影响的快照会跟着变。
 */

import { ref, onMounted } from 'vue'
import {
  listOrderTimeline,
  getInstoreSnapshot,
  listAccessRecords,
  syncAccessRecords,
  createAccessRecord,
  mockOpen
} from '@/api/admin'
import { toastError, toastSuccess } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatDateTime, fromInputDateTime } from '@/utils/format'
import AdminPager from '@/components/AdminPager.vue'
import AdminSheet from '@/components/AdminSheet.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

/** 每页条数。两个 tab 共用同一个分页参数口径（page + size） */
const PAGE_SIZE = 20

/** 当前 tab：traffic 进出记录 / lock 门锁开门记录 */
const tab = ref('traffic')

/* ==================== tab 一：进出记录（订单口径） ==================== */

const page = ref(1)
const total = ref(0)
const records = ref([])
const loading = ref(true)

/**
 * 筛选条件。
 *
 * <p>空串一律<b>不发给后端</b>（组装参数时整个字段不传）——
 * 后端对 userId 这类字段按值判断，发空串会被当成一个「值为空」的条件。
 *
 * <p><b>没有「按到店 / 按离店」那个视图切换</b>（2026-10-16 去掉）：事件流本身
 * 就是一条时间线，到店与离店混排；再按类型分视图，等于把它拆回两半。
 */
const filters = ref({ userId: '', from: '', to: '' })

/**
 * 把时间控件给的值转成后端认的格式。
 *
 * <p>控件给的是 {@code 2026-10-16T20:00}，后端只认 {@code yyyy-MM-dd HH:mm:ss}；
 * 转换失败（用户清空了控件）时返回空串，调用方据此不传这个字段。
 *
 * @param {string} raw 控件原值
 * @returns {string} 后端格式，或空串
 */
function toBackendTime(raw) {
  return raw ? fromInputDateTime(raw) || '' : ''
}

/**
 * 拉取进出事件时间线的当前页。
 *
 * <p>页码可能越界（改完筛选后停在了一个不存在的页上），那时列表空白却没有报错，
 * 与「本来就没有数据」长得一样 —— 所以拿到总数后夹一次。
 */
async function loadTimeline() {
  loading.value = true
  try {
    const params = { page: page.value, size: PAGE_SIZE }
    const f = filters.value
    if (f.userId.trim()) params.userId = f.userId.trim()
    const from = toBackendTime(f.from)
    const to = toBackendTime(f.to)
    if (from) params.from = from
    if (to) params.to = to

    const resp = await listOrderTimeline(params)
    const count = resp.data?.total || 0
    const maxPage = Math.max(1, Math.ceil(count / PAGE_SIZE))
    if (page.value > maxPage) {
      page.value = maxPage
      return loadTimeline()
    }
    records.value = resp.data?.records || []
    total.value = count
  } catch (err) {
    toastError(errorMessage(err, '加载进出记录失败'))
  } finally {
    loading.value = false
  }
}

/** 改筛选后回到第一页再查 —— 停在第 3 页上换条件，多半直接越界 */
function applyFilters() {
  page.value = 1
  loadTimeline()
}

/** 清空筛选 */
function resetFilters() {
  filters.value = { userId: '', from: '', to: '' }
  applyFilters()
}

/* ==================== 还原某一时刻（弹层） ==================== */

const snapshotVisible = ref(false)
const snapshotAt = ref('')
const snapshotError = ref('')
const snapshotLoading = ref(false)
/** 还原结果：{at, count, names} */
const snapshot = ref(null)

/** 打开还原弹层，并把时刻预填成「现在」（零点的秒会让后端解析失败，所以不带秒） */
function openSnapshot() {
  snapshotAt.value = toInputNow()
  snapshotError.value = ''
  snapshot.value = null
  snapshotVisible.value = true
}

/**
 * 生成 datetime-local 控件要的当前时刻串。
 *
 * @returns {string} 形如 2026-10-16T20:00
 */
function toInputNow() {
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours())}:${pad(now.getMinutes())}`
}

/** 执行还原 */
async function submitSnapshot() {
  const at = toBackendTime(snapshotAt.value)
  if (!at) {
    snapshotError.value = '请选择要还原的时刻'
    return
  }
  snapshotLoading.value = true
  snapshotError.value = ''
  try {
    const resp = await getInstoreSnapshot(at)
    snapshot.value = resp.data
  } catch (err) {
    snapshotError.value = errorMessage(err, '还原失败')
  } finally {
    snapshotLoading.value = false
  }
}

/* ==================== tab 二：门锁开门记录（旁证） ==================== */

const lockPage = ref(1)
const lockTotal = ref(0)
const lockRecords = ref([])
const lockLoading = ref(false)
/** 是否已经拉过 —— 切到那个 tab 时才第一次拉，打开页面时不必打两个请求 */
const lockLoaded = ref(false)
const lockFilters = ref({ userId: '', from: '', to: '' })

/** 拉取开门记录的当前页 */
async function loadLockRecords() {
  lockLoading.value = true
  try {
    const params = { page: lockPage.value, size: PAGE_SIZE }
    const f = lockFilters.value
    if (f.userId.trim()) params.userId = f.userId.trim()
    const from = toBackendTime(f.from)
    const to = toBackendTime(f.to)
    if (from) params.from = from
    if (to) params.to = to

    const resp = await listAccessRecords(params)
    const count = resp.data?.total || 0
    const maxPage = Math.max(1, Math.ceil(count / PAGE_SIZE))
    if (lockPage.value > maxPage) {
      lockPage.value = maxPage
      return loadLockRecords()
    }
    lockRecords.value = resp.data?.records || []
    lockTotal.value = count
    lockLoaded.value = true
  } catch (err) {
    toastError(errorMessage(err, '加载开门记录失败'))
  } finally {
    lockLoading.value = false
  }
}

/** 切换 tab：门锁那边第一次进才拉数据 */
function onTabChange(next) {
  tab.value = next
  if (next === 'lock' && !lockLoaded.value) loadLockRecords()
}

/** 改筛选后回到第一页 */
function applyLockFilters() {
  lockPage.value = 1
  loadLockRecords()
}

/** 清空开门记录的筛选 */
function resetLockFilters() {
  lockFilters.value = { userId: '', from: '', to: '' }
  applyLockFilters()
}

/* ==================== 同步 / 补录 / 模拟开门（三个弹层） ==================== */

const syncVisible = ref(false)
const syncError = ref('')
const syncing = ref(false)
const syncForm = ref({ lockId: '1', from: '', to: '' })

const manualVisible = ref(false)
const manualError = ref('')
const saving = ref(false)
const manualForm = ref({ lockId: '1', openTime: '', userId: '', orderId: '', passcode: '' })

const mockVisible = ref(false)
const mockError = ref('')
const mocking = ref(false)
const mockForm = ref({ lockId: '1', passcode: '', userId: '', orderId: '' })

/** 打开同步弹层：默认同步「今天 0 点到现在」 */
function openSync() {
  syncForm.value = { lockId: '1', from: `${toInputNow().slice(0, 10)}T00:00`, to: toInputNow() }
  syncError.value = ''
  syncVisible.value = true
}

/**
 * 执行同步。
 *
 * <p>⚠️ <b>它花门锁云的额度</b>（30,000 次/月是硬约束），所以只手动点、
 * <b>绝不做成轮询</b>。响应里的 cloudCalls 会如实报出这一次花了几次。
 */
async function submitSync() {
  const from = toBackendTime(syncForm.value.from)
  const to = toBackendTime(syncForm.value.to)
  if (!from || !to) {
    syncError.value = '请选择同步区间'
    return
  }
  syncing.value = true
  syncError.value = ''
  try {
    const resp = await syncAccessRecords({ lockId: syncForm.value.lockId.trim(), from, to })
    const d = resp.data || {}
    toastSuccess(`同步完成：拉取 ${d.fetched ?? 0} 条，新增 ${d.inserted ?? 0} 条，跳过 ${d.skipped ?? 0} 条`)
    syncVisible.value = false
    lockPage.value = 1
    loadLockRecords()
  } catch (err) {
    syncError.value = errorMessage(err, '同步失败')
  } finally {
    syncing.value = false
  }
}

/** 打开补录弹层 */
function openManual() {
  manualForm.value = { lockId: '1', openTime: toInputNow(), userId: '', orderId: '', passcode: '' }
  manualError.value = ''
  manualVisible.value = true
}

/**
 * 执行补录。
 *
 * <p>用于系统或门锁故障期间开门没留下记录的场景，事后按纸质登记或监控补入。
 * 补录来源固定记为「管理员补录」，请求方无法指定 —— 审计上必须一眼看得出
 * 哪些记录是人工填的。
 */
async function submitManual() {
  const openTime = toBackendTime(manualForm.value.openTime)
  if (!openTime) {
    manualError.value = '请选择开门时刻'
    return
  }
  saving.value = true
  manualError.value = ''
  try {
    const payload = { lockId: manualForm.value.lockId.trim(), openTime }
    if (manualForm.value.userId.trim()) payload.userId = manualForm.value.userId.trim()
    if (manualForm.value.orderId.trim()) payload.orderId = manualForm.value.orderId.trim()
    if (manualForm.value.passcode.trim()) payload.passcode = manualForm.value.passcode.trim()

    await createAccessRecord(payload)
    toastSuccess('已补录一条开门记录')
    manualVisible.value = false
    lockPage.value = 1
    loadLockRecords()
  } catch (err) {
    manualError.value = errorMessage(err, '补录失败')
  } finally {
    saving.value = false
  }
}

/** 打开模拟开门弹层 */
function openMock() {
  mockForm.value = { lockId: '1', passcode: '', userId: '', orderId: '' }
  mockError.value = ''
  mockVisible.value = true
}

/**
 * 执行模拟开门。
 *
 * <p>等价于「顾客在门口输入密码开了门」，只在本机门锁 provider 为 mock 时存在。
 * 它走的是真实那条链路：落一条开门记录、返回记录本身 ——
 * 演示「下发密码 → 开门 → 落记录」时用它，不必真的跑去门口。
 */
async function submitMock() {
  if (!mockForm.value.passcode.trim()) {
    mockError.value = '请填写要使用的门锁密码'
    return
  }
  mocking.value = true
  mockError.value = ''
  try {
    const payload = { lockId: mockForm.value.lockId.trim(), passcode: mockForm.value.passcode.trim() }
    if (mockForm.value.userId.trim()) payload.userId = mockForm.value.userId.trim()
    if (mockForm.value.orderId.trim()) payload.orderId = mockForm.value.orderId.trim()

    await mockOpen(payload)
    toastSuccess('模拟开门成功，已落一条开门记录')
    mockVisible.value = false
    lockPage.value = 1
    loadLockRecords()
  } catch (err) {
    mockError.value = errorMessage(err, '模拟开门失败')
  } finally {
    mocking.value = false
  }
}

onMounted(loadTimeline)
</script>

<template>
  <!--
    ⚠️ 根元素必须带 class="page"：后台的宽度限制挂在 AdminLayout 的
    :deep(.page) 上（max-width: var(--page-max-admin)，900px）。
    少了这个类，页面在宽屏上会铺满整屏、每行长得读不下去 ——
    本页第一版就漏了它（2026-10-16）。
  -->
  <div class="page">
    <div class="at__tabs">
      <button
        class="at__tab"
        :class="{ 'at__tab--on': tab === 'traffic' }"
        @click="onTabChange('traffic')"
      >
        进出记录
      </button>
      <button
        class="at__tab"
        :class="{ 'at__tab--on': tab === 'lock' }"
        @click="onTabChange('lock')"
      >
        门锁开门记录
      </button>
    </div>

    <!-- ==================== tab 一：进出记录 ==================== -->
    <template v-if="tab === 'traffic'">
      <p class="at__hint">
        口径：<b>到店 = 开始计时</b>，<b>离店 = 停止计时</b>。每条带当时的名册快照 ——
        到店那份含本人，离店那份不含本人（离店那一刻人已经不在店里了）。
      </p>

      <div class="at__bar">
        <label class="at__field">
          <span>用户 ID</span>
          <input v-model="filters.userId" type="text" placeholder="不限" @change="applyFilters" />
        </label>
        <label class="at__field">
          <span>起</span>
          <input v-model="filters.from" type="datetime-local" @change="applyFilters" />
        </label>
        <label class="at__field">
          <span>止</span>
          <input v-model="filters.to" type="datetime-local" @change="applyFilters" />
        </label>
        <div class="at__barBtns">
          <button class="btn btn-ghost" @click="resetFilters">清空</button>
          <button class="btn btn-primary" @click="openSnapshot">还原某一时刻</button>
        </div>
      </div>

      <LoadingMask v-if="loading" />

      <EmptyState
        v-else-if="!records.length"
        title="这段时间没有进出记录"
        desc="把时间范围放宽一点，或者确认这个门店已经有顾客下过单"
      />

      <template v-else>
        <!-- 时间线：一行一件事。左侧那根竖线与圆点由 CSS 画（见 .at__card::before） -->
        <div
          v-for="e in records"
          :key="e.orderId + '-' + e.eventType"
          class="at__card"
          :class="e.eventType === 'LEAVE' ? 'at__card--leave' : 'at__card--arrive'"
        >
          <div class="at__cardHead">
            <span class="at__badge">{{ e.eventLabel }}</span>
            <span class="at__name">{{ e.nickname }}</span>
            <span v-if="e.role === 'ADMIN'" class="tag tag-warning">STAFF</span>
            <span class="at__time">{{ formatDateTime(e.eventTime) }}</span>
          </div>

          <div class="at__cardSnap">
            <template v-if="e.instore && e.instore.count > 0">
              {{ e.eventType === 'LEAVE' ? '剩余' : '当时在店' }}
              <b>{{ e.instore.count }}</b> 人：{{ e.instore.names.join('、') }}
            </template>
            <template v-else>店里没有人</template>
          </div>

          <div class="at__cardFoot">
            {{ e.orderNo }} · {{ e.statusLabel }}
            <span class="at__muted">
              {{ e.eventType === 'ARRIVE' ? '开始计时' : '停止计时' }}
            </span>
          </div>
        </div>

        <AdminPager v-model:page="page" :size="PAGE_SIZE" :total="total" @update:page="loadTimeline" />
      </template>
    </template>

    <!-- ==================== tab 二：门锁开门记录 ==================== -->
    <template v-else>
      <p class="at__hint">
        这是<b>门锁侧的旁证</b>：只说明「有人在门口输对了密码」，不说明谁进谁出 ——
        出门是机械推杠，不产生门锁记录。要复盘进出请用上一个 tab。
        ⚠️ 同步会消耗门锁云额度（30,000 次/月），所以只有手动点，不做轮询。
      </p>

      <div class="at__bar">
        <label class="at__field">
          <span>用户 ID</span>
          <input v-model="lockFilters.userId" type="text" placeholder="不限" @change="applyLockFilters" />
        </label>
        <label class="at__field">
          <span>起</span>
          <input v-model="lockFilters.from" type="datetime-local" @change="applyLockFilters" />
        </label>
        <label class="at__field">
          <span>止</span>
          <input v-model="lockFilters.to" type="datetime-local" @change="applyLockFilters" />
        </label>
        <div class="at__barBtns">
          <button class="btn btn-ghost" @click="resetLockFilters">清空</button>
          <button class="btn btn-ghost" @click="openManual">补录</button>
          <button class="btn btn-ghost" @click="openMock">模拟开门</button>
          <button class="btn btn-primary" @click="openSync">同步门锁记录</button>
        </div>
      </div>

      <LoadingMask v-if="lockLoading" />

      <EmptyState
        v-else-if="!lockRecords.length"
        title="没有开门记录"
        desc="本机是模拟门锁，记录只来自模拟开门、云端同步与人工补录"
      />

      <template v-else>
        <div v-for="r in lockRecords" :key="r.id" class="at__row at__row--lock">
          <div class="at__rowHead">
            <span class="at__name">{{ r.userId ? '用户 #' + r.userId : '未关联用户' }}</span>
            <span class="at__status">{{ r.sourceLabel }}</span>
          </div>
          <div class="at__events">
            <div class="at__event">
              <span class="at__label">开门</span>
              <span class="at__time">{{ formatDateTime(r.openTime) }}</span>
              <span class="at__snap">
                门锁 #{{ r.lockId }}<template v-if="r.passcode"> · 密码 {{ r.passcode }}</template
                ><template v-if="r.orderId"> · 订单 #{{ r.orderId }}</template>
              </span>
            </div>
          </div>
        </div>

        <AdminPager
          v-model:page="lockPage"
          :size="PAGE_SIZE"
          :total="lockTotal"
          @update:page="loadLockRecords"
        />
      </template>
    </template>

    <!-- ==================== 还原某一时刻 ==================== -->
    <AdminSheet v-model:visible="snapshotVisible" title="还原某一时刻的在店名册" :error="snapshotError">
      <p class="at__hint">
        名册是<b>按订单的开始 / 结束时刻推算</b>的，所以任意历史时刻都能还原；
        代价是人工调整过离店时刻的单子，快照会跟着变。
      </p>
      <label class="field-label" for="at-snapshot">要还原的时刻</label>
      <input id="at-snapshot" v-model="snapshotAt" class="field-input" type="datetime-local" />

      <div v-if="snapshot" class="at__snapBox">
        <div class="at__snapTitle">
          {{ formatDateTime(snapshot.at) }} · 在店 <b>{{ snapshot.count }}</b> 人
        </div>
        <p v-if="snapshot.names?.length" class="at__snapNames">{{ snapshot.names.join('、') }}</p>
        <p v-else class="at__snapNames at__snapNames--empty">那个时刻店里没有人</p>
      </div>

      <template #footer>
        <button class="btn btn-ghost" @click="snapshotVisible = false">关闭</button>
        <button class="btn btn-primary" :disabled="snapshotLoading" @click="submitSnapshot">
          {{ snapshotLoading ? '还原中…' : '还原' }}
        </button>
      </template>
    </AdminSheet>

    <!-- ==================== 同步门锁记录 ==================== -->
    <AdminSheet v-model:visible="syncVisible" title="同步门锁记录" :error="syncError">
      <p class="at__hint">
        区间跨度上限 31 天；同步是幂等的，重复同步同一区间不会写重。
        若门锁云不可达会直接报错，而<b>不会</b>伪装成「成功、0 条」。
      </p>
      <label class="field-label" for="at-sync-lock">门锁 ID</label>
      <input id="at-sync-lock" v-model="syncForm.lockId" class="field-input" type="text" />
      <label class="field-label" for="at-sync-from">起点</label>
      <input id="at-sync-from" v-model="syncForm.from" class="field-input" type="datetime-local" />
      <label class="field-label" for="at-sync-to">终点</label>
      <input id="at-sync-to" v-model="syncForm.to" class="field-input" type="datetime-local" />
      <template #footer>
        <button class="btn btn-ghost" @click="syncVisible = false">取消</button>
        <button class="btn btn-primary" :disabled="syncing" @click="submitSync">
          {{ syncing ? '同步中…' : '开始同步' }}
        </button>
      </template>
    </AdminSheet>

    <!-- ==================== 补录 ==================== -->
    <AdminSheet v-model:visible="manualVisible" title="补录一条开门记录" :error="manualError">
      <p class="at__hint">
        用于系统或门锁故障期间开门没留下记录的场景，事后按纸质登记或监控补入。
        来源固定记为「管理员补录」，审计上一眼看得出哪些是人工填的。
      </p>
      <label class="field-label" for="at-manual-lock">门锁 ID</label>
      <input id="at-manual-lock" v-model="manualForm.lockId" class="field-input" type="text" />
      <label class="field-label" for="at-manual-time">开门时刻</label>
      <input id="at-manual-time" v-model="manualForm.openTime" class="field-input" type="datetime-local" />
      <label class="field-label" for="at-manual-user">用户 ID（可空）</label>
      <input id="at-manual-user" v-model="manualForm.userId" class="field-input" type="text" />
      <label class="field-label" for="at-manual-order">订单 ID（可空）</label>
      <input id="at-manual-order" v-model="manualForm.orderId" class="field-input" type="text" />
      <label class="field-label" for="at-manual-pass">所用密码（可空）</label>
      <input id="at-manual-pass" v-model="manualForm.passcode" class="field-input" type="text" />
      <template #footer>
        <button class="btn btn-ghost" @click="manualVisible = false">取消</button>
        <button class="btn btn-primary" :disabled="saving" @click="submitManual">
          {{ saving ? '提交中…' : '确定补录' }}
        </button>
      </template>
    </AdminSheet>

    <!-- ==================== 模拟开门 ==================== -->
    <AdminSheet v-model:visible="mockVisible" title="模拟开门" :error="mockError">
      <p class="at__hint">
        等价于「顾客在门口输入密码开了门」，只在本机门锁为模拟实现时可用。
        演示「下发密码 → 开门 → 落记录」时用它，不必真的跑到门口。
      </p>
      <label class="field-label" for="at-mock-lock">门锁 ID</label>
      <input id="at-mock-lock" v-model="mockForm.lockId" class="field-input" type="text" />
      <label class="field-label" for="at-mock-pass">门锁密码</label>
      <input id="at-mock-pass" v-model="mockForm.passcode" class="field-input" type="text" placeholder="如 12345678" />
      <label class="field-label" for="at-mock-user">用户 ID（可空）</label>
      <input id="at-mock-user" v-model="mockForm.userId" class="field-input" type="text" />
      <label class="field-label" for="at-mock-order">订单 ID（可空）</label>
      <input id="at-mock-order" v-model="mockForm.orderId" class="field-input" type="text" />
      <template #footer>
        <button class="btn btn-ghost" @click="mockVisible = false">取消</button>
        <button class="btn btn-primary" :disabled="mocking" @click="submitMock">
          {{ mocking ? '开门中…' : '模拟开门' }}
        </button>
      </template>
    </AdminSheet>
  </div>
</template>

<style scoped>
.at__tabs {
  display: flex;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}

.at__tab {
  flex: 1;
  height: 34px;
  border-radius: var(--r-btn);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-size: 13px;
}

.at__tab--on {
  background: var(--c-primary);
  color: #fff;
}

.at__hint {
  margin-bottom: var(--sp-3);
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-text-muted);
}

.at__bar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--sp-3);
  padding: var(--sp-3);
  margin-bottom: var(--sp-3);
  border-radius: var(--r-card);
  background: var(--c-bg-soft, #f7f5fb);
}

.at__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--c-text-muted);
}

.at__field input,
.at__field select {
  height: 30px;
  padding: 0 var(--sp-2);
  border: 1px solid var(--c-border, #ddd);
  border-radius: var(--r-btn);
  background: #fff;
  font-size: 12px;
  color: var(--c-text);
}

.at__barBtns {
  display: flex;
  gap: var(--sp-2);
  margin-left: auto;
}

.at__row {
  padding: var(--sp-3);
  margin-bottom: var(--sp-3);
  border-radius: var(--r-card);
  background: #fff;
  border: 1px solid var(--c-border-soft, #eee);
}

.at__rowHead {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-bottom: var(--sp-2);
}

.at__name {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}

.at__status {
  font-size: 12px;
  color: var(--c-text-muted);
}

.at__orderNo {
  margin-left: auto;
  font-size: 11px;
  color: var(--c-text-muted);
}

.at__events {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.at__event {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--sp-2);
  font-size: 12px;
}

.at__label {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-weight: 600;
}

.at__label--out {
  background: #fdecec;
  color: #c0392b;
}

.at__label--open {
  background: #e8f5e9;
  color: #2e7d32;
}

.at__time {
  color: var(--c-text);
}

.at__snap {
  color: var(--c-text-muted);
  line-height: 1.6;
}

.at__meta {
  margin-top: var(--sp-2);
  font-size: 12px;
  color: var(--c-text-muted);
}

/* ==================== 时间线卡片（一行一件事） ==================== */

/*
 * 左侧那根竖线是「时间线」的视觉主体：它把一屏卡片串成一条线。
 * ⚠️ 竖线画在每张卡片自己的 ::before 上（而不是容器的伪元素上）——
 * 卡片之间有间距，画在容器上会在每个间距处断开，看着像一串互不相干的块。
 */
.at__card {
  position: relative;
  padding: var(--sp-3) var(--sp-3) var(--sp-3) var(--sp-5);
  margin-bottom: var(--sp-3);
  border-radius: var(--r-card);
  background: #fff;
  border: 1px solid var(--c-border-soft, #eee);
}

.at__card::before {
  content: '';
  position: absolute;
  left: 15px;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--c-border-soft, #eee);
}

/* 首尾两张不把线画到边缘，线看起来才是从这里开始、到这里结束的 */
.at__card:first-of-type::before { top: 16px; }
.at__card:last-of-type::before { bottom: 16px; }

/* 圆点：到店用主色、离店用暖红 —— 扫一屏就能看出方向 */
.at__card::after {
  content: '';
  position: absolute;
  left: 11px;
  top: 16px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--c-primary);
  /* 白圈把圆点从竖线上「抠」出来，否则两者糊成一根粗线 */
  box-shadow: 0 0 0 3px #fff;
}

.at__card--leave::after { background: #c0392b; }

.at__cardHead {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
}

.at__badge {
  padding: 1px 7px;
  border-radius: 4px;
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-size: 12px;
  font-weight: 600;
}

.at__card--leave .at__badge {
  background: #fdecec;
  color: #c0392b;
}

.at__cardSnap {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-text-muted);
}

.at__cardSnap b {
  color: var(--c-text);
}

.at__cardFoot {
  display: flex;
  gap: var(--sp-2);
  margin-top: 6px;
  font-size: 11px;
  color: var(--c-text-muted);
}

.at__muted {
  opacity: 0.75;
}

.at__snapBox {
  margin-top: var(--sp-3);
  padding: var(--sp-3);
  border-radius: var(--r-btn);
  background: var(--c-primary-pale);
}

.at__snapTitle {
  font-size: 13px;
  color: var(--c-text);
}

.at__snapNames {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--c-text);
}

.at__snapNames--empty {
  color: var(--c-text-muted);
}
</style>
