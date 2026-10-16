<script setup>
/**
 * 用户管理（运营后台）。
 *
 * <p>看全部用户、按几个维度筛、按几个维度排、改资料、改角色与启停用。
 *
 * <h3>这一页有两个字段不是「用户表上的东西」</h3>
 *
 * <p>{@code cardType}（生效中的月卡）与 {@code totalStayMinutes}（累计在店时长）
 * 都由后端算出来。后者⚠️<b>与「累计消费」口径不同</b>：在店时长算的是
 * 「人在店里待了多久」，含免费时段与包场那几小时，所以它跟消费额本来就不该成正比，
 * 别把它当 bug 查。
 *
 * <h3>为什么编辑弹层里能改 QQ，用户自己那页却不能</h3>
 *
 * <p>QQ 号是机器人在群里认人的唯一依据，而注册时它经过一次群内验证。
 * 允许本人随便改，那次验证就等于白做（先随便填一个号注册，再改成别人的）。
 * 管理员改不走验证 —— 他知道谁是谁 —— 但<b>照样查重</b>。
 * 这一页是那个「联系管理员」的落点。
 */
import { ref, computed, onMounted } from 'vue'
import {
  listUsers,
  updateUser,
  updateUserStatus,
  updateUserRole,
  listAdminCards,
  issueCard,
  revokeCard
} from '@/api/admin'
import { userState } from '@/stores/user'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatDateTime } from '@/utils/format'
import {
  cardTypeLabel,
  cardStatusCls,
  MONTHLY_CARD_TYPES,
  parsePreference,
  joinPreference
} from '@/utils/labels'
import { listEquipmentTypes } from '@/api/device'
import AdminPager from '@/components/AdminPager.vue'
import AdminSheet from '@/components/AdminSheet.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'
import PreferencePicker from '@/components/PreferencePicker.vue'

/** 每页条数。与 AdminPager 的口径共用同一个常量 */
const PAGE_SIZE = 10

const page = ref(1)
const total = ref(0)
const records = ref([])
const loading = ref(true)

/**
 * 筛选条件。
 *
 * <p>⚠️ 空串一律<b>不发给后端</b>（组装参数时整个字段不传），而不是发空串。
 * 后端对 {@code role} / {@code hasCard} 这类字段是按值判断的，
 * 发一个空串过去要么 400、要么被当成一个「值为空」的筛选条件。
 */
const filters = ref({
  keyword: '',
  role: '',
  status: '',
  card: '' // '' | 'ALL_DAY' | 'NIGHT' | 'NONE'
})

/** 排序：键 + 方向。与后端白名单的三个键一一对应 */
const sortBy = ref('')
const desc = ref(true)

/* ---------------- 编辑弹层 ---------------- */

const formVisible = ref(false)
/** 正在编辑的那条 */
const editing = ref(null)
/**
 * 弹层表单。
 *
 * <p>⚠️ {@code preference} 是<b>数组</b>（多选），提交时才用
 * {@code joinPreference} 拼回逗号串。这里若还留着字符串，
 * 多选组件会直接报 {@code includes is not a function} —— 是响的，不是静默错。
 */
const form = ref({ nickname: '', phone: '', qq: '', preference: [] })
/** 弹层内的错误文案。⚠️ 不能靠 toast —— 它的层级比弹层低，会被遮罩压住 */
const formError = ref('')
/** 游玩偏好的选项来源（设备类型字典，进页面时拉一次） */
const prefTypes = ref([])
const submitting = ref(false)

/* ---------------- 启停用确认 ---------------- */

const toggling = ref(null)
const toggleError = ref('')

/**
 * 拉取当前页。
 *
 * <p>页码可能越界（改完筛选条件后停在了一个不存在的页上），那时列表空白
 * 却没有任何报错 —— 与「本来就没有数据」长得一样。所以拿到总数后夹一次。
 */
