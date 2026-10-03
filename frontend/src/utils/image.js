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

/**
 * 商品封面的成品边长（正方形）。
 *
 * <p>800 的依据是展示端：商城是 `minmax(150px, 1fr)` 的栅格，桌面内容区 900px
 * 约 5 列、每格约 170px，3 倍屏需要 510px —— 800 有余量；而后台列表的缩略图
 * 只有 52px，绰绰有余。压完的 JPEG 约 80~150KB，离 2MB 上限很远。
 */
const COVER_SIZE = 800

/**
 * 背景图的成品尺寸。3:1 的横长图 —— 与 UserCard 卡片素材区的比例【严格一致】。
 *
 * <p>⚠️ 高度这个数不能脱离 UserCard 素材区的 aspect-ratio 单独改：
 * 两者一致时，卡片上的 object-fit: cover 等于原样铺满、一格不裁；
 * 不一致时（比如 3:1 的成品放进 6:1 的框）它会再裁一次 ——
 * 用户在卡片上看到的就不是他传的那张完整的图了。
 *
 * <p>⚠️ 3:1 是<b> 2026-10-03 从 6:1 改来的</b>（卡片改成半宽两列之后，
 * 6:1 在半宽卡片上只有 27px 高，几乎看不清内容）。改比例时，
 * 卡片那一侧的 aspect-ratio（UserCard.vue 的 .user-card__banner）
 * 与下面的 {@link processBanner} 必须一起改。
 *
 * <p>成品仍是「宽固定 1200、高按比例算」：1200 在半宽卡片的 3 倍屏上
 * （160px × 3 = 480px）绰绰有余，体积也还很有限。
 */
const BANNER_WIDTH = 1200
const BANNER_HEIGHT = 400

/** 背景图不足部分的补白色。 */
const PAD_COLOR = '#ffffff'

/** 导出质量。标准化会重新编码一次，所以给得比「单纯压体积」时高一点。 */
const QUALITY = 0.9

/**
 * 收款码的长边上限。
 *
 * <p>二维码与别的图不一样：<b>缩放它会直接影响扫不扫得出来</b>。
 * 模块（那些黑白小方块）缩到几像素宽时，手机摄像头就分不清黑白了。
 * 所以这个上限给得很宽松 —— 手机截图常见 1080×2400 这种尺寸，
 * 1600 让它基本不会被缩，只在遇到超大图时才动一下
 *（后端还有 2MB 的上传上限兜着）。
 */
const QR_MAX_SIZE = 1600

/**
 * 付款截图的长边上限。
 *
 * <p>数值与收款码相同，但目的完全不同 —— 那个的下限是「摄像头认得出模块」，
 * 这个的下限是「OCR 认得出流水号」。1600 像素下，微信付款详情页里那一串
 * 28 位数字每个字仍有十来个像素高，足够识别；再大只是白白多占字节。
 */
const PROOF_MAX_SIZE = 1600

/**
 * 付款截图的导出质量。
 *
 * <p>比通用的 {@link QUALITY}（0.9）高一点，因为这里压的是<b>长数字串</b>：
 * JPEG 质量不足时，「8」和「6」这类笔画接近的字符会在压缩伪影里糊到一起，
 * 人眼还能靠上下文猜，OCR 认错一位就是一条对不上的账。
 */
const PROOF_QUALITY = 0.92

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
 * 画布编码成图片文件。
 *
 * <p>格式可指定：默认的 JPEG 适合照片（体积小得多），而收款码<b>必须用 PNG</b>
 *（透明底转 JPEG 会变黑，见 {@link processPayQr}），所以这里留一个出口。
 *
 * @param {HTMLCanvasElement} canvas  画布
 * @param {string}            type    MIME 类型
 * @param {number}            quality 导出质量（0~1）。<b>PNG 是无损的，会忽略它</b>
 * @returns {Promise<File>} 图片文件
 */
function toFile(canvas, type, quality) {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (!blob) {
          reject(new Error('图片处理失败，请换一张试试'))
          return
        }
        // 扩展名跟着 MIME 走，否则文件类型与服务端看到的内容对不上。
        // 文件名只用于日志与调试；服务端的文件名完全由自己生成，不采信这个名字
        const ext = type === 'image/png' ? 'png' : 'jpg'
        resolve(new File([blob], `upload.${ext}`, { type }))
      },
      type,
      quality
    )
  })
}

