<script setup>
/**
 * 用户卡片（banner / 头像与标签 / 信息行 / 偏好，四行堆叠）。
 *
 * <p>「在店用户」列表与「我的」页面<b>共用这一个组件</b> ——
 * 两个页面的卡片长得一样，只有第三行的信息不同（在店列表显示到店时刻与在店时长，
 * 我的页面显示消费与时长统计），所以第三行做成具名插槽 {@code #info}。
 *
 * <p><b>六行结构（2026-10-03 定稿；2026-10-10 调过顺序）</b>：
 * <ol>
 *   <li>自定义 banner（3:1）—— <b>右侧不叠任何东西</b>：店里是音游机，
 *       顾客传的多半是舞萌 DX 姓名框素材，右侧往往是段位、Rating 这些要看的内容</li>
 *   <li>头像 · STAFF · 昵称</li>
 *   <li>游玩偏好的文字标签</li>
 *   <li>月卡标签 —— 紧跟偏好（2026-10-10 由用户要求，从卡片最下面挪上来）</li>
 *   <li>到店时刻 —— 由调用方放进插槽（跨天时带「昨天 / 前天 / 几月几日」，
 *       见 {@code utils/format.js} 的 {@code formatArrivalTime}）</li>
 *   <li>在店时长 —— 同上，<b>两段各占一行、放在卡片底部左对齐</b>
 *      （2026-10-10 由用户要求：从名字下面挪到底部；当天试过右对齐又改回左对齐）</li>
 * </ol>
 *
 * <p>⚠️ <b>偏好与月卡这两行「没内容也占位」</b>（CSS 用 min-height 撑住）：
 * 卡片在名册里是并排的，有的一行有、有的没有，同一行的卡片就会高矮不一，
 * 而名册要的正是「扫一眼就能比」。代价是没卡没偏好的人，卡片里有一段空着。
 *
 * <p>⚠️ 月卡不挤进头像那一行是刻意的：半宽卡片一行放不下
 * 「头像 + 月卡 + STAFF + 昵称」，挤在一起的结果是昵称只剩一两个字。
 *
 * <p>⚠️ 素材区的 3:1 与 {@code utils/image.js} 的 BANNER_HEIGHT（背景图成品
 * 的裁剪比例）必须一致；{@code object-position: right center} 与那里的
 * 「超宽图保留右半」也是同一条规则 —— <b>三处改一处就要一起改</b>。
 */
import { ref, watch, computed } from 'vue'
import { versionedUrl } from '@/utils/image'

const props = defineProps({
  /** 用户对象：至少含 nickname、avatar、banner */
  user: { type: Object, default: () => ({}) },
  /** 月卡类型：ALL_DAY / NIGHT，未持卡为 null（决定第二行的月卡标签渲不渲染） */
  cardType: { type: String, default: null },
  /** 月卡类型的中文名（后端返回，不要在前端硬编码文案） */
  cardTypeLabel: { type: String, default: '' },
  /**
   * 游玩偏好的中文名数组，显示在第四行（每个偏好一个胶囊标签）。
   *
   * <p>传的是<b>已经翻好的中文名</b>：偏好 code → 中文名的映射要用到设备类型字典，
   * 那是调用方的事，卡片组件不认识设备包。
   * 由 {@code utils/labels.js} 的 {@code preferenceLabels} 统一生成
   *（含「字典里查不到就原样显示 code」的兜底）。
   */
  tags: { type: Array, default: () => [] },
  /**
   * 尺寸档。
   *
   * <ul>
   *   <li>{@code compact}（默认）—— 半宽卡片用（「在店用户」名册，约 160px）</li>
   *   <li>{@code roomy} —— 整列宽的卡片用（「我的」页面，约 328px）：
   *       同一套小字号放进整列宽的卡片里会显得空</li>
   * </ul>
   *
   * <p>⚠️ <b>用 props 而不是媒体查询</b>：两种卡片宽度在<b>同一个视口</b>下共存
   *（「我的」的 328px 与在店名册的 160px 都是手机布局），视口级的断点分不开它们 ——
   * 早先那版「宽屏放大字号」正是栽在这里：它按视口放大，
   * 而视口宽的那一端（在店名册）卡片反而更窄。
   */
  size: { type: String, default: 'compact' }
})