async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size: PAGE_SIZE }
    const f = filters.value
    if (f.keyword.trim()) params.keyword = f.keyword.trim()
    if (f.role) params.role = f.role
    if (f.status !== '') params.status = f.status
    // 月卡筛选：'NONE' 表示「没有卡」，其余具体卡种都表示「有卡且是该种」
    if (f.card === 'NONE') {
      params.hasCard = false
    } else if (f.card) {
      params.hasCard = true
      params.cardType = f.card
    }
    if (sortBy.value) {
      params.sortBy = sortBy.value
      params.desc = desc.value
    }

    const resp = await listUsers(params)
    records.value = resp.data?.records || []
    total.value = resp.data?.total || 0

    const pageCount = Math.max(1, Math.ceil(total.value / PAGE_SIZE))
    if (page.value > pageCount) {
      page.value = pageCount
      return load()
    }
  } catch (err) {
    toastError(errorMessage(err, '加载用户列表失败'))
  } finally {
    loading.value = false
  }
}

/** 改筛选条件后回到第一页再查 —— 停在第 3 页上换条件，多半会直接越界 */
function applyFilters() {
  page.value = 1
  load()
}

/** 清空筛选 */
function resetFilters() {
  filters.value = { keyword: '', role: '', status: '', card: '' }
  applyFilters()
}

/**
 * 点表头排序：同一个键再点一次就掉头，换键则默认降序
 * （三个键里有两个是「越大越值得看」，从降序起步更合直觉）。
 *
 * @param {string} key 排序键
 */
function toggleSort(key) {
  if (sortBy.value === key) {
    desc.value = !desc.value
  } else {
    sortBy.value = key
    desc.value = true
  }
  applyFilters()
}

/** 排序箭头。非当前排序列不显示 */
function sortMark(key) {
  if (sortBy.value !== key) return ''
  return desc.value ? ' ↓' : ' ↑'
}

/* ---------------- 编辑 ---------------- */

/**
 * 打开编辑弹层。
 *
 * <p>把当前值全部回填 —— 接口是全量替换语义，只带改动的字段
 * 会把手机号、偏好一起抹掉，而且不报任何错。
 *
 * @param {object} user 列表里的一行
 */
function openEdit(user) {
  editing.value = user
  formError.value = ''
  form.value = {
    nickname: user.nickname || '',
    phone: user.phone || '',
    qq: user.qq || '',
    preference: parsePreference(user.preference)
  }
  // 字典可能没拉到（首次请求失败、或返回得比列表慢），打开时补一次 ——
  // 否则多选是空的，管理员一保存就把这个人的偏好连同未知项一起清掉了
  if (!prefTypes.value.length) loadPrefTypes()
  formVisible.value = true
}

/**
 * 拉设备类型字典，作为偏好多选的选项来源。
 *
 * <p>失败时给一句 toast：多选空着不等于「他没设偏好」，
 * 不提示的话管理员只会以为这个人本来就没选。
 */
async function loadPrefTypes() {
  try {
    const resp = await listEquipmentTypes()
    prefTypes.value = resp.data || []
  } catch (err) {
    toastError(errorMessage(err, '设备类型加载失败，偏好暂时无法修改'))
  }
}

async function submitForm() {
  if (!editing.value) return
  submitting.value = true
  formError.value = ''
  try {
    await updateUser(editing.value.id, {
      // 空串转 null：后端把「空串」与「未填」等同看待，统一成 null 更清楚
      nickname: form.value.nickname.trim() || null,
      phone: form.value.phone.trim() || null,
      qq: form.value.qq.trim() || null,
      preference: joinPreference(form.value.preference)
    })
    formVisible.value = false
    toastSuccess('已保存')
    load()
  } catch (err) {
    formError.value = errorMessage(err, '保存失败')
  } finally {
    submitting.value = false
  }
}

/**
 * 把累计在店分钟数拼成人话。
 *
 * <p>用户端的在店名册有一份同样规则的格式化，两处保持一致
 * （不足 60 分钟按分钟显示，整小时不带「0 分」）。
 *
 * @param {number} minutes 累计分钟数，可能为空
 * @returns {string} 形如「12 小时 30 分」/「45 分钟」/「—」
 */
