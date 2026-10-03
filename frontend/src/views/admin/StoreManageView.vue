<script setup>
/**
 * 门店管理（运营后台）：两个 tab —— <b>停业时段</b>与<b>免费时段</b>。
 *
 * <p>两者形态一样（都是「某段时间」的记录 + 后台增删改查），但作用<b>完全不同</b>：
 * <ul>
 *   <li><b>停业</b>管【准入】—— 那段时间拒绝新下单，已在店内的顾客不受影响</li>
 *   <li><b>免费时段</b>管【计费】—— 照常营业、人照进、门照开，只是账单算 0</li>
 * </ul>
 * 放在同一页，是因为在运营眼里它们就是「门店接下来哪段时间不营业 / 不收钱」，
 * 而这一页正是从门店的角度看这两件事。数据库里也是两张表（不是门店上的状态位），
 * 因为两者都要能<b>提前安排</b>。
 *
 * <p>⚠️ <b>重叠校验一律交给后端</b>（{@code FREE_PERIOD_OVERLAP}(40945)、
 * 停业的同款校验）：前端预判等于把判定规则抄成两份，两处一旦漂移，
 * 就会出现「前端说可以、后端说不行」这种最难解释的现象。这里只把后端的文案原样显示。
 *
 * <p>⚠️ <b>起止用「日期 + 整点小时」两个框</b>，不用 {@code datetime-local} ——
 * 手机上得滚到分钟那一列才选得准，而这两个场景的分钟本来就没有意义
 * （停业与活动都是整点起止，边界宽松一点对顾客有利）。
 * 拼串共用 {@code fromDateTimeHour}，与包场排期是同一个函数。
 */
import { ref, computed, onMounted } from 'vue'
import {
  listClosures,
  createClosure,
  updateClosure,
  deleteClosure,
  listFreePeriods,
  createFreePeriod,
  updateFreePeriod,
  deleteFreePeriod
} from '@/api/admin'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatDate, formatDateTime, fromDateTimeHour } from '@/utils/format'
import { periodPhaseOf } from '@/utils/labels'
import AdminPager from '@/components/AdminPager.vue'
import AdminSheet from '@/components/AdminSheet.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

/** 每页条数。⚠️ 两个接口的分页参数都是 page + size（商品那边才是 pageNum + pageSize） */
const PAGE_SIZE = 10

/** 当前 tab：closures 停业时段 / free 免费时段 */
const tab = ref('closures')

/**
 * 两种记录的文案差异集中在这里。
 *
 * <p>两个 tab 的<b>结构</b>完全相同（时段 + 一个文本字段 + 增删改查），
 * 差别只在称呼上 —— 与其把整套逻辑写两遍，不如把措辞抽出来，
 * 模板与函数各只有一份。走 {@code computed} 而不是常量对，
 * 是为了让模板里直接写 {@code labels.value.xxx} 时不必再判当前 tab。
 */
const LABELS = {
  closures: {
    title: '停业时段',
    add: '新增停业',
    edit: '改停业时段',
    reason: '停业原因（选填）',
    reasonPlaceholder: '如：设备维护',
    empty: '还没有停业安排',
    emptyHint: '排一段时间之后，那段时间里系统会自动拒绝新下单',
    tip: '停业期间顾客下不了单（已在店内的不受影响）。要「照常营业但不收钱」，请用免费时段。'
  },
  free: {
    title: '免费时段',
    add: '新增活动',
    edit: '改免费活动',
    reason: '活动名称（选填）',
    reasonPlaceholder: '如：跨年活动',
    empty: '还没有免费活动',
    emptyHint: '排一场之后，活动期间所有订单都不计费',
    tip: '活动期间店里照常营业、顾客照常进出，只是账单算 0 —— 与停业是两回事（停业拒绝新下单）。'
  }
}

const labels = computed(() => LABELS[tab.value])

/* ---------------- 列表（两个 tab 各记自己的一页） ---------------- */

const closurePage = ref(1)
const closureTotal = ref(0)
const closures = ref([])
const closureLoading = ref(true)
/** 是否已经拉过一次。「空列表」不能用长度判断 —— 那会让每次切回来都重查一遍 */
const closureLoaded = ref(false)

const freePage = ref(1)
const freeTotal = ref(0)
const freePeriods = ref([])
const freeLoading = ref(true)
const freeLoaded = ref(false)

/* ---------------- 表单弹层 ---------------- */

const formVisible = ref(false)
/** 正在编辑的那条；为 null 表示「新增」 */
const editing = ref(null)

/** 表单。日期与小时分开存 —— 提交时才拼成后端认的时刻串 */
const form = ref({
  startDate: '',
  startHour: '10',
  endDate: '',
  endHour: '12',
  reason: ''
})
/** 弹层内的错误文案。⚠️ 不能靠 toast —— 它的层级比弹层低，会被遮罩压住 */
const formError = ref('')
const submitting = ref(false)

