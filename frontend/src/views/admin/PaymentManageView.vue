<script setup>
/**
 * 收款管理（运营后台）：三个 tab —— <b>待复核</b>、<b>收款码</b>与<b>对账</b>。
 *
 * <p>12 月投产时扫码转账是本店<b>唯一</b>的收款方式 —— 三条线上支付通道
 * 都因资质门槛走不通。所以这一页就是收银台的对账台，三个 tab 分别对应
 * 收银台工作的三个层次：
 * <ul>
 *   <li><b>待复核</b> —— 逐条看用户传的付款截图（单笔）</li>
 *   <li><b>对账</b> —— 上传收款账号导出的账单，与系统里的凭证勾稽（一整天）</li>
 *   <li><b>收款码</b> —— 配好就不动的设置项</li>
 * </ul>
 * <b>默认落在「待复核」</b>：那是需要动手做的事，管理员每次进来多半是为了处理待办。
 *
 * <p>⚠️ <b>收款码图片必须是不裁剪、不缩放、PNG 的</b>：二维码转成 JPEG 后
 * 透明底会变黑，四周一圈黑边足以让扫码失败；缩放会让模块糊在一起，
 * 摄像头就认不出来了。这三条由 {@code utils/image.js} 的 {@code payqr}
 * 处理器保证 —— 本页只要传 {@code kind="payqr"}，<b>不要换成别的 kind</b>。
 *
 * <p><b>停用的码仍然列在这里</b>：临时停用、过阵子再开是最常见的用法，
 * 不列出来的话管理员停用之后就再也找不回它了。收银台上只看得到启用的。
 *
 * <p><b>对账 tab 内部再分「批次列表 ↔ 批次详情」两个视图</b>（用
 * {@code reconcileView} 切换，不走路由）：对账的用法是「上传 → 看这一次的差异」，
 * 列表与详情是一件事的两步，拆成两个路由反而要处理「刷新后落在详情页、
 * 而那个批次已不存在」这类状态。
 */
import { computed, ref, onMounted } from 'vue'
import {
  listAdminPayQrs,
  createPayQr,
  updatePayQr,
  deletePayQr,
  listAdminProofs,
  confirmProof,
  rejectProof,
  listReconcileBatches,
  uploadReconcileBill,
  getReconcileBatch,
  listReconcileDiffs,
  handleReconcileDiff,
  downloadReconcileBill
} from '@/api/admin'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { PAY_QR_CHANNELS, RECONCILE_DIFF_TYPES, reconcileDiffTypeCls } from '@/utils/labels'
import { versionedUrl } from '@/utils/image'
import { formatDateTime, formatMoney } from '@/utils/format'
import AdminSheet from '@/components/AdminSheet.vue'
import AdminPager from '@/components/AdminPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import ImageUploader from '@/components/ImageUploader.vue'
import LoadingMask from '@/components/LoadingMask.vue'

/** 当前 tab：proofs 待复核 / qrs 收款码 / reconcile 对账 */
const tab = ref('proofs')

const qrs = ref([])
const loading = ref(true)

/* ---------------- 表单弹层 ---------------- */

const formVisible = ref(false)
/** 正在编辑的那张；为 null 表示「新增」 */
const editing = ref(null)
const form = ref({ channel: 'WXPAY', name: '', imageUrl: '', enabled: 1, sort: 0 })
const formError = ref('')
const submitting = ref(false)
/**
 * 图片上传中。
 *
 * <p>父组件据此禁用提交按钮 —— 不等上传完成就点保存的话，
 * {@code form.imageUrl} 还是旧值（新增时是空串），要传的图会静默丢掉。
 */
const imageUploading = ref(false)

/* ---------------- 删除确认 ---------------- */

const removing = ref(null)
const removeError = ref('')

/** 拉取收款码列表（含停用的）。 */
async function load() {
  loading.value = true
  try {
    const resp = await listAdminPayQrs()
    qrs.value = resp.data || []
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    loading.value = false
  }
}

/** 打开「新增」表单。默认启用 —— 新配一张码就是为了用。 */
function openCreate() {
  editing.value = null
  form.value = { channel: 'WXPAY', name: '', imageUrl: '', enabled: 1, sort: 0 }
  formError.value = ''
  formVisible.value = true
}

/**
 * 打开「编辑」表单。
 *
 * @param {object} qr 收款码（列表里的那一行）
 */
function openEdit(qr) {
  editing.value = qr
  form.value = {
    channel: qr.channel,
    name: qr.name,
    imageUrl: qr.imageUrl,
    enabled: qr.enabled,
    sort: qr.sort
  }
  formError.value = ''
  formVisible.value = true
}

/**
 * 图片上传成功。
 *
 * <p>接口的 payload 逐 kind 不同，收款码返回的是 {@code {imageUrl}}
 *（商品封面是 {@code {cover}}）—— 所以这里显式取字段而不是整个塞进表单。
 *
 * @param {object} data 接口返回的 data
 */
function onUploaded(data) {
  form.value.imageUrl = data?.imageUrl || ''
}

/** 保存（新增或修改）。 */
async function submit() {
  if (!form.value.imageUrl) {
    formError.value = '请先上传收款码图片'
    return
  }
  submitting.value = true
  formError.value = ''
  try {
    const payload = {
      channel: form.value.channel,
      name: form.value.name,
      imageUrl: form.value.imageUrl,
      // ⚠️ enabled 必须传：后端标了 @NotNull，漏传会返回 400。
      // 这是刻意的 —— 默认成「启用」会让一张本该停用的码继续收款，
      // 默认成「停用」又会让收银台静默少一张码，两种默认都会出事
      enabled: form.value.enabled,
      sort: form.value.sort
    }
    if (editing.value) {
      await updatePayQr(editing.value.id, payload)
      toastSuccess('已保存')
    } else {
      await createPayQr(payload)
      toastSuccess('已新增')
    }
    formVisible.value = false
    await load()
  } catch (err) {
    // 错误必须走 AdminSheet 的 error prop，不能用 toast ——
    // ToastHost 的层级低于弹层遮罩，toast 会被盖住
    formError.value = errorMessage(err, '保存失败')
  } finally {
    submitting.value = false
  }
}

/** 执行删除（后端是逻辑删除，行还在）。 */
async function confirmRemove() {
  submitting.value = true
  removeError.value = ''
  try {
    await deletePayQr(removing.value.id)
    toastSuccess('已删除')
    removing.value = null
    await load()
  } catch (err) {
    removeError.value = errorMessage(err, '删除失败')
  } finally {
    submitting.value = false
  }
}

/* ==================== 待复核（付款凭证） ==================== */

/** 每页条数。收款码那一列不走分页，这个常量只服务凭证列表 */
const PROOF_PAGE_SIZE = 10

const proofs = ref([])
const proofsLoading = ref(false)
const proofTotal = ref(0)
const proofPage = ref(1)

/**
 * 状态筛选。默认只看<b>待复核</b>。
 *
 * <p>默认给「全部」的话，处理过的凭据会把待办挤到后面几页去 ——
 * 而这一页的用途就是「把待办的清掉」。
 */
const proofStatus = ref('SUBMITTED')

/** 正在复核的那条（禁用按钮，防连点） */
const reviewingId = ref(null)

