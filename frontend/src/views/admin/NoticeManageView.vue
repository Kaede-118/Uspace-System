<script setup>
/**
 * 公告管理（运营后台）。
 *
 * <p><b>公告是一条消息，不是一份状态</b>：自动公告只增不改 —— 机台修好不会把
 * 「转为维护中」那条改掉，而是再产生一条「转为良好」。所以这个列表读起来是一段历史，
 * 不要按惯性去「去重」或「清理」。
 *
 * <p>由此，<b>只有手写公告可改可删</b>。自动公告是把已发生的事实记下来，
 * 改写或删除等于篡改历史 —— 后端用 {@code NOTICE_AUTO_READONLY}(40929) 拦着，
 * 前端这边按后端给的 {@code editable} 把按钮隐掉（只是别让人白点一下，
 * 真正的拦截在服务端）。
 *
 * <p>⚠️ 自动公告的时间、来源、文案全部<b>用后端给的字段</b>
 * （{@code publishModeText} / {@code sourceTypeText} / {@code sourceId}），
 * 前端一个字都不用自己映射。
 */
import { ref, onMounted } from 'vue'
import { listNotices, createNotice, updateNotice, deleteNotice } from '@/api/admin'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { relativeTime, formatDateTime } from '@/utils/format'
import AdminPager from '@/components/AdminPager.vue'
import AdminSheet from '@/components/AdminSheet.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

/** 每页条数。与 AdminPager 的口径共用同一个常量，免得两处各写一个 10 */
const PAGE_SIZE = 10

const page = ref(1)
const total = ref(0)
const records = ref([])
const loading = ref(true)

/**
 * 发布方式筛选，空串表示「全部」。
 *
 * <p>⚠️ 空串<b>不能原样发给后端</b> —— 组装参数时要整个字段不传。
 * 后端 {@code publishMode} 虽然走的 {@code trimToNull}，但同样的写法
 * 用在 {@code enabled} 这类数字参数上会直接 400「取值不合法」，
 * 所以这里统一按「空即不传」处理，不给后面的页面留一个坏榜样。
 */
const publishMode = ref('')

/* ---------------- 表单弹层 ---------------- */

const formVisible = ref(false)
/** 正在编辑的那条；为 null 表示「新增」 */
const editing = ref(null)
const form = ref({ title: '', content: '', pinned: false })
/** 弹层内的错误文案。⚠️ 不能靠 toast —— 它的层级比弹层低，会被遮罩压住 */
const formError = ref('')
const submitting = ref(false)

/* ---------------- 删除确认 ---------------- */

const removing = ref(null)
const removeError = ref('')

/**
 * 拉取当前页。
 *
 * <p>页码可能「越界」：删掉最后一页的最后一条后，页码停在了一个不存在的页上，
 * 列表空白且没有任何报错 —— 与「本来就没有数据」长得一模一样。所以拿到总数后
 * 夹一次，越界就回到最后一页重拉（最多递归一层：新页码必定不越界）。
 */
async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size: PAGE_SIZE }
    if (publishMode.value) params.publishMode = publishMode.value

    const resp = await listNotices(params)
    const count = resp.data?.total || 0
    const maxPage = Math.max(1, Math.ceil(count / PAGE_SIZE))

    if (page.value > maxPage) {
      page.value = maxPage
      return load()
    }

    records.value = resp.data?.records || []
    total.value = count
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    loading.value = false
  }
}

/** 切筛选：回到第一页再查，否则会停在一个可能不存在的页码上。 */
function onFilterChange() {
  page.value = 1
  load()
}

/** 翻页。 */
function onPageChange(target) {
  page.value = target
  load()
}

/** 打开「新增」表单。 */
function openCreate() {
  editing.value = null
  form.value = { title: '', content: '', pinned: false }
  formError.value = ''
  formVisible.value = true
}

/**
 * 打开「编辑」表单。
 *
 * <p>⚠️ 取的是<b>新的对象</b>，不是列表里的那一行 —— 直接引用的话，
 * 表单上敲的每一个字都会实时改到列表数据上，点「取消」也回不去了。
 */
