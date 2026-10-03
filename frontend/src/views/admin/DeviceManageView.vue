<script setup>
/**
 * 机台管理（运营后台）。
 *
 * <p><b>这是纯展示的资产台账</b>：机台不绑定订单、不参与准入。哪怕全店机台都标成
 * 「维护中」，系统照样放人进门 —— 真要拦住顾客，排一条停业区间。
 * 所以这一页的产出只有两件事：顾客看到的陈列，以及首页那几条机台公告。
 *
 * <p>⚠️ <b>状况只走列表上的内联下拉，不进编辑表单</b>：
 * <ul>
 *   <li>后端为此专门留了 {@code PUT /{id}/status}（只更新一列）与
 *       「{@code status} 传 null 保持原值」这一个全量替换的例外</li>
 *   <li>表单是该接口最容易走错的入口：编辑表单若带上状况，
 *       改个名字就可能把「维护中」顺手改回「良好」，而管理员毫无察觉</li>
 * </ul>
 * 所以本页两份职责分得很清：<b>表单管静态属性，下拉管状况</b>。
 *
 * <p>⚠️ 改完状况<b>必须整表重取</b>：接口返回的 {@code data} 是 null，
 * 而状况的中文（{@code statusLabel}）由后端给 —— 只在本地改 {@code status} 的话，
 * 标签的中文还是旧的，只有颜色变了。机台不分页、几十条封顶，重取一次最省事。
 */
import { ref, computed, onMounted } from 'vue'
import {
  listAdminDevices,
  createDevice,
  updateDevice,
  updateDeviceStatus,
  deleteDevice
} from '@/api/admin'
import { listEquipmentTypes } from '@/api/device'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { DEVICE_STATUS, deviceStatusCls } from '@/utils/labels'
import AdminSheet from '@/components/AdminSheet.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingMask from '@/components/LoadingMask.vue'

const devices = ref([])
const types = ref([])
const loading = ref(true)

/** 状况筛选。纯本地筛选 —— 接口本来就不分页，没必要为它多打一次请求。 */
const statusFilter = ref('')

/** 正在改状况的那台（挡住重复点击）。 */
const busyId = ref(null)

/* ---------------- 表单弹层 ---------------- */

const formVisible = ref(false)
/** 正在编辑的那台；为 null 表示「新增」 */
const editing = ref(null)
const form = ref({ name: '', deviceNo: '', typeId: null, location: '', sort: 0, remark: '' })
const formError = ref('')
const submitting = ref(false)

/* ---------------- 删除确认 ---------------- */

const removing = ref(null)
const removeError = ref('')

/** 本地筛选后的列表。 */
const visibleDevices = computed(() =>
  statusFilter.value
    ? devices.value.filter((d) => d.status === statusFilter.value)
    : devices.value
)

/**
 * 类型下拉的选项。
 *
 * <p>⚠️ 接口只给<b>启用中</b>的类型，而某台机台的类型可能刚好被停用了 ——
 * 那时下拉里没有对应的 option，{@code v-model} 绑一个不存在的值，
 * 表现是「类型那一栏空着」，提交还会被判成没选。
 * 所以编辑时把这条机台自己的类型补进选项里 —— 它已经在 {@code DeviceVo} 上了。
 */
const typeOptions = computed(() => {
  const list = types.value.map((t) => ({ id: t.id, name: t.name }))
  const current = editing.value
  if (current?.typeId && !list.some((t) => t.id === current.typeId)) {
    list.push({ id: current.typeId, name: `${current.typeName || '未知类型'}（已停用）` })
  }
  return list
})

/** 拉取机台与类型字典。 */
async function load() {
  loading.value = true
  try {
    const [deviceResp, typeResp] = await Promise.all([
      listAdminDevices(),
      listEquipmentTypes()
    ])
    devices.value = deviceResp.data || []
    types.value = typeResp.data || []
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    loading.value = false
  }
}

/**
 * 内联改状况。
 *
 * @param {object} device 机台
 * @param {string} status 新状况（NORMAL / NEEDS_REPAIR / MAINTAINING）
 */
async function onStatusChange(device, status) {
  if (status === device.status) return
  busyId.value = device.id
  try {
    await updateDeviceStatus(device.id, status)
    toastSuccess('状况已更新')
    // 中文标签来自后端，必须重取，不能只改本地那一个字段
    await load()
  } catch (err) {
    toastError(errorMessage(err, '修改失败'))
    // 失败时下拉已经是新值了，重取一次把它拉回真实的状况
    await load()
  } finally {
    busyId.value = null
  }
}

/** 打开「新增」表单。 */
function openCreate() {
  editing.value = null
  form.value = {
    name: '',
    deviceNo: '',
    typeId: types.value[0]?.id ?? null,
    location: '',
    sort: 0,
    remark: ''
  }
  formError.value = ''
  formVisible.value = true
}

