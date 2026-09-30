<script setup>
/**
 * 商品详情弹层（含数量选择与下单）。
 *
 * <p>下单时要留意后端的软检查：可售量 = 库存 − 未支付的待支付单占用量，
 * 它不锁库存，所以理论上仍有竞态；最后一道防线是支付时的条件 UPDATE。
 * 前端只要把 40932（已售罄）如实提示出来即可，不必自己做库存判断 ——
 * 那样两边口径会分岔。
 */
import { ref, computed } from 'vue'
import { formatMoney } from '@/utils/format'

const props = defineProps({
  /** 是否显示 */
  visible: { type: Boolean, default: false },
  /** 商品：{ id, name, cover, description, price, soldOut } */
  product: { type: Object, default: null },
  /** 下单中 */
  submitting: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible', 'submit'])

const quantity = ref(1)

/** 合计金额。用后端给的单价乘数量，不做浮点运算以免金额出现 0.30000000000000004。 */
const totalText = computed(() => {
  if (!props.product) return '0.00'
  const price = Number(props.product.price) || 0
  return formatMoney((price * quantity.value).toFixed(2))
})

function change(delta) {
  const next = quantity.value + delta
  if (next < 1) return
  // 上限 99：单店量级下没人会买 100 件，卡住能避免误触长按输入出天文数字
  if (next > 99) return
  quantity.value = next
}

function onSubmit() {
  emit('submit', quantity.value)
}

function onClose() {
  quantity.value = 1
  emit('update:visible', false)
}
</script>

<template>
  <div v-if="visible && product" class="sheet-mask" @click.self="onClose">
    <div class="sheet">
      <img v-if="product.cover" class="sheet__cover" :src="product.cover" :alt="product.name" />
      <div v-else class="sheet__cover sheet__cover--fallback">🎁</div>

      <h2 class="sheet__name">{{ product.name }}</h2>
      <p v-if="product.description" class="sheet__desc">{{ product.description }}</p>

      <div class="sheet__price">¥{{ formatMoney(product.price) }}</div>

      <div class="sheet__qty">
        <span>数量</span>
        <div class="stepper">
          <button class="stepper__btn" :disabled="quantity <= 1" @click="change(-1)">−</button>
          <span class="stepper__value">{{ quantity }}</span>
          <button class="stepper__btn" :disabled="quantity >= 99" @click="change(1)">+</button>
        </div>
      </div>

      <div class="sheet__total">
        <span>合计</span>
        <span class="sheet__total-value">¥{{ totalText }}</span>
      </div>

      <button
        class="btn btn-primary"
        :disabled="submitting || product.soldOut"
        @click="onSubmit"
      >
        {{ product.soldOut ? '已售罄' : submitting ? '下单中…' : '立即购买' }}
      </button>
      <button class="sheet__close" @click="onClose">再看看</button>
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
  padding: var(--sp-5) var(--sp-5) calc(var(--sp-5) + var(--safe-bottom));
  border-radius: var(--r-card) var(--r-card) 0 0;
  background: #fff;
  text-align: center;
}

.sheet__cover {
  width: 100%;
  height: 140px;
  object-fit: cover;
  border-radius: var(--r-btn);
  margin-bottom: var(--sp-3);
}

.sheet__cover--fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 40px;
  background: var(--c-primary-pale);
}

.sheet__name {
  font-size: 17px;
  font-weight: 600;
}

.sheet__desc {
  margin-top: var(--sp-2);
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-text-sub);
}

.sheet__price {
  margin: var(--sp-3) 0;
  font-size: 22px;
  font-weight: 600;
  color: var(--c-primary);
}

.sheet__qty,
.sheet__total {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--sp-3) 0;
  font-size: 14px;
}

.sheet__qty {
  border-top: 1px solid var(--c-border);
  border-bottom: 1px solid var(--c-border);
}

.sheet__total-value {
  font-size: 18px;
  font-weight: 600;
  color: var(--c-primary);
  font-variant-numeric: tabular-nums;
}

.stepper {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}

.stepper__btn {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--c-card);
  color: var(--c-text);
  font-size: 16px;
  line-height: 1;
}

.stepper__btn:disabled {
  opacity: 0.4;
}

.stepper__value {
  min-width: 24px;
  font-variant-numeric: tabular-nums;
}

.sheet__close {
  margin-top: var(--sp-3);
  width: 100%;
  font-size: 13px;
  color: var(--c-text-sub);
}
</style>