function openEdit(row) {
  editing.value = row
  form.value = { title: row.title || '', content: row.content || '', pinned: !!row.pinned }
  formError.value = ''
  formVisible.value = true
}

/** 提交表单（新增或编辑）。 */
async function submit() {
  const title = form.value.title.trim()
  if (!title) {
    formError.value = '标题不能为空'
    return
  }

  submitting.value = true
  formError.value = ''
  try {
    // ⚠️ 只传 title 与 content。publishMode / sourceType / sourceId 一律不传 ——
    // 走到这个接口就是手写公告，后端硬编码，传了也不会生效
    // ⚠️ pinned 是 1 / 0，不是布尔 —— 后端的请求字段是 Integer，与商品 enabled 同一套
    const body = { title, content: form.value.content.trim(), pinned: form.value.pinned ? 1 : 0 }
    if (editing.value) {
      await updateNotice(editing.value.id, body)
      toastSuccess('已保存')
    } else {
      await createNotice(body)
      toastSuccess('已发布')
    }
    formVisible.value = false
    await load()
  } catch (err) {
    formError.value = errorMessage(err, '保存失败')
  } finally {
    submitting.value = false
  }
}

/** 打开删除确认。 */
function openRemove(row) {
  removeError.value = ''
  removing.value = row
}