function formatStay(minutes) {
  if (minutes == null) return '—'
  const m = Number(minutes)
  if (m <= 0) return '—'
  if (m < 60) return `${m} 分钟`
  const h = Math.floor(m / 60)
  const rest = m % 60
  return rest === 0 ? `${h} 小时` : `${h} 小时 ${rest} 分`
}

/* ---------------- 启停用 ---------------- */

function askToggle(user) {
  toggling.value = user
  toggleError.value = ''
}

async function confirmToggle() {
  const user = toggling.value
  if (!user) return
  const next = user.status === 1 ? 0 : 1
  try {
    await updateUserStatus(user.id, next)
    toggling.value = null
    toastSuccess(next === 1 ? '已启用' : '已禁用')
    load()
  } catch (err) {
    // ⚠️ 错误走弹层而不是 toast —— toast 的层级压不住遮罩，见 AdminSheet 的说明
    toggleError.value = errorMessage(err, '操作失败')
  }
}

/* ---------------- 改角色 ---------------- */

/** 正在改角色的那条记录。null 表示弹层关着 */
const roleEditing = ref(null)
const roleError = ref('')
const roleSaving = ref(false)

/**
 * 当前登录的管理员自己。
 *
 * <p>⚠️ 后端<b>禁止改自己的角色</b>（把自己降成普通用户后就再也进不去后台了），
 * 所以前端把那一行的按钮直接禁掉 —— 让他在点之前就知道，
 * 而不是点了才收到一句报错。
 */
const myId = computed(() => userState.user?.id)

/* ---------------- 月卡弹层（面对面开卡 / 停卡） ---------------- */

/**
 * 弹层对着的那个人。null 表示关着。
 *
 * <p>这里同时承担「开卡」与「停卡」两件事：面对面收款后补录一张，
 * 以及把发错的卡或离职的 STAFF 卡收回。两件事都对着同一个人、
 * 都要先看清他手上已有什么，所以合成一个弹层而不是两个入口。
 */
const cardUser = ref(null)

/** 该用户名下的卡（开卡前先看清他手上有什么，含历史卡） */
const cards = ref([])
const cardsLoading = ref(false)

/** 弹层内的错误文案。⚠️ 不能靠 toast —— 它的层级比弹层低（见 AdminSheet 的说明） */
const cardError = ref('')
const cardSaving = ref(false)

/** 开卡表单：卡种与实收金额（留空＝按配置价；STAFF 卡忽略它） */
const newCardType = ref('ALL_DAY')
const newCardPrice = ref('')

/** 正在二次确认停用的那张卡 ID。停卡是「对顾客生效」的动作，点错了要能收回 */
const revokingId = ref(null)

/**
 * 打开月卡弹层并拉取该用户的卡。
 *
 * @param {object} user 列表里的一行
 */
async function openCardSheet(user) {
  cardUser.value = user
  cardError.value = ''
  newCardType.value = 'ALL_DAY'
  newCardPrice.value = ''
  revokingId.value = null
  cards.value = []
  await loadCards()
}

/** 拉该用户的卡（最新的在前）。分页参数是 pageNum —— 月卡接口与其他页不同 */
async function loadCards() {
  if (!cardUser.value) return
  cardsLoading.value = true
  try {
    const resp = await listAdminCards({
      userId: cardUser.value.id,
      pageNum: 1,
      pageSize: 20
    })
    cards.value = resp.data?.records || []
  } catch (err) {
    cardError.value = errorMessage(err, '加载月卡失败')
  } finally {
    cardsLoading.value = false
  }
}

/**
 * 提交开卡。
 *
 * <p>金额留空则不传，后端按配置价 —— 当面交易大多照价收；
 * 填 0 是「不收钱」（赠送、测试），那种不会记进累计消费。
 * STAFF 卡不传金额（它本来就无价）。
 */
