<script setup>
/**
 * 商品管理（运营后台）：两个 tab —— <b>商品维护</b>与<b>商品订单</b>。
 *
 * <p>为什么商品订单也在这里：后台的「商品」这一块如果只有货架，管理员摆好了货
 * 却看不到任何一笔交易，那这一页只是半个功能。两者共用同一套分页与状态标签。
 *
 * <p>⚠️ <b>本页的分页参数是 {@code pageNum} / {@code pageSize}</b>，
 * 与公告、包场那两页的 {@code page} / {@code size} <b>不一样</b>
 * （后端如此，不是笔误）。传错不会报错，只会永远返回第一页 ——
 * 表现为「翻页没反应」，而控制台里一行异常都没有。
 *
 * <p><b>封面图有自己的上传端点</b>（{@code POST /api/admin/products/cover}），
 * 但它<b>只产路径、不写库</b> —— 上传拿到的那串路径填进表单，
 * 仍然随下面那个全量 {@code PUT} 一起提交。所以「既然有单独的封面接口，
 * 为什么保存还要全量提交」这个疑问的答案是：那个接口只负责把文件存下来。
 *
 * <p>⚠️ <b>商品没有「只改上下架」的接口</b>（机台有 {@code PUT /{id}/status}，
 * 商品没有），所以上下架必须走全量替换的 {@code PUT}：把整条记录读出来、
 * 改掉 {@code enabled}、再整体提交。只发一个 {@code {enabled: 0}} 的话，
 * {@code name} / {@code price} / {@code stock} 的非空校验会直接把请求挡回来，
 * 文案还是「商品名称不能为空」—— 排查方向被指到完全错误的地方。
 */
import { ref, computed, onMounted } from 'vue'
import {
  listAdminProducts,
  createProduct,
  updateProduct,
  deleteProduct,
  uploadProductCover,
  listAdminProductOrders
} from '@/api/admin'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatMoney, formatDateTime } from '@/utils/format'
import { PRODUCT_ORDER_STATUS, productOrderStatusCls } from '@/utils/labels'
import AdminPager from '@/components/AdminPager.vue'
import AdminSheet from '@/components/AdminSheet.vue'
import EmptyState from '@/components/EmptyState.vue'
import ImageUploader from '@/components/ImageUploader.vue'
import LoadingMask from '@/components/LoadingMask.vue'

/** 每页条数。⚠️ 本页两个列表的分页参数都是 pageNum + pageSize */
const PAGE_SIZE = 10

/** 当前 tab：products 商品维护 / orders 商品订单 */
const tab = ref('products')

/* ---------------- 商品维护 ---------------- */

const page = ref(1)
const total = ref(0)
const products = ref([])
const loading = ref(true)
/** 关键词与上架状态筛选。空串表示「全部」——⚠️ 空串要整个字段不传，见 load() */
const keyword = ref('')
const enabledFilter = ref('')

/**
 * 「库存少→多」排序开关。
 *
 * <p>⚠️ <b>排序走的是后端的 {@code stockAsc} 参数，不是前端排</b> ——
 * 结果是分页的，前端只能排当前这一页：第二页可能藏着比本页更少的库存，
 * 而运营看的是「最上面那条最少」。所以要翻页就翻页、要排序让后端排，
 * 两件事不能只做一半。
 */
const sortByStock = ref(false)

/* ---------------- 商品订单 ---------------- */

const orderPage = ref(1)
const orderTotal = ref(0)
const orders = ref([])
const ordersLoading = ref(false)
const orderStatus = ref('')
/** 是否已经加载过订单。切到那一页时才去拉，不在打开页面时就打两个请求 */
const ordersLoaded = ref(false)

/* ---------------- 表单弹层 ---------------- */

const formVisible = ref(false)
/** 正在编辑的那件；为 null 表示「新增」 */
const editing = ref(null)
const form = ref({
  name: '',
  cover: '',
  description: '',
  price: '',
  stock: 0,
  sortNo: 0,
  enabled: true
})
const formError = ref('')
const submitting = ref(false)

