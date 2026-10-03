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
 *
 * <p>⚠️ <b>本页刻意不提供「开门」按钮</b>（曾经有过，2026-09-30 去掉）：
 * 点开邀请链接的人多半<b>不在店里</b> —— 他可能是在群里翻到链接、随手点开看看。
 * 而「开门」一旦点下去就是创建订单 + 下发密码 + <b>开始计费</b>，
 * 包场又往往是几天之后的事，误触的代价是真金白银。
 * 到店之后的入口只有一个：首页的「开门计时」（本页底部也这么写着）。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getInviteInfo, joinByInvite } from '@/api/booking'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
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

/** 是否正在重试。首次自动加入不走这里，只有用户点了按钮才置位。 */
const retrying = ref(false)

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
  if (joinFailed.value) return '自动加入未成功'
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
 * <p>失败不阻断页面（信息照常展示），但要提醒用户刷新重试：
 * 包场时段里没进名单的人会被当成散客挡在门外，而那个时候他多半已经到店了。
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
 * 重试加入。
 *
 * <p>首次加入是页面加载时自动做的（用户无感），失败时只留一句话 ——
 * 而加入失败的人到店后会被门槛挡在外面（包场时段只放参与者进来），
 * 所以他需要一个显式的重试入口，不能只让他去刷新页面。
 *
 * <p>失败态先清掉：不清的话重试期间那句警告还挂着，看着像「又失败了一次」。
 */
async function onRetry() {
  retrying.value = true
  joinFailed.value = false
  try {
    await doJoin()
  } finally {
    retrying.value = false
  }
}

/**
 * 回首页。
 *
 * <p>到店之后的入口在首页的「开门计时」，那里下单带的是账号身份 ——
 * 而上面那次自动加入已经把他记进参与者表了，所以包场时段照样进得去。
 */
function goHome() {
  router.replace('/home')
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
        <!--
          加入失败时的重试入口。少了它，那个人在包场时段会被门槛挡在外面，
          而他自己没有任何补救手段（页面只会告诉他「刷新试试」）。
        -->
        <button
          v-if="joinFailed"
          class="btn btn-primary invite__retry"
          :disabled="retrying"
          @click="onRetry"
        >
          {{ retrying ? '重试中…' : '重试' }}
        </button>
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

      <!--
        ⚠️ 这里是「返回首页」而不是「去开门」—— 点开邀请链接的人未必在店里，
        而开门会立刻开始计费。到店的入口在首页，见文件头那段说明。
      -->
      <button class="btn btn-primary invite__action" @click="goHome">返回首页</button>
      <p class="invite__hint">
        包场时段内到店，用首页的「开门计时」即可进入
      </p>
    </template>

    <EmptyState
      v-else-if="!loading"
      icon="🔗"
      text="邀请链接无效或已失效"
      hint="包场时段一过、或包场被撤销，链接就失效了"
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

/* 重试按钮：只在加入失败时出现，所以给它一点上间距，别贴在提示语上 */
.invite__retry {
  margin-top: var(--sp-3);
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
