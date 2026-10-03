<script setup>
/**
 * 计费规则（价目表）。
 *
 * <p>内容与 `docs/用户版计费与优惠说明.md` 一一对应 —— 那份文档的用途本来就写着
 * 「店内张贴、移动端『价格说明』页面文案」，这一页就是它的移动端落地。
 * **改计费规则时两处要一起改**，否则顾客在店里看到的价目表和手机上看到的对不上。
 *
 * <p>节序（2026-10-03 加免费活动那节时重排）：
 * <b>基础价格 → 免费活动 → 月度累计优惠 → 月卡 → 阶梯表 → 常见问题</b>。
 * 各节回答一个问题：多少钱一小时（含封顶与计费方式）/ 现在有没有正在进行的优惠 /
 * 常客能便宜多少 / 买卡划不划算 / 我这个时长要付多少。
 * **封顶与「前 N 分钟免费」这类说明都归基础价格节** —— 它们与价格同属基础信息，
 * 摆在一起顾客一次读全；阶梯表节只留表，供已经知道规则的人直接查金额。
 * ⚠️ 免费活动排在月度优惠之前：前者是「此刻就有」，后者是「攒够门槛之后」。
 * 而且它是本页唯一会变的内容（随运营排期），没有活动时整节不出现。
 *
 * <p>⚠️ <b>页面上的数字一个都不写死</b>：单价、封顶、宽限、优惠门槛全部来自
 * `GET /api/billing/rules`，月卡的卡种与价格来自现成的 `GET /api/cards/types`。
 * 前端抄一份等于把计费规则复制了一份，调价之后两处必然对不上 ——
 * 而那种不一致不会有任何报错，只是这页还挂着旧价格。
 *
 * <p>⚠️ 两个「7 元/小时」不要搞混：优惠后的<b>日间</b>价是 7 元/小时，
 * 而<b>夜间原价</b>也是 7 元/小时。优惠表里分行写，别让顾客以为日间没降价。
 */