/* ---------------- 删除确认 ---------------- */

const removing = ref(null)
const removeError = ref('')

/** 日期选择框的最早可选值：今天。是否落在过去交给后端判（它允许补录历史） */
const todayInput = computed(() => formatDate(formatDateTime(new Date())))

/**
 * 拉取停业时段当前页。
 *
 * <p>页码可能「越界」：删掉最后一页的最后一条后，页码停在了一个不存在的页上，
 * 列表空白且没有任何报错 —— 与「本来就没有数据」长得一模一样。所以拿到总数后
 * 夹一次，越界就回到最后一页重拉（最多递归一层：新页码必定不越界）。
 */
async function loadClosures() {
  closureLoading.value = true
  try {
    const resp = await listClosures({ page: closurePage.value, size: PAGE_SIZE })
    const count = resp.data?.total || 0
    const maxPage = Math.max(1, Math.ceil(count / PAGE_SIZE))
    if (closurePage.value > maxPage) {
      closurePage.value = maxPage
      return loadClosures()
    }
    closures.value = resp.data?.records || []
    closureTotal.value = count
    closureLoaded.value = true
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    closureLoading.value = false
  }
}

/**
 * 拉取免费活动当前页。夹页码的理由同 {@link loadClosures}。
 */
async function loadFreePeriods() {
  freeLoading.value = true
  try {
    const resp = await listFreePeriods({ page: freePage.value, size: PAGE_SIZE })
    const count = resp.data?.total || 0
    const maxPage = Math.max(1, Math.ceil(count / PAGE_SIZE))
    if (freePage.value > maxPage) {
      freePage.value = maxPage
      return loadFreePeriods()
    }
    freePeriods.value = resp.data?.records || []
    freeTotal.value = count
    freeLoaded.value = true
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    freeLoading.value = false
  }
}

/** 当前 tab 的列表与加载态，供模板统一取用 */
const current = computed(() => (tab.value === 'closures'
  ? { records: closures.value, loading: closureLoading.value, total: closureTotal.value }
  : { records: freePeriods.value, loading: freeLoading.value, total: freeTotal.value }))

/** 切 tab。列表按需加载 —— 打开页面时只拉当前那个 */
function onTabChange(next) {
  if (next === tab.value) return
  tab.value = next
  removing.value = null
  if (next === 'closures' && !closureLoaded.value) loadClosures()
  if (next === 'free' && !freeLoaded.value) loadFreePeriods()
}

/** 翻页。 */
function onPageChange(target) {
  if (tab.value === 'closures') {
    closurePage.value = target
    loadClosures()
  } else {
    freePage.value = target
    loadFreePeriods()
  }
}

/** 当前 tab 的页码（AdminPager 只认一个 page） */
const currentPage = computed(() => (tab.value === 'closures' ? closurePage.value : freePage.value))

/**
 * 一条记录此刻的阶段（未开始 / 进行中 / 已结束），供列表打标签。
 *
 * <p>名字取短一点，免得模板里把这个调用写三遍。
 * 判定与文案都在 {@link periodPhaseOf} 里 —— 用户端首页的免费活动卡用的是同一个。
 *
 * @param {object} row 记录
 * @returns {{label: string, cls: string}}
 */
function phaseOf(row) {
  return periodPhaseOf(row.startAt, row.endAt)
}

/**
 * 一条记录是否跨越了自然日。
 *
 * <p>跨天的时段（如跨年活动 20:00 – 次日 02:00）值得单独标出来：
 * 只看「10-01 20:00 → 10-02 02:00」这串数字，得先认出两个日期不同才反应过来。
 *
 * @param {object} row 记录
 * @returns {boolean} 起止不在同一天
 */
function isCrossDay(row) {
  return String(row.startAt || '').slice(0, 10) !== String(row.endAt || '').slice(0, 10)
}

/* ---------------- 表单 ---------------- */

/** 打开「新增」表单。 */
function openCreate() {
  editing.value = null
  form.value = { startDate: '', startHour: '10', endDate: '', endHour: '12', reason: '' }
  formError.value = ''
  formVisible.value = true
}

/**
 * 打开「编辑」表单。
 *
 * <p>⚠️ 取的是<b>新的对象</b>，不是列表里的那一行 —— 直接引用的话，
 * 表单上敲的每一个字都会实时改到列表数据上，点「取消」也回不去了。
 *
 * <p>回填的是「日期 + 小时」两个框而不是整串：小时取 {@code slice(11, 13)}
 * 刚好两位（后端一律给 {@code yyyy-MM-dd HH:mm:ss}）。
 */