async function submitIssueCard() {
  if (!cardUser.value) return
  cardSaving.value = true
  cardError.value = ''
  try {
    const data = { userId: cardUser.value.id, cardType: newCardType.value }
    if (newCardType.value !== 'STAFF' && newCardPrice.value !== '') {
      const price = Number(newCardPrice.value)
      if (!Number.isFinite(price) || price < 0) {
        cardError.value = '实收金额不能为负数；不收钱请填 0，按配置价请留空'
        return
      }
      data.price = price
    }
    await issueCard(data)
    toastSuccess('已开卡')
    newCardPrice.value = ''
    await loadCards()
    // 用户列表上的「月卡」那一列要跟着刷新，免得开完还显示「无」
    load()
  } catch (err) {
    cardError.value = errorMessage(err, '开卡失败')
  } finally {
    cardSaving.value = false
  }
}

/**
 * 执行停卡。
 *
 * <p>只停权益、不动钱 —— 弹层里的说明文字与后端口径一致
 * （已结算的订单一分不动，退款走线下）。
 *
 * @param {object} card 列表里的一张卡
 */
async function submitRevoke(card) {
  cardSaving.value = true
  cardError.value = ''
  try {
    await revokeCard(card.id)
    toastSuccess('已停用')
    revokingId.value = null
    await loadCards()
    load()
  } catch (err) {
    cardError.value = errorMessage(err, '停用失败')
  } finally {
    cardSaving.value = false
  }
}

/**
 * 一张卡的有效期展示。
 *
 * <p>⚠️ <b>STAFF 卡按卡种显示「长期有效」，不要摆出 2099-12-31</b> ——
 * 那是后端「无到期日」的实现方式（远期日期），对管理员来说是个实现细节；
 * 而按日期大小去判（如「大于 2090 年就是长期」）会在 2099 年之后静默失效。
 *
 * @param {object} card 卡视图
 * @returns {string} 形如 "2026-10-10 ~ 2026-11-08" / "长期有效"
 */
function cardPeriodText(card) {
  if (card.cardType === 'STAFF') return '长期有效'
  if (!card.startDate || !card.endDate) return '—'
  return `${card.startDate} ~ ${card.endDate}`
}

function askRole(user) {
  roleEditing.value = user
  roleError.value = ''
}

/**
 * 提交改角色。
 *
 * <p>角色只有 {@code USER} / {@code ADMIN} 两个取值，目标值由调用处传入：
 * 弹层按当前角色给出那一个反向动作，不做下拉 —— 就两个选项，
 * 下拉反而多一步。改完<b>立即生效、不必重新登录</b>
 * （鉴权每请求重读库里的角色，这也正是「改角色不升 token 版本」的依据）。
 *
 * @param {string} role 目标角色 USER / ADMIN
 */
async function confirmRole(role) {
  const user = roleEditing.value
  if (!user) return
  roleSaving.value = true
  roleError.value = ''
  try {
    await updateUserRole(user.id, role)
    roleEditing.value = null
    toastSuccess(role === 'ADMIN' ? '已设为管理员' : '已降为普通用户')
    load()
  } catch (err) {
    // ⚠️ 错误走弹层而不是 toast —— 层级问题，理由同 confirmToggle
    roleError.value = errorMessage(err, '操作失败')
  } finally {
    roleSaving.value = false
  }
}

onMounted(() => {
  load()
  loadPrefTypes()
})
</script>