/**
 * 画布转成 JPEG 文件。头像、背景图、商品封面都走它。
 *
 * @param {HTMLCanvasElement} canvas 画布
 * @returns {Promise<File>} JPEG 文件
 */
function toJpegFile(canvas) {
  return toFile(canvas, 'image/jpeg', QUALITY)
}

/**
 * 画布转成 PNG 文件。目前只有收款码用它 —— 理由见 {@link processPayQr}。
 *
 * @param {HTMLCanvasElement} canvas 画布
 * @returns {Promise<File>} PNG 文件
 */
function toPngFile(canvas) {
  return toFile(canvas, 'image/png')
}

/**
 * 读图 → 交给绘制函数 → 编码成文件。
 *
 * @param {File}     file   原图
 * @param {Function} draw   绘制函数，签名 (ctx, source, srcWidth, srcHeight)
 * @param {number}   width  画布宽
 * @param {number}   height 画布高
 * @param {Function} encode 编码函数，默认 {@link toJpegFile}。
 *                          只有收款码会换成 {@link toPngFile}
 * @returns {Promise<File>} 处理后的文件
 */
async function process(file, draw, width, height, encode = toJpegFile) {
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

  return encode(canvas)
}

/**
 * 把图压成正方形，取<b>中间</b>画面。
 *
 * <p>头像与商品封面共用这一段：两者的展示端都是 1:1 的方形卡片，只是成品边长不同。
 * 各写一份的话，改了其中一份的锚点，另一份不会有任何提示。
 *
 * <p>取中间而不是左上角（那是背景图的做法）：头像里的人像、商品图里的那罐可乐，
 * 主体通常都在画面中央。
 *
 * @param {File}   file 原图
 * @param {number} size 成品边长
 * @returns {Promise<File>} size×size 的 JPEG
 */
function processSquare(file, size) {
  return process(
    file,
    (ctx, source, srcWidth, srcHeight) => {
      // 原图上截一个居中的正方形，再整个铺满画布
      const side = Math.min(srcWidth, srcHeight)
      const sx = (srcWidth - side) / 2
      const sy = (srcHeight - side) / 2
      ctx.drawImage(source, sx, sy, side, side, 0, 0, size, size)
    },
    size,
    size
  )
}

/**
 * 处理头像：压成正方形，取中间画面。
 *
 * @param {File} file 原图
 * @returns {Promise<File>} 400×400 的 JPEG
 */
export function processAvatar(file) {
  return processSquare(file, AVATAR_SIZE)
}

/**
 * 处理商品封面：压成正方形，取中间画面。
 *
 * <p>商城与后台列表都按正方形展示（`aspect-ratio: 1/1` + `object-fit: cover`），
 * 所以在这里就裁好 —— 管理员在上传预览里看到的就是最终效果，也顺带把手机拍的
 * 3~5MB 照片压进 2MB 的限制里。
 *
 * @param {File} file 原图
 * @returns {Promise<File>} 800×800 的 JPEG
 */
export function processCover(file) {
  return processSquare(file, COVER_SIZE)
}

/**
 * 处理背景图：压成 3:1 的横长图，<b>铺满画布（cover）、以右上角为锚点</b>。
 *
 * <p>绘制规则是「铺满、不留白」：取两个方向里较大的缩放比把画布填满，
 * 代价是另一个方向必然被裁掉一截。
 *
 * <ul>
 *   <li>比目标<b>宽</b>（如 6:1 的舞萌 DX 姓名框素材）→ 高度撑满，
 *       <b>裁掉左边、保留右半</b>（2026-10-03 定）。早先的做法是补白 ——
 *       但补白会让素材下方出现一条空白，看起来像渲染坏了</li>
 *   <li>比目标<b>高</b>（方图、竖图）→ 宽度撑满，<b>裁掉下面、保留顶部</b> ——
 *       背景图常常是带文字的横幅或店面照，左上角往往才是有效内容</li>
 *   <li>正好 3:1 → 一格不裁，<b>传什么就完整看到什么</b></li>
 * </ul>
 *
 * <p>不做「拉伸填满」—— 那会把照片压扁变形。
 *
 * <p>⚠️ <b>卡片那一侧的 {@code object-position: right center} 与本函数
 * 是同一条「超宽图保留右半」的规则，改一处必须一起改</b>
 *（见 UserCard.vue 的 .user-card__banner）。少了那边，存量老图
 *（改版前按 6:1 裁出来的成品）在卡片上会被 CSS 默认的居中裁切，与新图对不上。
 *
 * @param {File} file 原图
 * @returns {Promise<File>} 1200×400 的 JPEG
 */