import { ref, computed, onMounted } from 'vue'
import { getBillingRules } from '@/api/billing'
import { listCardTypes } from '@/api/card'
import { getFreePeriods } from '@/api/store'
import { toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'
import { formatDuration, formatMoney, formatTime, parseDateTime } from '@/utils/format'
import { periodPhaseOf } from '@/utils/labels'
import NavBar from '@/components/NavBar.vue'
import LoadingMask from '@/components/LoadingMask.vue'

const rules = ref(null)
const cardTypes = ref([])

/**
 * 近期免费活动（含正在进行的那一场）。没有活动时【整节不出现】。
 *
 * <p>它是本页唯一的「会变的」内容 —— 其余几节都是配置项算出来的固定规则，
 * 而这一节随运营排期变化，所以排在基础价格之后（见文件头的节序说明）。
 */
const freePeriods = ref([])
const loading = ref(true)

/**
 * 分钟数 → 表格里用的紧凑写法（「4 小时 36 分」）。
 *
 * <p>⚠️ 刻意不用 `utils/format.js` 的 `formatDuration`（它给的是「4 小时 36 分钟」）：
 * 阶梯表是三分栏，时长那一列宽约 99px，多出来的那个「钟」字会把列挤到换行。
 * 这是【展示层】的取舍，不影响别处 —— 结账页与订单详情仍用 formatDuration。
 *
 * @param {number} minutes 分钟数
 * @returns {string} 形如 "36 分" / "1 小时 6 分"
 */
function shortDuration(minutes) {
  const m = Number(minutes) || 0
  if (m < 60) return `${m} 分`
  const h = Math.floor(m / 60)
  const rest = m % 60
  return rest === 0 ? `${h} 小时` : `${h} 小时 ${rest} 分`
}

/**
 * 价格阶梯表。
 *
 * <p>⚠️ <b>由数据生成，不写死那 11 行</b>：第 n 档的起点是
 * 「宽限 + (n−1) × 档位 + 1」（默认就是 6、36、66… 分钟），金额是 n × 单价。
 * 调价或改宽限之后，表格跟着变。
 *
 * <p>第 10 档起金额不再增长（四组封顶都等于 10 档 × 单价），所以只列到第 10 档，
 * 那一行标「封顶」。
 */
const tiers = computed(() => {
  const r = rules.value
  if (!r) return []
  const rows = []
  for (let n = 1; n <= 10; n++) {
    rows.push({
      n,
      from: r.graceMinutes + (n - 1) * r.unitMinutes + 1,
      day: n * Number(r.dayUnitPrice),
      night: n * Number(r.nightUnitPrice),
      capped: n === 10
    })
  }
  return rows
})

/**
 * 达到封顶所需的时长（分钟）—— 第 10 档的起点。
 *
 * <p>⚠️ 不写死 276（= 5 + 270 + 1）：那是默认配置算出来的，
 * 改宽限或档位时长它就会变。
 */
const capMinutes = computed(() => {
  const r = rules.value
  return r ? r.graceMinutes + 9 * r.unitMinutes + 1 : 0
})

/**
 * 档位边界上的那个分钟数（默认 6）。
 *
 * <p>⚠️ 边界是【从进场时刻起算】的：进场后第 6、36、66… 分钟各进一档，
 * <b>不是「每小时的 :06」</b> —— 只有整点进场时两者才恰好重合。
 */
const boundaryMinute = computed(() => (rules.value ? rules.value.graceMinutes + 1 : 0))

/**
 * 「计费容错」的说明文案（「35 分钟算 30 分钟、36 分钟算 1 小时、…」）。
 *
 * <p>规律：**边界时长（档位 + 容错）仍按整数档计，再多 1 分钟就跳下一档** ——
 * 所以第 n 档的边界是 {@code 容错 + n × 档位}，它 +1 就进第 n+1 档。
 *
 * <p>⚠️ <b>四个数字全部由配置算出，不是写死的 35 / 36 / 65 / 66</b>：
 * 与页面上其它数字同一条纪律 —— 档位时长或容错一改，例子跟着变。
 *
 * <p>用 {@code formatDuration}（「1 小时 5 分钟」）而不是 {@code shortDuration}
 *（「1 小时 5 分」）：这一处是段落文字，横向空间够，说全比省字重要。
 */
const toleranceText = computed(() => {
  const r = rules.value
  if (!r) return ''
  const u = r.unitMinutes
  const g = r.graceMinutes
  return [
    [g + u, u], // 35 分钟算 30 分钟
    [g + u + 1, u * 2], // 36 分钟算 1 小时
    [g + u * 2, u * 2], // 1 小时 5 分钟算 1 小时
    [g + u * 2 + 1, u * 3] // 1 小时 6 分钟算 1 小时 30 分钟
  ]
    .map(([used, charged]) => `${formatDuration(used)}算 ${formatDuration(charged)}`)
    .join('、')
})

/**
 * 一场活动的时段文案。
 *
 * <p>跨天的写成「次日 HH:mm」，而不是把两个日期都列出来 ——
 * 一场活动最长也就跨一天，列两个完整日期反而要多花一眼去比对。
 *
 * @param {object} item 活动
 * @returns {string} 形如 "12 月 31 日 20:00 – 次日 02:00"
 */
function periodLabel(item) {
  const start = parseDateTime(item.startAt)
  const end = parseDateTime(item.endAt)
  if (!start || !end) return `${item.startAt || ''} – ${item.endAt || ''}`
  const head = `${start.getMonth() + 1} 月 ${start.getDate()} 日 ${formatTime(item.startAt)}`
  const sameDay = start.toDateString() === end.toDateString()
  return `${head} – ${sameDay ? formatTime(item.endAt) : `次日 ${formatTime(item.endAt)}`}`
}

async function load() {
  loading.value = true
  // 三路并发；月卡与活动那两路挂了只影响各自那一节，不该让整页失败
  const [rulesRes, cardsRes, freeRes] = await Promise.allSettled([
    getBillingRules(),
    listCardTypes(),
    getFreePeriods(3)
  ])
  if (rulesRes.status === 'fulfilled') {
    rules.value = rulesRes.value.data
  } else {
    toastError(errorMessage(rulesRes.reason, '加载失败'))
  }
  if (cardsRes.status === 'fulfilled') cardTypes.value = cardsRes.value.data || []
  if (freeRes.status === 'fulfilled') freePeriods.value = freeRes.value.data || []
  loading.value = false
}

onMounted(load)
</script>

<template>
  <div class="page">
    <NavBar title="计费规则" />

    <LoadingMask :loading="loading" />

    <template v-if="rules">
      <!-- 一、基础价格。⚠️ 封顶与单价同表 —— 它是「一小时多少钱」的另一半（上限），不单开一节 -->
      <div class="card">
        <div class="card-title">基础价格</div>
        <table class="pricing__table">
          <thead>
            <tr>
              <th>时段</th>
              <th>时间</th>
              <th>价格</th>
              <th>封顶</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>日间</td>
              <td>{{ rules.dayStart }} – {{ rules.dayEnd }}</td>
              <td>{{ formatMoney(rules.dayPricePerHour) }} 元/小时</td>
              <td>{{ formatMoney(rules.dayCap) }} 元</td>
            </tr>
            <tr>
              <td>夜间</td>
              <td>{{ rules.dayEnd }} – 次日 {{ rules.dayStart }}</td>
              <td>{{ formatMoney(rules.nightPricePerHour) }} 元/小时</td>
              <td>{{ formatMoney(rules.nightCap) }} 元</td>
            </tr>
          </tbody>
        </table>
        <p class="pricing__note">夜间更便宜。从日间玩到夜间，前后两段分别按各自的价格算。</p>
        <p class="pricing__note">
          进场后<strong>前 {{ rules.graceMinutes }} 分钟免费</strong>，从第
          {{ boundaryMinute }} 分钟起，每满 {{ rules.unitMinutes }} 分钟计一次。
        </p>
        <p class="pricing__note">
          小提示：时长为 {{ rules.unitMinutes }} 分钟的整数倍时，有
          {{ rules.graceMinutes }} 分钟计费容错 —— 即 {{ toleranceText }}。
        </p>
        <p class="pricing__note">
          封顶之后<strong>当前时段</strong>不再跳费（{{ formatDuration(capMinutes) }} 封顶）。
        </p>
      </div>

      <!--
        二、免费活动。⚠️ 排在这里（基础价格之后、月度累计优惠之前）：
        它是「此刻正在进行」的优惠 —— 正在店里、或正准备来的人最该看见；
        而月度累计优惠是「这个月攒够门槛之后」的事，属于下一层信息。
        没有活动时【整节不出现】，而不是留一句「暂无活动」——
        那会让人以为这家店从来不办活动。
      -->
      <div v-if="freePeriods.length" class="card">
        <div class="card-title">免费活动</div>
        <p class="pricing__lead">活动期间<strong>所有时长都不计费</strong>，进来玩就行。</p>
        <div v-for="item in freePeriods" :key="item.id" class="pricing__freebie">
          <span
            v-if="periodPhaseOf(item.startAt, item.endAt).ongoing"
            class="pricing__freebie-live"
          >
            进行中
          </span>
          <span class="pricing__freebie-time">{{ periodLabel(item) }}</span>
          <span v-if="item.reason" class="pricing__freebie-name">{{ item.reason }}</span>
        </div>
      </div>

      <!-- 三、月度累计优惠 -->
      <div class="card" v-if="rules.discountEnabled">
        <div class="card-title">月度累计优惠</div>
        <p class="pricing__lead">
          每个自然月内，累计消费满
          <strong>{{ formatMoney(rules.discountThreshold) }} 元</strong>后，
          当月之后下的单都降价：
        </p>
        <table class="pricing__table">
          <thead>
            <tr>
              <th>时段</th>
              <th>原价 → 优惠价</th>
              <th>原封顶 → 封顶</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>日间</td>
              <td>
                {{ formatMoney(rules.dayPricePerHour) }} →
                {{ formatMoney(rules.discountDayPricePerHour) }} 元/小时
              </td>
              <td>
                {{ formatMoney(rules.dayCap) }} →
                {{ formatMoney(rules.discountDayCap) }} 元
              </td>
            </tr>
            <tr>
              <td>夜间</td>
              <td>
                {{ formatMoney(rules.nightPricePerHour) }} →
                {{ formatMoney(rules.discountNightPricePerHour) }} 元/小时
              </td>
              <td>
                {{ formatMoney(rules.nightCap) }} →
                {{ formatMoney(rules.discountNightCap) }} 元
              </td>
            </tr>
          </tbody>
        </table>
        <ul class="pricing__list">
          <li>每月 1 日重新开始累计，上个月的不带入下个月</li>
          <li>只有<strong>已付款</strong>的消费才计入累计</li>
          <li>
            达标后<strong>从下一次订单开始</strong>自动按优惠价算，不需要领券或操作
            <div class="pricing__note">刚好凑满门槛的那一单仍是原价 —— 它不计入自己的累计</div>
          </li>
          <li>已经结算过的订单不追溯退差价</li>
        </ul>
      </div>

      <!-- 三、月卡（数据来自 GET /api/cards/types，价格归模块 9 管） -->
      <div class="card" v-if="cardTypes.length">
        <div class="card-title">月卡</div>
        <table class="pricing__table">
          <thead>
            <tr>
              <th>卡种</th>
              <th>价格</th>
              <th>有效期</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in cardTypes" :key="c.cardType">
              <td>
                {{ c.label }}
                <div class="pricing__sub">{{ c.periodText }}</div>
              </td>
              <td>{{ formatMoney(c.price) }} 元</td>
              <td>{{ c.validDays }} 天</td>
            </tr>
          </tbody>
        </table>
        <ul class="pricing__list">
          <li>有效期内使用<strong>免费</strong>，下单时自动识别，不需要每次出示</li>
          <li>夜间月卡在日间时段的使用照常计费</li>
          <li>月卡与「满 {{ formatMoney(rules.discountThreshold) }} 元优惠」不叠加，因为月卡期间本来就免费</li>
          <li>有效期从<strong>购买当天</strong>起算，含购买当日</li>
          <li>
            <strong>免费只在有效期内</strong>：卡的最后一天玩过了零点，零点之后的部分照常计费；
            生效当天零点之前的部分同理
          </li>
        </ul>
      </div>

      <!--
        四、阶梯表。
        ⚠️ 「前 N 分钟免费」「每满 N 分钟计一次」「:0N 之前结算按上一档」那三句
        在【基础价格】节里 —— 它们讲的是**计费方式**，与价格同属基础信息，
        摆在一起顾客一次读全；这里只留表，供已经知道规则的人直接查金额。
      -->
      <div class="card">
        <div class="card-title">阶梯表</div>
        <p class="pricing__lead">每一档的金额如下：</p>

        <!--
          ⚠️ 时长列只写【档位起点 +「起」】，不写区间。
          两个理由：区间写法（「1 小时 6 分 – 1 小时 35 分」）在手机的 99px 列宽里
          会折成两行；而对顾客来说，「36 分起 8 元」比「36 分到 1 小时 5 分之间 8 元」
          更直接地回答了「玩多久要多少钱」—— 下一行的起点就是上一行的终点。
        -->
        <table class="pricing__table">
          <thead>
            <tr>
              <th>使用时长</th>
              <th>日间</th>
              <th>夜间</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>{{ rules.graceMinutes }} 分钟以内</td>
              <td>免费</td>
              <td>免费</td>
            </tr>
            <tr v-for="t in tiers" :key="t.n">
              <td>{{ shortDuration(t.from) }}起</td>
              <td>
                {{ formatMoney(t.day) }} 元<template v-if="t.capped">（封顶）</template>
              </td>
              <td>
                {{ formatMoney(t.night) }} 元<template v-if="t.capped">（封顶）</template>
              </td>
            </tr>
          </tbody>
        </table>

        <p class="pricing__note">
          举例：日间玩 1 小时 {{ formatMoney(rules.dayPricePerHour) }} 元；
          夜间玩 1 小时 {{ formatMoney(rules.nightPricePerHour) }} 元。
        </p>
      </div>

      <!-- 五、常见问题 -->
      <div class="card">
        <div class="card-title">常见问题</div>
        <dl class="pricing__faq">
          <dt>只玩了 {{ rules.graceMinutes }} 分钟就出来了，要付钱吗？</dt>
          <dd>不用，{{ rules.graceMinutes }} 分钟以内免费。</dd>

          <dt>怎么知道我这个月消费了多少？</dt>
          <dd>「我的」页面会显示本月累计消费与当前适用的价格。</dd>

          <dt>为什么跨时段的时候，价格和我按总时长估的不一样？</dt>
          <dd>
            日间与夜间是分开计算的，两段各自都有一次 {{ rules.graceMinutes }} 分钟免费，
            所以和直接按总时长算会有小额出入。
          </dd>

          <dt>月卡最后一天，我玩到过了零点，怎么算？</dt>
          <dd>
            零点之前的部分免费，零点之后照常计费（夜间
            {{ formatMoney(rules.nightUnitPrice) }} 元 / {{ rules.unitMinutes }} 分钟）。
            卡的生效当天同理：零点之前还没生效，那部分要正常付费。
          </dd>
        </dl>
      </div>
    </template>
  </div>
</template>

<style scoped>
.pricing__lead {
  font-size: 13px;
  line-height: 1.7;
  color: var(--c-text-sub);
}

.pricing__note {
  margin-top: var(--sp-2);
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-text-muted);
}