<template>
  <!-- ⚠️ 根元素必须是 .page：后台各页都用它，宽度上限与居中都在那个类里。
       后台的宽度是 AdminLayout 覆写过的 --page-max-admin（900px）——
       用户端那个 --page-max 已经改成按手机的 480，后台跟着变的话表格要横滚 -->
  <div class="page">
    <!-- ---------------- 筛选栏 ---------------- -->
    <div class="users__filters">
      <input
        v-model="filters.keyword"
        class="field-input users__kw"
        type="text"
        placeholder="搜登录名 / 昵称 / QQ 号"
        @keyup.enter="applyFilters"
      />
      <select v-model="filters.role" class="field-input users__sel" @change="applyFilters">
        <option value="">全部角色</option>
        <option value="USER">普通用户</option>
        <option value="ADMIN">管理员</option>
      </select>
      <select v-model="filters.status" class="field-input users__sel" @change="applyFilters">
        <option value="">全部状态</option>
        <option :value="1">正常</option>
        <option :value="0">已禁用</option>
      </select>
      <select v-model="filters.card" class="field-input users__sel" @change="applyFilters">
        <option value="">全部月卡</option>
        <option value="ALL_DAY">全天月卡</option>
        <option value="NIGHT">夜间月卡</option>
        <option value="STAFF">STAFF 卡</option>
        <option value="NONE">没有月卡</option>
      </select>
      <button class="btn btn-ghost users__btn" @click="applyFilters">查询</button>
      <button class="btn btn-ghost users__btn" @click="resetFilters">重置</button>
    </div>

    <LoadingMask v-if="loading" />

    <EmptyState v-else-if="!records.length" text="没有符合条件的用户" />

    <template v-else>
      <!-- ---------------- 排序 + 表头 ---------------- -->
      <div class="users__sorts">
        <span class="users__sorts-label">排序：</span>
        <button
          v-for="s in [
            { key: 'createdAt', label: '注册时间' },
            { key: 'totalPaid', label: '累计消费' },
            { key: 'stayMinutes', label: '在店时长' }
          ]"
          :key="s.key"
          class="users__sort"
          :class="{ 'users__sort--on': sortBy === s.key }"
          @click="toggleSort(s.key)"
        >
          {{ s.label }}{{ sortMark(s.key) }}
        </button>
      </div>

      <ul class="users__list">
        <li v-for="u in records" :key="u.id" class="urow">
          <div class="urow__head">
            <span class="urow__name">{{ u.nickname || u.username }}</span>
            <span v-if="u.role === 'ADMIN'" class="tag tag-warning">管理员</span>
            <span v-if="u.status !== 1" class="tag tag-danger">已禁用</span>
            <span class="urow__account">@{{ u.username }}</span>
          </div>

          <div class="urow__meta">
            <span>QQ：{{ u.qq || '未绑定' }}</span>
            <span>手机：{{ u.phone || '—' }}</span>
            <span>月卡：{{ cardTypeLabel(u.cardType) }}</span>
          </div>

          <div class="urow__stats">
            <span>累计消费 ¥{{ u.totalPaid ?? '0.00' }}</span>
            <span>在店 {{ formatStay(u.totalStayMinutes) }}</span>
            <span class="urow__time">注册于 {{ formatDateTime(u.createdAt) }}</span>
          </div>

          <div class="urow__ops">
            <button class="btn btn-ghost urow__op" @click="openCardSheet(u)">月卡</button>
            <button class="btn btn-ghost urow__op" @click="openEdit(u)">编辑资料</button>
            <!-- ⚠️ 自己那一行禁掉 —— 后端禁止改自己的角色（降级后就进不去后台了），
                 前端提前拦一道，让他在点之前就知道，而不是点了才吃一句报错 -->
            <button
              class="btn btn-ghost urow__op"
              :disabled="u.id === myId"
              :title="u.id === myId ? '不能修改自己的角色 —— 降级后就进不去后台了' : ''"
              @click="askRole(u)"
            >
              改角色
            </button>
            <button class="btn btn-ghost urow__op" @click="askToggle(u)">
              {{ u.status === 1 ? '禁用' : '启用' }}
            </button>
          </div>
        </li>
      </ul>

      <AdminPager v-model:page="page" :size="PAGE_SIZE" :total="total" @update:page="load" />
    </template>

    <!-- ---------------- 编辑弹层 ---------------- -->
    <AdminSheet v-model:visible="formVisible" :error="formError" title="编辑用户资料">
      <div v-if="editing" class="form">
        <p class="form__who">
          {{ editing.nickname || editing.username }}
          <span class="form__account">@{{ editing.username }}</span>
        </p>

        <div class="field">
          <label class="field-label" for="f-nickname">昵称</label>
          <input id="f-nickname" v-model="form.nickname" class="field-input" type="text" />
        </div>

        <div class="field">
          <label class="field-label" for="f-phone">手机号</label>
          <input id="f-phone" v-model="form.phone" class="field-input" type="tel" />
        </div>

        <div class="field">
          <label class="field-label" for="f-qq">QQ 号</label>
          <input id="f-qq" v-model="form.qq" class="field-input" type="text" />
          <p class="form__hint">
            清空即解绑。改完照样查重 —— 两个账号绑同一个 QQ 会让群里的播报认错人
          </p>
        </div>

        <div class="field">
          <label class="field-label">游玩偏好</label>
          <PreferencePicker v-model="form.preference" :types="prefTypes" />
          <p class="form__hint">
            选项来自设备类型字典（后台「机台」页维护），点一下选中 / 取消，一个都不选 = 清空。
            虚线框的是字典里已停用、而他仍选着的类型。
          </p>
        </div>

        <p class="form__note">
          ⚠️ 没填的项会被清空（全量替换语义），改之前先确认每一项都是想要的。
          <br />登录名与 ID 不可改 —— 前者是登录凭据的一半，后者被所有业务表引用着。
        </p>

        <button class="btn btn-primary" :disabled="submitting" @click="submitForm">
          {{ submitting ? '保存中…' : '保 存' }}
        </button>
      </div>
    </AdminSheet>

    <!-- ---------------- 启停用确认 ---------------- -->
    <AdminSheet
      :visible="!!toggling"
      :error="toggleError"
      :title="toggling?.status === 1 ? '禁用这个用户' : '启用这个用户'"
      mask-closable
      @update:visible="toggling = null"
    >
      <p class="confirm">
        <template v-if="toggling?.status === 1">
          禁用后 <b>{{ toggling?.nickname || toggling?.username }}</b> 手上所有未过期的登录凭证
          <b>立即失效</b>，他下次访问就会被登出。
        </template>
        <template v-else>
          启用后 <b>{{ toggling?.nickname || toggling?.username }}</b> 可以重新登录。
        </template>
      </p>
      <div class="confirm__ops">
        <button class="btn btn-ghost" @click="toggling = null">取消</button>
        <button
          class="btn"
          :class="toggling?.status === 1 ? 'btn-danger' : 'btn-primary'"
          @click="confirmToggle"
        >
          确定
        </button>
      </div>
    </AdminSheet>

    <!-- ---------------- 改角色 ---------------- -->
    <AdminSheet
      :visible="!!roleEditing"
      :error="roleError"
      title="修改角色"
      mask-closable
      @update:visible="roleEditing = null"
    >
      <p class="confirm">
        <b>{{ roleEditing?.nickname || roleEditing?.username }}</b> 当前是
        <b>{{ roleEditing?.role === 'ADMIN' ? '管理员' : '普通用户' }}</b>。
      </p>
      <p class="form__note">
        ⚠️ 设为管理员后，<b>运营后台的全部功能</b>对他开放（用户、门店、设备、包场、
        收款复核……），在店名册上也会挂 STAFF 徽章。改动立即生效、不必重新登录；
        降回普通用户同理，他手上的凭证也不会被撤销。
      </p>
      <div class="confirm__ops">
        <button class="btn btn-ghost" @click="roleEditing = null">取消</button>
        <button
          v-if="roleEditing?.role !== 'ADMIN'"
          class="btn btn-primary"
          :disabled="roleSaving"
          @click="confirmRole('ADMIN')"
        >
          {{ roleSaving ? '处理中…' : '设为管理员' }}
        </button>
        <button
          v-else
          class="btn btn-danger"
          :disabled="roleSaving"
          @click="confirmRole('USER')"
        >
          {{ roleSaving ? '处理中…' : '降为普通用户' }}
        </button>
      </div>
    </AdminSheet>

    <!-- ---------------- 月卡：面对面开卡 / 停卡 ---------------- -->
    <AdminSheet
      :visible="!!cardUser"
      :error="cardError"
      :title="`月卡 · ${cardUser?.nickname || cardUser?.username || ''}`"
      @update:visible="cardUser = null"
    >
      <LoadingMask :loading="cardsLoading" />

      <!-- 名下已有的卡（含历史）。开卡前先看清他手上有什么 -->
      <div v-if="cards.length" class="cardsheet__list">
        <div v-for="c in cards" :key="c.id" class="cardsheet__card">
          <div class="cardsheet__head">
            <span class="cardsheet__type">{{ c.cardTypeLabel }}</span>
            <span :class="cardStatusCls(c.status)">{{ c.statusLabel }}</span>
            <!-- ⚠️ 两张同时生效是正常的（STAFF 卡不挡已有月卡），所以这里是「生效中」徽章而不是单选 -->
            <span v-if="c.effective" class="tag tag-success">生效中</span>
          </div>
          <p class="cardsheet__meta">
            {{ c.cardNo }} · {{ cardPeriodText(c) }}
            <template v-if="c.remainingDays != null"> · 还剩 {{ c.remainingDays }} 天</template>
          </p>

          <!-- 停卡只对生效中的卡给出；二次确认在卡片内展开（理由见 revokingId 的说明） -->
          <template v-if="c.effective">
            <div v-if="revokingId === c.id" class="cardsheet__confirm">
              <p class="cardsheet__warn">
                停用后<b>立刻不再免单</b>；已结算的订单不受影响，已收的卡费退不退走线下。
              </p>
              <div class="cardsheet__confirm-ops">
                <button class="btn btn-ghost" @click="revokingId = null">取消</button>
                <button class="btn btn-danger" :disabled="cardSaving" @click="submitRevoke(c)">
                  {{ cardSaving ? '停用中…' : '确定停用' }}
                </button>
              </div>
            </div>
            <button v-else class="btn btn-ghost cardsheet__stop" @click="revokingId = c.id">
              停用这张卡
            </button>
          </template>
        </div>
      </div>
      <p v-else-if="!cardsLoading" class="cardsheet__empty">名下暂无月卡。</p>

      <!-- 开卡 -->
      <div class="cardsheet__form">
        <div class="field">
          <label class="field-label" for="cs-type">开卡</label>
          <select id="cs-type" v-model="newCardType" class="field-input">
            <option v-for="t in MONTHLY_CARD_TYPES" :key="t.value" :value="t.value">
              {{ t.label }}
            </option>
          </select>
        </div>

        <div v-if="newCardType !== 'STAFF'" class="field">
          <label class="field-label" for="cs-price">实收金额（元，选填）</label>
          <input
            id="cs-price"
            v-model="newCardPrice"
            class="field-input"
            type="number"
            min="0"
            step="0.01"
            placeholder="留空按配置价"
          />
        </div>

        <p class="form__hint">
          <template v-if="newCardType === 'STAFF'">
            STAFF 卡无价格、长期有效，只在后台发放；可与月卡并存（计费按 STAFF 卡全免）。
          </template>
          <template v-else>
            面对面收款后在这里补录。填 0 表示不收钱（赠送、测试），那笔不记入累计消费；
            该用户已有生效卡时会被拒绝。
          </template>
        </p>
      </div>

      <template #footer>
        <button class="btn btn-ghost" @click="cardUser = null">关闭</button>
        <button class="btn btn-primary" :disabled="cardSaving" @click="submitIssueCard">
          {{ cardSaving ? '处理中…' : '开卡' }}
        </button>
      </template>
    </AdminSheet>
  </div>