/** 放大查看的截图地址。为空表示不显示预览 */
const previewUrl = ref('')

/** 驳回弹层。{@code rejecting} 是被驳回的那条凭证 */
const rejecting = ref(null)
const rejectReason = ref('')
const rejectError = ref('')
const rejectSubmitting = ref(false)

/** 拉取凭证列表。 */
async function loadProofs() {
  proofsLoading.value = true
  try {
    const resp = await listAdminProofs({
      page: proofPage.value,
      size: PROOF_PAGE_SIZE,
      verifyStatus: proofStatus.value
    })
    proofs.value = resp.data?.records || []
    proofTotal.value = resp.data?.total || 0
  } catch (err) {
    toastError(errorMessage(err, '凭证加载失败'))
  } finally {
    proofsLoading.value = false
  }
}

/** 换筛选条件要回到第一页 —— 停在第 3 页看一组只剩 1 条的结果是空的 */
function onProofStatusChange() {
  proofPage.value = 1
  loadProofs()
}

/**
 * 确认到账。
 *
 * <p>对订单与商品这是纯登记（钱在用户提交那刻就算收到了），
 * 对包场与月卡则是<b>交付本身</b> —— 邀请令牌与月卡都产生在这一步。
 */
async function onConfirmProof(proof) {
  reviewingId.value = proof.id
  try {
    await confirmProof(proof.id)
    toastSuccess('已确认到账')
    await loadProofs()
  } catch (err) {
    toastError(errorMessage(err, '确认失败'))
  } finally {
    reviewingId.value = null
  }
}

/**
 * 打开驳回弹层。
 *
 * @param {object} proof 凭证
 */
function openReject(proof) {
  rejecting.value = proof
  rejectReason.value = ''
  rejectError.value = ''
}

/** 提交驳回。 */
async function submitReject() {
  const reason = rejectReason.value.trim()
  if (!reason) {
    // ⚠️ 错误走 AdminSheet 的 error prop，不用 toast ——
    // ToastHost 的层级低于弹层遮罩，toast 会被盖住
    rejectError.value = '请填写未通过的原因'
    return
  }
  rejectSubmitting.value = true
  rejectError.value = ''
  try {
    await rejectProof(rejecting.value.id, reason)
    toastSuccess('已标记为未通过')
    rejecting.value = null
    await loadProofs()
  } catch (err) {
    rejectError.value = errorMessage(err, '操作失败')
  } finally {
    rejectSubmitting.value = false
  }
}

/** 切 tab。列表都按需加载 —— 打开页面时默认在「待复核」，那一个先拉一次。 */
function onTabChange(next) {
  tab.value = next
  if (next === 'proofs' && !proofs.value.length) {
    loadProofs()
  }
  // 对账同理懒加载：管理员进来多半是为了处理待复核，
  // 一打开就把批次也拉一遍是白花的请求
  if (next === 'reconcile' && !batches.value.length) {
    loadBatches()
  }
}

/**
 * 复核状态对应的标签样式。
 *
 * <p>状态中文名由后端给（{@code verifyStatusLabel}），这里只决定颜色 ——
 * 与 {@code utils/labels.js} 那条「配色没有第二个来源」同源，
 * 但凭证状态的中文只有后端一处定义就够了，不必再进标签表。
 *
 * @param {string} status SUBMITTED / CONFIRMED / REJECTED
 * @returns {string} 标签的 class
 */
function statusClassOf(status) {
  if (status === 'CONFIRMED') return 'tag tag-success'
  if (status === 'REJECTED') return 'tag tag-danger'
  return 'tag tag-warning'
}

/* ==================== 对账（支付改造 Phase 5） ==================== */

/** 批次每页条数 */
const BATCH_PAGE_SIZE = 10

/** 差异每页条数。比批次多 —— 一个批次几十条差异是常态 */
const DIFF_PAGE_SIZE = 20

/** 对账 tab 内部的视图：list 批次列表 / detail 批次详情 */
const reconcileView = ref('list')

const batches = ref([])
const batchesLoading = ref(false)
const batchTotal = ref(0)
const batchPage = ref(1)

/** 上传中（从选完文件到接口返回）。据此禁用上传按钮，防连点传两份 */
const uploading = ref(false)

/** 正在看的那个批次（详情视图的头部信息） */
const currentBatch = ref(null)

/**
 * 总账：账单侧<b>未被任何有效凭证认领</b>的笔数。
 *
 * <p><b>它回答的是月底对账的第一个问题 —— 「钱少没少」</b>，而不是「这 30 笔
 * 分别是谁付的」。对平了就可以直接走人，不必翻下面的逐笔差异：逐笔差异永远有杂音
 *（OCR 读错、顾客跨月才付款），拿它们判断账平不平，等于用人力去消化机器的误差。
 *
 * <p>口径由后端定死（见 {@code ReconcileMatcher}）：未被认领 = 系统里没有一条
 * 有效凭证指着这笔钱。金额不符与重复认领<b>不算</b> —— 那两类钱确实到了，
 * 只进差异列表。
 */
const unclaimedCount = computed(() => currentBatch.value?.billUnclaimedCount ?? null)

/** 总账：账单侧未被认领的金额（元），口径见上 */
const unclaimedAmount = computed(() => currentBatch.value?.billUnclaimedAmount ?? null)

/**
 * 这个批次有没有总账数据。
 *
 * <p>⚠️ <b>刻意用 {@code != null} 判断，而不是「取不到就当 0」</b>：
 * 本次升级之前建的批次没有这两列，当 0 处理会让它们显示「账已对平」——
 * 那是一个<b>没有依据的结论</b>，比不显示这一块糟得多。
 */
const hasBalance = computed(() => unclaimedCount.value != null)

/** 已认领笔数 = 账单笔数 − 未被认领的（账单笔数里包含「之前批次已对过」的那些） */
const claimedCount = computed(() =>
  hasBalance.value ? (currentBatch.value?.billCount ?? 0) - unclaimedCount.value : 0
)

/**
 * 已认领金额（元）。
 *
 * <p>两端都显式转数字再相减：后端返回的金额是字符串形式的 {@code DECIMAL}，
 * 而 {@code undefined} 参与减法会得到 {@code NaN}，页面上显示成「¥0.00」——
 * 一个看起来正常、实际什么都没有的数。
 */
const claimedAmount = computed(() =>
  hasBalance.value
    ? Number(currentBatch.value?.billAmount ?? 0) - Number(unclaimedAmount.value)
    : 0
)

/** 账对不对得平。结论只由笔数决定，金额陈列在旁边供核对 */
const isBalanced = computed(() => hasBalance.value && unclaimedCount.value === 0)

const diffs = ref([])
const diffsLoading = ref(false)
const diffTotal = ref(0)
const diffPage = ref(1)

/** 类型筛选，空串表示不过滤 */
const diffType = ref('')

/**
 * 处理状态筛选。默认只看<b>待处理</b>。
 *
 * <p>与凭证列表同一个理由：默认给「全部」的话，处理过的条目会把待办
 * 挤到后面几页去，而这一页的用途就是「把待办清掉」。
 */
const diffHandled = ref(0)

