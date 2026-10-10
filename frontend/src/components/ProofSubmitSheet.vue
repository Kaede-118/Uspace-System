<script setup>
/**
 * 扫码转账的收银台：展示店内收款码 → 用户扫码付款 → 上传付款截图 → 提交。
 *
 * <p>与 {@code MockCashierSheet} 是一对：那个模拟「用户已经在微信 / 支付宝里
 * 付完了」，这个走真实的收款码路径。2026-09-30 起扫码转账是 12 月投产时
 * <b>唯一</b>的收款方式（三条线上通道都因资质门槛走不通），所以它是用户端
 * 最常走的那条付款路径。
 *
 * <p><b>提交之后会怎样取决于收款类型</b>：订单与商品「提交即交付」
 * （当场结清、库存当场扣），包场与月卡要等管理员复核。
 * 这一点由后端返回的 {@code delivered} 说明，本组件原样展示 ——
 * <b>不要按 {@code targetType} 自己判一遍</b>，判重了就会出现
 * 「页面说已结清、实际还在待复核」这种最难查的错。
 */
import { ref, computed, watch } from 'vue'
import { listPayQrs, submitProof } from '@/api/payment'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { copyText } from '@/utils/clipboard'
import ImageUploader from './ImageUploader.vue'

const props = defineProps({
  /** 是否显示 */
  visible: { type: Boolean, default: false },
  /** 收款目标类型：ORDER / BOOKING / MONTHLY_CARD / PRODUCT */
  targetType: { type: String, required: true },
  /** 目标 ID */
  targetId: { type: [Number, String], default: null },
  /** 应付金额（用于展示与「复制金额」） */
  amount: { type: [Number, String], default: null }
})

const emit = defineEmits(['update:visible', 'submitted'])

/** 店内启用中的收款码 */
const qrs = ref([])
/** 用户选中的那张码的 ID。多张时决定「这笔钱进了哪个账号」 */
const selectedQrId = ref(null)
/** 上传成功后的截图站内路径 */
const proofUrl = ref('')
/** 用户填写的交易流水号，可空 */
const paymentNo = ref('')
/**
 * 上传接口回传的识别结果，原样保存、提交时原样带回。
 *
 * <p>它们可能全是空的：没配识别引擎（后端 `uspace.ocr.provider=mock`）时恒为空，
 * 装了引擎也可能认不出用户传的这张图 —— 那时页面与从前没有任何区别，用户照常手填。
 */
const ocrPaymentNo = ref('')
const ocrAmount = ref(null)
const ocrText = ref('')
/**
 * 上一次**自动填进**输入框的那个单号。
 *
 * <p>用来区分输入框里的号是「机器填的」还是「用户自己填或改过的」：
 * 前者可以被新图的结果覆盖，后者不能动 —— 否则换一张图会把他刚填好的号悄悄改掉。
 */
const lastAutoNo = ref('')
const loadingQrs = ref(false)
const submitting = ref(false)
/**
 * 是否停在「确认提交」这一步（同层换视图，不叠弹层）。
 *
 * <p>2026-10-10 由用户定：提交前先让用户看一眼自己传的是哪张图、单号填没填。
 * 传错图的代价是走一轮「等管理员处理」再被驳回 —— 而这一步只花两秒。
 */
const confirming = ref(false)

/** 选中的收款码对象；一张都没配时为 null */
const selectedQr = computed(() => qrs.value.find((q) => q.id === selectedQrId.value) || null)

/** 能否提交：有截图就行，流水号可空（有些收款方式确实没有流水号） */
const canSubmit = computed(() => !!proofUrl.value && !submitting.value)

/** 识别出的金额（两位小数的文本），没识别出时为空串 */
const ocrAmountText = computed(() =>
  ocrAmount.value == null ? '' : Number(ocrAmount.value).toFixed(2)
)

/**
 * 识别出的金额与应付金额对不上。
 *
 * <p>比对用 {@code toFixed(2)} 之后的字符串，而不是直接比数字：
 * 后端传下来的是 JSON 数字（{@code 8.00} 到这边就是 {@code 8}），
 * 与页面上的 {@code 8} 比不出问题；但一旦两边精度写法不一致，
 * 直接比会让提示在两张其实相同的金额之间闪来闪去。
 */
const ocrAmountMismatch = computed(() =>
  ocrAmount.value != null
  && props.amount != null
  && ocrAmountText.value !== Number(props.amount).toFixed(2)
)