/** banner 加载失败标记。用户传的图后来被删了、或网络抖动都会走到这里。 */
const bannerFailed = ref(false)
/** 头像加载失败标记。 */
const avatarFailed = ref(false)

/**
 * 图片地址变了要重置失败标记。
 *
 * <p>不重置的话，一次加载失败之后即使换了新图也永远显示兜底 ——
 * 用户会以为自己传的图没生效，而这个状态<b>刷新页面才会好</b>。
 *
 * <p>⚠️ 盯的是 {@code versionedUrl(...)} 而不是原始的 {@code props.user.banner}：
 * 文件名固定，重传同格式时库里那个路径<b>根本没变</b>，
 * 变的是我们挂上去的版本参数。盯原值的话这条 watch 永远不会触发。
 */
watch(
  () => versionedUrl(props.user?.banner),
  () => {
    bannerFailed.value = false
  }
)

watch(
  () => versionedUrl(props.user?.avatar),
  () => {
    avatarFailed.value = false
  }
)

const banner = computed(() => props.user?.banner || '')
const avatar = computed(() => props.user?.avatar || '')
const name = computed(() => props.user?.nickname || props.user?.username || '顾客')

/**
 * 是不是店员。
 *
 * <p>昵称前会挂一个 STAFF 徽章，让顾客一眼看出「这人是店里的」——
 * 在店名册里尤其有用：同一张卡片上既有顾客也有店员，
 * 没有标识的话分不清谁是来玩的、谁是来盯店的。
 *
 * <p>取不到 {@code role} 时（如旧版在店名册接口没有这个字段）不显示，
 * 而不是猜一个。
 */
const isStaff = computed(() => props.user?.role === 'ADMIN')

/**
 * 头像与 banner 兜底用的首字母。
 *
 * <p>用文字而不是默认头像图片 —— 少一次网络请求，而且不同用户有区分度。
 */
const avatarInitial = computed(() => name.value.slice(0, 1))

const showBannerImage = computed(() => !!banner.value && !bannerFailed.value)
const showAvatarImage = computed(() => !!avatar.value && !avatarFailed.value)

/**
 * 月卡标签的中文。
 *
 * <p>优先用后端返回的 {@code cardTypeLabel}，这里的兜底文案只在后端没给时才生效。
 * 认不出的卡种回落到中性的「月卡」—— 与 {@code labels.js} 那些
 * 「认不出就原样显示」的做法不同，是因为这是一枚要挤进昵称前的胶囊，
 * 露出 code（如 "WEEKEND"）更长也更看不懂。
 */
const FALLBACK_LABELS = { ALL_DAY: '全天月卡', NIGHT: '夜间月卡' }
const cardLabel = computed(() => {
  if (!props.cardType) return ''
  return props.cardTypeLabel || FALLBACK_LABELS[props.cardType] || '月卡'
})

/**
 * 第四行最多显示几个偏好标签，超出的收成「+N」。
 *
 * <p>取 3 是沿用当初的估算：那会儿字典是三条三字标签（拍拍机 / 抬手乐 / 日麻），
 * 3 个按收紧后的内边距约 131px、半宽卡片内容宽约 136px，恰好排得下。
 * ⚠️ 2026-10-10 字典改版后是四条**单字**标签（击 / 中 / 萌 / 雀），
 * 空间比当初宽松得多 —— 但上限仍是 3：**用户选满四个时第 4 个会收成「+1」**，
 * 这是该上限第一次真正生效的场景（`.tag-more` 那个样式就是为它准备的）。
 */