</template>

<style scoped>
/* ⚠️ 这里【不写】padding —— 根元素上的 .page 已经带了
   （含顶部安全区那一份，见 base.css）。这里再写一次会叠成两倍留白，
   而 iPhone 上标题与内容之间会空出一大片 */

/* ---------- 筛选栏 ---------- */
.users__filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  margin-bottom: var(--sp-4);
}

/* ⚠️ .field-input 是 width:100%（给整行的表单字段用的），在 flex 行里要显式收窄，
   否则一个输入框就吃掉整行 —— 注册页的 QQ 那一栏踩过同样的坑 */
.users__filters .field-input {
  width: auto;
  height: 38px;
  font-size: 13px;
}

.users__kw {
  flex: 1 1 180px;
  min-width: 0;
}

.users__sel {
  flex: 0 0 auto;
}

.users__btn {
  flex: 0 0 auto;
  width: auto;
  height: 38px;
  padding: 0 var(--sp-3);
  font-size: 13px;
}

/* ---------- 排序 ---------- */
.users__sorts {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
  font-size: 13px;
  color: var(--c-text-sub);
}

.users__sort {
  background: none;
  border: none;
  padding: 2px 6px;
  font-size: 13px;
  color: var(--c-text-sub);
  cursor: pointer;
}