/** 标记已处理弹层。{@code handling} 是被标记的那条差异 */
const handling = ref(null)
const handleNote = ref('')
const handleError = ref('')
const handleSubmitting = ref(false)

/** 拉取批次列表。 */
async function loadBatches() {
  batchesLoading.value = true
  try {
    const resp = await listReconcileBatches({ page: batchPage.value, size: BATCH_PAGE_SIZE })
    batches.value = resp.data?.records || []
    batchTotal.value = resp.data?.total || 0
  } catch (err) {
    toastError(errorMessage(err, '批次加载失败'))
  } finally {
    batchesLoading.value = false
  }
}

/**
 * 上传账单并执行对账。
 *
 * <p><b>选完立刻清空 input</b>：不清的话再选同一个文件不会触发 change 事件，
 * 而「重传一次刚才那个文件」恰恰是最常见的重试动作（上次传错了区间、
 * 或者想再对一遍确认）。
 *
 * <p>上传成功后<b>直接跳进详情</b>：管理员传完要看的就是这一次的差异，
 * 让他再点一下列表行是多余的一步。
 *
 * @param {Event} event input 的 change 事件
 */
async function onUploadBill(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) {
    return
  }

  uploading.value = true
  try {
    const resp = await uploadReconcileBill(file)
    const vo = resp.data
    toastSuccess(`对账完成：匹配 ${vo?.matchedCount ?? 0} 笔，发现 ${vo?.diffCount ?? 0} 条差异`)
    await loadBatches()
    if (vo?.id) {
      await openBatch(vo)
    }
  } catch (err) {
    // 解析失败的提示由后端给（它知道是编码问题、表头问题还是空账单，
    // 而这三件事管理员要做的事完全不同），这里只负责显示出来
    toastError(errorMessage(err, '对账失败'))
  } finally {
    uploading.value = false
  }
}

/**
 * 打开某个批次的详情。
 *
 * @param {object} batch 列表里那一行（或上传接口返回的新批次）
 */
async function openBatch(batch) {
  try {
    const resp = await getReconcileBatch(batch.id)
    currentBatch.value = resp.data
    reconcileView.value = 'detail'
    diffPage.value = 1
    diffType.value = ''
    diffHandled.value = 0
    await loadDiffs()
  } catch (err) {
    toastError(errorMessage(err, '批次加载失败'))
  }
}

/** 从详情返回列表。列表数据可能已经被刚才的操作改过，重新拉一次。 */
function backToList() {
  reconcileView.value = 'list'
  currentBatch.value = null
  loadBatches()
}

/** 拉取当前批次的差异明细。 */
async function loadDiffs() {
  if (!currentBatch.value) {
    return
  }
  diffsLoading.value = true
  try {
    const resp = await listReconcileDiffs(currentBatch.value.id, {
      page: diffPage.value,
      size: DIFF_PAGE_SIZE,
      diffType: diffType.value,
      handled: diffHandled.value
    })
    diffs.value = resp.data?.records || []
    diffTotal.value = resp.data?.total || 0
  } catch (err) {
    toastError(errorMessage(err, '差异加载失败'))
  } finally {
    diffsLoading.value = false
  }
}

/** 换筛选条件要回到第一页 —— 停在第 3 页看一组只剩 1 条的结果是空的。 */
function onDiffFilterChange() {
  diffPage.value = 1
  loadDiffs()
}

/**
 * 重新拉一次批次头部。
 *
 * <p>标记差异之后要刷新「还有几条没处理」与各类型的角标。
 * <b>刷新失败不打断主流程</b>：那只是几个数字，而管理员刚才做的事已经完成了，
 * 为它弹一个错误提示只会让人以为操作失败了。
 */
async function refreshBatchHead() {
  if (!currentBatch.value) {
    return
  }
  try {
    const resp = await getReconcileBatch(currentBatch.value.id)
    currentBatch.value = resp.data
  } catch (err) {
    // 静默：见方法注释
  }
}

/**
 * 打开「标记已处理」弹层。
 *
 * <p>动作走弹层是一道防误点的闸门 —— 因为<b>不提供「取消已处理」</b>，
 * 点错了没法撤回（标错的条目仍然筛得出来、看得见，这是刻意的取舍）。
 *
 * @param {object} diff 差异
 */
function openHandle(diff) {
  handling.value = diff
  handleNote.value = ''
  handleError.value = ''
}

/** 提交「标记已处理」。备注选填 —— 它是管理员给自己的备忘。 */
async function submitHandle() {
  handleSubmitting.value = true
  handleError.value = ''
  try {
    await handleReconcileDiff(handling.value.id, handleNote.value.trim() || null)
    toastSuccess('已标记为处理完成')
    handling.value = null
    await loadDiffs()
    await refreshBatchHead()
  } catch (err) {
    // 错误走 AdminSheet 的 error prop，不能用 toast —— 会被遮罩盖住
    handleError.value = errorMessage(err, '操作失败')
  } finally {
    handleSubmitting.value = false
  }
}

/**
 * 下载账单原文件。
 *
 * @param {object} batch 批次
 */
async function onDownload(batch) {
  try {
    await downloadReconcileBill(batch.id, batch.fileName)
  } catch (err) {
    toastError(errorMessage(err, '下载失败'))
  }
}

onMounted(() => {
  load()
  loadProofs()
})
</script>