const MAX_TAGS = 3

/** 实际渲染的偏好标签。 */
const visibleTags = computed(() => props.tags.slice(0, MAX_TAGS))

/** 被收起来的标签数量。 */
const overflowCount = computed(() => Math.max(0, props.tags.length - visibleTags.value.length))
</script>

<template>
  <div class="user-card" :class="{ 'user-card--roomy': size === 'roomy' }">
    <!--
      第一行：素材区。banner 未设置、或加载失败时回落到纯色底 + 一个淡的首字母 ——
      绝不能是破图。两种失败用同一个 :class 处理：用户不需要知道
      「你是没传」还是「传了但加载失败」。
    -->
    <img
      v-if="showBannerImage"
      class="user-card__banner"
      :src="versionedUrl(banner)"
      alt=""
      @error="bannerFailed = true"
    />
    <div v-else class="user-card__banner user-card__banner--fallback">
      <span class="user-card__banner-initial">{{ avatarInitial }}</span>
    </div>

    <!-- 第二行：头像 · STAFF · 昵称 -->
    <div class="user-card__identity">
      <img
        v-if="showAvatarImage"
        class="user-card__avatar"
        :src="versionedUrl(avatar)"
        alt=""
        @error="avatarFailed = true"
      />
      <div v-else class="user-card__avatar user-card__avatar--fallback">
        {{ avatarInitial }}
      </div>

      <span v-if="isStaff" class="badge-staff">STAFF</span>
      <span class="user-card__name">{{ name }}</span>
    </div>

    <!--
      第三行：偏好标签。**没设偏好也占这一行**（只渲染一个空容器）——
      卡片在名册里是并排的，有的一行有、有的没有，同一行的卡片就会高矮不一，
      而名册要的正是「扫一眼就能比」。高度由 CSS 的 min-height 撑住。
    -->
    <div class="user-card__tags">
      <span v-for="t in visibleTags" :key="t" class="tag">{{ t }}</span>
      <span v-if="overflowCount" class="tag tag-more">+{{ overflowCount }}</span>
    </div>

    <!--
      第四行：月卡标签 —— 紧跟昵称与偏好
      （2026-10-10 由用户要求，从卡片最下面挪上来）。同样**没持卡也占这一行**。
    -->
    <div class="user-card__card-row">
      <span v-if="cardLabel" class="tag tag-success">{{ cardLabel }}</span>
    </div>

    <!--
      第五、六行：调用方给的两段信息，**各占一行、放在卡片底部（左对齐）**
      （2026-10-10 由用户要求：从名字下面挪到底部；先试过右对齐，改回左对齐）——
      在店列表是「到店时刻 / 在店时长」，我的页面是「累计消费 / 累计时长」。
    -->
    <div class="user-card__foot">
      <slot name="info" />
    </div>
  </div>
</template>

<style scoped>
.user-card {
  /*
   * 宽度由容器决定：在店名册里是栅格单元（约 160px），「我的」页面里是整行。
   *
   * ⚠️ 原来这里写的是 width: min(90vw, 100%) —— 90vw 是【视口】单位，
   * 放进栅格就会大过单元本身（一屏宽 390px 时 90vw = 351px，而单元只有 175px），
   * 卡片会撑破栅格、叠在一起。改由容器定宽之后两边都对。
   */
  width: 100%;
  /* 半宽卡片下 --r-card 的 16px 显得过圆，收一档 */
  border-radius: 12px;
  overflow: hidden;
  background: var(--c-card);
}

/* ---------- 第一行：素材区 ---------- */

