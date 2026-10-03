<script setup>
/**
 * 注册页。
 *
 * <p>⚠️ <b>注册接口不返回 token</b>（模块 1 不依赖模块 2），
 * 所以注册成功后要再调一次登录接口才能进首页。
 * 这是刻意的模块边界，不是遗漏。
 *
 * <p><b>QQ 号那一栏带一整套验证流程</b>：填了 QQ 就必须先证明这个号是本人的 ——
 * 点「获取验证码」拿到 6 位码，把它发到 QQ 群，机器人在群里看到后回执给后端，
 * 状态转「已验证」之后才提交得了。理由见后端 QqVerifyService 的类注释：
 * 群播报与群查询全靠 sys_user.qq 认人，而这个号在注册页填的时候没有任何可信度。
 */
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { register, issueQqVerify, getQqVerifyStatus } from '@/api/user'
import { login } from '@/api/auth'
import { setAuth } from '@/stores/user'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'

const router = useRouter()

/**
 * 轮询间隔（毫秒）。
 *
 * <p>3 秒：用户要切到 QQ、翻群、粘贴、发送，这点时间够他做完第一步；
 * 而查得太勤对后端没有意义（这条链路每 3 秒一次查询，比在店名册的 30 秒轮询密，
 * 但只在注册这一小段时间内跑）。
 */
const POLL_INTERVAL = 3000

/**
 * sessionStorage 的键。
 *
 * <p>存它是因为<b>取码与发群之间用户会切走</b>（去 QQ 粘贴），回来时页面
 * 可能已被浏览器回收重建。不恢复的话他会看到一个空的表单，
 * 而验证码已经发出去了 —— 只能重新取一次码，前一条就成了死信。
 */
const STORAGE_KEY = 'uspace.qq-verify'

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
 * QQ 验证状态。
 *
 * <p>`qq` 也存一份，用来判断「用户改了 QQ 号」—— 改了就整份作废重来。
 * 不作废的话，他会拿 A 号的验证去注册 B 号，而后端按 B 号查表查不到，
 * 报一个 404，用户完全看不懂发生了什么。
 */
const qqVerify = ref({
  qq: '',
  challengeId: '',
  code: '',
  verified: false,
  issuing: false
})

/** 轮询定时器。null 表示没在轮询 */
let pollTimer = null

/** 停掉轮询 */
function stopPolling() {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/** 把当前验证状态写进 sessionStorage */
function persist() {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(qqVerify.value))
  } catch {
    // 隐私模式下 sessionStorage 可能不可用。存不了就存不了，
    // 代价只是刷新后要重新取码，主流程不受影响
  }
}

/** 清空验证状态 */
function resetVerify() {
  stopPolling()
  qqVerify.value = { qq: '', challengeId: '', code: '', verified: false, issuing: false }
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // 同上
  }
}

/**
 * 轮询「群里确认了没有」。
 *
 * <p>只有 404 会停：那表示挑战不存在、已过期或凭证对不上（后端刻意不区分，
 * 免得泄露「这个 challengeId 曾经存在过」），此时继续轮询永远不会成功，
 * 必须让用户重新取码。网络抖动之类的错误不停，下一轮再试。
 */
function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    const { qq, challengeId } = qqVerify.value
    if (!qq || !challengeId) {
      stopPolling()
      return
    }
    try {
      const resp = await getQqVerifyStatus(qq, challengeId)
      if (resp.data.verified) {
        qqVerify.value.verified = true
        persist()
        stopPolling()
        toastSuccess('QQ 号验证成功')
      }
    } catch (err) {
      if (err?.response?.status === 404) {
        toastError('验证已失效，请重新获取验证码')
        resetVerify()
      }
    }
  }, POLL_INTERVAL)
}

/** 页面加载时恢复上一次的验证状态（用户可能取完码去群里发了一趟） */
function restoreVerify() {
  try {
    const saved = sessionStorage.getItem(STORAGE_KEY)
    if (!saved) {
      return
    }
    const parsed = JSON.parse(saved)
    // 只恢复属于当前表单里那个 QQ 的 —— QQ 换了就不作数
    if (parsed.qq && parsed.qq === form.value.qq.trim()) {
      qqVerify.value = { ...parsed, issuing: false }
      if (!parsed.verified) {
        startPolling()
      }
    }
  } catch {
    resetVerify()
  }
}

/**
 * 点「获取验证码」。
 *
 * <p>对应后端 {@code POST /api/user/qq-verify}。返回的 challengeId 留在页面里
 * （轮询与提交注册都要带），6 位码显示给用户让他复制到群里。
 */
async function onIssueCode() {
  const qq = form.value.qq.trim()
  if (!/^\d{5,12}$/.test(qq)) {
    toastError('请先填写正确的 QQ 号')
    return
  }
  qqVerify.value.issuing = true
  try {
    const resp = await issueQqVerify(qq)
    qqVerify.value = {
      qq,
      challengeId: resp.data.challengeId,
      code: resp.data.code,
      verified: false,
      issuing: false
    }
    persist()
    startPolling()
  } catch (err) {
    qqVerify.value.issuing = false
    toastError(errorMessage(err, '获取验证码失败，请稍后重试'))
  }
}

