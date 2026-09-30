<script setup>
/**
 * 注册页。
 *
 * <p>⚠️ <b>注册接口不返回 token</b>（模块 1 不依赖模块 2），
 * 所以注册成功后要再调一次登录接口才能进首页。
 * 这是刻意的模块边界，不是遗漏。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { register } from '@/api/user'
import { login } from '@/api/auth'
import { setAuth } from '@/stores/user'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'

const router = useRouter()

const form = ref({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
  phone: '',
  qq: ''
})
const submitting = ref(false)

/**
 * 提交注册。
 *
 * <p>前端只做「明显填错」的即时校验，真正的规则以后端为准 ——
 * 前端重复实现一遍校验规则，改规则时会出现「前端过了、后端拒了」
 * 这种让用户困惑的组合。这里校验三条：两次密码一致、用户名格式、密码长度。
 */
async function onSubmit() {
  const { username, password, confirmPassword, nickname, phone, qq } = form.value

  if (!/^[a-zA-Z0-9_]{3,20}$/.test(username)) {
    toastError('用户名为 3~20 位字母、数字或下划线')
    return
  }
  if (password.length < 8 || password.length > 32) {
    toastError('密码为 8~32 位')
    return
  }
  if (password !== confirmPassword) {
    toastError('两次输入的密码不一致')
    return
  }
  // QQ 号是选填的，但填了就必须合法 —— 模块 11 靠它定位用户，格式错了会让播报找不到人
  if (qq && !/^\d{5,12}$/.test(qq)) {
    toastError('QQ 号应为 5~12 位数字')
    return
  }

  submitting.value = true
  try {
    await register({
      username,
      password,
      // 空串一律转成 null：后端把「空串」与「未填」区别对待（后者会按 QQ → 用户名兜底）
      nickname: nickname.trim() || null,
      phone: phone.trim() || null,
      qq: qq.trim() || null
    })

    // 注册成功没有凭证，紧接着登录一次 —— 用户感知上是「注册完直接就进去了」
    const resp = await login(username, password)
    setAuth(resp.data.token, resp.data.user)

    toastSuccess('注册成功')
    router.replace('/home')
  } catch (err) {
    toastError(errorMessage(err, '注册失败，请稍后重试'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="register">
    <h1 class="register__title">注册账号</h1>

    <form class="register__form" @submit.prevent="onSubmit">
      <div class="field">
        <label class="field-label" for="username">用户名 <span class="req">*</span></label>
        <input
          id="username"
          v-model="form.username"
          class="field-input"
          type="text"
          autocomplete="username"
          placeholder="3~20 位字母、数字或下划线"
        />
      </div>

      <div class="field">
        <label class="field-label" for="password">密码 <span class="req">*</span></label>
        <input
          id="password"
          v-model="form.password"
          class="field-input"
          type="password"
          autocomplete="new-password"
          placeholder="8~32 位"
        />
      </div>

      <div class="field">
        <label class="field-label" for="confirm">确认密码 <span class="req">*</span></label>
        <input
          id="confirm"
          v-model="form.confirmPassword"
          class="field-input"
          type="password"
          autocomplete="new-password"
          placeholder="再输入一次"
        />
      </div>

      <div class="field">
        <label class="field-label" for="nickname">昵称</label>
        <input
          id="nickname"
          v-model="form.nickname"
          class="field-input"
          type="text"
          placeholder="选填，留空时按 QQ 号 → 用户名兜底"
        />
      </div>

      <div class="field">
        <label class="field-label" for="phone">手机号</label>
        <input
          id="phone"
          v-model="form.phone"
          class="field-input"
          type="tel"
          placeholder="选填"
        />
      </div>

      <div class="field">
        <label class="field-label" for="qq">QQ 号</label>
        <input id="qq" v-model="form.qq" class="field-input" type="text" placeholder="选填，绑定后可在群里找到你" />
      </div>

      <button class="btn btn-primary" type="submit" :disabled="submitting">
        {{ submitting ? '提交中…' : '注 册' }}
      </button>
    </form>

    <p class="register__foot">
      已有账号？
      <router-link to="/login">去登录</router-link>
    </p>
  </div>
</template>

<style scoped>
.register {
  min-height: 100%;
  /* 同登录页：表单限宽居中 */
  max-width: 420px;
  margin: 0 auto;
  padding: calc(var(--sp-5) + var(--safe-top)) var(--sp-5) var(--sp-6);
}

.register__title {
  font-size: 20px;
  font-weight: 600;
  margin-bottom: var(--sp-5);
  color: var(--c-text);
}

.register__form {
  background: #fff;
  border-radius: var(--r-card);
  padding: var(--sp-5);
}

.req {
  color: var(--c-danger);
}

.register__foot {
  margin-top: var(--sp-5);
  text-align: center;
  font-size: 13px;
  color: var(--c-text-sub);
}
</style>
