<script setup>
/**
 * 用户管理（运营后台）。
 *
 * <p>看全部用户、按几个维度筛、按几个维度排、改资料与启停用。
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
import { ref, onMounted } from 'vue'
import { listUsers, updateUser, updateUserStatus } from '@/api/admin'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatDateTime } from '@/utils/format'
import { cardTypeLabel } from '@/utils/labels'
import AdminPager from '@/components/AdminPager.vue'
import AdminSheet from '@/components/AdminSheet.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

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
const form = ref({ nickname: '', phone: '', qq: '', preference: '' })
/** 弹层内的错误文案。⚠️ 不能靠 toast —— 它的层级比弹层低，会被遮罩压住 */
const formError = ref('')
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
    preference: user.preference || ''
  }
  formVisible.value = true
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
      preference: form.value.preference.trim() || null
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

onMounted(load)
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
            <button class="btn btn-ghost urow__op" @click="openEdit(u)">编辑资料</button>
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
          <label class="field-label" for="f-pref">游玩偏好</label>
          <input
            id="f-pref"
            v-model="form.preference"
            class="field-input"
            type="text"
            placeholder="逗号分隔的类型 code，如 PAIPAI,TAISHOU"
          />
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
  gap: var(--sp-2);
  margin-top: var(--sp-3);
}

.urow__op {
  width: auto;
  height: 32px;
  padding: 0 var(--sp-3);
  font-size: 12px;
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
</style>