/**
 * 封面图是否正在上传。
 *
 * <p>用来禁用「确定」：上传还没回来就点保存的话，{@code form.cover} 还是旧值，
 * 提交成功、接口 200，而封面悄悄丢了（新增时）或还是上一张（编辑时）。
 */
const coverUploading = ref(false)

/**
 * 封面传完了：接口只返回 {@code {cover}}，填进表单，等提交时随全量替换一起走。
 *
 * <p>⚠️ <b>弹层已经关掉就直接丢弃</b>：管理员传完图立刻取消、又打开另一件商品时，
 * 这个响应可能才回来 —— 不挡的话，路径会写到【另一件商品】的表单上，
 * 而他若无其事地点保存，B 商品就带上了 A 的封面。全程没有任何报错。
 */
function onCoverUploaded(data) {
  if (!formVisible.value) return
  form.value.cover = data?.cover || ''
}

/** 「确定」按钮的文案：上传中优先提示它，免得管理员以为界面卡住了。 */
const submitText = computed(() => {
  if (coverUploading.value) return '图片上传中…'
  return submitting.value ? '提交中…' : '确定'
})

/* ---------------- 删除确认 ---------------- */

const removing = ref(null)
const removeError = ref('')

/* ---------------- 下架确认 ---------------- */

/**
 * 正在确认下架的那件。
 *
 * <p><b>下架要二次确认，上架不用</b>：下架会让顾客立刻看不到这件商品、也下不了单，
 * 是「对顾客生效」的动作；而上架只是恢复，点错了再点回来即可。
 * 两件事的后果不对称，就不该用同一套交互。
 */
const disabling = ref(null)
const disableError = ref('')

/* ---------------- 补货（只改库存） ---------------- */

/** 正在调库存的那件 */
const restocking = ref(null)
/** 输入框里的值：**补完后的总数**，不是「补了几件」 */
const restockValue = ref('')
const restockError = ref('')

/**
 * 补货弹层里那行实时预览（「当前 47 → 改为 57」）。
 *
 * <p>让用户当场看到自己填的是「总数」而不是「补了几件」——
 * 这两个含义填错一个，库存就错得离谱，而接口不会有任何提示。
 *
 * @returns {{ok: boolean, from: number, to: number}}
 */
const restockPreview = computed(() => {
  const row = restocking.value
  const from = row?.stock ?? 0
  const to = Number(restockValue.value)
  const ok = restockValue.value !== '' && Number.isFinite(to) && to >= 0
  return { ok, from, to: ok ? Math.trunc(to) : null }
})

/**
 * 拉取商品列表。
 *
 * <p>⚠️ 筛选值为空时<b>整个字段不传</b>：{@code enabled} 在后端是 {@code Integer}，
 * 传一个空的 {@code ?enabled=} 会直接 400「参数取值不合法」，
 * 而那句文案不会告诉你「是因为筛选项没选」。
 */
async function load() {
  loading.value = true
  try {
    const params = { pageNum: page.value, pageSize: PAGE_SIZE }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (enabledFilter.value !== '') params.enabled = Number(enabledFilter.value)
    // 只在打开时带上，默认序让后端用自己的 ORDER BY（sort_no）
    if (sortByStock.value) params.stockAsc = true

    const resp = await listAdminProducts(params)
    const count = resp.data?.total || 0
    const maxPage = Math.max(1, Math.ceil(count / PAGE_SIZE))

    if (page.value > maxPage) {
      page.value = maxPage
      return load()
    }

    products.value = resp.data?.records || []
    total.value = count
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    loading.value = false
  }
}

/** 拉取商品订单（切到那个 tab 时才第一次调）。 */
async function loadOrders() {
  ordersLoading.value = true
  try {
    const params = { pageNum: orderPage.value, pageSize: PAGE_SIZE }
    if (orderStatus.value) params.status = orderStatus.value

    const resp = await listAdminProductOrders(params)
    const count = resp.data?.total || 0
    const maxPage = Math.max(1, Math.ceil(count / PAGE_SIZE))

    if (orderPage.value > maxPage) {
      orderPage.value = maxPage
      return loadOrders()
    }

    orders.value = resp.data?.records || []
    orderTotal.value = count
    ordersLoaded.value = true
  } catch (err) {
    toastError(errorMessage(err, '加载失败'))
  } finally {
    ordersLoading.value = false
  }
}

