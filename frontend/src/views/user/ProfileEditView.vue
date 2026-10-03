<script setup>
/**
 * 修改个人资料。
 *
 * <p>⚠️ <b>本页最大的坑是「全量替换」</b>：{@code PUT /api/user/me} 传 {@code null}
 * 表示<b>清空该项</b>。所以进入页面必须先把当前值全部回填，
 * 提交时把四个字段一起带上 —— 只提交改动的那个，会把手机号和 QQ 号一起清掉，
 * <b>而且不报任何错</b>。
 *
 * <p>头像与背景图<b>不走这个接口</b>：它们各有独立的上传端点，
 * 传完即生效。混进表单的话，上面那条的后果就变成「把头像清空」。
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getProfile, updateProfile, changePassword } from '@/api/user'
import { userState, setUser, clear } from '@/stores/user'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import NavBar from '@/components/NavBar.vue'
import ImageUploader from '@/components/ImageUploader.vue'
import LoadingMask from '@/components/LoadingMask.vue'

const router = useRouter()

/** 表单。初始值从接口回填，**不要留空** —— 见文件头那条说明。 */
const form = ref({
  nickname: '',
  phone: '',
  qq: '',
  preference: '',
  avatar: '',
  banner: ''
})

const loading = ref(true)
const saving = ref(false)

/* ---------------- 改密码 ---------------- */

const pwd = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })
const changingPwd = ref(false)

async function load() {
  loading.value = true
  try {
    const resp = await getProfile()
    applyProfile(resp.data)
  } catch (err) {
    toastError(errorMessage(err, '资料加载失败'))
  } finally {
    loading.value = false
  }
}

/** 把接口返回的资料灌进表单与全局 store。 */
function applyProfile(user) {
  setUser(user)
  form.value = {
    nickname: user.nickname || '',
    phone: user.phone || '',
    qq: user.qq || '',
    preference: user.preference || '',
    avatar: user.avatar || '',
    banner: user.banner || ''
  }
}

/** 图片上传成功后，接口返回的是完整资料，直接整体替换。 */
function onUploaded(user) {
  applyProfile(user)
}

/**
 * 保存基本资料。
 *
 * <p>空串一律转成 {@code null}（后端把两者都当作「清空」，但显式传 null 更清楚）。
 */
async function onSave() {
  saving.value = true
  try {
    const resp = await updateProfile({
      nickname: form.value.nickname.trim() || null,
      phone: form.value.phone.trim() || null,
      qq: form.value.qq.trim() || null,
      preference: form.value.preference || null
    })
    applyProfile(resp.data)
    toastSuccess('已保存')
    router.back()
  } catch (err) {
    toastError(errorMessage(err, '保存失败'))
  } finally {
    saving.value = false
  }
}

/**
 * 改密码。
 *
 * <p>⚠️ 改密成功后服务端会升 {@code token_version}，<b>所有旧凭证立即失效</b> ——
 * 包括当前这台设备。所以成功之后必须清登录态并回登录页，
 * 否则用户会看到一个「已登录但每个请求都 401」的诡异状态。
 */
async function onChangePassword() {
  const { oldPassword, newPassword, confirmPassword } = pwd.value
  if (!oldPassword || !newPassword) {
    toastError('请填写原密码与新密码')
    return
  }
  if (newPassword.length < 8 || newPassword.length > 32) {
    toastError('新密码为 8~32 位')
    return
  }
  if (newPassword !== confirmPassword) {
    toastError('两次输入的新密码不一致')
    return
  }

  changingPwd.value = true
  try {
    await changePassword(oldPassword, newPassword)
    toastSuccess('密码已修改，请重新登录')
    clear()
    router.replace('/login')
  } catch (err) {
    toastError(errorMessage(err, '修改密码失败'))
  } finally {
    changingPwd.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <NavBar title="修改个人资料" />

    <LoadingMask :loading="loading" />

    <div class="card">
      <div class="card-title">头像与背景</div>
      <div class="profile__uploads">
        <ImageUploader
          kind="avatar"
          shape="circle"
          :url="form.avatar"
          hint="点击更换头像"
          @uploaded="onUploaded"
        />
        <div class="profile__banner">
          <ImageUploader
            kind="banner"
            shape="rect"
            :url="form.banner"
            hint="会自动裁成 3:1 的长条作为卡片背景，比 3:1 更宽的素材（如舞萌姓名框）保留右半"
            @uploaded="onUploaded"
          />
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-title">基本信息</div>

      <div class="field">
        <label class="field-label" for="nickname">昵称</label>
        <input
          id="nickname"
          v-model="form.nickname"
          class="field-input"
          type="text"
          placeholder="留空会按 QQ 号 → 用户名兜底"
        />
      </div>

      <div class="field">
        <label class="field-label" for="phone">手机号</label>
        <input id="phone" v-model="form.phone" class="field-input" type="tel" placeholder="选填" />
      </div>

      <div class="field">
        <label class="field-label" for="qq">QQ 号</label>
        <!--
          ⚠️ 只读。QQ 号是机器人在群里认人的唯一依据，而注册时它经过一次群内验证
          （取码 → 发到群里 → 机器人回执）；允许本人改，那次验证就等于白做 ——
          先随便填一个号注册，再改成群里某个人的号。后端也会拒（40937），
          这里禁用是为了让用户一眼看出「这栏不是我能动的」，而不是填完才被拒。
        -->
        <input
          id="qq"
          v-model="form.qq"
          class="field-input"
          type="text"
          disabled
          placeholder="未绑定"
        />
        <p class="field-hint">
          绑定后不能自己改 —— 需要换号请联系管理员
        </p>
      </div>

      <p class="profile__note">
        清空昵称、手机号、游玩偏好并保存即可解除绑定；<b>QQ 号除外</b>，它只能由管理员改
      </p>

      <button class="btn btn-primary" :disabled="saving" @click="onSave">
        {{ saving ? '保存中…' : '保存' }}
      </button>
    </div>

    <div class="card">
      <div class="card-title">修改密码</div>

      <div class="field">
        <label class="field-label" for="old">原密码</label>
        <input id="old" v-model="pwd.oldPassword" class="field-input" type="password" />
      </div>
      <div class="field">
        <label class="field-label" for="new">新密码</label>
        <input
          id="new"
          v-model="pwd.newPassword"
          class="field-input"
          type="password"
          placeholder="8~32 位"
        />
      </div>
      <div class="field">
        <label class="field-label" for="confirm">确认新密码</label>
        <input id="confirm" v-model="pwd.confirmPassword" class="field-input" type="password" />
      </div>

      <p class="profile__note profile__note--warn">
        改密码后所有设备都会退出登录，需要重新登录
      </p>

      <button class="btn btn-ghost" :disabled="changingPwd" @click="onChangePassword">
        {{ changingPwd ? '提交中…' : '修改密码' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.profile__uploads {
  display: flex;
  gap: var(--sp-4);
  align-items: flex-start;
}

.profile__banner {
  flex: 1;
  min-width: 0;
}

/*
 * 只读输入框的视觉：要让「这栏不是我能动的」一眼看得出来。
 * 继承 .field-input 的 disabled 默认样式在各浏览器里差别很大（有的几乎看不出区别），
 * 所以显式给它一个浅底 + 次要文字色 + 禁用光标。
 */
.field-input:disabled {
  background: #f5f4f8;
  color: var(--c-text-sub);
  cursor: not-allowed;
}

.field-hint {
  margin-top: var(--sp-2);
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-text-muted);
}

.profile__note {
  margin-bottom: var(--sp-3);
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-text-muted);
}

.profile__note--warn {
  color: var(--c-warning);
}
</style>