<template>
  <div class="page">
    <div class="card">
      <div class="card-title">
        收款
        <span v-if="tab === 'qrs'" class="card-sub">{{ qrs.length }} 张</span>
        <button v-if="tab === 'qrs'" class="btn btn-primary qr__new" @click="openCreate">
          新增收款码
        </button>
      </div>

      <div class="pay__tabs">
        <button
          class="pay__tab"
          :class="{ 'pay__tab--on': tab === 'proofs' }"
          @click="onTabChange('proofs')"
        >
          待复核
        </button>
        <button
          class="pay__tab"
          :class="{ 'pay__tab--on': tab === 'qrs' }"
          @click="onTabChange('qrs')"
        >
          收款码
        </button>
        <button
          class="pay__tab"
          :class="{ 'pay__tab--on': tab === 'reconcile' }"
          @click="onTabChange('reconcile')"
        >
          对账
        </button>
      </div>

      <!-- ============ tab 一：待复核 ============ -->
      <template v-if="tab === 'proofs'">
        <div class="pay__filters">
          <select
            v-model="proofStatus"
            class="field-input pay__select"
            @change="onProofStatusChange"
          >
            <option value="SUBMITTED">待复核</option>
            <option value="">全部</option>
            <option value="CONFIRMED">已核对</option>
            <option value="REJECTED">未通过</option>
          </select>
          <button class="btn btn-ghost pay__refresh" @click="loadProofs">刷新</button>
        </div>

        <LoadingMask :loading="proofsLoading" />

        <div v-if="proofs.length" class="proof__list">
          <div
            v-for="p in proofs"
            :key="p.id"
            class="proof__item"
            :class="{ 'proof__item--risk': p.risk }"
          >
            <!-- 截图：点开看大图 —— 管理员在这一页上的核心动作就是「看清这张图」 -->
            <img
              class="proof__shot"
              :src="versionedUrl(p.proofUrl)"
              :alt="'付款截图 ' + p.orderNo"
              @click="previewUrl = p.proofUrl"
            />

            <div class="proof__main">
              <div class="proof__line">
                <span class="proof__no">{{ p.orderNo }}</span>
                <span class="proof__badges">
                  <span :class="statusClassOf(p.verifyStatus)">{{ p.verifyStatusLabel }}</span>
                  <!--
                    「对账确认」与「到账」是两件事（见 AdminProofVo#reconcileBatchId）：
                    到账 = 管理员/机器认下了这张截图；已对账 = 它与收款账单勾稽上了。
                    管理员先认了、账单隔月才导出，两者本来就会错开 —— 分开显示
                  -->
                  <span v-if="p.reconcileBatchId" class="proof__reconciled">已对账</span>
                </span>
              </div>

              <div class="proof__meta">
                {{ p.targetTypeLabel }} · {{ p.userNickname || '用户 ' + p.userId }}
              </div>

              <div class="proof__amount">
                <span class="proof__money">¥{{ formatMoney(p.amount) }}</span>
                <!-- OCR 识别出的金额与提交额不符时值得多看一眼（Phase 4 起有值） -->
                <span v-if="p.ocrAmount && p.ocrAmount !== p.amount" class="proof__warn">
                  识别到 ¥{{ formatMoney(p.ocrAmount) }}
                </span>
              </div>

              <div class="proof__meta">
                <span v-if="p.paymentNo">流水号 {{ p.paymentNo }}</span>
                <span v-else class="proof__warn">未填流水号</span>
                <span v-if="p.payQrName"> · {{ p.payQrName }}</span>
              </div>

              <!--
                机器读出的单号 vs 用户确认的单号。两者不一致时标出来 ——
                要么用户改过（他知道原号不对），要么机器读错了；
                无论哪一种，都正是管理员在这一行上要看的那一眼。

                ⚠️ 用户【没填】时不标「不一致」（2026-10-10 修）：那是拿空白去比，
                必然误报。群内传图那条路没有填单号的表单，走这条路的凭证
                paymentNo 恒为空 —— 机器替他认出来是件好事，不是异常
              -->
              <div v-if="p.ocrPaymentNo" class="proof__meta">
                <span>识别单号 {{ p.ocrPaymentNo }}</span>
                <span v-if="p.paymentNo && p.ocrPaymentNo !== p.paymentNo" class="proof__warn">（与提交的不一致）</span>
              </div>

              <!--
                识别原文：默认收起。它是「这张截图里到底有些什么字」的完整记录，
                只在核对有疑问时才需要翻出来 —— 比如金额那栏报了不一致，
                管理员想看看是不是机器把别的数字认成了金额
              -->
              <details v-if="p.ocrText" class="proof__ocr">
                <summary>识别原文</summary>
                <pre class="proof__ocr-text">{{ p.ocrText }}</pre>
              </details>

              <div class="proof__meta">{{ formatDateTime(p.createdAt) }}</div>

              <!--
                复核来源：机器自动通过（confirmedBy 为空）与管理员核对分开说 ——
                前者是「识别到单号、金额相符、无重复引用」时系统当场放的
                （三道闸见 PaymentProofService），看到这个标记就知道
                「这张图没有被人工看过」，觉得可疑可以补一次驳回
              -->
              <div v-if="p.verifyStatus === 'CONFIRMED' && p.confirmedAt" class="proof__meta">
                <span :class="{ 'proof__warn': p.confirmedBy == null }">
                  {{ p.confirmedBy == null ? '机器自动通过（未人工核对）' : '管理员核对' }}
                </span>
                · {{ formatDateTime(p.confirmedAt) }}
              </div>

              <!--
                「提交即结清」的提示：这两类（订单 / 商品）在用户提交那一刻
                就已经落账了，驳回不会自动回退 —— 必须让管理员看见，
                否则他点完「未通过」会以为事情结束了
              -->
              <p v-if="p.delivered && p.verifyStatus === 'SUBMITTED'" class="proof__flag">
                提交即结清：若驳回，需人工回退（钱已入账、库存已扣）
              </p>
              <p v-if="p.risk" class="proof__flag proof__flag--risk">
                ⚠️ 同一流水号被多笔凭证引用，请核对是否重复提交
              </p>
              <p v-if="p.rejectReason" class="proof__flag">未通过原因：{{ p.rejectReason }}</p>
            </div>

            <!--
              待复核的可以「确认 / 未通过」；机器自动通过（CONFIRMED 且复核人为空）
              只剩「未通过」—— 它已经确认过了，但那次没有人看过图，
              管理员补一次驳回是合法动作（后端驳回守卫同样认这个区分）。
              管理员亲手核对过的不可覆盖：复核是唯一的资金结论
            -->
            <div
              v-if="p.verifyStatus === 'SUBMITTED'
                || (p.verifyStatus === 'CONFIRMED' && p.confirmedBy == null)"
              class="proof__ops"
            >
              <button
                v-if="p.verifyStatus === 'SUBMITTED'"
                class="proof__op"
                :disabled="reviewingId === p.id"
                @click="onConfirmProof(p)"
              >
                确认
              </button>
              <button
                class="proof__op proof__op--danger"
                :disabled="reviewingId === p.id"
                @click="openReject(p)"
              >
                未通过
              </button>
            </div>
          </div>
        </div>

        <EmptyState
          v-else-if="!proofsLoading"
          icon="🧾"
          :text="proofStatus ? '没有待处理的付款凭证' : '还没有任何付款凭证'"
          hint="顾客扫码付款后上传的截图会出现在这里"
        />

        <AdminPager
          :page="proofPage"
          :size="PROOF_PAGE_SIZE"
          :total="proofTotal"
          @update:page="(p) => { proofPage = p; loadProofs() }"
        />
      </template>

      <!-- ============ tab 二：收款码 ============ -->
      <template v-else>
      <LoadingMask :loading="loading" />

      <div v-if="qrs.length" class="qr__list">
        <div
          v-for="q in qrs"
          :key="q.id"
          class="qr__item"
          :class="{ 'qr__item--off': !q.enabled }"
        >
          <img
            v-if="q.imageUrl"
            class="qr__thumb"
            :src="versionedUrl(q.imageUrl)"
            :alt="q.name"
          />
          <div v-else class="qr__thumb qr__thumb--empty">无图</div>

          <div class="qr__main">
            <div class="qr__line">
              <span class="qr__name">{{ q.name }}</span>
              <!-- 渠道中文用后端给的 channelLabel，本页只提供下拉选项 -->
              <span :class="q.enabled ? 'tag tag-success' : 'tag'">
                {{ q.enabled ? '启用中' : '已停用' }}
              </span>
            </div>
            <div class="qr__meta">
              <span>{{ q.channelLabel }}</span>
              <span>· 排序 {{ q.sort }}</span>
            </div>
          </div>

          <div class="qr__ops">
            <button class="qr__op" @click="openEdit(q)">编辑</button>
            <button class="qr__op qr__op--danger" @click="removing = q">删除</button>
          </div>
        </div>
      </div>

      <EmptyState
        v-else-if="!loading"
        icon="💳"
        text="还没有配置收款码"
        hint="点右上角「新增收款码」，顾客才能扫码付款"
      />
      </template>
    </div>

    <p v-if="tab === 'qrs'" class="qr__hint">
      停用的收款码不会出现在收银台上，但这里仍然看得到 —— 临时停用、过阵子再开是常见用法。
      店里一张启用的都没有时，顾客就付不了款。
    </p>

    <!-- ============ tab 三：对账 ============ -->
    <template v-if="tab === 'reconcile'">
      <!--
        上传区。列表与详情两个视图里都显示 —— 无论在哪儿，管理员都该能再传一份
        （传错了区间想重来、或者微信支付宝各传一份）
      -->
      <div class="rec__upload">
        <label class="btn btn-primary rec__upload-btn" :class="{ 'rec__upload-btn--busy': uploading }">
          {{ uploading ? '对账中…' : '上传账单' }}
          <input
            type="file"
            accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            :disabled="uploading"
            @change="onUploadBill"
          />
        </label>
        <p class="rec__upload-hint">
          微信账单在 App 里导出（xlsx），支付宝导出的是 CSV，也可以手工整理成标准模板。
          一次上传对一次账；重复上传同一份不会产生重复差异，已经对过的会自动跳过。
        </p>
      </div>

      <!-- ============ 视图一：批次列表 ============ -->
      <template v-if="reconcileView === 'list'">
        <LoadingMask :loading="batchesLoading" />

        <div v-if="batches.length" class="rec__batches">
          <button
            v-for="b in batches"
            :key="b.id"
            class="rec__batch"
            :class="{ 'rec__batch--todo': b.unhandledCount > 0 }"
            @click="openBatch(b)"
          >
            <div class="rec__batch-head">
              <span class="rec__batch-channel">{{ b.channelLabel }}</span>
              <!-- 未处理数是这一页最该被一眼看到的东西 ——
                   传错文件产生的那种批次，就是一屏差异、一条都没处理 -->
              <span v-if="b.unhandledCount > 0" class="tag tag-warning">
                待处理 {{ b.unhandledCount }}
              </span>
              <span v-else class="tag tag-success">已处理完</span>
              <span class="rec__batch-time">{{ formatDateTime(b.createdAt) }}</span>
            </div>
            <div class="rec__batch-file">{{ b.fileName }}</div>
            <div class="rec__batch-meta">
              账单 {{ b.billCount }} 笔 · ¥{{ formatMoney(b.billAmount) }}
              <!--
                「未参与对账」必须显示出来。一份个人收款账单上写着收了 47764 元，
                而这里只写「账单 3 笔」的话，管理员连那个文件原本有 40 行都不知道 ——
                少算看得见，是这套解析一路守着的纪律
              -->
              <template v-if="b.billExcludedCount > 0">
                （另有 {{ b.billExcludedCount }} 笔未参与对账）
              </template>
              ｜ 匹配 {{ b.matchedCount }} 笔 ｜ 差异 {{ b.diffCount }} 条
              <template v-if="b.billSkippedCount > 0">
                （其中 {{ b.billSkippedCount }} 笔之前已对过）
              </template>
            </div>
          </button>
        </div>

        <EmptyState
          v-else-if="!batchesLoading"
          icon="📋"
          title="还没有对账记录"
          hint="点上面的「上传账单」开始，传一份收款账号导出的账单即可"
        />

        <AdminPager
          :page="batchPage"
          :size="BATCH_PAGE_SIZE"
          :total="batchTotal"
          @update:page="(p) => { batchPage = p; loadBatches() }"
        />
      </template>

      <!-- ============ 视图二：批次详情 ============ -->
      <template v-else>
        <div class="rec__detail-head">
          <button class="btn btn-ghost rec__back" @click="backToList">‹ 返回列表</button>
          <span class="rec__detail-title">
            {{ currentBatch?.channelLabel }} · {{ currentBatch?.fileName }}
          </span>
          <button
            v-if="currentBatch?.hasBillFile"
            class="btn btn-ghost rec__download"
            @click="onDownload(currentBatch)"
          >
            下载原文件
          </button>
        </div>

        <!--
          总账摆在最前面：月底打开一个批次，第一个要回答的是「钱少没少」，
          而不是「这 30 笔分别是谁付的」。对平了就不必往下翻 —— 下面的逐笔差异
          永远有杂音（OCR 读错、顾客跨月才付款），拿它们判断账平不平，
          等于用人力去消化机器的误差。

          hasBalance 为 false 时整块不显示：那是本次升级之前建的批次，
          库里没有这两列。当 0 处理会显示「账已对平」，而那是个没有依据的结论。
        -->
        <div
          v-if="currentBatch && hasBalance"
          class="rec__balance"
          :class="{ 'rec__balance--ok': isBalanced }"
        >
          <div class="rec__balance-head">
            <span class="rec__balance-mark">{{ isBalanced ? '✓' : '!' }}</span>
            <span class="rec__balance-title">
              {{ isBalanced ? '账已对平' : `${unclaimedCount} 笔没对上 · ¥${formatMoney(unclaimedAmount)}` }}
            </span>
          </div>

          <div class="rec__balance-rows">
            <div class="rec__balance-row">
              <span class="rec__balance-label">账单收款</span>
              <span class="rec__balance-value">
                {{ currentBatch.billCount }} 笔 · ¥{{ formatMoney(currentBatch.billAmount) }}
              </span>
            </div>
            <div class="rec__balance-row">
              <span class="rec__balance-label">已认领</span>
              <span class="rec__balance-value">
                {{ claimedCount }} 笔 · ¥{{ formatMoney(claimedAmount) }}
              </span>
            </div>
            <div class="rec__balance-row" :class="{ 'rec__balance-row--miss': !isBalanced }">
              <span class="rec__balance-label">未认领</span>
              <span class="rec__balance-value">
                {{ unclaimedCount }} 笔 · ¥{{ formatMoney(unclaimedAmount) }}
              </span>
            </div>
          </div>

          <p class="rec__balance-note">
            <template v-if="isBalanced">
              账单里每一笔钱都有凭证认领，可以按需处理剩下的逐笔差异。
            </template>
            <template v-else>
              未认领 = 系统里没有一条有效凭证指着这笔钱。往下翻差异列表定位。
            </template>
          </p>
        </div>

        <div v-if="currentBatch" class="rec__summary">
          <div class="rec__sum-item">
            <span class="rec__sum-num">{{ currentBatch.billCount }}</span>
            <span class="rec__sum-label">账单笔数</span>
          </div>
          <div class="rec__sum-item">
            <span class="rec__sum-num">{{ currentBatch.matchedCount }}</span>
            <span class="rec__sum-label">匹配成功</span>
          </div>
          <div class="rec__sum-item">
            <span class="rec__sum-num">{{ currentBatch.diffCount }}</span>
            <span class="rec__sum-label">差异条数</span>
          </div>
          <div class="rec__sum-item">
            <span class="rec__sum-num" :class="{ 'rec__sum-num--warn': currentBatch.unhandledCount > 0 }">
              {{ currentBatch.unhandledCount }}
            </span>
            <span class="rec__sum-label">未处理</span>
          </div>
        </div>

        <!--
          窗口区间要显示出来：它回答了「为什么某条凭证没被算进来」——
          而那笔账过去之后，光看一条批次记录是推不回去的
        -->
        <p v-if="currentBatch" class="rec__window">
          本次比对了 {{ formatDateTime(currentBatch.windowStart) }} 至
          {{ formatDateTime(currentBatch.windowEnd) }} 之间提交的凭证。
          <!--
            这条解释了「账单合计数与这里的金额对不上」—— 个人收款账单里混着
            转账、红包、别处的退款，它们不是本店的收款，既不匹配也不产生差异
          -->
          <template v-if="currentBatch.billExcludedCount > 0">
            账单里另有 {{ currentBatch.billExcludedCount }} 笔未参与对账
            （不是本店的收款，或方向不是「收入」）。
          </template>
          <template v-if="currentBatch.billSkippedCount > 0 || currentBatch.proofSkippedCount > 0">
            另有 {{ currentBatch.billSkippedCount }} 笔账单、{{ currentBatch.proofSkippedCount }} 条凭证在此前已经对过，本次跳过。
          </template>
        </p>

        <div class="pay__filters">
          <select
            v-model="diffHandled"
            class="field-input pay__select"
            @change="onDiffFilterChange"
          >
            <option :value="0">待处理</option>
            <option :value="null">全部</option>
            <option :value="1">已处理</option>
          </select>
          <select v-model="diffType" class="field-input pay__select" @change="onDiffFilterChange">
            <option value="">全部类型</option>
            <!-- diffTypeCounts 里没有那个键就是 0 条，所以要 ?? 0 -->
            <option v-for="t in RECONCILE_DIFF_TYPES" :key="t.value" :value="t.value">
              {{ t.label }}（{{ currentBatch?.diffTypeCounts?.[t.value] ?? 0 }}）
            </option>
          </select>
        </div>

        <LoadingMask :loading="diffsLoading" />

        <div v-if="diffs.length" class="rec__diffs">
          <div
            v-for="d in diffs"
            :key="d.id"
            class="rec__diff"
            :class="{ 'rec__diff--done': d.handled }"
          >
            <div class="rec__diff-head">
              <span :class="reconcileDiffTypeCls(d.diffType)">{{ d.diffTypeLabel }}</span>
              <span v-if="d.handled" class="tag tag-success">已处理</span>
            </div>

            <!-- 一句话说明「这类差异意味着什么、该做什么」——
                 六类的处置动作完全不同，让管理员现推是没必要的负担 -->
            <p class="rec__diff-hint">{{ d.diffTypeHint }}</p>

            <!-- 两侧摆在一起看，这是管理员判断「为什么对不上」的全部依据 -->
            <div class="rec__sides">
              <div class="rec__side">
                <span class="rec__side-label">账单侧</span>
                <template v-if="d.billAmount !== null">
                  <span class="rec__side-amount">¥{{ formatMoney(d.billAmount) }}</span>
                  <span v-if="d.billTime" class="rec__side-dim">{{ formatDateTime(d.billTime) }}</span>
                  <span v-if="d.billSummary" class="rec__side-dim">{{ d.billSummary }}</span>
                </template>
                <span v-else class="rec__side-none">账上没有这笔</span>
              </div>
              <div class="rec__side">
                <span class="rec__side-label">系统侧</span>
                <template v-if="d.proofAmount !== null">
                  <span class="rec__side-amount">¥{{ formatMoney(d.proofAmount) }}</span>
                  <span v-if="d.orderNo" class="rec__side-dim">
                    {{ d.targetTypeLabel }} {{ d.orderNo }}
                  </span>
                </template>
                <span v-else class="rec__side-none">系统里没人认领</span>
              </div>
            </div>

            <div class="rec__diff-foot">
              <span v-if="d.paymentNo" class="rec__no">{{ d.paymentNo }}</span>
              <span v-else class="rec__side-dim">未填流水号</span>
            </div>

            <div v-if="d.handled" class="rec__handled">
              {{ d.handledByName || '管理员' }} 于 {{ formatDateTime(d.handledAt) }} 标记为已处理
              <template v-if="d.handleNote">：{{ d.handleNote }}</template>
            </div>
            <button v-else class="btn btn-ghost rec__handle-btn" @click="openHandle(d)">
              标记已处理
            </button>
          </div>
        </div>

        <EmptyState
          v-else-if="!diffsLoading"
          icon="🔍"
          :title="diffHandled === 0 ? '没有待处理的差异' : '没有差异'"
          :hint="diffHandled === 0
            ? '这一批的差异都处理完了，切到「全部」可以看到处理记录'
            : '这一次对账两边完全对得上，或者筛的是某个没有记录的类型'"
        />

        <AdminPager
          :page="diffPage"
          :size="DIFF_PAGE_SIZE"
          :total="diffTotal"
          @update:page="(p) => { diffPage = p; loadDiffs() }"
        />
      </template>
    </template>

    <!-- 新增 / 编辑 -->
    <AdminSheet
      v-model:visible="formVisible"
      :title="editing ? '编辑收款码' : '新增收款码'"
      :error="formError"
    >
      <div class="field">
        <label class="field-label" for="q-channel">收款渠道</label>
        <select id="q-channel" v-model="form.channel" class="field-input">
          <option v-for="c in PAY_QR_CHANNELS" :key="c.value" :value="c.value">
            {{ c.label }}
          </option>
        </select>
      </div>

      <div class="field">
        <label class="field-label" for="q-name">显示名</label>
        <input
          id="q-name"
          v-model="form.name"
          class="field-input"
          maxlength="50"
          placeholder="如：微信收款码"
        />
        <p class="field-hint">并排展示多张码时，顾客靠它分辨该扫哪一张。</p>
      </div>

      <div class="field">
        <label class="field-label">收款码图片</label>
        <ImageUploader
          kind="payqr"
          shape="square"
          :url="form.imageUrl"
          hint="建议直接用微信/支付宝「收款码」页面的原图，不要截图后裁剪 —— 二维码四周的留白是它的一部分"
          @uploaded="onUploaded"
          @uploading="imageUploading = $event"
        />
      </div>

      <div class="field">
        <label class="field-label" for="q-sort">排序</label>
        <input id="q-sort" v-model.number="form.sort" class="field-input" type="number" min="0" />
        <p class="field-hint">越小越靠前。两张码同值时按添加顺序排。</p>
      </div>

      <label class="qr__toggle">
        <input v-model.number="form.enabled" type="checkbox" :true-value="1" :false-value="0" />
        启用（停用的不会出现在收银台上）
      </label>

      <template #footer>
        <button class="btn btn-ghost" @click="formVisible = false">取消</button>
        <button
          class="btn btn-primary"
          :disabled="submitting || imageUploading"
          @click="submit"
        >
          {{ submitting ? '保存中…' : '保存' }}
        </button>
      </template>
    </AdminSheet>

    <!-- 删除确认 -->
    <AdminSheet
      :visible="!!removing"
      title="删除收款码"
      :error="removeError"
      mask-closable
      @update:visible="removing = null"
    >
      <p class="qr__confirm">
        确定删除「{{ removing?.name }}」吗？删除后它不会再出现在任何列表里。
      </p>
      <p class="qr__confirm-note">
        已经用它收过款的订单不受影响 —— 那些凭证上记的仍是这张码，
        对账时还查得到。
      </p>
      <template #footer>
        <button class="btn btn-ghost" @click="removing = null">再想想</button>
        <button class="btn btn-danger" :disabled="submitting" @click="confirmRemove">
          确定删除
        </button>
      </template>
    </AdminSheet>

    <!-- 驳回 -->
    <AdminSheet
      :visible="!!rejecting"
      title="标记为未通过"
      :error="rejectError"
      mask-closable
      @update:visible="rejecting = null"
    >
      <p class="proof__confirm">这笔凭证将标记为「未通过」，原因会展示给顾客。</p>
      <textarea
        v-model="rejectReason"
        class="field-input proof__reason"
        rows="3"
        maxlength="200"
        placeholder="如：金额对不上，截图上显示 8 元，本单应付 12 元"
      />
      <!--
        驳回之后还有一步要做 —— 这两类在用户提交那刻就已经交付了，
        系统不会自动回退。写清楚「要做什么」，不然管理员点完就以为结束了
      -->
      <p v-if="rejecting?.delivered" class="proof__flag proof__flag--risk">
        这条已交付（订单/商品提交即结清）：驳回后请另行处置 ——
        订单需人工退款、商品需人工回补库存
      </p>
      <template #footer>
        <button class="btn btn-ghost" @click="rejecting = null">再想想</button>
        <button class="btn btn-danger" :disabled="rejectSubmitting" @click="submitReject">
          确定未通过
        </button>
      </template>
    </AdminSheet>

    <!-- 标记差异已处理 -->
    <AdminSheet
      :visible="!!handling"
      title="标记为已处理"
      :error="handleError"
      mask-closable
      @update:visible="handling = null"
    >
      <p v-if="handling" class="proof__confirm">
        <span :class="reconcileDiffTypeCls(handling.diffType)">{{ handling.diffTypeLabel }}</span>
        {{ handling.diffTypeHint }}
      </p>
      <!--
        这一句必须说清楚：标记不等于处置。差异列表里每一类的处置动作都不同
        （回去改复核结论 / 找顾客 / 找钱），而这一页一个都不代劳
      -->
      <p class="proof__confirm">
        <b>这一步不改动任何业务数据</b> —— 订单状态、付款凭证、批次都不会变，
        只是记下「你看过了、结论是什么」。要处置这笔钱，请到对应的页面去操作。
      </p>
      <textarea
        v-model="handleNote"
        class="field-input proof__reason"
        rows="3"
        maxlength="200"
        placeholder="处理备注（选填），如：已联系顾客补交凭证"
      />
      <template #footer>
        <button class="btn btn-ghost" @click="handling = null">取消</button>
        <button class="btn btn-primary" :disabled="handleSubmitting" @click="submitHandle">
          标记已处理
        </button>
      </template>
    </AdminSheet>

    <!-- 截图预览。付款截图是核对的核心依据，必须能放大看清 -->
    <div v-if="previewUrl" class="proof__preview" @click="previewUrl = ''">
      <img :src="versionedUrl(previewUrl)" alt="付款截图" />
      <p class="proof__preview-tip">点击任意处关闭</p>
    </div>
  </div>