/** 切 tab。订单是只读的，切过去时按需加载一次。 */
function onTabChange(next) {
  tab.value = next
  if (next === 'orders' && !ordersLoaded.value) loadOrders()
}

/** 切筛选：回第一页再查。 */
function onFilterChange() {
  page.value = 1
  load()
}

/**
 * 切「库存少→多」。
 *
 * <p>换排序要重新问后端要数据（排序在 SQL 里），不像纯前端排序那样改一下本地顺序就行。
 * 页码不动 —— 在第 2 页切换排序后就停在第 2 页，这是列表页的常规行为。
 */
function onSortToggle() {
  sortByStock.value = !sortByStock.value
  load()
}

/** 切订单状态筛选。 */
function onOrderFilterChange() {
  orderPage.value = 1
  loadOrders()
}

/** 打开「新增」表单。 */
function openCreate() {
  editing.value = null
  form.value = {
    name: '',
    cover: '',
    description: '',
    price: '',
    stock: 0,
    sortNo: 0,
    enabled: true
  }
  formError.value = ''
  formVisible.value = true
}

/** 打开「编辑」表单。⚠️ 逐字段取新对象，不要引用列表行。 */
function openEdit(row) {
  editing.value = row
  form.value = {
    name: row.name || '',
    cover: row.cover || '',
    description: row.description || '',
    price: String(row.price ?? ''),
    stock: row.stock ?? 0,
    sortNo: row.sortNo ?? 0,
    enabled: !!row.enabled
  }
  formError.value = ''
  formVisible.value = true
}

/**
 * 组装提交体。
 *
 * <p>⚠️ <b>全量替换语义</b>：没传的字段会被清空 —— {@code cover} 与
 * {@code description} 传 null 就是「清空封面、清空描述」，而接口照样返回 200。
 * 所以这里两个字段一定要带上（表单里有输入框，天然带上了）。
 *
 * <p>⚠️ {@code sortNo} 不传是按 <b>0</b> 处理（不是保持原值），同样必须显式带上。
 *
 * <p>⚠️ {@code enabled} 读出来是 <b>布尔</b>（{@code ProductVo}），
 * 提交要的是 <b>1 / 0</b>（{@code ProductSaveRequest}）。不显式转的话，
 * 就得赌 Jackson 会不会替我们把 true 强转成 1 —— 这种事不该赌。
 *
 * @param {object} source 数据来源：表单对象，或列表里的一行
 * @param {object} [override] 覆盖个别字段：{@code enabled} 一键上下架、
 *                            {@code stock} 补货。其余字段一律取自 source
 */
function buildBody(source, override = {}) {
  const price = Number(source.price)
  const stock = override.stock ?? Number(source.stock)
  const sortNo = Number(source.sortNo)
  const enabled = override.enabled ?? source.enabled
  return {
    name: String(source.name || '').trim(),
    cover: String(source.cover || '').trim(),
    description: String(source.description || '').trim(),
    price: Number.isFinite(price) && price >= 0 ? price : 0,
    stock: Number.isFinite(stock) && stock >= 0 ? Math.trunc(stock) : 0,
    sortNo: Number.isFinite(sortNo) ? Math.trunc(sortNo) : 0,
    enabled: enabled ? 1 : 0
  }
}

