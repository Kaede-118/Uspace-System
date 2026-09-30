<script setup>
/**
 * 用户卡片（banner 做背景）。
 *
 * <p>「在店用户」列表与「我的」页面<b>共用这一个组件</b> ——
 * 两个页面的卡片长得一样，只有右侧的信息不同（在店列表显示进店时间与时长，
 * 我的页面显示消费与时长统计），所以右侧做成具名插槽 {@code #info}。
 *
 * <p><b>内容整体浮在 banner 之上</b>：头像是卡片的视觉主体（占高度的 80%），
 * 昵称与月卡状态紧挨着它，右侧是调用方决定的信息。
 * 卡片高度固定，不再让 banner 按 6:1 撑开 ——
 * 6:1 在手机上只有 60px 高（内容放不下），在电脑上却有 230px 高（一大块空白），
 * 同一个比例两头都不合适。
 */
import { ref, watch, computed } from 'vue'
import { versionedUrl } from '@/utils/image'

const props = defineProps({
  /** 用户对象：至少含 nickname、avatar、banner */
  user: { type: Object, default: () => ({}) },
  /** 月卡类型：ALL_DAY / NIGHT，未持卡为 null */
  cardType: { type: String, default: null },
  /** 月卡类型的中文名（后端返回，不要在前端硬编码文案） */
  cardTypeLabel: { type: String, default: '' },
  /** 是否显示月卡标签 */
  showCard: { type: Boolean, default: true },
  /**
   * 附加标签（游玩偏好），与月卡标签并排显示在中文名下方。
   *
   * <p>传的是<b>已经翻好的中文名数组</b>：偏好 code → 中文名的映射要用到设备类型字典，
   * 那是调用方的事，卡片组件不认识设备包。
   */
  tags: { type: Array, default: () => [] }
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
 * 头像的兜底：取昵称首字。
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
 */
const cardText = computed(() => {
  if (!props.cardType) return ''
  if (props.cardTypeLabel) return props.cardTypeLabel
  return props.cardType === 'ALL_DAY' ? '全天月卡' : '夜间月卡'
})

/**
 * 标签最多显示几个（含月卡）。
 *
 * <p>卡片是<b>固定高度</b>的（头像是它的 80%），标签换行会溢出卡片，
 * 所以超出部分收成「+N」而不是让它挤出去。放不下的信息点进详情还能看到。
 * 5 个是在 375px 宽的手机上实测能排下的数量。
 */
const MAX_TAGS = 5

/** 实际渲染的附加标签。 */
const visibleTags = computed(() => {
  const room = Math.max(0, MAX_TAGS - (props.showCard && cardText.value ? 1 : 0))
  return props.tags.slice(0, room)
})

/** 被收起来的标签数量。 */
const overflowCount = computed(() => Math.max(0, props.tags.length - visibleTags.value.length))
</script>

<template>
  <div class="user-card">
    <!--
      banner 未设置、或加载失败时回落到纯色底 —— 绝不能是破图。
      两种失败用同一个 :class 处理：用户不需要知道「你是没传」还是「传了但加载失败」。
    -->
    <img
      v-if="showBannerImage"
      class="user-card__banner"
      :src="versionedUrl(banner)"
      alt=""
      @error="bannerFailed = true"
    />
    <div v-else class="user-card__banner user-card__banner--fallback" />

    <!--
      压暗/提亮层。banner 是用户上传的任意图片，可能是纯白、也可能是花哨的风景照 ——
      没有这一层的话，浅色文字压在浅色图上会完全看不清。
      两端接近实白（文字都在两端），中间留出 45% 透出图片本身。
    -->
    <div class="user-card__scrim" />

    <div class="user-card__body">
      <div class="user-card__left">
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

        <div class="user-card__ident">
          <div class="user-card__name-row">
            <span v-if="isStaff" class="badge-staff">STAFF</span>
            <span class="user-card__name">{{ name }}</span>
          </div>
          <!-- 月卡与偏好并排成一列标签：月卡用绿色（「今天免单」是个状态），偏好用主色淡紫 -->
          <div v-if="(showCard && cardText) || visibleTags.length" class="user-card__tags">
            <span v-if="showCard && cardText" class="tag tag-success">{{ cardText }}</span>
            <span v-for="t in visibleTags" :key="t" class="tag">{{ t }}</span>
            <span v-if="overflowCount" class="tag tag-more">+{{ overflowCount }}</span>
          </div>
        </div>
      </div>

      <div class="user-card__right">
        <slot name="info" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.user-card {
  position: relative;
  border-radius: var(--r-card);
  overflow: hidden;
  background: var(--c-card);
  /*
   * 固定高度。内容整体绝对定位在它上面，所以这个值就是 banner 的高度 ——
   * 头像按它的 80% 算（见 .user-card__avatar）。
   */
  height: 132px;
}

/* 宽屏上给一点余量，免得卡片显得比周围的文字块还矮 */
@media (min-width: 768px) {
  .user-card {
    height: 148px;
  }
}

.user-card__banner {
  position: absolute;
  inset: 0;
  display: block;
  width: 100%;
  height: 100%;
  /*
   * ⚠️ cover 是三种裁切方式里唯一合理的：
   * 用户传的图比例不会正好合上卡片，contain 会留白、拉伸会变形。
   */
  object-fit: cover;
}

.user-card__banner--fallback {
  background: linear-gradient(120deg, var(--c-primary-pale), var(--c-icon-bg));
}

.user-card__scrim {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    90deg,
    rgba(255, 255, 255, 0.94) 0%,
    rgba(255, 255, 255, 0.45) 38%,
    rgba(255, 255, 255, 0.45) 62%,
    rgba(255, 255, 255, 0.94) 100%
  );
}

.user-card__body {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  /*
   * ⚠️ 只有左右 padding，上下留 0 ——
   * 头像的 height: 80% 是按内容盒算的，若这里再给上下 padding，
   * 内容盒变矮，头像就够不到卡片高度的 80%。
   */
  padding: 0 var(--sp-4);
}

.user-card__left {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  min-width: 0;
  /*
   * ⚠️ 这一行不能少。头像的 height: 80% 是相对【父元素】算的，
   * 而 .user-card__left 作为 flex item 的高度默认是 auto ——
   * 百分比没有依据时浏览器会退回图片的自然尺寸（一张 648px 的头像
   * 就会撑爆整张卡片，把昵称挤成一个「系…」）。
   * 给它一个确定高度，百分比才有意义。
   */
  height: 100%;
}

.user-card__avatar {
  /* 卡片高度的 80% */
  height: 80%;
  aspect-ratio: 1 / 1;
  border-radius: 50%;
  object-fit: cover;
  background: var(--c-icon-bg);
  flex-shrink: 0;
  /* 描一圈白边，让它从 banner 上「浮」起来 */
  border: 2px solid #fff;
  box-shadow: 0 2px 8px rgba(43, 35, 64, 0.12);
}

.user-card__avatar--fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  font-weight: 600;
  color: var(--c-primary);
}