/** 执行删除。 */
async function confirmRemove() {
  submitting.value = true
  removeError.value = ''
  try {
    await deleteNotice(removing.value.id)
    toastSuccess('已删除')
    removing.value = null
    await load()
  } catch (err) {
    removeError.value = errorMessage(err, '删除失败')
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
        公告
        <span class="card-sub">只增不改的消息流</span>
        <button class="btn btn-primary notice__new" @click="openCreate">发公告</button>
      </div>

      <div class="notice__filter">
        <button
          v-for="opt in [
            { value: '', label: '全部' },
            { value: 'MANUAL', label: '手写' },
            { value: 'AUTO', label: '自动' }
          ]"
          :key="opt.value"
          class="notice__filter-item"
          :class="{ 'notice__filter-item--on': publishMode === opt.value }"
          @click="publishMode = opt.value; onFilterChange()"
        >
          {{ opt.label }}
        </button>
      </div>

      <LoadingMask :loading="loading" />

      <div v-if="records.length" class="notice__list">
        <div v-for="row in records" :key="row.id" class="notice__item">
          <div class="notice__head">
            <!-- 置顶的给个标记：不然列表上看不出这条为什么排在最前面 -->
            <span v-if="row.pinned" class="notice__pin">置顶</span>
            <span class="notice__title">{{ row.title }}</span>
            <!-- 自动/手写的中文由后端给（publishModeText），前端不自己映射 -->
            <span class="tag" :class="{ 'notice__tag-auto': !row.editable }">
              {{ row.publishModeText }}
            </span>
          </div>

          <p v-if="row.content" class="notice__content">{{ row.content }}</p>

          <div class="notice__foot">
            <span class="notice__meta">
              <template v-if="row.sourceTypeText">
                {{ row.sourceTypeText }} #{{ row.sourceId }} ·
              </template>
              <span :title="formatDateTime(row.createdAt)">
                {{ relativeTime(row.createdAt) }}
              </span>
            </span>

            <!--
              自动公告不渲染这两个按钮：它是已发生的事实的记录，
              改写等于篡改历史（后端会返回 40929）。这是语义问题，不是权限问题
            -->
            <span v-if="row.editable" class="notice__ops">
              <button class="notice__op" @click="openEdit(row)">编辑</button>
              <button class="notice__op notice__op--danger" @click="openRemove(row)">删除</button>
            </span>
          </div>
        </div>
      </div>

      <EmptyState
        v-else-if="!loading"
        icon="📢"
        text="还没有公告"
        hint="改机台状况、发一条手写公告，这里就会有内容"
      />

      <AdminPager
        :page="page"
        :size="PAGE_SIZE"
        :total="total"
        @update:page="onPageChange"
      />
    </div>

    <!-- 新增 / 编辑 -->
    <AdminSheet
      v-model:visible="formVisible"
      :title="editing ? '编辑公告' : '发布公告'"
      :error="formError"
    >
      <div class="field">
        <label class="field-label" for="notice-title">标题</label>
        <input
          id="notice-title"
          v-model="form.title"
          class="field-input"
          type="text"
          maxlength="100"
          placeholder="如：本周六 14:00–16:00 包场"
        />
      </div>
      <div class="field">
        <label class="field-label" for="notice-content">正文（选填）</label>
        <textarea
          id="notice-content"
          v-model="form.content"
          class="field-input"
          rows="3"
          maxlength="500"
          placeholder="补充说明，可以留空"
        />
      </div>
      <label class="notice__pin-field">
        <input v-model="form.pinned" type="checkbox" />
        <span>置顶（排在首页公告栏最前面）</span>
      </label>
      <p class="notice__hint">
        公告发出来就是可见的，没有「定时生效」—— 想提前准备就写到点再发。
        置顶用来放「今天临时调整营业时间」这类必须让顾客先看到的消息。
      </p>

      <template #footer>
        <button class="btn btn-ghost" @click="formVisible = false">取消</button>
        <button class="btn btn-primary" :disabled="submitting" @click="submit">
          {{ submitting ? '提交中…' : '确定' }}
        </button>
      </template>
    </AdminSheet>

    <!-- 删除确认 -->
    <AdminSheet
      :visible="!!removing"
      title="删除公告"
      :error="removeError"
      mask-closable
      @update:visible="removing = null"
    >
      <p class="notice__confirm">确定删除「{{ removing?.title }}」吗？</p>
      <p class="notice__hint">删掉之后首页就不再显示这条了，历史提交仍在数据库里。</p>

      <template #footer>
        <button class="btn btn-ghost" @click="removing = null">取消</button>
        <button class="btn btn-danger" :disabled="submitting" @click="confirmRemove">
          {{ submitting ? '删除中…' : '删除' }}
        </button>
      </template>
    </AdminSheet>
  </div>
</template>

<style scoped>
.notice__new {
  width: auto;
  height: 32px;
  margin-left: auto;
  padding: 0 var(--sp-4);
  font-size: 13px;
}

.notice__filter {
  display: flex;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}

.notice__filter-item {
  height: 28px;
  padding: 0 var(--sp-3);
  border-radius: var(--r-pill);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-size: 12px;
}

.notice__filter-item--on {
  background: var(--c-primary);
  color: #fff;
}

.notice__item {
  padding: var(--sp-3) 0;
}

.notice__item + .notice__item {
  border-top: 1px solid var(--c-border);
}

.notice__head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.notice__pin {
  flex-shrink: 0;
  padding: 0 6px;
  border-radius: var(--r-pill);
  background: var(--c-primary);
  color: #fff;
  font-size: 10px;
  line-height: 16px;
}

.notice__pin-field {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
  font-size: 13px;
  color: var(--c-text);
}

.notice__title {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 自动公告的标签压一档：满色的标签会让系统消息看着像运营发的通知 */
.notice__tag-auto {
  background: var(--c-icon-bg);
  color: var(--c-text-sub);
}

.notice__content {
  margin-top: var(--sp-1);
  font-size: 12px;
  color: var(--c-text-sub);
  line-height: 1.6;
}

.notice__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-top: var(--sp-2);
}

.notice__meta {
  font-size: 11px;
  color: var(--c-text-muted);
}

.notice__ops {
  display: flex;
  gap: var(--sp-4);
  flex-shrink: 0;
}

.notice__op {
  font-size: 12px;
  color: var(--c-primary);
}

.notice__op--danger {
  color: var(--c-danger);
}

.notice__confirm {
  font-size: 14px;
  color: var(--c-text);
}

.notice__hint {
  margin-top: var(--sp-2);
  font-size: 11px;
  color: var(--c-text-muted);
  line-height: 1.6;
}
</style>