/** 提交表单（新增或编辑）。 */
async function submit() {
  if (!form.value.name.trim()) {
    formError.value = '商品名称不能为空'
    return
  }
  const price = Number(form.value.price)
  if (form.value.price === '' || !Number.isFinite(price) || price < 0) {
    formError.value = '售价不能为空，且不能为负数'
    return
  }
  const stock = Number(form.value.stock)
  if (!Number.isFinite(stock) || stock < 0) {
    formError.value = '库存不能为空，且不能为负数'
    return
  }

  submitting.value = true
  formError.value = ''
  try {
    if (editing.value) {
      await updateProduct(editing.value.id, buildBody(form.value))
      toastSuccess('已保存')
    } else {
      await createProduct(buildBody(form.value))
      toastSuccess('已上架')
    }
    formVisible.value = false
    await load()
  } catch (err) {
    formError.value = errorMessage(err, '保存失败')
  } finally {
    submitting.value = false
  }
}

/**
 * 一键上下架。
 *
 * <p>没有单独的接口，只能把整条记录原样回传、只改 {@code enabled} ——
 * 所以这里传的是列表里的那一行，而不是表单。
 *
 * <p><b>下架要先过一次确认</b>（见 {@link openDisable}），上架直接执行。
 *
 * @param {object} row 列表里的商品
 */
async function toggleEnabled(row) {
  submitting.value = true
  try {
    await updateProduct(row.id, buildBody(row, { enabled: !row.enabled }))
    toastSuccess(row.enabled ? '已下架' : '已上架')
    disabling.value = null
    await load()
  } catch (err) {
    // 确认弹层开着时错误显示在弹层里（toast 的层级低于弹层，会被遮住）
    if (disabling.value) disableError.value = errorMessage(err, '操作失败')
    else toastError(errorMessage(err, '操作失败'))
  } finally {
    submitting.value = false
  }
}

/** 点「下架」：已上架的先确认，已下架的直接上架。 */
function openDisable(row) {
  if (row.enabled) {
    disableError.value = ''
    disabling.value = row
    return
  }
  toggleEnabled(row)
}

/** 打开补货弹层（只改库存一列，其余字段原样回传）。 */
function openRestock(row) {
  restockError.value = ''
  restockValue.value = String(row.stock ?? 0)
  restocking.value = row
}

/**
 * 提交补货。
 *
 * <p>⚠️ 输入的<b>是补完后的总数</b>，不是「补了几件」—— 与后端
 * {@code ProductSaveRequest.stock} 的口径一致（那是新的库存值，不是扣减量）。
 * 弹层里有实时预览，就是为了让这件事不用猜。
 */
async function submitRestock() {
  if (!restockPreview.value.ok) {
    restockError.value = '请填写调整后的库存总数（不能为空、不能为负数）'
    return
  }
  submitting.value = true
  restockError.value = ''
  try {
    const row = restocking.value
    await updateProduct(row.id, buildBody(row, { stock: restockPreview.value.to }))
    toastSuccess(`库存已改为 ${restockPreview.value.to}`)
    restocking.value = null
    await load()
  } catch (err) {
    restockError.value = errorMessage(err, '调整失败')
  } finally {
    submitting.value = false
  }
}