// 用户改了 QQ 号 → 之前那份验证作废
watch(() => form.qq, (val) => {
  const trimmed = val.trim()
  if (qqVerify.value.challengeId && trimmed !== qqVerify.value.qq) {
    resetVerify()
  }
})

onMounted(restoreVerify)
onUnmounted(stopPolling)

/**
 * 提交注册。
 *
 * <p>前端只做「明显填错」的即时校验，真正的规则以后端为准 ——
 * 前端重复实现一遍校验规则，改规则时会出现「前端过了、后端拒了」
 * 这种让用户困惑的组合。这里校验四条：两次密码一致、用户名格式、
 * 密码长度、以及「填了 QQ 就必须先完成验证」。
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
  // QQ 号是【必填】的 —— 店里没有店员，「不绑 QQ 也能进店」等于留一个找不到主的顾客。
  // 前端这层校验是为了把话说在前面，真正的门槛在后端的 @NotBlank + 群内验证
  if (!qq) {
    toastError('请填写 QQ 号 —— 它是出了事能找到你的唯一线索')
    return
  }
  if (!/^\d{5,12}$/.test(qq)) {
    toastError('QQ 号应为 5~12 位数字')
    return
  }
  // 而且必须先过群内验证。不拦的话用户填完整张表才被拒，而那时代码已经发到群里了
  if (!qqVerify.value.verified) {
    toastError('请先点「获取验证码」，并把它发到 QQ 群完成验证')
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
      qq: qq.trim(),
      // QQ 必填，所以凭证也必带 —— 后端拿它去换「这个 QQ 已验证」的结论
      challengeId: qqVerify.value.challengeId
    })

    // 注册成功，验证凭证已经用完即焚，清掉本地的痕迹
    resetVerify()

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
        <label class="field-label" for="qq">QQ 号 <span class="req">*</span></label>
        <div class="qq-row">
          <input
            id="qq"
            v-model="form.qq"
            class="field-input"
            type="text"
            placeholder="必填，用于在群里找到你"
          />
          <button
            type="button"
            class="btn btn-ghost qq-row__btn"
            :disabled="qqVerify.issuing || submitting"
            @click="onIssueCode"
          >
            {{ qqVerify.issuing ? '获取中…' : '获取验证码' }}
          </button>
        </div>
        <p
          v-if="qqVerify.challengeId"
          class="qq-hint"
          :class="{ 'qq-hint--ok': qqVerify.verified }"
        >
          <template v-if="qqVerify.verified">已验证 ✓ 这个 QQ 号可以注册了</template>
          <template v-else>
            把 <b class="qq-hint__code">{{ qqVerify.code }}</b> 发到 QQ 群，验证会自动完成…
          </template>
        </p>
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

/* ---- QQ 号那一栏：输入框 + 取码按钮并排 ---- */
.qq-row {
  display: flex;
  align-items: stretch;
  gap: var(--sp-2);
}

/*
 * ⚠️ 必须覆盖 .field-input 的 width:100% —— 在普通的块级布局里那个宽度是对的，
 * 但在这一行 flex 里它会让输入框先要占满整行，再加上按钮的宽度就溢出了，
 * 于是 flex 把输入框压扁到只剩几十像素，连一个字都显示不全。
 *
 * min-width:0 是配套的那一半：flex item 的 min-width 默认是 auto，
 * 它会拒绝收缩到「内容宽度」以下 —— 对 input 来说那个宽度是按 size 属性
 * （约 20 个字符）算的，不写 min-width:0 的话上面那句 flex 也压不下来。
 */
.qq-row .field-input {
  flex: 1 1 auto;
  width: auto;
  min-width: 0;
}

/*
 * ⚠️ width:auto 这一行是必须的，不能省。
 * .btn 基础样式里带着 width:100%（它设计上是「整行的主按钮」），而这里
 * flex-shrink 取 0（按钮不该被压扁）—— 两者一组合，按钮就凭 100% 的基准宽度
 * 吃掉整行，右边的输入框被挤到只剩几十像素，一个字都显示不出来。
 * 教训：往 flex 行里放「本来按整行宽度设计」的组件时，
 * 光改容器的 display 不够，两个子元素身上的 width:100% 都要逐个覆盖。
 */
.qq-row__btn {
  flex: 0 0 auto;
  width: auto;
  white-space: nowrap;
  font-size: 13px;
  padding: 0 var(--sp-3);
}

.qq-hint {
  margin-top: var(--sp-2);
  font-size: 13px;
  line-height: 1.6;
  color: var(--c-text-sub);
}

.qq-hint--ok {
  color: var(--c-success);
}

.qq-hint__code {
  font-size: 16px;
  letter-spacing: 2px;
  color: var(--c-primary);
}

.register__foot {
  margin-top: var(--sp-5);
  text-align: center;
  font-size: 13px;
  color: var(--c-text-sub);
}
</style>
