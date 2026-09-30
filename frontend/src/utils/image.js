import { ref } from 'vue'

/**
 * 图片版本号。
 *
 * <p><b>为什么需要它</b>：文件名是固定的（`{用户名}_{用户ID}.{扩展名}`），
 * 同一个用户重传同一种格式会落到<b>同一个 URL</b> 上。于是：
 * <ul>
 *   <li>组件里 `form.avatar` 的值没变 → Vue 不更新 `<img>` 的 src</li>
 *   <li>就算更新了，浏览器也会拿缓存里的旧图（URL 没变）</li>
 * </ul>
 * 用户看到的就是「提示上传成功，图还是老的，非得刷新一下」。
 *
 * <p>解法是给展示用的 URL 挂一个版本参数（`?v=…`），每次上传成功就变一次。
 * <b>只影响 `<img src>`，不影响存进库的路径</b> —— 库里存的仍是干净的相对路径，
 * 上传接口也不认这个参数。
 */
export const imageVersion = ref(0)

/** 上传成功后调用，让全站带版本参数的图片重新加载。 */
export function bumpImageVersion() {
  imageVersion.value = Date.now()
}

/**
 * 给图片 URL 挂上当前版本号。
 *
 * <p>在模板里调用它（`:src="versionedUrl(user.avatar)"`）会建立对
 * {@link imageVersion} 的响应式依赖，所以版本一变、所有用到的地方一起刷新。
 *
 * @param {string} url 站内相对路径
 * @returns {string} 带版本参数的 URL；空值原样返回
 */
export function versionedUrl(url) {
  if (!url) return ''
  return imageVersion.value ? `${url}?v=${imageVersion.value}` : url
}

/**
 * 上传前的图片标准化。
 *
 * <p>两件事一起做，缺一不可：
 *
 * <p><b>① 压体积</b>：后端只收 JPEG / PNG / WebP 且单张上限 2MB，
 * 而手机随手拍一张就是 3~5MB（iPhone 默认还是 HEIC）—— 不压的话
 * 用户只会看到一句「图片不能超过 2MB」，然后就没有然后了。
 *
 * <p><b>② 定尺寸</b>：头像与背景图各有固定的展示形状，让<b>上传这一端</b>
 * 就把图裁成那个形状，显示端就不必再操心任何比例问题 ——
 * 传什么都是规范的，卡片也就不会因为某张特别高或特别扁的图而错乱。
 *
 * <p>两者的裁剪策略不同，是刻意的：
 * <ul>
 *   <li><b>头像</b>取<b>中间</b>画面 —— 人像多半在正中间</li>
 *   <li><b>背景图</b>以<b>左上角</b>为锚点：比目标高就裁掉下面，
 *       比目标矮就在下面补白。背景图常常是带文字的横幅或店面照，
 *       左上角往往才是有效内容</li>
 * </ul>
 */

/** 头像的成品边长（正方形）。400px 在高分屏上也够清晰，体积却很有限。 */
const AVATAR_SIZE = 400

/** 背景图的成品尺寸。6:1 的横长图，与卡片上留给它的高度相称。 */
const BANNER_WIDTH = 1200
const BANNER_HEIGHT = 200

/** 背景图不足部分的补白色。 */
const PAD_COLOR = '#ffffff'

/** 导出质量。标准化会重新编码一次，所以给得比「单纯压体积」时高一点。 */
const QUALITY = 0.9

/**
 * 把 File 读成可绘制的图像源。
 *
 * <p>优先用 {@code createImageBitmap} —— 它比 {@code new Image()} 少了
 * 一次「解码 → 挂到 DOM → 等 onload」的往返，大图上的差别很明显。
 * 老浏览器没有它时退回 Image + objectURL。
 *
 * @param {File} file 图片文件
 * @returns {Promise<ImageBitmap|HTMLImageElement>} 图像源
 */