</template>

<style scoped>
/* ⚠️ width: auto 不能少：.btn 默认是全宽的（手机上按钮占满一行更好点），
   放在标题行里必须覆盖掉，否则它会把「收款码」三个字挤成两行、
   自己也被拉得横跨整个卡片 —— 与 DeviceManageView 的 .device__new 是同一处 */
.qr__new {
  width: auto;
  height: 32px;
  margin-left: auto;
  padding: 0 var(--sp-4);
  font-size: 13px;
}

.qr__list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.qr__item {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
}

/* 停用的整行淡化，一眼能看出它不参与收款 */
.qr__item--off {
  opacity: 0.55;
}

.qr__thumb {
  width: 64px;
  height: 64px;
  flex: none;
  object-fit: contain;
  border-radius: 8px;
  background: #fff;
  border: 1px solid var(--line);
}

.qr__thumb--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--text-weak);
}

.qr__main {
  flex: 1;
  min-width: 0;
}

.qr__line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.qr__name {
  font-weight: 600;
}

.qr__meta {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-weak);
  display: flex;
  gap: 6px;
}

.qr__ops {
  display: flex;
  gap: 8px;
  flex: none;
}

.qr__op {
  padding: 6px 10px;
  font-size: 13px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
}

.qr__op--danger {
  color: var(--danger);
  border-color: var(--danger);
}