export function processBanner(file) {
  return process(
    file,
    (ctx, source, srcWidth, srcHeight) => {
      // 先铺白底：PNG 的透明区直接转 JPEG 会变成黑块
      ctx.fillStyle = PAD_COLOR
      ctx.fillRect(0, 0, BANNER_WIDTH, BANNER_HEIGHT)

      // cover：两个方向取较大的缩放比，保证画布被填满（另一边必然溢出）
      const scale = Math.max(BANNER_WIDTH / srcWidth, BANNER_HEIGHT / srcHeight)
      const drawWidth = srcWidth * scale
      const drawHeight = srcHeight * scale

      // 右对齐 + 顶对齐 —— 画到画布外的部分会被 drawImage 自动裁掉，
      // 于是溢出的左边与下面正好被裁走（即「保留右半」与「保留顶部」）
      ctx.drawImage(source, BANNER_WIDTH - drawWidth, 0, drawWidth, drawHeight)
    },
    BANNER_WIDTH,
    BANNER_HEIGHT
  )
}

/**
 * 处理收款码：<b>不裁剪、不缩放，输出 PNG</b>。
 *
 * <p>三条都是「这个码还能不能扫出来」的前提，没有一条是风格偏好：
 * <ul>
 *   <li><b>不裁剪</b> —— 二维码四周的留白是它的一部分。
 *       裁掉之后看起来还是那张码，但扫描成功率会明显下降</li>
 *   <li><b>不缩放</b>（除非超限）—— 缩小会让模块糊在一起。这与头像、
 *       商品封面「压到 400 / 800 就够」的取舍正好相反：那两处是给人看的，
 *       这里是给摄像头认的</li>
 *   <li><b>输出 PNG</b> —— 二维码常常是透明底，转成 JPEG 后透明区会<b>变成黑色</b>，
 *       四周一圈黑边足以让扫码失败。背景图当年踩的是同一个坑
 *       （见 {@link processBanner} 的填白底），但那里填白底就够，
 *       二维码连白底都不该填 —— 原样保留透明度最稳</li>
 * </ul>
 *
 * <p>唯一会动的是「超过 {@link QR_MAX_SIZE} 时按<b>整数倍</b>缩小」：
 * 手机截图可能很大而后端有 2MB 上限。取整数倍是为了不引入缩放插值 ——
 * 非整数倍会让模块边缘出现半灰的像素，那正是摄像头最认不准的东西。
 *
 * @param {File} file 原图
 * @returns {Promise<File>} PNG 文件，尺寸不变或按整数倍缩小
 */
export async function processPayQr(file) {
  const source = await loadImage(file)
  const { width, height } = source
  const longest = Math.max(width, height)

  // 向上取整，保证缩完一定不超上限：4000 / 1600 → 3 倍 → 1333
  const scale = longest > QR_MAX_SIZE ? Math.ceil(longest / QR_MAX_SIZE) : 1
  const targetWidth = Math.round(width / scale)
  const targetHeight = Math.round(height / scale)

  const canvas = document.createElement('canvas')
  canvas.width = targetWidth
  canvas.height = targetHeight
  const ctx = canvas.getContext('2d')

  // 刻意不填底色：保留二维码的透明底，这正是要输出 PNG 的原因
  ctx.drawImage(source, 0, 0, targetWidth, targetHeight)

  if (typeof source.close === 'function') source.close()
  return toPngFile(canvas)
}