/*
 * 免费活动那一节的行。与首页那张卡同形（一行时段 + 可选徽章），
 * 但这里是「规则页」的语境，跟着 .pricing__* 这一套走。
 */
.pricing__freebie {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
  font-size: 13px;
}

.pricing__freebie + .pricing__freebie {
  padding-top: var(--sp-2);
  border-top: 1px solid var(--c-border);
}

/* 「进行中」用主色 —— 它是好消息（此刻进来不要钱），与包场时间表那个橙色警示相反 */
.pricing__freebie-live {
  flex-shrink: 0;
  padding: 1px var(--sp-2);
  border-radius: var(--r-pill);
  background: var(--c-primary);
  color: #fff;
  font-size: 11px;
}

.pricing__freebie-time {
  font-variant-numeric: tabular-nums;
}

.pricing__freebie-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 12px;
  color: var(--c-text-sub);
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pricing__list {
  margin-top: var(--sp-3);
  font-size: 12px;
  line-height: 1.8;
  color: var(--c-text-sub);
}

.pricing__list li::before {
  content: '·';
  margin-right: var(--sp-2);
  color: var(--c-text-muted);
}

/*
 * 表格。⚠️ 字号 12px 且内边距收紧：内容宽只有 296px，
 * 而基础价格表有四列（时段 / 时间 / 价格 / 封顶）—— 按正文尺寸会换行。
 */