/**
 * 识别结果给用户的一句提示。
 *
 * <p>没有识别结果时返回空串 —— 页面上一片安静，与从前完全一样。
 * 那才是常态（默认配置下每次都是），不该为此在界面上常驻一行字。
 */
const ocrHint = computed(() => {
  if (!ocrPaymentNo.value && ocrAmount.value == null) {
    return ''
  }
  const parts = []
  if (ocrPaymentNo.value) {
    parts.push('已从截图识别出交易单号，请核对')
  }
  if (ocrAmountMismatch.value) {
    parts.push(`识别到的金额是 ¥${ocrAmountText.value}，与应付金额不一致`)
  }
  return parts.join('；')
})

/**
 * 拉取收款码。
 *
 * <p><b>一张都没有是一种真实的运营状态</b>（刚部署完还没配），
 * 所以要给一句明确的提示，而不是让用户对着空白发呆。
 */
async function loadQrs() {
  loadingQrs.value = true
  try {
    const resp = await listPayQrs()
    qrs.value = resp.data || []
    selectedQrId.value = qrs.value[0]?.id ?? null
  } catch (err) {
    toastError(errorMessage(err, '收款码加载失败'))
  } finally {
    loadingQrs.value = false
  }
}

/**
 * 上传成功。
 *
 * <p>{@code ImageUploader} 的 {@code uploaded} 事件原样透传接口返回的 data，
 * 本 kind（{@code proof}）的载荷是
 * {@code { proofUrl, ocrPaymentNo, ocrAmount, ocrText }} ——
 * 后三项是后端在上传时顺手识别出来的，可能全都是空的。
 *
 * @param {object} payload 接口返回的 data
 */
function onUploaded(payload) {
  proofUrl.value = payload?.proofUrl || ''

  /*
   * ⚠️ 三个识别字段【每次都无条件覆盖】，包括把它们置回空值 ——
   * 不要写成「有值才赋值」。
   *
   * 写成条件赋值的后果很具体：用户传了图 A（识别到金额 22.00），
   * 发现传错、换成图 B（这张识别不出），提交时 proofUrl 指向 B，
   * 而 ocrAmount 还留着 A 的 22.00。后台于是渲染出「识别到 ¥22.00」，
   * 而当前截图上写的是 8.00 —— 管理员看到一条与这张图毫无关系的数据，
   * 而它恰恰是「识别」这一栏唯一的价值所在。
   */
  const detectedNo = payload?.ocrPaymentNo || ''
  ocrPaymentNo.value = detectedNo
  ocrAmount.value = payload?.ocrAmount ?? null
  ocrText.value = payload?.ocrText || ''

  // 预填：输入框空着、或者里面装的正是上一次自动填进去的那个号（说明用户没动过），
  // 就用新结果覆盖。他手工改过就不碰 —— 那是他填的，不该被系统悄悄改掉
  if (!paymentNo.value.trim() || paymentNo.value === lastAutoNo.value) {
    paymentNo.value = detectedNo
  }
  lastAutoNo.value = detectedNo
}

/** 复制金额。用户扫完码要手输金额，能复制一下就少一处抄错。 */
async function onCopyAmount() {
  try {
    await copyText(String(props.amount ?? ''))
    toastSuccess('金额已复制，粘贴到付款页即可')
  } catch {
    toastError('复制失败，请手动输入金额')
  }
}

/** 提交凭证。 */
async function onSubmit() {
  if (!proofUrl.value) {
    toastError('请先上传付款截图')
    return
  }
  submitting.value = true
  try {
    const resp = await submitProof({
      targetType: props.targetType,
      targetId: props.targetId,
      proofUrl: proofUrl.value,
      // 空串归一成 null：后端把「没填」和「填了个空串」当同一回事，
      // 但传 null 更明确，也少一次 trim 的歧义
      paymentNo: paymentNo.value.trim() || null,
      payQrId: selectedQrId.value,
      // 识别结果原样带回。它们与 paymentNo 是两回事：用户可能改过单号，
      // 而「机器当初读出了什么」要单独留着，后台据此判断这个号是认出来的还是人填的
      ocrPaymentNo: ocrPaymentNo.value || null,
      ocrAmount: ocrAmount.value,
      ocrText: ocrText.value || null
    })
    toastSuccess(resp.data?.message || '付款凭证已提交')
    emit('submitted', resp.data)
    emit('update:visible', false)
  } catch (err) {
    // 驳回、目标状态不对等业务错误的中文说明都在这里 —— 直接展示给用户
    toastError(errorMessage(err, '提交失败'))
  } finally {
    submitting.value = false
  }
}

