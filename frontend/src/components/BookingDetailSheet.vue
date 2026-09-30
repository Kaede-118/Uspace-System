<script setup>
/**
 * 包场详情弹层。
 *
 * <p>没有独立的「包场详情」接口（后端只有列表与邀请链接两个入口），
 * 所以详情由列表项的数据加上按需拉取的邀请链接组成。
 *
 * <p>三件事按状态分流：
 * <ul>
 *   <li><b>待付款</b> —— 给付款入口。包场在付款前<b>不产生排他性</b>，
 *       也拿不到邀请链接（令牌是付款那一刻才生成的）</li>
 *   <li><b>已付款</b> —— 给邀请链接与参与者名单</li>
 *   <li>已取消 / 已结束 —— 只读展示</li>
 * </ul>
 */
import { ref, computed, watch } from 'vue'
import { getInviteLink, getInviteInfo } from '@/api/booking'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatDateTime, formatTime, formatMoney } from '@/utils/format'
import { versionedUrl } from '@/utils/image'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** BookingVo */
  booking: { type: Object, default: null },
  /** 是否是包场人本人（被邀请者看不到邀请链接） */
  isHost: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible', 'pay'])

const inviteUrl = ref('')
const participants = ref([])
const loadingLink = ref(false)

const isPending = computed(() => props.booking?.status === 'PENDING_PAYMENT')
const isPaid = computed(() => props.booking?.status === 'PAID')

const timeRange = computed(() => {
  const b = props.booking
  if (!b?.startAt || !b?.endAt) return '—'
  const sameDay = String(b.startAt).slice(0, 10) === String(b.endAt).slice(0, 10)
  return sameDay
    ? `${formatDateTime(b.startAt)} – ${formatTime(b.endAt)}`
    : `${formatDateTime(b.startAt)} – ${formatDateTime(b.endAt)}`
})

/**
 * 拉取邀请链接与参与者名单。
 *
 * <p>⚠️ <b>链接优先用 {@code location.origin + path} 自己拼</b>，
 * 不用后端返回的 {@code url} —— 那个域名取自服务端配置
 * （{@code uspace.web.base-url}），开发期配的多半是 {@code localhost:8080}，
 * 粘到微信里打不开。后端那个字段留着给「将来部署到正式域名」用。
 */
async function loadInvite() {
  if (!props.booking || !props.isHost || !isPaid.value) return
  loadingLink.value = true
  try {
    const resp = await getInviteLink(props.booking.id)
    // hash 模式：完整链接形如 http://host/#/invite/xxx
    inviteUrl.value = `${location.origin}${location.pathname}#${resp.data.path}`

    // 顺手用令牌查一次参与者名单，让包场人看到「谁已经进来了」
    const info = await getInviteInfo(resp.data.inviteToken)
    participants.value = info.data?.participants || []
  } catch (err) {
    toastError(errorMessage(err, '邀请链接获取失败'))
  } finally {
    loadingLink.value = false
  }
}

/** 复制链接。剪贴板 API 在非 HTTPS 下不可用，所以留一条降级路径。 */
async function onCopy() {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(inviteUrl.value)
    } else {
      copyFallback(inviteUrl.value)
    }
    toastSuccess('链接已复制，发给朋友即可')
  } catch {
    toastError('复制失败，请长按链接手动复制')
  }
}

/**
 * 剪贴板降级方案：临时 textarea + execCommand。
 *
 * @param {string} text 要复制的文本
 */
function copyFallback(text) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  document.execCommand('copy')
  document.body.removeChild(ta)
}

function onClose() {
  emit('update:visible', false)
}

// 每次打开都重新拉一次：参与者可能在这一会儿又多了人
watch(
  () => props.visible,
  (v) => {
    if (v) {
      inviteUrl.value = ''
      participants.value = []
      loadInvite()
    }
  }
)
</script>