/**
 * 打开「编辑」表单。
 *
 * <p>⚠️ 逐字段取新对象而不是直接引用列表行：引用的话表单上的输入会实时改到
 * 列表数据上，点取消也回不去。
 */
function openEdit(row) {
  editing.value = row
  form.value = {
    name: row.name || '',
    deviceNo: row.deviceNo || '',
    typeId: row.typeId ?? null,
    location: row.location || '',
    sort: row.sort ?? 0,
    remark: row.remark || ''
  }
  formError.value = ''
  formVisible.value = true
}

/**
 * 组装提交体。
 *
 * <p>⚠️ <b>全量替换语义</b>：没传的字段会被清空，其中 {@code sort} 尤其阴 ——
 * 它不传是按 <b>0</b> 处理（不是保持原值），漏掉它只会让列表顺序悄悄变化。
 * 所以表单里必须有「排序」这一项，且这里一定要带上。
 * 唯一的例外是 {@code status}：这里<b>刻意不传</b>，由后端保持原值。
 */
function buildBody() {
  const sort = Number(form.value.sort)
  return {
    name: form.value.name.trim(),
    deviceNo: form.value.deviceNo.trim(),
    typeId: form.value.typeId,
    location: form.value.location.trim(),
    sort: Number.isFinite(sort) && sort >= 0 ? sort : 0,
    remark: form.value.remark.trim()
  }
}

/** 提交表单（新增或编辑）。 */
async function submit() {
  if (!form.value.name.trim()) {
    formError.value = '机台名称不能为空'
    return
  }
  if (!form.value.typeId) {
    formError.value = '请选择设备类型'
    return
  }

  submitting.value = true
  formError.value = ''
  try {
    if (editing.value) {
      await updateDevice(editing.value.id, buildBody())
      toastSuccess('已保存')
    } else {
      await createDevice(buildBody())
      toastSuccess('已新增，首页会多一条公告')
    }
    formVisible.value = false
    await load()
  } catch (err) {
    formError.value = errorMessage(err, '保存失败')
  } finally {
    submitting.value = false
  }
}