/**
 * 处理付款截图：<b>不裁剪</b>，长边超过 1600 时等比缩小，JPEG 质量 0.92。
 *
 * <p>三个参数都是为 OCR 与人工复核服务的，不是风格偏好：
 * <ul>
 *   <li><b>不裁剪</b> —— 头像取中间、封面压正方形、背景图以左上角为锚点，
 *       那些都是「展示形状」的考虑。付款截图没有展示形状可言，
 *       而流水号、金额、收款方可能出现在画面任何位置，裁掉一块就可能裁到它们</li>
 *   <li><b>长边 1600</b> —— 与收款码同一个数量级，理由却完全不同：
 *       那个是给<b>摄像头</b>认（缩放会让模块糊成一片），
 *       这个是给 <b>OCR</b> 认（字太小认不出）。上限仍是后端那 2MB</li>
 *   <li><b>质量 0.92</b>（高于通用的 {@link QUALITY}）—— 长数字串最怕压缩伪影：
 *       JPEG 质量低时会把「8」和「6」的笔画糊在一起，人眼还能猜，
 *       OCR 认错一位就是一条对不上的账</li>
 * </ul>
 *
 * <p>输出 JPEG 而不是 PNG：截图是照片类内容（同类画面下体积差好几倍），
 * 而它不像收款码那样需要保留透明底。不过为了保险仍先铺一层白底 ——
 * 不透明的截图会把它完全盖住（深色模式截的图也不受影响），
 * 真有透明区时填白也比转出来变黑好，与 {@link processBanner} 是同一个考虑。
 *
 * @param {File} file 原图
 * @returns {Promise<File>} 长边不超过 1600 的 JPEG
 */
export async function processProof(file) {
  const source = await loadImage(file)
  const { width, height } = source
  const longest = Math.max(width, height)

  // 等比缩小，且只缩不放 —— 放大不会凭空造出细节，只会让字节数变大
  const scale = longest > PROOF_MAX_SIZE ? PROOF_MAX_SIZE / longest : 1
  // 取 max(1, …)：极端窄长的图缩完可能算成 0 宽，而画布尺寸为 0 时
  // toBlob 会返回 null，用户看到的是「图片处理失败」这种摸不着头脑的提示
  const targetWidth = Math.max(1, Math.round(width * scale))
  const targetHeight = Math.max(1, Math.round(height * scale))

  const canvas = document.createElement('canvas')
  canvas.width = targetWidth
  canvas.height = targetHeight
  const ctx = canvas.getContext('2d')

  ctx.fillStyle = PAD_COLOR
  ctx.fillRect(0, 0, targetWidth, targetHeight)
  ctx.drawImage(source, 0, 0, targetWidth, targetHeight)

  if (typeof source.close === 'function') source.close()
  return toFile(canvas, 'image/jpeg', PROOF_QUALITY)
}

/**
 * 各上传目标对应的处理函数。
 *
 * <p>⚠️ <b>用映射表而不是 {@code kind === 'avatar' ? A : B} 那样的二元分支</b>：
 * 二元分支下多出第三种 kind 时，它会<b>静默地走 else 那一支</b> ——
 * 商品封面会被裁成 1200×400 的横幅，而后端不校验宽高比，
 * 于是商城里出现一张被压扁的图，<b>全程没有任何报错</b>。
 * 查不到处理器就抛错，至少能让这种问题在开发期现形。
 *
 * <p>新增 kind 时<b>三处必须一起改</b>：本表、{@code ImageUploader.vue} 的
 * {@code UPLOADERS}，以及那个组件 prop 的 validator 字面量
 *（{@code defineProps()} 会被提升，引用不到外部常量，只能重写一遍）。
 */
const PROCESSORS = {
  avatar: processAvatar,
  banner: processBanner,
  product: processCover,
  payqr: processPayQr,
  proof: processProof
}

/**
 * 按上传目标分发到对应的处理函数。
 *
 * @param {File}   file 原图
 * @param {string} kind 上传目标：avatar / banner / product / payqr / proof
 * @returns {Promise<File>} 处理后的文件
 * @throws {Error} 传了未登记的上传目标
 */
export function processImage(file, kind) {
  const processor = PROCESSORS[kind]
  if (!processor) {
    throw new Error(`未知的图片用途：${kind}`)
  }
  return processor(file)
}
