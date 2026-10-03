<script setup>
/**
 * 登录页。
 *
 * <p><b>后台管理员也从这一页登录</b>：后端只有一个登录接口，
 * 分成两个登录页意味着两套表单校验与两处「登录后往哪跳」的逻辑。
 * 管理员从 {@code /admin} 进来时会被路由守卫带上 {@code redirect} 参数，
 * 登录成功后跳回那一页。
 */
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { login } from '@/api/auth'
import { setAuth } from '@/stores/user'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'

const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const submitting = ref(false)

/** 登录成功后要跳去的地址，由路由守卫带过来。 */
const redirect = ref('')

onMounted(() => {
  redirect.value = route.query.redirect || ''
})

/**
 * 提交登录。
 *
 * <p>成功后必须调用 {@link setAuth} 把凭证与用户资料写进 store 与 localStorage ——
 * 只写 store 的话刷新页面就掉线，只写 localStorage 的话当前页面拿不到用户信息。
 */
async function onSubmit() {
  if (!username.value.trim()) {
    toastError('请输入用户名')
    return
  }
  if (!password.value) {
    toastError('请输入密码')
    return
  }

  submitting.value = true
  try {
    const resp = await login(username.value.trim(), password.value)
    // resp 是 ApiResult，业务数据在 resp.data 里（少写这一层拿到的是恒为真的对象）
    const { token, user } = resp.data
    setAuth(token, user)
    toastSuccess('登录成功')

    // 有 redirect 就回去（管理员从 /admin 进来时，守卫会带上它），
    // 否则**一律进用户端首页** —— 管理员也是用户，登录后先看到的是店里的样子；
    // 要进后台，从地址栏或「我的」页进即可（后台不设独立登录页）。
    router.replace(redirect.value || '/home')
  } catch (err) {
    // 密码错误时后端返回 401，但 `http.js` 已把登录接口排除在
    // 「登录过期」处置之外，所以这里拿到的是「用户名或密码错误」这句原文
    toastError(errorMessage(err, '登录失败，请稍后重试'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login">
    <div class="login__head">
      <h1 class="login__title">共享娱乐空间</h1>
      <p class="login__sub">无人值守 · 自助开门 · 按时计费</p>
    </div>

    <form class="login__form" @submit.prevent="onSubmit">
      <div class="field">
        <label class="field-label" for="username">用户名</label>
        <input
          id="username"
          v-model="username"
          class="field-input"
          type="text"
          autocomplete="username"
          placeholder="请输入用户名"
        />
      </div>

      <div class="field">
        <label class="field-label" for="password">密码</label>
        <input
          id="password"
          v-model="password"
          class="field-input"
          type="password"
          autocomplete="current-password"
          placeholder="请输入密码"
        />
      </div>

      <button class="btn btn-primary" type="submit" :disabled="submitting">
        {{ submitting ? '登录中…' : '登 录' }}
      </button>
    </form>

    <p class="login__foot">
      还没有账号？
      <router-link to="/register">立即注册</router-link>
    </p>
  </div>
</template>

<style scoped>
.login {
  min-height: 100%;
  /* 表单限宽居中：输入框横跨整块屏幕既难读、也不好点 */
  max-width: 420px;
  margin: 0 auto;
  padding: calc(var(--sp-6) + var(--safe-top)) var(--sp-5) var(--sp-6);
  display: flex;
  flex-direction: column;
}

.login__head {
  margin-top: 12vh;
  margin-bottom: var(--sp-6);
  text-align: center;
}

.login__title {
  font-size: 24px;
  font-weight: 600;
  color: var(--c-primary);
  letter-spacing: 1px;
}

.login__sub {
  margin-top: var(--sp-2);
  font-size: 13px;
  color: var(--c-text-muted);
}

.login__form {
  background: #fff;
  border-radius: var(--r-card);
  padding: var(--sp-5);
}

.login__foot {
  margin-top: auto;
  padding-top: var(--sp-6);
  text-align: center;
  font-size: 13px;
  color: var(--c-text-sub);
}
</style>