/** 执行退役（逻辑删除）。 */
async function confirmRemove() {
  submitting.value = true
  removeError.value = ''
  try {
    await deleteDevice(removing.value.id)
    toastSuccess('已退役')
    removing.value = null
    await load()
  } catch (err) {
    removeError.value = errorMessage(err, '操作失败')
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
        机台
        <span class="card-sub">{{ devices.length }} 台</span>
        <button class="btn btn-primary device__new" @click="openCreate">新增机台</button>
      </div>

      <div class="device__filter">
        <button
          class="device__filter-item"
          :class="{ 'device__filter-item--on': statusFilter === '' }"
          @click="statusFilter = ''"
        >
          全部
        </button>
        <button
          v-for="s in DEVICE_STATUS"
          :key="s.value"
          class="device__filter-item"
          :class="{ 'device__filter-item--on': statusFilter === s.value }"
          @click="statusFilter = s.value"
        >
          {{ s.label }}
        </button>
      </div>

      <LoadingMask :loading="loading" />

      <div v-if="visibleDevices.length" class="device__list">
        <div v-for="d in visibleDevices" :key="d.id" class="device__item">
          <div class="device__main">
            <div class="device__line">
              <span class="device__name">{{ d.name }}</span>
              <!-- 状况中文用后端给的 statusLabel，只有配色取自 labels.js -->
              <span :class="deviceStatusCls(d.status)">{{ d.statusLabel }}</span>
            </div>
            <div class="device__meta">
              <span>{{ d.typeName || '类型已停用' }}</span>
              <span v-if="d.deviceNo">· {{ d.deviceNo }}</span>
              <span v-if="d.location">· {{ d.location }}</span>
              <span>· 排序 {{ d.sort }}</span>
            </div>
            <p v-if="d.remark" class="device__remark">备注：{{ d.remark }}</p>
          </div>

          <div class="device__ops">
            <!--
              状况改这里：接口只更新这一列，不会碰到名称、位置等其他字段，
              也就不存在「覆盖别人刚改的内容」的窗口
            -->
            <select
              class="device__status"
              :value="d.status"
              :disabled="busyId === d.id"
              @change="onStatusChange(d, $event.target.value)"
            >
              <option v-for="s in DEVICE_STATUS" :key="s.value" :value="s.value">
                {{ s.label }}
              </option>
            </select>
            <button class="device__op" @click="openEdit(d)">编辑</button>
            <button class="device__op device__op--danger" @click="removing = d">退役</button>
          </div>
        </div>
      </div>

      <EmptyState
        v-else-if="!loading"
        icon="🕹"
        text="没有符合条件的机台"
        hint="换一个状况筛选，或新增一台"
      />
    </div>

    <p class="device__hint">
      机台状况不影响准入 —— 标成「维护中」只是告诉顾客别碰，系统照样放人进门。
      要挡住顾客，请到「门店」页排一条停业时段。
    </p>

    <!-- 新增 / 编辑 -->
    <AdminSheet
      v-model:visible="formVisible"
      :title="editing ? '编辑机台' : '新增机台'"
      :error="formError"
    >
      <div class="field">
        <label class="field-label" for="d-name">机台名称</label>
        <input id="d-name" v-model="form.name" class="field-input" maxlength="50" placeholder="如：拍拍机 1 号" />
      </div>

      <div class="field">
        <label class="field-label" for="d-type">设备类型</label>
        <select id="d-type" v-model="form.typeId" class="field-input">
          <option v-for="t in typeOptions" :key="t.id" :value="t.id">{{ t.name }}</option>
        </select>
      </div>

      <div class="field">
        <label class="field-label" for="d-no">资产编号（选填）</label>
        <input id="d-no" v-model="form.deviceNo" class="field-input" maxlength="32" placeholder="如 PP-001，同店内不可重复" />
      </div>

      <div class="field">
        <label class="field-label" for="d-loc">摆放位置（选填）</label>
        <input id="d-loc" v-model="form.location" class="field-input" maxlength="64" placeholder="如：进门左手第一台" />
      </div>

      <div class="field">
        <label class="field-label" for="d-sort">展示顺序</label>
        <input id="d-sort" v-model="form.sort" class="field-input" type="number" min="0" />
        <p class="device__hint">数字越小越靠前。⚠️ 不填会按 0 处理，可能让这台机在列表里提前。</p>
      </div>

      <div class="field">
        <label class="field-label" for="d-remark">运营备注（选填）</label>
        <textarea id="d-remark" v-model="form.remark" class="field-input" rows="2" maxlength="255" placeholder="只给运营看，顾客看不到，也不会进公告" />
      </div>

      <p class="device__hint">
        状况请到列表上直接改 —— 它是最常动的字段，单独一条通道才不会
        被一次普通改名顺手改掉。
      </p>

      <template #footer>
        <button class="btn btn-ghost" @click="formVisible = false">取消</button>
        <button class="btn btn-primary" :disabled="submitting" @click="submit">
          {{ submitting ? '提交中…' : '确定' }}
        </button>
      </template>
    </AdminSheet>

    <!-- 退役确认 -->
    <AdminSheet
      :visible="!!removing"
      title="机台退役"
      :error="removeError"
      mask-closable
      @update:visible="removing = null"
    >
      <p class="device__confirm">确定让「{{ removing?.name }}」退役吗？</p>
      <p class="device__hint">
        退役后它不再出现在顾客的机台页上，历史记录不受影响，首页会多一条公告。
      </p>

      <template #footer>
        <button class="btn btn-ghost" @click="removing = null">取消</button>
        <button class="btn btn-danger" :disabled="submitting" @click="confirmRemove">
          {{ submitting ? '处理中…' : '确定退役' }}
        </button>
      </template>
    </AdminSheet>
  </div>
</template>

<style scoped>
.device__new {
  width: auto;
  height: 32px;
  margin-left: auto;
  padding: 0 var(--sp-4);
  font-size: 13px;
}

.device__filter {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}

.device__filter-item {
  height: 28px;
  padding: 0 var(--sp-3);
  border-radius: var(--r-pill);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-size: 12px;
}

.device__filter-item--on {
  background: var(--c-primary);
  color: #fff;
}

.device__item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-3) 0;
}

.device__item + .device__item {
  border-top: 1px solid var(--c-border);
}

.device__main {
  min-width: 0;
}

.device__line {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.device__name {
  font-size: 14px;
  color: var(--c-text);
}

.device__meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--c-text-muted);
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.device__remark {
  margin-top: 2px;
  font-size: 11px;
  color: var(--c-text-sub);
}

.device__ops {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  flex-shrink: 0;
}

.device__status {
  height: 30px;
  padding: 0 var(--sp-2);
  border: 1px solid var(--c-border);
  border-radius: var(--r-btn);
  background: #fff;
  color: var(--c-text);
  font-size: 12px;
}

.device__op {
  font-size: 12px;
  color: var(--c-primary);
}

.device__op--danger {
  color: var(--c-danger);
}

.device__confirm {
  font-size: 14px;
  color: var(--c-text);
}

.device__hint {
  margin-top: var(--sp-2);
  font-size: 11px;
  color: var(--c-text-muted);
  line-height: 1.6;
}
</style>