.user-card__ident {
  min-width: 0;
}

/*
 * 标签一行排开。卡片是固定高度的，所以这里【不换行】——
 * 放不下的由 +N 收起来（见 visibleTags）。
 */
.user-card__tags {
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  overflow: hidden;
  white-space: nowrap;
}

.user-card__tags .tag {
  /* 窄屏上不被压缩，宁可让 +N 去承担 */
  flex-shrink: 0;
  /* 标签本身的间距比默认胶囊紧一点，一行能多放一个 */
  padding: 0 6px;
}

.tag-more {
  background: #e4e0ec;
  color: #5d5476;
}

/* 徽章固定在左、昵称在右截断 —— 昵称再长也不会把徽章挤没 */
.user-card__name-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  margin-bottom: var(--sp-1);
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
  height: 16px;
  padding: 0 5px;
  border-radius: 4px;
  background: #5fc08f;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.4px;
  line-height: 16px;
}

.user-card__name {
  font-size: 17px;
  font-weight: 600;
  color: var(--c-text);
  /* 昵称过长时截断，不要撑破卡片 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * 右侧信息块。
 *
 * 给它一层实底（白底 + 一点点阴影）而不是让文字直接浮在 banner 上 ——
 * banner 是用户上传的任意图片，浅色文字压在浅色图或花哨的图上都读不稳。
 * 有了底就与图长什么样无关了。
 *
 * ⚠️ align-self: flex-end 让它【单独】沉到卡片底部（父容器是居中对齐，
 * 左侧的头像与昵称不受影响）；margin-bottom 是别让它贴着卡片边缘。
 */
.user-card__right {
  align-self: flex-end;
  margin-bottom: var(--sp-3);
  flex-shrink: 0;
  padding: var(--sp-1) var(--sp-3);
  border-radius: var(--r-btn);
  background: rgba(255, 255, 255, 0.92);
  color: var(--c-text-sub);
  font-size: 13px;
  line-height: 1.6;
  text-align: right;
  box-shadow: 0 1px 3px rgba(43, 35, 64, 0.08);
}
</style>