.qr__hint {
  margin: 12px 4px 0;
  font-size: 13px;
  color: var(--text-weak);
  line-height: 1.6;
}

.qr__toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.qr__confirm {
  margin: 0 0 8px;
}

.qr__confirm-note {
  margin: 0;
  font-size: 13px;
  color: var(--text-weak);
  line-height: 1.6;
}

/* ==================== 两个 tab 的切换条 ==================== */

.pay__tabs {
  display: flex;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}

.pay__tab {
  height: 30px;
  padding: 0 var(--sp-4);
  border-radius: var(--r-pill);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-size: 13px;
}

.pay__tab--on {
  background: var(--c-primary);
  color: #fff;
}

.pay__filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}

.pay__select {
  flex: 1;
  min-width: 120px;
}

/* ==================== 待复核列表 ==================== */

.proof__list {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}

.proof__item {
  display: flex;
  gap: var(--sp-3);
  padding: var(--sp-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r-card);
  background: #fff;
}

/*
 * 有风险标记的条目描红边。同一流水号被多笔凭证引用是纯信任制下
 * 最值得先看一眼的作弊手法，而后端已经把它排到了列表最前面 ——
 * 这里再把颜色接上，免得管理员逐条读文字才发现
 */
.proof__item--risk {
  border-color: var(--c-danger);
}

