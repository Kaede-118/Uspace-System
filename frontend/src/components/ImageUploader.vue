<script setup>
/**
 * 图片上传（头像 / 背景图）。
 *
 * <p>⚠️ <b>图片不走 {@code PUT /api/user/me}</b>，而是各有独立的上传端点。
 * 那个接口是全量替换语义，把图片路径混进去的话，
 * 「只改昵称」的表单会因为没带图片路径而<b>把头像清空，且不报任何错</b>。
 *
 * <p>类型校验以后端为准（它按文件头判，不信任 Content-Type）；
 * 前端只做一次大小预检 —— 让用户在上传 3MB 之前就知道超了，
 * 而不是等上传完再收到 413。
 */
import { ref, computed } from 'vue'
import { uploadAvatar, uploadBanner } from '@/api/user'
import { processImage, versionedUrl, bumpImageVersion } from '@/utils/image'
import { toastSuccess, toastError } from '@/composables/useToast'
import { errorMessage } from '@/utils/error'

const props = defineProps({
  /** 上传目标：avatar 头像 / banner 背景图 */
  kind: { type: String, required: true },
  /** 当前图片地址 */
  url: { type: String, default: '' },
  /** 形状：circle 圆形（头像）/ rect 矩形（背景图） */
  shape: { type: String, default: 'rect' },
  /** 说明文字 */
  hint: { type: String, default: '' }
})

const emit = defineEmits(['uploaded'])

/** 与后端 uspace.upload.max-image-bytes 保持一致（2MB）。 */
const MAX_BYTES = 2 * 1024 * 1024

const uploading = ref(false)
const failed = ref(false)
const inputRef = ref(null)

/**
 * 本地预览地址（blob）。
 *
 * <p>用户选完图到上传完成之间有 1~2 秒（压缩 + 传输），这段时间里
 * 只显示「上传中…」会让人以为没反应。用选中文件的本地地址先画出来，
 * 上传成功后自动切回服务端那张。
 */
const previewUrl = ref('')

/** 当前该显示的图：上传中显示本地预览，其余用服务端地址（带版本号）。 */
const displayUrl = computed(() => previewUrl.value || versionedUrl(props.url))

function pick() {
  if (uploading.value) return
  inputRef.value?.click()
}

async function onChange(event) {
  const input = event.target
  const file = input.files?.[0]
  // 先清空 input，否则「再选同一个文件」不会触发 change。
  // 放在最前面还有个好处：下面的 await 期间 target 不会被回收
  input.value = ''
  if (!file) return

  uploading.value = true
  // 先画本地那张，不等上传完成。顺带清掉上一次的加载失败标记 ——
  // 不清的话，失败过之后即使重选一张也不会显示出来，看着像「卡住了」
  failed.value = false
  const localUrl = URL.createObjectURL(file)
  previewUrl.value = localUrl
  try {
    /*
     * ⚠️ 必须先处理。两件事一起做：
     *   ① 压体积 —— 后端只收 JPEG / PNG / WebP 且上限 2MB，
     *      而手机随手拍一张就是 3~5MB，不压的话用户只会看到
     *      一句「图片不能超过 2MB」，然后就没有然后了。
     *      顺带把 HEIC 这类后端不认的格式统一成 JPEG
     *      （浏览器解得开才转得了，Safari 可以，而 iPhone 上用的正是 Safari）。
     *   ② 定尺寸 —— 头像裁成正方形取中间，背景图裁成 6:1 以左上角为锚点。
     *      传什么进来都是规范的，卡片就不会被某张特别高或特别扁的图搞乱。
     */
    const prepared = await processImage(file, props.kind)

    if (prepared.size > MAX_BYTES) {
      // 压完还超限：多半是张分辨率极高的图，如实告诉用户有多大
      const mb = (prepared.size / 1024 / 1024).toFixed(1)
      toastError(`图片压完仍有 ${mb}MB，请换一张小一点的`)
      return
    }

    const resp =
      props.kind === 'avatar' ? await uploadAvatar(prepared) : await uploadBanner(prepared)
    failed.value = false
    /*
     * ⚠️ 这一行不能少。文件名是固定的，重传同格式会落到同一个 URL 上 ——
     * 不换版本号的话，<img src> 的值没变，浏览器直接拿缓存里的旧图，
     * 用户看到的就是「提示上传成功，图还是老的」。
     */
    bumpImageVersion()
    // 接口返回完整的用户资料，父组件直接整体替换即可
    emit('uploaded', resp.data)
    toastSuccess('上传成功')
  } catch (err) {
    // 压缩阶段的错误（如浏览器解不开 HEIC）自带可读文案，优先用它
    toastError(err instanceof Error && !err.code ? err.message : errorMessage(err, '上传失败'))
  } finally {
    uploading.value = false
    // 释放 blob，让画面切回服务端那张 —— 不释放会一直占着内存
    URL.revokeObjectURL(localUrl)
    previewUrl.value = ''
  }
}
</script>

<template>
  <div class="uploader">
    <button
      class="uploader__preview"
      :class="[`uploader__preview--${shape}`, { 'uploader__preview--uploading': uploading }]"
      :disabled="uploading"
      @click="pick"
    >
      <img v-if="displayUrl && !failed" :src="displayUrl" alt="" @error="failed = true" />
      <span v-else class="uploader__placeholder">{{ uploading ? '处理中…' : '点击上传' }}</span>
      <!--
        已经有图时补一个角标：否则预览块看起来只是一张展示图，
        用户根本不知道它还能点（这是「点了没反应」的另一种来源）。
      -->
      <span v-if="displayUrl && !failed" class="uploader__badge">
        {{ uploading ? '处理中…' : '点击更换' }}
      </span>
    </button>

    <p v-if="hint" class="uploader__hint">{{ hint }}</p>

    <!--
      ⚠️ accept 用 image/* 而不是逐个列出 jpeg/png/webp：
      写死这三个的话，iPhone 相册里的 HEIC 照片在文件选择器里是【灰的，选不中】——
      用户只会觉得「点了没反应」，而看不到任何错误提示。
      真正的格式校验在后端（按文件头判），这里放宽不影响安全。
    -->
    <input
      ref="inputRef"
      class="uploader__input"
      type="file"
      accept="image/*"
      @change="onChange"
    />
  </div>
</template>

<style scoped>
.uploader {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.uploader__preview {
  position: relative;
  overflow: hidden;
  background: var(--c-icon-bg);
  border: 1px dashed var(--c-border);
  display: flex;
  align-items: center;
  justify-content: center;
}

.uploader__preview--circle {
  width: 72px;
  height: 72px;
  border-radius: 50%;
}

.uploader__preview--rect {
  width: 100%;
  height: 96px;
  border-radius: var(--r-card);
}

.uploader__preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.uploader__preview--uploading {
  opacity: 0.6;
}

.uploader__placeholder {
  font-size: 12px;
  color: var(--c-text-muted);
}

.uploader__badge {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 1px 6px;
  border-radius: var(--r-pill);
  background: rgba(43, 35, 64, 0.62);
  color: #fff;
  font-size: 10px;
  line-height: 1.6;
  pointer-events: none;
}

.uploader__hint {
  margin-top: var(--sp-2);
  font-size: 11px;
  color: var(--c-text-muted);
}

/* 隐藏原生 file input —— 样式无法统一，改用上面的预览块触发 */
.uploader__input {
  display: none;
}
</style>