.pricing__table {
  width: 100%;
  margin-top: var(--sp-3);
  border-collapse: collapse;
  font-size: 12px;
}

.pricing__table th,
.pricing__table td {
  padding: var(--sp-2) 2px;
  text-align: left;
  border-bottom: 1px solid var(--c-border);
  font-weight: 400;
}

.pricing__table th {
  color: var(--c-text-muted);
  font-size: 11px;
}

.pricing__table td {
  color: var(--c-text);
}

/* 数字列靠右对齐，位数不同的金额才比得出来 */
.pricing__table th:not(:first-child),
.pricing__table td:not(:first-child) {
  text-align: right;
  white-space: nowrap;
}

.pricing__table tbody tr:last-child td {
  border-bottom: none;
}

/* 月卡那一节：卡种下面跟一行适用时段的小字 */
.pricing__sub {
  margin-top: 2px;
  font-size: 11px;
  color: var(--c-text-muted);
}

.pricing__faq {
  margin-top: var(--sp-3);
}

.pricing__faq dt {
  margin-top: var(--sp-3);
  font-size: 13px;
  color: var(--c-text);
}

.pricing__faq dt:first-child {
  margin-top: 0;
}

.pricing__faq dd {
  margin: var(--sp-1) 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-text-sub);
}
</style>