.proof__shot {
  width: 84px;
  height: 84px;
  flex-shrink: 0;
  border-radius: var(--r-btn);
  border: 1px solid var(--c-border);
  object-fit: cover;
  background: var(--c-card);
  cursor: zoom-in;
}

.proof__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.proof__line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
}

.proof__no {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--c-text);
  word-break: break-all;
}

/* 状态与「已对账」并排成一组靠右 —— proof__line 是 space-between 的两栏布局 */
.proof__badges {
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  flex-shrink: 0;
}

/*
 * 「已对账」标记。中性色 —— 它是一条补充信息（与账单勾稽上了），
 * 不是状态变更：到账与否看左边那个状态标签
 */
.proof__reconciled {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--c-card);
  color: var(--c-text-sub);
  font-size: 11px;
}

.proof__meta {
  font-size: 12px;
  color: var(--c-text-muted);
  word-break: break-all;
}

.proof__amount {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
}

.proof__money {
  font-size: 16px;
  font-weight: 600;
  color: var(--c-primary);
  font-variant-numeric: tabular-nums;
}

.proof__warn {
  font-size: 12px;
  color: var(--c-warning);
}

/*
 * 识别原文的折叠块。
 * 刻意不展开：一段原文有十几行，全铺开会把一屏里真正要比对的那几个数挤下去。
 * 用原生 <details>，不需要为它引入任何状态
 */
.proof__ocr {
  margin-top: 2px;
  font-size: 12px;
  color: var(--c-text-muted);
}