function openEdit(row) {
  editing.value = row
  form.value = {
    startDate: formatDate(row.startAt),
    startHour: String(row.startAt || '').slice(11, 13) || '10',
    endDate: formatDate(row.endAt),
    endHour: String(row.endAt || '').slice(11, 13) || '12',
    reason: row.reason || ''
  }
  formError.value = ''
  formVisible.value = true
}

/** 提交表单：按当前 tab 分派到对应接口。 */
async function onSubmit() {
  const startAt = fromDateTimeHour(form.value.startDate, form.value.startHour)
  const endAt = fromDateTimeHour(form.value.endDate, form.value.endHour)
  if (!startAt || !endAt) {
    formError.value = '请把起止日期与小时都填齐（小时为 0~23 的整数）'
    return
  }

  // reason 空串统一发 null：空串与 null 在库里的含义一样，
  // 发两种形态只会让后端多一层归一
  const payload = { startAt, endAt, reason: form.value.reason.trim() || null }
  const isClosure = tab.value === 'closures'

  submitting.value = true
  formError.value = ''
  try {
    if (editing.value) {
      if (isClosure) {
        await updateClosure(editing.value.id, payload)
      } else {
        await updateFreePeriod(editing.value.id, payload)
      }
    } else if (isClosure) {
      await createClosure(payload)
    } else {
      await createFreePeriod(payload)
    }

    toastSuccess(editing.value ? '已保存' : '已新增')
    formVisible.value = false
    if (isClosure) {
      await loadClosures()
    } else {
      await loadFreePeriods()
    }
  } catch (err) {
    // 时段冲突、起止不合法这些业务错误都走这里 —— 文案用后端给的，前端不重写一份
    formError.value = errorMessage(err, '保存失败')
  } finally {
    submitting.value = false
  }
}

/* ---------------- 删除 ---------------- */

/** 确认删除。 */
async function onDelete() {
  if (!removing.value) return
  const isClosure = tab.value === 'closures'
  removeError.value = ''
  try {
    if (isClosure) {
      await deleteClosure(removing.value.id)
    } else {
      await deleteFreePeriod(removing.value.id)
    }
    toastSuccess('已删除')
    removing.value = null
    if (isClosure) {
      await loadClosures()
    } else {
      await loadFreePeriods()
    }
  } catch (err) {
    removeError.value = errorMessage(err, '删除失败')
  }
}

onMounted(loadClosures)
</script>

<template>
  <div class="page">
    <div class="card">
      <div class="card-title">
        门店
        <button class="btn btn-primary store__new" @click="openCreate">
          {{ labels.add }}
        </button>
      </div>

      <div class="store__tabs">
        <button
          class="store__tab"
          :class="{ 'store__tab--on': tab === 'closures' }"
          @click="onTabChange('closures')"
        >
          停业时段
        </button>
        <button
          class="store__tab"
          :class="{ 'store__tab--on': tab === 'free' }"
          @click="onTabChange('free')"
        >
          免费时段
        </button>
      </div>

      <LoadingMask :loading="current.loading" />

      <div v-if="current.records.length" class="store__list">
        <div v-for="row in current.records" :key="row.id" class="store__row">
          <div class="store__body">
            <p class="store__range">
              {{ formatDateTime(row.startAt) }} → {{ formatDateTime(row.endAt) }}
              <span v-if="isCrossDay(row)" class="tag store__tag">跨天</span>
              <span
                v-if="phaseOf(row).label"
                class="store__tag"
                :class="phaseOf(row).cls"
              >
                {{ phaseOf(row).label }}
              </span>
            </p>
            <p class="store__meta">
              {{ row.reason || (tab === 'closures' ? '未填原因' : '未命名活动') }}
              <span v-if="row.createdAt"> · 登记于 {{ formatDateTime(row.createdAt) }}</span>
            </p>
          </div>

          <span class="store__ops">
            <button class="store__op" @click="openEdit(row)">编辑</button>
            <button class="store__op store__op--danger" @click="removing = row">删除</button>
          </span>
        </div>
      </div>

      <EmptyState
        v-else-if="!current.loading"
        icon="🏠"
        :text="labels.empty"
        :hint="labels.emptyHint"
      />

      <AdminPager
        :page="currentPage"
        :size="PAGE_SIZE"
        :total="current.total"
        @update:page="onPageChange"
      />
    </div>

    <p class="store__tip">{{ labels.tip }}</p>

    <!-- 新增 / 编辑 -->
    <AdminSheet
      v-model:visible="formVisible"
      :title="editing ? labels.edit : labels.add"
      :error="formError"
    >
      <!--
        日期 + 整点两个框。原来那种 datetime-local 得滚到分钟才选得准，
        而停业与活动都只到小时一级 —— 拆开之后选完日期、敲两位数字就行。
      -->
      <div class="field">
        <label class="field-label" for="s-start-date">开始时刻</label>
        <div class="store__time">
          <input
            id="s-start-date"
            v-model="form.startDate"
            class="field-input store__date"
            type="date"
          />
          <input
            v-model="form.startHour"
            class="field-input store__hour"
            type="text"
            inputmode="numeric"
            maxlength="2"
            aria-label="开始小时"
          />
          <span class="store__time-unit">时 00 分</span>
          <span class="store__time-mark">起</span>
        </div>
      </div>

      <div class="field">
        <label class="field-label" for="s-end-date">结束时刻</label>
        <div class="store__time">
          <input
            id="s-end-date"
            v-model="form.endDate"
            class="field-input store__date"
            type="date"
          />
          <input
            v-model="form.endHour"
            class="field-input store__hour"
            type="text"
            inputmode="numeric"
            maxlength="2"
            aria-label="结束小时"
          />
          <span class="store__time-unit">时 00 分</span>
          <span class="store__time-mark">止</span>
        </div>
      </div>

      <p class="store__hint">
        结束时刻<b>不含</b>：10:00–12:00 与 12:00–14:00 可以背靠背排，不算重叠。
        跨天就选到次日（如跨年活动 20:00 起、次日 02:00 止）。
      </p>

      <div class="field">
        <label class="field-label" for="s-reason">{{ labels.reason }}</label>
        <input
          id="s-reason"
          v-model="form.reason"
          class="field-input"
          type="text"
          maxlength="255"
          :placeholder="labels.reasonPlaceholder"
        />
      </div>

      <template #footer>
        <button class="btn btn-ghost" @click="formVisible = false">取消</button>
        <button class="btn btn-primary" :disabled="submitting" @click="onSubmit">
          {{ submitting ? '保存中…' : '保存' }}
        </button>
      </template>
    </AdminSheet>

    <!-- 删除确认 -->
    <AdminSheet
      :visible="!!removing"
      title="确认删除"
      :error="removeError"
      @update:visible="removing = null"
    >
      <p class="store__confirm">
        删除「{{ formatDateTime(removing?.startAt) }} → {{ formatDateTime(removing?.endAt) }}」？
      </p>
      <p class="store__hint">
        删除后立刻不再生效。已发生的订单不受影响 —— 金额在结算时就已落库。
      </p>

      <template #footer>
        <button class="btn btn-ghost" @click="removing = null">取消</button>
        <button class="btn btn-primary" @click="onDelete">删除</button>
      </template>
    </AdminSheet>
  </div>
