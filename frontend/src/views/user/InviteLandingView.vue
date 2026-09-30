<script setup>
/**
 * 邀请落地页 —— 被邀请者点开分享链接后看到的第一屏。
 *
 * <p>两件事：展示包场信息与参与者名单、<b>自动把访问者记进参与者表</b>。
 *
 * <p>⚠️ 加入是<b>单独的一次 POST</b>，不是靠加载这个页面完成的：
 * GET 会被浏览器预取、被刷新、被爬虫重复请求，让这些无关动作决定「谁进了名单」
 * 是不对的。用户感受完全一样（点开链接就进去了）。
 *
 * <p>重复加入不是错误 —— 刷新页面、从聊天记录里再点一次都会走到那条分支，
 * 后端返回的是「你已经进入过了」这个<b>答案</b>，而不是异常。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getInviteInfo, joinByInvite } from '@/api/booking'
import { createOrder } from '@/api/order'
import { toastSuccess, toastError, toastInfo } from '@/composables/useToast'
import { errorMessage, isCode, ErrorCode } from '@/utils/error'
import { formatDateTime, formatTime } from '@/utils/format'
import { versionedUrl } from '@/utils/image'
import NavBar from '@/components/NavBar.vue'
import LoadingMask from '@/components/LoadingMask.vue'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const token = route.params.token

const invite = ref(null)
const participants = ref([])
const loading = ref(true)
const notFound = ref(false)

/** 加入结果：null 表示还没跑完（或失败了）。 */
const joinResult = ref(null)
const joinFailed = ref(false)

const opening = ref(false)

const timeRange = computed(() => {
  const i = invite.value
  if (!i?.startAt || !i?.endAt) return '—'
  const sameDay = String(i.startAt).slice(0, 10) === String(i.endAt).slice(0, 10)
  return sameDay
    ? `${formatDateTime(i.startAt)} – ${formatTime(i.endAt)}`
    : `${formatDateTime(i.startAt)} – ${formatDateTime(i.endAt)}`
})

/** 加入结果的一句话说明。 */
const joinText = computed(() => {
  if (joinFailed.value) return '自动加入未成功，你仍可以正常下单进场'
  if (!joinResult.value) return ''
  if (joinResult.value.alreadyJoined) return '你已经在名单里了'
  return '已加入，包场时段到店即可'
})

async function load() {
  loading.value = true
  try {
    const resp = await getInviteInfo(token)
    invite.value = resp.data
    participants.value = resp.data?.participants || []
    // 拿到名单后立刻加入 —— 用户无感，但系统里从此记着他
    await doJoin()
  } catch (err) {
    notFound.value = true
    toastError(errorMessage(err, '邀请链接无效或已失效'))
  } finally {
    loading.value = false
  }
}

/**
 * 自动加入。
 *
 * <p>失败不阻断页面 —— 加入是「兜底路径」之外的正常路径，但它失败时
 * 用户仍可以带令牌下单进场（见 {@link onOpenDoor}）。
 */
async function doJoin() {
  try {
    const resp = await joinByInvite(token)
    joinResult.value = resp.data
    if (resp.data?.joined) {
      toastSuccess('已加入包场')
    }
    // 重新拉一次名单，把自己显示出来
    const info = await getInviteInfo(token)
    participants.value = info.data?.participants || []
  } catch (err) {
    joinFailed.value = true
  }
}

/**
 * 现在就开门（带上邀请令牌）。
 *
 * <p>令牌在这里是<b>兜底路径</b>：正常流程下上面那次自动加入已经把他记进表里了，
 * 下单靠查表就能认出他。但万一那次加入失败了（网络抖动、或者用户没等页面加载完
 * 就点了按钮），带上令牌仍然进得去。
 */
async function onOpenDoor() {
  opening.value = true
  try {
    const resp = await createOrder(token)
    toastSuccess(resp.data?.message || '开门成功，请在门锁上输入密码')
    router.replace('/home')
  } catch (err) {
    if (
      isCode(err, ErrorCode.ORDER_ALREADY_ACTIVE) ||
      isCode(err, ErrorCode.ORDER_UNPAID_EXISTS)
    ) {
      toastInfo(errorMessage(err))
      router.replace('/home')
    } else {
      toastError(errorMessage(err, '开门失败，请稍后重试'))
    }
  } finally {
    opening.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <NavBar title="包场邀请" :back="false" />

    <LoadingMask :loading="loading" text="正在确认邀请…" />

    <template v-if="invite">
      <div class="card invite__hero">
        <span class="invite__badge">你被邀请参加包场</span>
        <p class="invite__time">{{ timeRange }}</p>
        <p v-if="joinText" class="invite__join" :class="{ 'invite__join--warn': joinFailed }">
          {{ joinText }}
        </p>
      </div>

      <div class="card">
        <div class="card-title">参与者（{{ participants.length }} 人）</div>
        <div class="participants">
          <div v-for="p in participants" :key="p.userId" class="participant">
            <img v-if="p.avatar" class="participant__avatar" :src="versionedUrl(p.avatar)" alt="" />
            <span v-else class="participant__avatar participant__avatar--fallback">
              {{ (p.nickname || '?').slice(0, 1) }}
            </span>
            <span class="participant__name">{{ p.nickname }}</span>
            <span class="tag" :class="{ 'tag-success': p.role === 'HOST' }">{{ p.roleText }}</span>
          </div>
        </div>
      </div>

      <button class="btn btn-primary invite__action" :disabled="opening" @click="onOpenDoor">
        {{ opening ? '处理中…' : '现在去开门' }}
      </button>
      <p class="invite__hint">
        包场时段内到店，用首页的「开门计时」即可进入
      </p>
    </template>

    <EmptyState
      v-else-if="!loading"
      icon="🔗"
      text="邀请链接无效或已失效"
      hint="包场时段的排他性一过，链接也就没有意义了"
    />
  </div>
</template>

<style scoped>
.invite__hero {
  margin-top: var(--sp-4);
  text-align: center;
}

.invite__badge {
  display: inline-block;
  padding: 3px var(--sp-3);
  border-radius: var(--r-pill);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-size: 12px;
}

.invite__time {
  margin: var(--sp-3) 0 var(--sp-2);
  font-size: 17px;
  font-weight: 600;
  color: var(--c-text);
}

.invite__join {
  font-size: 12px;
  color: var(--c-success);
}

.invite__join--warn {
  color: var(--c-warning);
}

.participants {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}

.participant {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  font-size: 13px;
}

.participant__avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  object-fit: cover;
  background: var(--c-icon-bg);
  flex-shrink: 0;
}

.participant__avatar--fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: var(--c-primary);
}

.participant__name {
  flex: 1;
  color: var(--c-text);
}

.invite__action {
  margin-top: var(--sp-4);
}

.invite__hint {
  margin-top: var(--sp-3);
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-text-muted);
  text-align: center;
}
</style>