/** 执行删除（逻辑删除）。 */
async function confirmRemove() {
  submitting.value = true
  removeError.value = ''
  try {
    await deleteProduct(removing.value.id)
    toastSuccess('已删除')
    removing.value = null
    await load()
  } catch (err) {
    removeError.value = errorMessage(err, '删除失败')
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="card">
      <div class="card-title">
        商品
        <button class="btn btn-primary product__new" @click="openCreate">新增商品</button>
      </div>

      <div class="product__tabs">
        <button
          class="product__tab"
          :class="{ 'product__tab--on': tab === 'products' }"
          @click="onTabChange('products')"
        >
          商品维护
        </button>
        <button
          class="product__tab"
          :class="{ 'product__tab--on': tab === 'orders' }"
          @click="onTabChange('orders')"
        >
          商品订单
        </button>
      </div>

      <!-- ============ tab 一：商品维护 ============ -->
      <template v-if="tab === 'products'">
        <div class="product__filters">
          <input
            v-model="keyword"
            class="field-input product__search"
            placeholder="按名称搜索"
            @keyup.enter="onFilterChange"
          />
          <select v-model="enabledFilter" class="field-input product__select" @change="onFilterChange">
            <option value="">全部状态</option>
            <option value="1">已上架</option>
            <option value="0">已下架</option>
          </select>
          <button class="btn btn-ghost product__search-btn" @click="onFilterChange">查询</button>

          <!-- 补货优先：点一下让后端按库存从少到多重排（跨页有效） -->
          <button
            class="product__sort"
            :class="{ 'product__sort--on': sortByStock }"
            :title="sortByStock ? '当前：库存从少到多' : '点一下按库存从少到多排列'"
            @click="onSortToggle"
          >
            库存少→多
          </button>
        </div>

        <LoadingMask :loading="loading" />

        <div v-if="products.length" class="product__list">
          <div v-for="p in products" :key="p.id" class="product__item">
            <div class="product__cover">
              <img v-if="p.cover" :src="p.cover" :alt="p.name" />
              <span v-else class="product__cover-fallback">🎁</span>
            </div>

            <div class="product__info">
              <div class="product__line">
                <!--
                  状态贴最左、自成一行 —— 一栏对齐，扫一眼就能看出哪几件下架了。
                  售罄用后端给的 soldOut：可售量为 0（含被未支付单占掉的部分）时就算售罄
                -->
                <span :class="p.enabled ? 'tag tag-success' : 'tag'">
                  {{ p.enabled ? '已上架' : '已下架' }}
                </span>
                <span v-if="p.soldOut" class="tag tag-danger">已售罄</span>
                <span class="product__name">{{ p.name }}</span>
              </div>
              <div class="product__price">¥{{ formatMoney(p.price) }}</div>
            </div>

            <!--
              库存与可售量是两个数：有未支付的待支付单时库存还有货，但那些货已经被占住，
              只显示库存会让管理员以为「明明有货怎么下不了单」。
              库存给大字号，可售量只在两者不同时补一行小字
            -->
            <div class="product__stock">
              <span class="product__stock-label">库存</span>
              <span class="product__stock-value">{{ p.stock }}</span>
              <span v-if="p.availableStock !== p.stock" class="product__occupied">
                可售 {{ p.availableStock }}
              </span>
            </div>

            <!--
              操作区。四个按钮<b>一律横排、绝不上下堆叠</b> ——
              竖着排的话相邻两个只隔几个像素，手指一歪就删了商品。
              顺序也刻意：「删除」在最左（离右手最远），最常用的「补货」在最右。
            -->
            <div class="product__actions">
              <button class="product__btn product__btn--danger" @click="removing = p">删除</button>
              <button
                class="product__btn product__btn--warn"
                :disabled="submitting"
                @click="openDisable(p)"
              >
                {{ p.enabled ? '下架' : '上架' }}
              </button>
              <button class="product__btn" @click="openEdit(p)">编辑</button>
              <button class="product__btn product__btn--ok" @click="openRestock(p)">补货</button>
            </div>
          </div>
        </div>

        <EmptyState
          v-else-if="!loading"
          icon="🛍"
          text="没有符合条件的商品"
          hint="换个筛选条件，或新增一件"
        />

        <AdminPager :page="page" :size="PAGE_SIZE" :total="total" @update:page="(p) => { page = p; load() }" />
      </template>

      <!-- ============ tab 二：商品订单 ============ -->
      <template v-else>
        <div class="product__filters">
          <select v-model="orderStatus" class="field-input product__select" @change="onOrderFilterChange">
            <option value="">全部状态</option>
            <option v-for="s in PRODUCT_ORDER_STATUS" :key="s.value" :value="s.value">
              {{ s.label }}
            </option>
          </select>
        </div>

        <LoadingMask :loading="ordersLoading" />

        <div v-if="orders.length" class="product__list">
          <div v-for="o in orders" :key="o.id" class="product__order">
            <div class="product__head">
              <span class="product__no">{{ o.orderNo }}</span>
              <!-- 状态中文用后端给的 statusLabel，只有配色取自 labels.js -->
              <span :class="productOrderStatusCls(o.status)">{{ o.statusLabel }}</span>
            </div>

            <div class="product__line">
              <span class="product__name">{{ o.productName }} × {{ o.quantity }}</span>
              <span class="product__price">¥{{ formatMoney(o.amount) }}</span>
            </div>

            <p class="product__meta">
              单价 ¥{{ formatMoney(o.unitPrice) }} · 下单于 {{ formatDateTime(o.createdAt) }}
              <template v-if="o.paidAt"> · 付款于 {{ formatDateTime(o.paidAt) }}</template>
            </p>
          </div>
        </div>

        <EmptyState
          v-else-if="!ordersLoading"
          icon="🧾"
          text="还没有商品订单"
          hint="顾客在商城里下单后，这里就能看到"
        />

        <AdminPager
          :page="orderPage"
          :size="PAGE_SIZE"
          :total="orderTotal"
          @update:page="(p) => { orderPage = p; loadOrders() }"
        />
      </template>
    </div>

    <p class="product__hint">
      店内无人值守，付了钱自己取 —— 系统<b>不做核销</b>，也分不清货被拿了没有。
      这是明知的取舍，不是遗漏。
    </p>

    <!-- 新增 / 编辑 -->
    <AdminSheet
      v-model:visible="formVisible"
      :title="editing ? '编辑商品' : '新增商品'"
      :error="formError"
    >
      <div class="field">
        <label class="field-label" for="p-name">商品名称</label>
        <input id="p-name" v-model="form.name" class="field-input" maxlength="100" placeholder="如：冰镇可乐 330ml" />
      </div>

      <div class="field">
        <label class="field-label">封面图（选填）</label>
        <ImageUploader
          kind="product"
          shape="square"
          :url="form.cover"
          hint="会自动裁成正方形；不传则商城里显示占位图"
          @uploaded="onCoverUploaded"
          @uploading="coverUploading = $event"
        />
        <!--
          保留「清空封面」这个能力：原来是清空文本框就能撤掉，
          换成上传器之后要补一个按钮。置空后 buildBody 送上去的是空串，
          后端归一成 null 即撤掉封面（全量替换语义）。
        -->
        <button v-if="form.cover" class="product__cover-remove" @click="form.cover = ''">
          移除封面
        </button>
      </div>

      <div class="field">
        <label class="field-label" for="p-desc">商品描述（选填）</label>
        <textarea id="p-desc" v-model="form.description" class="field-input" rows="2" maxlength="500" />
      </div>

      <div class="field">
        <label class="field-label" for="p-price">售价（元）</label>
        <input id="p-price" v-model="form.price" class="field-input" type="number" min="0" step="0.01" />
      </div>

      <div class="field">
        <label class="field-label" for="p-stock">库存</label>
        <input id="p-stock" v-model="form.stock" class="field-input" type="number" min="0" />
        <p class="product__hint">
          填的是<b>盘点后的实际数量</b>，不是「又卖了几件」。
          支付时系统会按购买数量自动扣减。
        </p>
      </div>

      <div class="field">
        <label class="field-label" for="p-sort">排序值</label>
        <input id="p-sort" v-model="form.sortNo" class="field-input" type="number" />
        <p class="product__hint">越小越靠前。⚠️ 不填按 0 处理。</p>
      </div>

      <label class="product__check">
        <input v-model="form.enabled" type="checkbox" />
        <span>上架（顾客能在商城里看到并下单）</span>
      </label>

      <template #footer>
        <button class="btn btn-ghost" @click="formVisible = false">取消</button>
        <!--
          ⚠️ 上传中也要禁用：图片还没传完就提交的话，form.cover 还是旧值，
          提交成功、接口 200，而封面悄悄丢了
        -->
        <button class="btn btn-primary" :disabled="submitting || coverUploading" @click="submit">
          {{ submitText }}
        </button>
      </template>
    </AdminSheet>

    <!-- 删除确认 -->
    <AdminSheet
      :visible="!!removing"
      title="删除商品"
      :error="removeError"
      mask-closable
      @update:visible="removing = null"
    >
      <p class="product__confirm">确定删除「{{ removing?.name }}」吗？</p>
      <p class="product__hint">
        删除后它从后台与商城双双消失，历史订单不受影响。
        只是想暂时不卖的话，用「下架」—— 下架还能再上架。
      </p>

      <template #footer>
        <button class="btn btn-ghost" @click="removing = null">取消</button>
        <button class="btn btn-danger" :disabled="submitting" @click="confirmRemove">
          {{ submitting ? '删除中…' : '删除' }}
        </button>
      </template>
    </AdminSheet>

    <!-- 下架确认。⚠️ 上架不走这里，见 disabling 的注释 -->
    <AdminSheet
      :visible="!!disabling"
      title="下架商品"
      :error="disableError"
      mask-closable
      @update:visible="disabling = null"
    >
      <p class="product__confirm">确定下架「{{ disabling?.name }}」吗？</p>
      <p class="product__hint">
        下架后顾客在商城里看不到它，也不能再下单；<b>已经卖出去的订单不受影响</b>。
        想再卖时点一下「上架」即可，商品数据都还在。
      </p>

      <template #footer>
        <button class="btn btn-ghost" @click="disabling = null">取消</button>
        <button class="btn btn-primary" :disabled="submitting" @click="toggleEnabled(disabling)">
          {{ submitting ? '处理中…' : '确定下架' }}
        </button>
      </template>
    </AdminSheet>

    <!-- 补货：只改库存一列，其余字段原样回传（全量替换语义） -->
    <AdminSheet
      :visible="!!restocking"
      title="调整库存"
      :error="restockError"
      @update:visible="restocking = null"
    >
      <p class="product__confirm">{{ restocking?.name }}</p>

      <div class="field">
        <label class="field-label" for="p-restock">调整后的库存总数</label>
        <input
          id="p-restock"
          v-model="restockValue"
          class="field-input"
          type="number"
          min="0"
          inputmode="numeric"
        />
      </div>

      <!-- 实时预览：让「填总数」这件事不用猜，填错一个含义库存就差得离谱 -->
      <p v-if="restockPreview.ok" class="product__restock-preview">
        当前库存 {{ restockPreview.from }} → 改为 {{ restockPreview.to }}
      </p>

      <p class="product__hint">
        填的是<b>盘点之后的总数</b>，不是「这次补了几件」——
        原来 47 件、这次进了 10 件，就填 57。
      </p>

      <template #footer>
        <button class="btn btn-ghost" @click="restocking = null">取消</button>
        <button class="btn btn-primary" :disabled="submitting" @click="submitRestock">
          {{ submitting ? '提交中…' : '确定' }}
        </button>
      </template>
    </AdminSheet>
  </div>
</template>

<style scoped>
.product__new {
  width: auto;
  height: 32px;
  margin-left: auto;
  padding: 0 var(--sp-4);
  font-size: 13px;
}

.product__tabs {
  display: flex;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}

.product__tab {
  height: 30px;
  padding: 0 var(--sp-4);
  border-radius: var(--r-pill);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-size: 13px;
}

.product__tab--on {
  background: var(--c-primary);
  color: #fff;
}

.product__filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}

.product__search {
  flex: 1;
  /* 给下限，窄屏上宁可换行也不把搜索框压成一条缝 */
  min-width: 140px;
  height: 38px;
}

/* 「库存从少到多」开关：与筛选区的胶囊按钮同一套外观 */
.product__sort {
  height: 38px;
  padding: 0 var(--sp-3);
  border-radius: var(--r-btn);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-size: 13px;
  flex-shrink: 0;
}

.product__sort--on {
  background: var(--c-primary);
  color: #fff;
}

.product__select {
  width: auto;
  min-width: 108px;
  height: 38px;
  padding: 0 var(--sp-2);
}

.product__search-btn {
  width: auto;
  height: 38px;
  padding: 0 var(--sp-4);
  font-size: 13px;
  flex-shrink: 0;
}

/*
 * 一行商品：左起「图 → 状态与名称 → 库存 → 操作区」。
 *
 * 窄屏放不下时整体换行（flex-wrap），操作区整块落到第二行 ——
 * 而不是把四个按钮压扁或让页面横向滚动。
 */
.product__item {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-3);
  padding: var(--sp-3) 0;
}