</template>

<style scoped>
/*
 * 标题右侧的「新增」按钮。
 *
 * ⚠️ 靠 `margin-left: auto` 推到右边，不是 `float: right` ——
 * `.card-title` 是 flex 容器，浮动在 flex 里不生效，按钮会被拉成整行宽，
 * 把标题挤成竖排（两个字一个一行）。
 */
.store__new {
  width: auto;
  height: 32px;
  margin-left: auto;
  padding: 0 var(--sp-4);
  font-size: 13px;
}

/* ==================== 两个 tab 的切换条 ==================== */

.store__tabs {
  display: flex;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}

.store__tab {
  height: 30px;
  padding: 0 var(--sp-4);
  border-radius: var(--r-pill);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-size: 13px;
}

.store__tab--on {
  background: var(--c-primary);
  color: #fff;
}

/* ==================== 列表 ==================== */

.store__list {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}

.store__row {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3);
  border-radius: var(--r-card);
  background: var(--c-card);
}

.store__body {
  flex: 1;
  min-width: 0;
}

.store__range {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
  /* 时间戳用等宽数字，上下两行才对得齐 */
  font-variant-numeric: tabular-nums;
}

.store__tag {
  font-weight: 400;
}

.store__meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--c-text-sub);
  line-height: 1.5;
}

.store__ops {
  display: flex;
  flex-shrink: 0;
  gap: var(--sp-2);
}

.store__op {
  font-size: 12px;
  color: var(--c-primary);
}

.store__op--danger {
  color: #c0392b;
}

/* ==================== 弹层内的表单 ==================== */

.store__time {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.store__date {
  flex: 1;
  min-width: 0;
}

/* 小时那格给两位数字的宽度就够，多出来的地方留给日期 */
.store__hour {
  width: 46px;
  text-align: center;
}

.store__time-unit {
  font-size: 12px;
  color: var(--c-text-sub);
  white-space: nowrap;
}

.store__time-mark {
  font-size: 12px;
  color: var(--c-text-muted);
}

.store__hint {
  margin-top: var(--sp-2);
  font-size: 12px;
  color: var(--c-text-sub);
  line-height: 1.6;
}

.store__confirm {
  font-size: 14px;
  line-height: 1.6;
}

.store__tip {
  margin-top: var(--sp-3);
  font-size: 12px;
  color: var(--c-text-sub);
  line-height: 1.6;
}
</style>