.users__sort--on {
  color: var(--c-primary);
  font-weight: 600;
}

/* ---------- 列表 ---------- */
.users__list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}

.urow {
  background: #fff;
  border-radius: var(--r-card);
  padding: var(--sp-4);
}

.urow__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
  margin-bottom: var(--sp-2);
}

.urow__name {
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text);
}

.urow__account {
  font-size: 12px;
  color: var(--c-text-muted);
}

.urow__meta,
.urow__stats {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-3);
  font-size: 12px;
  color: var(--c-text-sub);
  line-height: 1.8;
}

.urow__stats {
  color: var(--c-text);
}

.urow__time {
  color: var(--c-text-muted);
}

.urow__ops {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  margin-top: var(--sp-3);
}

.urow__op {
  width: auto;
  height: 32px;
  padding: 0 var(--sp-3);
  font-size: 12px;
}

/* 禁用态：自己那一行的「改角色」用得上（后端禁止改自己的角色）。
   全局那套按钮样式没有覆盖 disabled，不写这一条的话它看起来跟能点一样 */
.urow__op:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* ---------- 弹层表单 ---------- */
.form__who {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
  margin-bottom: var(--sp-4);
}

.form__account {
  font-weight: 400;
  font-size: 12px;
  color: var(--c-text-muted);
}