.product__item + .product__item {
  border-top: 1px solid var(--c-border);
}

.product__cover {
  width: 52px;
  height: 52px;
  flex-shrink: 0;
  border-radius: var(--r-btn);
  overflow: hidden;
  background: var(--c-icon-bg);
  display: flex;
  align-items: center;
  justify-content: center;
}

.product__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.product__cover-fallback {
  font-size: 20px;
  opacity: 0.6;
}

/*
 * 「移除封面」：上传器下方的一个小按钮。
 * 用中性色而不是危险色 —— 撤掉封面随时可以重传，不是破坏性操作。
 */
.product__cover-remove {
  margin-top: var(--sp-2);
  font-size: 12px;
  color: var(--c-text-sub);
}

.product__info {
  flex: 1;
  /* 给一个下限，窄屏上宁可让操作区整块换行，也不把名称挤成一个字一行 */
  min-width: 140px;
}

.product__line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
}

.product__name {
  min-width: 0;
  font-size: 14px;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product__price {
  margin-top: 2px;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-primary);
}

/* 商品订单 tab 用（那一行仍是「名称 × 数量 / 金额」的老排布） */
.product__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  margin-top: 2px;
  font-size: 11px;
  color: var(--c-text-muted);
}

.product__stock {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  flex-shrink: 0;
}