.user-card__banner {
  display: block;
  width: 100%;
  /*
   * ⚠️ 3:1 —— 与背景图成品（utils/image.js 的 BANNER_HEIGHT）是同一个数，
   * 改一个必须一起改。图本身也是 3:1，这里的比例给兜底与旧数据钉形状用
   *（万一某张图不是 3:1）。
   */
  aspect-ratio: 3 / 1;
  /*
   * ⚠️ cover 是三种裁切方式里唯一合理的：
   * 用户传的图比例不会正好合上卡片，contain 会留白、拉伸会变形。
   *
   * ⚠️ object-position: right center 是【刻意的】，不是默认值 ——
   * 与上传裁剪的「超宽图保留右半」（utils/image.js 的 processBanner）
   * 是同一条规则。少了它，cover 会按默认的居中裁，改版前传的老图
   *（6:1 成品）在卡片上裁出的就是中间一段，与新图对不上。
   */
  object-fit: cover;
  object-position: right center;
}

.user-card__banner--fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(120deg, var(--c-primary-pale), var(--c-icon-bg));
}

/*
 * 没传背景图时，素材区正中放一个淡的大首字母 —— 空着一块纯色比有内容的更显突兀。
 *
 * ⚠️ 用 opacity 而不是把主题色调淡：那是另一处写死的颜色，
 * 换配色时要多改一个地方，而这里要的本来就是「整体透明一点」。
 */
.user-card__banner-initial {
  font-size: 24px;
  font-weight: 600;
  color: var(--c-primary);
  opacity: 0.3;
}

/* ---------- 第四行：月卡 ---------- */

/*
 * 月卡标签独占一行，紧跟昵称与偏好
 *（2026-10-10 由用户要求，从卡片最下面挪上来）。
 *
 * ⚠️ 不挤进头像那一行是刻意的：它是顾客之间会看的信息（谁今天免单），
 * 而半宽卡片一行放不下「头像 + 月卡 + STAFF + 昵称」—— 挤在一起的结果
 * 是昵称只剩一两个字。分开之后两边都完整。
 *
 * ⚠️ 没持卡时这一行【仍然占位】（min-height 撑住）：
 * 卡片在名册里是并排的，高矮不一会让整个栅格看起来参差。
 * 高度构成 = 标签本身 20px（base.css 的 .tag）+ 上内边距 6px
 *（上内边距与偏好那一行同款，两行标签的垂直节奏才一致；它现在是
 *  「跟在偏好后面」而不是「收尾」，所以留白从上边走，不再从下边走）。
 */
.user-card__card-row {
  display: flex;
  padding: 6px var(--sp-2) 0;
  min-height: calc(20px + 6px);
}

/* ---------- 第二行：头像 · STAFF · 昵称 ---------- */

.user-card__identity {
  display: flex;
  align-items: center;
  gap: 5px;
  /* 左右 8px（--sp-2）比别处的 12px 紧一档：半宽卡片本来就只有一百多像素宽，
     让给内容比让给留白值 */
  padding: var(--sp-2) var(--sp-2) 0;
  min-width: 0;
}

.user-card__avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  object-fit: cover;
  background: var(--c-icon-bg);
  /* 描一圈白边，让它从卡片底色上「浮」起来 */
  border: 2px solid #fff;
  box-shadow: 0 1px 4px rgba(43, 35, 64, 0.12);
  flex-shrink: 0;
}

.user-card__avatar--fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-primary);
}

/*
 * STAFF 徽章。
 *
 * ⚠️ 绿底用的是一个【比 --c-success 浅】的独立色值，没有复用主题变量 ——
 * 主题那个绿（#4CAF7D）配白字偏暗、配深灰字又发闷，这里要的是「浅绿 + 白字」
 * 那种轻快感。代价是它比主题绿浅，所以字重给到 700、
 * 全大写加字距，靠字形本身撑住辨识度。
 */
.badge-staff {
  flex-shrink: 0;
  height: 14px;
  padding: 0 4px;
  border-radius: 4px;
  background: #5fc08f;
  color: #fff;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.3px;
  line-height: 14px;
}