.form__hint {
  margin-top: var(--sp-2);
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-text-muted);
}

.form__note {
  margin: var(--sp-3) 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-warning);
}

.confirm {
  font-size: 14px;
  line-height: 1.7;
  color: var(--c-text);
}

.confirm__ops {
  display: flex;
  gap: var(--sp-3);
  margin-top: var(--sp-5);
}

/* ---------- 月卡弹层（开卡 / 停卡） ---------- */
.cardsheet__list {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  margin-bottom: var(--sp-4);
}

.cardsheet__card {
  padding: var(--sp-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r-btn);
  /* 白底而不是主题色：弹层本身已经在 --c-bg 上，再套一层同色系就看不出边界了 */
  background: #fff;
}

.cardsheet__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
}

.cardsheet__type {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}

.cardsheet__meta {
  margin-top: 2px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-text-muted);
  /* 「卡号 + 有效期」串较长，窄屏上别把卡片撑破 */
  word-break: break-all;
}

.cardsheet__stop {
  width: auto;
  height: 30px;
  margin-top: var(--sp-2);
  padding: 0 var(--sp-3);
  font-size: 12px;
}

.cardsheet__confirm {
  margin-top: var(--sp-2);
}

.cardsheet__warn {
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-warning);
}

.cardsheet__confirm-ops {
  display: flex;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
}

.cardsheet__empty {
  margin-bottom: var(--sp-4);
  font-size: 13px;
  color: var(--c-text-muted);
}
</style>
