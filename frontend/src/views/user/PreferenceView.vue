<script setup>
/**
 * 设置游玩偏好。
 *
 * <p>偏好是<b>用户自报</b>的（逗号分隔的设备类型 code，多选），不靠系统推断 ——
 * 推断需要设备级使用记录，成本高一个量级，而单门店下尤其难做准。
 *
 * <p>⚠️ <b>本页同样踩在全量替换上</b>：{@code PUT /api/user/me} 传 null 即清空，
 * 所以提交时<b>必须把昵称 / 手机号 / QQ 一起带上</b>，
 * 否则用户改一次偏好就把联系方式全清了，且不报任何错。
 * 做法是进页面先 {@code GET /api/user/me} 回填全部字段，提交时整份发回去。
 *
 * <p>⚠️ <b>偏好是「倾向」，不是「占用事实」</b>：它说明张三通常玩拍拍机，
 * 不说明他此刻正占着那台机器。这一页只负责如实收集，不承担设备级追踪。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getProfile, updateProfile } from '@/api/user'
import { listEquipmentTypes } from '@/api/device'
import { setUser } from '@/stores/user'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import NavBar from '@/components/NavBar.vue'
import LoadingMask from '@/components/LoadingMask.vue'

const router = useRouter()

const types = ref([])
/** 当前选中的 code 集合。 */
const selected = ref([])
/** 原始资料 —— 提交时要原样带回去，见文件头那条说明。 */
const profile = ref(null)
const loading = ref(true)
const saving = ref(false)

const isSelected = (code) => selected.value.includes(code)

/** 已选数量，用于按钮上的一句话。 */
const selectedText = computed(() => {
  if (!selected.value.length) return '未设置'
  const names = types.value
    .filter((t) => selected.value.includes(t.code))
    .map((t) => t.name)
  return names.join('、')
})

function toggle(code) {
  const i = selected.value.indexOf(code)
  if (i > -1) {
    selected.value.splice(i, 1)
  } else {
    selected.value.push(code)
  }
}

async function load() {
  loading.value = true
  try {
    const [profileRes, typesRes] = await Promise.all([
      getProfile(),
      listEquipmentTypes()
    ])
    profile.value = profileRes.data
    types.value = typesRes.data || []
    selected.value = (profileRes.data?.preference || '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    loading.value = false
  }
}

async function onSave() {
  saving.value = true
  try {
    const resp = await updateProfile({
      // ⚠️ 这三项是从原始资料里原样带回来的，不是空的 ——
      // 少带任何一项，那个字段就会被清空且不报错
      nickname: profile.value?.nickname ?? null,
      phone: profile.value?.phone ?? null,
      qq: profile.value?.qq ?? null,
      // 一个都没选时传 null（清空偏好），而不是空串
      preference: selected.value.length ? selected.value.join(',') : null
    })
    setUser(resp.data)
    toastSuccess('偏好已保存')
    router.back()
  } catch (err) {
    toastError(errorMessage(err, '保存失败'))
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <NavBar title="游玩偏好" />

    <LoadingMask :loading="loading" />

    <div class="card">
      <div class="card-title">你常玩什么？</div>
      <p class="pref__intro">
        选填，可多选。它只作为「倾向」展示给同店的其他顾客，
        不代表你此刻正在使用哪台机台。
      </p>

      <div class="pref__options">
        <button
          v-for="t in types"
          :key="t.code"
          class="pref__option"
          :class="{ 'pref__option--on': isSelected(t.code) }"
          @click="toggle(t.code)"
        >
          {{ t.name }}
        </button>
      </div>

      <p class="pref__current">当前：{{ selectedText }}</p>
    </div>

    <button class="btn btn-primary pref__save" :disabled="saving" @click="onSave">
      {{ saving ? '保存中…' : '保存' }}
    </button>
  </div>
</template>

<style scoped>
.pref__intro {
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-text-sub);
  margin-bottom: var(--sp-4);
}

.pref__options {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
}

.pref__option {
  padding: var(--sp-2) var(--sp-4);
  border-radius: var(--r-pill);
  border: 1px solid var(--c-border);
  background: #fff;
  font-size: 13px;
  color: var(--c-text-sub);
}

.pref__option--on {
  border-color: var(--c-primary);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-weight: 500;
}

.pref__current {
  margin-top: var(--sp-4);
  font-size: 12px;
  color: var(--c-text-muted);
}

.pref__save {
  margin-top: var(--sp-4);
}
</style>