<template>
  <div v-if="visible && booking" class="sheet-mask" @click.self="onClose">
    <div class="sheet">
      <div class="sheet__head">
        <span class="sheet__no">{{ booking.bookingNo }}</span>
        <button class="sheet__x" aria-label="关闭" @click="onClose">×</button>
      </div>

      <div class="sheet__row">
        <span>包场时段</span>
        <span>{{ timeRange }}</span>
      </div>
      <div class="sheet__row">
        <span>包场费用</span>
        <span class="sheet__price">¥{{ formatMoney(booking.price) }}</span>
      </div>
      <div v-if="booking.paidAt" class="sheet__row">
        <span>付款时间</span>
        <span>{{ formatDateTime(booking.paidAt) }}</span>
      </div>
      <div v-if="booking.remark" class="sheet__row">
        <span>备注</span>
        <span>{{ booking.remark }}</span>
      </div>

      <!-- 待付款：给付款入口 -->
      <template v-if="isPending && isHost">
        <p class="sheet__note">
          包场付款后才产生排他性，也才能生成邀请链接
        </p>
        <button class="btn btn-primary" @click="emit('pay', booking)">去付款</button>
      </template>

      <!-- 已付款的包场人：邀请链接 + 参与者 -->
      <template v-else-if="isPaid && isHost">
        <div class="divider" />

        <p class="sheet__section">邀请朋友</p>
        <p v-if="loadingLink" class="sheet__note">正在获取链接…</p>
        <template v-else-if="inviteUrl">
          <div class="invite-link">{{ inviteUrl }}</div>
          <button class="btn btn-primary" @click="onCopy">复制邀请链接</button>
          <p class="sheet__note">
            朋友打开链接即自动加入，包场时段内可以凭它进场
          </p>
        </template>
        <p v-else class="sheet__note">链接获取失败，请稍后重试</p>

        <template v-if="participants.length">
          <p class="sheet__section">已加入（{{ participants.length }} 人）</p>
          <div class="participants">
            <div v-for="p in participants" :key="p.userId" class="participant">
              <img v-if="p.avatar" class="participant__avatar" :src="versionedUrl(p.avatar)" alt="" />
              <span v-else class="participant__avatar participant__avatar--fallback">
                {{ (p.nickname || '?').slice(0, 1) }}
              </span>
              <span class="participant__name">{{ p.nickname }}</span>
              <span class="tag">{{ p.roleText }}</span>
            </div>
          </div>
        </template>
      </template>

      <!-- 被邀请者：只说时段，不给链接 -->
      <p v-else-if="isPaid" class="sheet__note">
        你在这场包场的名单里，包场时段到店后即可正常开门
      </p>
      <p v-else class="sheet__note">这场包场已取消或已结束</p>
    </div>
  </div>
</template>

<style scoped>
.sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 450;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: rgba(43, 35, 64, 0.45);
}

.sheet {
  width: 100%;
  max-width: 480px;
  max-height: 86vh;
  overflow-y: auto;
  padding: var(--sp-5) var(--sp-5) calc(var(--sp-5) + var(--safe-bottom));
  border-radius: var(--r-card) var(--r-card) 0 0;
  background: #fff;
}

.sheet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--sp-3);
}

.sheet__no {
  font-size: 12px;
  color: var(--c-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.sheet__x {
  font-size: 22px;
  line-height: 1;
  color: var(--c-text-muted);
  padding: 0 var(--sp-1);
}

.sheet__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  font-size: 13px;
  color: var(--c-text-sub);
  padding: 5px 0;
}

.sheet__price {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-primary);
}

.sheet__section {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
  margin: var(--sp-3) 0 var(--sp-2);
}

.sheet__note {
  margin: var(--sp-3) 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-muted);
}

.invite-link {
  padding: var(--sp-3);
  border-radius: var(--r-btn);
  background: var(--c-card);
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-text-sub);
  word-break: break-all;
  margin-bottom: var(--sp-3);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.participants {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}

.participant {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  font-size: 13px;
}

.participant__avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  object-fit: cover;
  background: var(--c-icon-bg);
  flex-shrink: 0;
}

.participant__avatar--fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--c-primary);
}

.participant__name {
  flex: 1;
  color: var(--c-text);
}
</style>