async function loadImage(file) {
  if (typeof createImageBitmap === 'function') {
    try {
      return await createImageBitmap(file)
    } catch {
      // 某些浏览器对 HEIC 等格式会在这一步抛错，交给下面的降级路径再试一次
    }
  }
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => {
      URL.revokeObjectURL(url)
      resolve(img)
    }
    img.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('这个图片格式浏览器打不开，请换一张 JPG 或 PNG'))
    }
    img.src = url
  })
}

/**
 * 画布转成 JPEG 文件。
 *
 * @param {HTMLCanvasElement} canvas 画布
 * @returns {Promise<File>} JPEG 文件
 */
function toJpegFile(canvas) {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (!blob) {
          reject(new Error('图片处理失败，请换一张试试'))
          return
        }
        // 文件名只用于日志与调试；服务端的文件名完全由自己生成，不采信这个名字
        resolve(new File([blob], 'upload.jpg', { type: 'image/jpeg' }))
      },
      'image/jpeg',
      QUALITY
    )
  })
}

/**
 * 读图 → 交给绘制函数 → 转成 JPEG。
 *
 * @param {File} file 原图
 * @param {Function} draw 绘制函数，签名 (ctx, source, srcWidth, srcHeight)
 * @param {number} width 画布宽
 * @param {number} height 画布高
 * @returns {Promise<File>} 处理后的文件
 */
async function process(file, draw, width, height) {
  const source = await loadImage(file)
  const srcWidth = source.width
  const srcHeight = source.height

  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')

  draw(ctx, source, srcWidth, srcHeight)

  // ImageBitmap 用完要显式释放，否则大图会一直占着内存
  if (typeof source.close === 'function') source.close()

  return toJpegFile(canvas)
}

/**
 * 处理头像：压成正方形，取<b>中间</b>画面。
 *
 * @param {File} file 原图
 * @returns {Promise<File>} 400×400 的 JPEG
 */
export function processAvatar(file) {
  return process(
    file,
    (ctx, source, srcWidth, srcHeight) => {
      // 原图上截一个居中的正方形，再整个铺满画布
      const side = Math.min(srcWidth, srcHeight)
      const sx = (srcWidth - side) / 2
      const sy = (srcHeight - side) / 2
      ctx.drawImage(source, sx, sy, side, side, 0, 0, AVATAR_SIZE, AVATAR_SIZE)
    },
    AVATAR_SIZE,
    AVATAR_SIZE
  )
}

/**
 * 处理背景图：压成 6:1 的横长图，以<b>左上角</b>为锚点。
 *
 * <p>宽度撑满、高度按原始比例：
 * <ul>
 *   <li>比目标高 → 超出的部分自然画到画布外，等于裁掉下面</li>
 *   <li>比目标矮 → 下方露出预设的白底，等于补白</li>
 * </ul>
 *
 * <p>不做「拉伸填满」—— 那会把照片压扁变形；也不做居中裁剪 ——
 * 背景图常常是带文字的横幅，切掉左边等于把标题切没了。
 *
 * @param {File} file 原图
 * @returns {Promise<File>} 1200×200 的 JPEG
 */
export function processBanner(file) {
  return process(
    file,
    (ctx, source, srcWidth, srcHeight) => {
      // 先铺白底：PNG 的透明区直接转 JPEG 会变黑块，图比目标矮时也靠它补白
      ctx.fillStyle = PAD_COLOR
      ctx.fillRect(0, 0, BANNER_WIDTH, BANNER_HEIGHT)

      // 宽度撑满，高度按原始比例。drawImage 画到画布外的部分会被自动裁掉
      const drawHeight = (srcHeight / srcWidth) * BANNER_WIDTH
      ctx.drawImage(source, 0, 0, BANNER_WIDTH, drawHeight)
    },
    BANNER_WIDTH,
    BANNER_HEIGHT
  )
}

/**
 * 按上传目标分发到对应的处理函数。
 *
 * @param {File} file 原图
 * @param {string} kind 上传目标：avatar / banner
 * @returns {Promise<File>} 处理后的文件
 */
export function processImage(file, kind) {
  return kind === 'avatar' ? processAvatar(file) : processBanner(file)
}