.proof__ocr summary {
  cursor: pointer;
  color: var(--c-primary);
}

.proof__ocr-text {
  margin: var(--sp-2) 0 0;
  padding: var(--sp-2);
  border-radius: var(--r-btn);
  background: var(--c-card);
  font-size: 11px;
  line-height: 1.6;
  /* 保留换行：识别结果是按行来的，挤成一行就没法对照着看了 */
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
}

.proof__flag {
  margin: 2px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--c-warning);
}

.proof__flag--risk {
  color: var(--c-danger);
}

.proof__ops {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: var(--sp-2);
  flex-shrink: 0;
}

.proof__op {
  padding: 6px var(--sp-3);
  border: 1px solid var(--c-primary);
  border-radius: var(--r-pill);
  font-size: 12px;
  color: var(--c-primary);
  background: #fff;
}

.proof__op--danger {
  border-color: var(--c-danger);
  color: var(--c-danger);
}

.proof__op:disabled {
  opacity: 0.5;
}

/* ==================== 弹层与预览 ==================== */

.proof__confirm {
  margin: 0 0 8px;
}

.proof__reason {
  width: 100%;
  resize: vertical;
  font-family: inherit;
}

.proof__preview {
  position: fixed;
  inset: 0;
  z-index: 600;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--sp-3);
  padding: var(--sp-4);
  background: rgba(20, 16, 32, 0.88);
  cursor: zoom-out;
}

/* 截图可能很长（付款详情页是竖长条），所以限高 + contain，不裁切 */
.proof__preview img {
  max-width: 100%;
  max-height: 82vh;
  object-fit: contain;
  border-radius: var(--r-btn);
}

.proof__preview-tip {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.75);
}

/* ==================== 对账 ==================== */

/* 上传区。用 <label> 包着隐藏的 <input type="file">：
   原生文件框在手机上的样式不可控，而 label 包 input 是唯一能自定义外观
   又保留原生行为（点开系统文件选择器）的做法 */
.rec__upload {
  margin-bottom: var(--sp-4);
}

.rec__upload-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  cursor: pointer;
}

.rec__upload-btn--busy {
  opacity: 0.6;
  cursor: progress;
}

.rec__upload-btn input {
  display: none;
}

.rec__upload-hint {
  margin-top: var(--sp-2);
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-muted);
}

.rec__batches {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 批次行做成按钮：整行可点，手机上好按 */
.rec__batch {
  display: block;
  width: 100%;
  padding: var(--sp-3) var(--sp-4);
  text-align: left;
  background: var(--c-icon-bg);
  border: 1px solid var(--c-border);
  border-radius: var(--r-card);
  cursor: pointer;
}

/* 有未处理差异的批次描一道边 —— 传错文件的那种就是这么被一眼认出来的 */
.rec__batch--todo {
  border-color: var(--c-warning);
}

.rec__batch-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.rec__batch-channel {
  font-weight: 600;
}

.rec__batch-time {
  margin-left: auto;
  font-size: 12px;
  color: var(--c-text-muted);
}

.rec__batch-file {
  margin-top: 6px;
  font-size: 13px;
  word-break: break-all;
}

.rec__batch-meta {
  margin-top: 6px;
  font-size: 12px;
  color: var(--c-text-muted);
}

/* ---------------- 批次详情 ---------------- */

.rec__detail-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}

.rec__back {
  width: auto;
  flex: none;
}

.rec__detail-title {
  flex: 1;
  min-width: 0;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rec__download {
  width: auto;
  flex: none;
}

/* ---------- 总账（对没对平） ---------- */

/*
  用左侧色条而不是整块染色：整块染色会让下面的差异列表显得「不重要」，
  而没对平时恰恰要往下看
*/
.rec__balance {
  padding: var(--sp-3);
  margin-bottom: var(--sp-3);
  background: var(--c-icon-bg);
  border-left: 3px solid var(--c-warning);
  border-radius: var(--r-btn);
}

.rec__balance--ok {
  border-left-color: var(--c-success);
}

.rec__balance-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-bottom: var(--sp-2);
}

/* 圆形的状态标记。用色块而不是 emoji，与后台其余处的状态提示保持一致 */
.rec__balance-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--c-warning);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}

.rec__balance--ok .rec__balance-mark {
  background: var(--c-success);
}

.rec__balance-title {
  font-size: 14px;
  font-weight: 600;
}

.rec__balance-rows {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.rec__balance-row {
  display: flex;
  justify-content: space-between;
  gap: var(--sp-2);
  font-size: 13px;
}

/* 未认领那一行加粗 —— 没对平时它是唯一需要追的数 */
.rec__balance-row--miss {
  font-weight: 600;
}

.rec__balance-label {
  color: var(--c-text-muted);
}

.rec__balance-row--miss .rec__balance-label {
  color: inherit;
}

.rec__balance-note {
  margin-top: var(--sp-2);
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-muted);
}

.rec__summary {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}

.rec__sum-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: var(--sp-2);
  background: var(--c-icon-bg);
  border-radius: var(--r-btn);
}

.rec__sum-num {
  font-size: 18px;
  font-weight: 700;
}

.rec__sum-num--warn {
  color: var(--c-warning);
}

.rec__sum-label {
  font-size: 11px;
  color: var(--c-text-muted);
}

/* 窗口区间。字号小、颜色淡 —— 它是「解释性」的信息，不是每天都要读的 */
.rec__window {
  margin-bottom: var(--sp-3);
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-muted);
}

.rec__diffs {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.rec__diff {
  padding: var(--sp-3) var(--sp-4);
  background: var(--c-icon-bg);
  border: 1px solid var(--c-border);
  border-radius: var(--r-card);
}

/* 处理过的整条变淡：列表默认只看待处理，但切到「全部」时
   要有办法一眼分出哪些是已经了结的 */
.rec__diff--done {
  opacity: 0.62;
}

.rec__diff-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.rec__diff-hint {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-muted);
}

/* 两侧并排。手机上两列仍放得下 —— 一边只有金额与一两个短字段 */
.rec__sides {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
}

.rec__side {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--sp-2);
  background: var(--c-card);
  border-radius: var(--r-btn);
}

.rec__side-label {
  font-size: 11px;
  color: var(--c-text-muted);
}

.rec__side-amount {
  font-weight: 600;
}

.rec__side-dim {
  font-size: 11px;
  color: var(--c-text-muted);
  word-break: break-all;
}

/* 「没有这一笔」用斜体：它是「查无此事」这个结论，不是一个值 */
.rec__side-none {
  font-size: 12px;
  font-style: italic;
  color: var(--c-text-muted);
}

.rec__diff-foot {
  margin-top: var(--sp-2);
  font-size: 12px;
}

/* 单号用等宽字体：管理员要拿它去账单里逐位比，字体不等宽很容易看串行 */
.rec__no {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  word-break: break-all;
}

.rec__handled {
  margin-top: var(--sp-2);
  font-size: 12px;
  color: var(--c-text-muted);
}

.rec__handle-btn {
  width: auto;
  height: 32px;
  margin-top: var(--sp-3);
  padding: 0 var(--sp-4);
  font-size: 13px;
}
</style>