.user-card__name {
  /*
   * flex: 1 1 auto —— 昵称吃掉剩余宽度（截断发生在昵称上，而不是把
   * 标签或徽章挤变形）；min-width: 0 才允许它真的缩到省略号。
   */
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
  /* 昵称过长时截断，不要撑破卡片 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------- 第五、六行（卡片底部）：到店 / 在店 ---------- */

.user-card__foot {
  display: flex;
  /* 每段各占一行：「到店」一行、「在店」一行 —— 每段因此都能用回 11px，
     不必为了塞进一行而降字号（那是「两段挤一行」时的妥协） */
  flex-direction: column;
  gap: 1px;
  /*
   * ⚠️ 位置挪到了卡片最后一行（2026-10-10 由用户要求，原来在名字下面）——
   * 上边留白 4px 与月卡隔开，下边 8px 收尾。
   * 对齐保持左对齐：当天先试过右对齐，用户看过之后要求改回左对齐。
   */
  padding: var(--sp-1) var(--sp-2) var(--sp-2);
  /*
   * ⚠️ 2026-10-10 由用户要求加深加粗（--c-text-sub → --c-text、700）——
   * 与群里的名册图同步：那边这两行从灰色（0x8A9099）改成了纯黑加粗。
   * 用 --c-text（#2b2340）而不是纯黑：它就是这个设计系统里「最深的文字色」，
   * 网页端不该为这一处破例。
   * 字号维持 11px 不动 —— 同步的是颜色与字重，而两段各占一行的排法
   * 正是为 11px 留的（见上面）。roomy 档（「我的」页）只覆写 padding 与字号，
   * 颜色与字重跟着这里走，是刻意的（同一个组件、同一个观感）。
   */
  color: var(--c-text);
  font-size: 11px;
  font-weight: 700;
  line-height: 1.35;
}

/* 段内不折行：一行就是一段，别把「2 小时 / 15 分钟」拆开 */
.user-card__foot > * {
  white-space: nowrap;
}

/* ---------- 尺寸档：整列宽的卡片（「我的」页面，约 328px） ---------- */

/*
 * ⚠️ 为什么不用媒体查询：两种卡片宽度在【同一个视口】下共存 ——
 * 「我的」的 328px 与在店名册的 160px 都是手机布局，视口级的断点分不开它们。
 *（早先那版「宽屏放大字号」正是栽在这里：它按视口放大，
 * 而视口宽的那一端也就是在店名册，卡片反而更窄。）
 *
 * ⚠️ 放大幅度【不是等比例的】：同一个小字号放进 328px 的卡片里显得空，
 * 但按 328 ÷ 160 的倍率放大又会大得离谱 —— 取「比正文大一档」为止。
 *
 * ⚠️ 与 padding / min-height 是【一对】：留空行的高度由 padding 累加而来，
 * 改了 padding 不改 min-height，有内容与没内容的卡片就会差几个像素。
 */
.user-card--roomy .user-card__banner-initial {
  font-size: 40px;
}

.user-card--roomy .user-card__identity {
  gap: var(--sp-2);
  padding: var(--sp-3) var(--sp-4) 0;
}

.user-card--roomy .user-card__avatar {
  width: 44px;
  height: 44px;
}

.user-card--roomy .user-card__avatar--fallback {
  font-size: 18px;
}

.user-card--roomy .badge-staff {
  height: 16px;
  font-size: 10px;
  line-height: 16px;
}

.user-card--roomy .user-card__name {
  font-size: 16px;
}

.user-card--roomy .user-card__tags {
  gap: var(--sp-2);
  padding: var(--sp-2) var(--sp-4) 0;
  /* 22px 跟的是 .user-card__tags .tag 的高度（2026-10-10 放大加粗时从 20 提上来的） */
  min-height: calc(22px + var(--sp-2));
}

.user-card--roomy .user-card__tags .tag {
  padding: 0 var(--sp-2);
  font-size: 12px;
}

.user-card--roomy .user-card__foot {
  /* ⚠️ 它现在收尾，底部留白从这里给（原来由月卡那一行收尾） */
  padding: var(--sp-2) var(--sp-4) var(--sp-3);
  font-size: 13px;
}

.user-card--roomy .user-card__card-row {
  /* 与 roomy 的偏好那一行同款：留白从上边走（它现在是「跟在偏好后面」而非收尾） */
  padding: var(--sp-2) var(--sp-4) 0;
  min-height: calc(20px + var(--sp-2));
}

/* ---------- 第四行：偏好标签 ---------- */

/*
 * ⚠️ 标签的左右内边距收到 4px（通用 .tag 是 8px）：半宽卡片的内容宽
 * 只有约 144px，而当年那三个三字标签（拍拍机 + 抬手乐 + 日麻）按通用内边距
 * 要 147px —— 正好放不下。收到 4px 后约 123px，三个标签能排成一行还有余量。
 * ⚠️ 2026-10-10 字典改版后标签都是单字（击 / 中 / 萌 / 雀），空间宽松了不少，
 * 但这段收紧保留 —— 类型名随时可能再加长。
 *
 * flex-wrap 是兜底：更窄的屏（320px）上仍会换行 —— 那是可接受的，
 * 换行只是卡片高一点，而裁掉标签会真的丢信息。
 */
.user-card__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 6px var(--sp-2) 0;
  /*
   * ⚠️ 没设偏好时这一行【仍然占位】（见模板注释）：与月卡那一行同理，
   * 撑住高度是为了让同一行的卡片等高。高度 = 标签 22px + 上内边距 6px。
   * ⚠️ 22px 与 .user-card__tags .tag 的高度是【一对】，改一处要改另一处。
   */
  min-height: calc(22px + 6px);
}