.product__stock-label {
  font-size: 11px;
  color: var(--c-text-muted);
}

.product__stock-value {
  font-size: 20px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--c-primary);
  /* 等宽数字：翻页时数字不会因字形宽窄而左右跳动 */
  font-variant-numeric: tabular-nums;
}

.product__occupied {
  font-size: 11px;
  color: var(--c-warning);
}

/*
 * 操作区：用一张白色卡片包起来，四个按钮<b>横排一行</b>。
 *
 * ⚠️ 不做两行或网格堆叠：竖着排时相邻按钮只隔几个像素，
 * 移动端手指一歪就从「编辑」点到「删除」。
 *
 * 底色取白而不是主题色 —— 整行本来就压在 --c-card（淡紫）上，
 * 再用同色系包一层就看不出边界了。
 */
.product__actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
  /* 窄屏这一整块会换到第二行，靠右对齐（不占满整行，免得四个按钮被拉得过宽） */
  margin-left: auto;
  padding: 8px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-btn);
  background: #fff;
}

/*
 * 按钮配色沿用 base.css 里那套标签色（同一组已经按对比度挑过的值），
 * 按后果轻重分色：删除红、下架橙、编辑紫、补货绿。
 */
.product__btn {
  /* 四等分卡片宽度：手机上每个约 70px，两字标签放得下且不至于太窄 */
  flex: 1;
  min-width: 64px;
  height: 36px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 500;
  background: var(--c-primary-pale);
  color: var(--c-primary);
  transition: opacity 0.15s;
}

.product__btn:active {
  opacity: 0.7;
}

.product__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.product__btn--danger {
  background: #f8d7d5;
  color: #a33a36;
}

.product__btn--warn {
  background: #fbe7c8;
  color: #9a6212;
}

.product__btn--ok {
  background: #cdead9;
  color: #2b7d52;
}

.product__order {
  padding: var(--sp-3) 0;
}

.product__order + .product__order {
  border-top: 1px solid var(--c-border);
}

.product__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
}

.product__no {
  font-size: 12px;
  color: var(--c-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.product__check {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: 13px;
  color: var(--c-text);
}

.product__confirm {
  font-size: 14px;
  color: var(--c-text);
}

/* 补货弹层里的「当前 47 → 改为 57」 */
.product__restock-preview {
  margin-bottom: var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--r-btn);
  background: var(--c-primary-pale);
  color: var(--c-primary);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.product__hint {
  margin-top: var(--sp-2);
  font-size: 11px;
  color: var(--c-text-muted);
  line-height: 1.6;
}
</style>