function onClose() {
  emit('update:visible', false)
}

// 每次打开都重来一遍：收款码可能刚被管理员换过，
// 而上一轮填的截图、流水号与识别结果都属于上一笔，不能留到这一笔上
watch(
  () => props.visible,
  (v) => {
    if (v) {
      proofUrl.value = ''
      paymentNo.value = ''
      ocrPaymentNo.value = ''
      ocrAmount.value = null
      ocrText.value = ''
      lastAutoNo.value = ''
      confirming.value = false
      loadQrs()
    }
  }
)
</script>

<template>
  <div v-if="visible" class="sheet-mask" @click.self="onClose">
    <div class="sheet">
      <!--
        第二步：提交前的确认（同层换视图，不叠弹层 —— 与后台包场页的
        「过去时间确认」同一个模式）。让用户把「传的是哪张图、单号填没填」
        再看一眼：传错的代价是等一轮复核再被驳回，而这一步只花两秒
      -->
      <template v-if="confirming">
        <p class="sheet__title">确认提交付款凭证</p>

        <div class="sheet__amount">
          <span class="sheet__money">¥{{ amount ?? '0.00' }}</span>
        </div>

        <img v-if="proofUrl" class="sheet__confirm-shot" :src="proofUrl" alt="付款截图" />
        <p v-if="paymentNo.trim()" class="sheet__confirm-line">
          交易单号：{{ paymentNo.trim() }}
        </p>
        <p v-else class="sheet__confirm-line sheet__confirm-line--muted">
          未填交易单号（选填）—— 填上便于对账，不填也能提交
        </p>

        <p class="sheet__note">
          请先确认截图清晰、金额与单号可辨认 —— 看不清的话管理员会驳回，届时可以重新上传。
        </p>
        <p class="sheet__note">
          提交后系统会先自动核对：识别到单号且金额相符的将当场结清；
          其余进入人工复核，管理员确认后生效。
        </p>

        <button class="btn btn-primary" :disabled="submitting" @click="onSubmit">
          {{ submitting ? '提交中…' : '确认提交' }}
        </button>
        <button
          class="btn btn-ghost sheet__cancel"
          :disabled="submitting"
          @click="confirming = false"
        >
          返回修改
        </button>
      </template>

      <template v-else>
      <p class="sheet__title">扫码付款</p>

      <div class="sheet__amount">
        <span class="sheet__money">¥{{ amount ?? '0.00' }}</span>
        <button class="sheet__copy" @click="onCopyAmount">复制金额</button>
      </div>

      <!-- 收款码 -->
      <div v-if="loadingQrs" class="sheet__empty">正在加载收款码…</div>
      <div v-else-if="!qrs.length" class="sheet__empty">
        店里还没有配置收款码，请联系管理员
      </div>
      <template v-else>
        <!-- 多于一张时才给切换：只有一张还让用户选，是凭空的负担 -->
        <div v-if="qrs.length > 1" class="sheet__tabs">
          <button
            v-for="qr in qrs"
            :key="qr.id"
            class="sheet__tab"
            :class="{ 'sheet__tab--on': qr.id === selectedQrId }"
            @click="selectedQrId = qr.id"
          >
            {{ qr.name }}
          </button>
        </div>
        <img v-if="selectedQr" class="sheet__qr" :src="selectedQr.imageUrl" :alt="selectedQr.name" />
        <p class="sheet__tip">
          长按识别二维码，或保存图片后用微信 / 支付宝扫一扫
        </p>
      </template>

      <!-- 付款凭证 -->
      <div class="sheet__form">
        <p class="sheet__label">付完后，上传付款截图</p>
        <ImageUploader
          kind="proof"
          :url="proofUrl"
          shape="rect"
          hint="请上传能看清交易单号与金额的那一屏"
          @uploaded="onUploaded"
        />
        <input
          v-model="paymentNo"
          class="sheet__input"
          type="text"
          inputmode="numeric"
          placeholder="交易单号（选填，便于对账）"
        />
        <!--
          识别结果提示。没有识别结果时整行不渲染 —— 那才是常态
          （默认配置下每次都是），不该为此在界面上常驻一行字
        -->
        <p
          v-if="ocrHint"
          class="sheet__ocr"
          :class="{ 'sheet__ocr--warn': ocrAmountMismatch }"
        >
          {{ ocrHint }}
        </p>
      </div>

      <p class="sheet__note">
        提交后管理员会核对到账情况。订单与商品提交即结清，包场与月卡需等确认。
      </p>

      <button class="btn btn-primary" :disabled="!canSubmit" @click="confirming = true">
        提交付款凭证
      </button>
      <button class="btn btn-ghost sheet__cancel" @click="onClose">稍后再说</button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 500;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: rgba(43, 35, 64, 0.45);
  overflow-y: auto;
}