.user-card__tags .tag {
  /* 胶囊本身不压缩：压了会变形，宁可换行 */
  flex-shrink: 0;
  padding: 0 4px;
  /*
   * ⚠️ 2026-10-10 由用户要求放大加粗（11px / 500 → 12px / 700），
   * 与群里的名册图同步（那边偏好胶囊也从 18 号常规调成了 20 号粗体）。
   *
   * 空间是算过的：⚠️ 2026-10-10 字典改版后标签都是单字（击 / 中 / 萌 / 雀），
   * 三个加起来约 60px、选满四个也不到 80px，而这一行的内容宽约 160px
   *（在店列表卡片 176px − 左右各 8px）—— 放得下、不会换行。
   *（改版前是三个三字标签、约 140px，当时也放得下。）
   * ⚠️ 改字号前先照着算一遍：这一行是 flex-wrap，放不下就会换行，
   * 而并排卡片一旦不等高，名册「扫一眼就能比」就没了。
   *
   * ⚠️ 高度 22px 与下面 .user-card__tags 里 min-height 的 22px 是【一对】：
   * 没设偏好的人那一行靠 min-height 占位，两个数不一致就会差几像素。
   *
   * ⚠️ 字号只在紧凑档覆盖（roomy 档本来就是 12px），字重与高度两档一起吃 ——
   * 「我的」页的偏好标签跟着变大加粗，是刻意的（同一个组件、同一个观感）。
   */
  height: 22px;
  font-size: 12px;
  font-weight: 700;
}

.tag-more {
  background: #e4e0ec;
  color: #5d5476;
}

/*
 * ⚠️ 本组件【没有媒体查询】—— 一套尺寸走天下（2026-10-03 定）。
 *
 * 此前宽屏（≥768px）另有一套放大的字号与内边距，它与「在店用户」栅格的
 * 加宽是一对，必须同步改；而两套规则各改各的就会出现
 *「15px 昵称塞进 165px 卡片」这类错配 —— 实机截图报过两次。
 *
 * 现在页面宽度本身就按手机来（见 base.css 的 --page-max），
 * 卡片只管填满容器：名册里是栅格单元、我的页面里是整列。
 */
</style>