.sheet {
  width: 100%;
  max-width: 480px;
  padding: var(--sp-5) var(--sp-5) calc(var(--sp-5) + var(--safe-bottom));
  border-radius: var(--r-card) var(--r-card) 0 0;
  background: var(--c-bg);
  text-align: center;
}

.sheet__title {
  font-size: 13px;
  color: var(--c-text-sub);
}

.sheet__amount {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: var(--sp-3);
  margin: var(--sp-2) 0 var(--sp-4);
}

.sheet__money {
  font-size: 30px;
  font-weight: 600;
  color: var(--c-primary);
  font-variant-numeric: tabular-nums;
}

.sheet__copy {
  font-size: 12px;
  color: var(--c-primary);
  padding: 2px var(--sp-2);
  border: 1px solid var(--c-primary);
  border-radius: var(--r-pill);
}

.sheet__empty {
  padding: var(--sp-5) var(--sp-4);
  border-radius: var(--r-btn);
  background: var(--c-card);
  font-size: 13px;
  color: var(--c-text-muted);
}

.sheet__tabs {
  display: flex;
  justify-content: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}

.sheet__tab {
  padding: 4px var(--sp-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r-pill);
  background: #fff;
  font-size: 12px;
  color: var(--c-text-sub);
}

.sheet__tab--on {
  border-color: var(--c-primary);
  color: var(--c-primary);
  background: var(--c-primary-pale);
}

/*
 * 收款码要够大 —— 这是唯一一个「尺寸直接影响功能」的图片：
 * 码太小手机扫不出来。白色内边距是刻意的（二维码四周的留白
 * 是它的一部分，紧贴边界的码识别率会下降）
 */
.sheet__qr {
  display: block;
  width: 100%;
  max-width: 240px;
  margin: 0 auto;
  padding: var(--sp-3);
  border-radius: var(--r-card);
  background: #fff;
  object-fit: contain;
}

.sheet__tip {
  margin-top: var(--sp-2);
  font-size: 12px;
  color: var(--c-text-muted);
}

.sheet__form {
  margin-top: var(--sp-4);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--c-border);
  text-align: left;
}

.sheet__label {
  margin-bottom: var(--sp-2);
  font-size: 13px;
  color: var(--c-text);
}

.sheet__input {
  width: 100%;
  margin-top: var(--sp-3);
  padding: var(--sp-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r-btn);
  font-size: 14px;
  color: var(--c-text);
  background: #fff;
}

.sheet__ocr {
  margin-top: var(--sp-2);
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-muted);
}

/* 确认页的截图缩略 —— 让人再看一眼传的是哪张（contain 而不是 cover：看全图） */
.sheet__confirm-shot {
  display: block;
  width: 100%;
  max-height: 220px;
  margin-top: var(--sp-3);
  object-fit: contain;
  border-radius: var(--r-btn);
  border: 1px solid var(--c-border);
  background: var(--c-card);
}

.sheet__confirm-line {
  margin-top: var(--sp-2);
  font-size: 13px;
  color: var(--c-text);
  word-break: break-all;
}

.sheet__confirm-line--muted {
  color: var(--c-text-muted);
}

/*
 * 识别金额与应付金额对不上时换成警告色。
 * 这是用户最该看一眼的一种情形：他可能付错了金额，而提交之后
 * 要等管理员复核才能发现
 */
.sheet__ocr--warn {
  color: var(--c-warning);
}

.sheet__note {
  margin: var(--sp-3) 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--c-text-muted);
  text-align: left;
}

.sheet__cancel {
  margin-top: var(--sp-2);
}
</style>
