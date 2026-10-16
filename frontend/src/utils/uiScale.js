/**
 * 桌面端整体等比放大 —— 全站唯一的 `--ui-scale` 出处。
 *
 * <p><b>要解决的问题</b>：用户端按 360px 手机竖屏宽度设计（见 base.css 的 `--page-max`），
 * 电脑上就是一条居中的窄列。窄列「看着大还是小」，取决于<b>这块屏幕把 1 个 CSS 像素
 * 画成几个物理像素</b> —— 而这正是 Windows 显示缩放在做的事：
 *
 * <ul>
 *   <li>本机 1920×1080 屏按 <b>125%</b> 缩放时，窗口最大化后的视口宽是
 *       {@code 1920 ÷ 1.25 = 1536} CSS 像素，360px 的窄列在这里看着正合适；</li>
 *   <li>同一台屏按 <b>100%</b> 缩放，视口变成 1920 CSS 像素 —— 同一套 CSS
 *       被画小了 20%，于是「界面元素特别小」；</li>
 *   <li>再换一台更大或更小的屏，每个人看到的又都不一样。</li>
 * </ul>
 *
 * <p><b>做法</b>：把<b>布局宽度钉在 1536 这一档</b>，再整体等比放大到填满窗口 ——
 * {@code scale = 视口宽 ÷ 1536}，夹在 {@code [1, 2]} 之间。窗口比 1536 窄时（手机、
 * 平板、没最大化的窗口）不放大，与原来一模一样。
 *
 * <p>于是不论对方是 100% 还是 125% 缩放、显示器多大，界面都占窗口宽度的同一个比例 ——
 * 也就是「别人看到的 ≈ 我在 125% 缩放下看到的」。换个说法：
 * <b>不管窗口多宽，应用始终按「1536 宽的窗口」排版，只是整体画大或画小</b>。
 *
 * <p><b>为什么不用 {@code devicePixelRatio} 反推</b>：它把【系统缩放】与
 * 【浏览器自己的页面缩放（Ctrl+加号）】乘在了一起，看不出来哪个是哪个。
 * 而视口宽度是两个因素共同作用之后的<b>结果</b>，用它不会与浏览器的缩放打架：
 * 无论对方怎么缩放，窄列占窗口的比例恒定，观感与基准机器一致。
 *
 * <p>⚠️ <b>只放大、不缩小</b>（下界 1）：手机上必须保持原样 —— 那才是设计稿的本色，
 * 而且手机上本来就不缺宽度。上界 2 是防呆：再宽的窗口也不至于把字号放大到 28px 以上。
 *
 * <p>⚠️ 落点只有一个：{@code base.css} 里的 {@code #app { zoom: var(--ui-scale) }}。
 * 这里只负责算数并写变量，不碰任何 DOM 结构。
 */

/**
 * 基准视口宽度（CSS px）—— 1920 物理像素 ÷ 125% Windows 缩放 = 1536。
 *
 * <p>它就是「这套 CSS 看着正合适」的那台机器（本机）的视口宽度。
 * 换机器改观感时<b>只动这一个数</b>：调大 → 电脑上整体变小，调小 → 整体变大。
 */
const REFERENCE_WIDTH = 1536

/** 放大倍数下界：手机 / 平板 / 窄窗口保持原样，不缩小。 */
const MIN_SCALE = 1

/** 放大倍数上界：防止超宽屏把界面放到离谱（2 倍时正文字号 28px）。 */
const MAX_SCALE = 2

/**
 * 算当前的放大倍数。
 *
 * <p>取 {@code clientWidth} 而不是 {@code innerWidth}：前者扣掉了纵向滚动条那十几个像素。
 * 用 innerWidth 的话，页面从「没滚动条」变成「有滚动条」时倍数会跳一下。
 *
 * @param {number} [viewportWidth] 视口宽度，默认取当前值（测试可直接传）
 * @returns {number} 保留两位小数的放大倍数，恒在 [1, 2] 内
 */
export function computeUiScale(viewportWidth = document.documentElement.clientWidth) {
  const raw = viewportWidth / REFERENCE_WIDTH
  const clamped = Math.min(MAX_SCALE, Math.max(MIN_SCALE, raw))
  // 抹掉长尾小数（1.239 → 1.24）：写在 :root 上的值要能一眼看懂，也少几次无谓的重算
  return Math.round(clamped * 100) / 100
}

/**
 * 把算出来的倍数写到 {@code :root} 的 {@code --ui-scale} 上。
 *
 * @returns {number} 本次生效的倍数
 */
export function applyUiScale() {
  const scale = computeUiScale()
  document.documentElement.style.setProperty('--ui-scale', String(scale))
  return scale
}

/**
 * 装上缩放：先算一次，再跟着窗口变化重算。
 *
 * <p>在 {@code app.mount()} <b>之前</b>调用 —— 此时页面上还没有任何内容，
 * 所以不会出现「先按原样画一遍、再跳一下」。
 *
 * <p>重算挂在 {@code resize} 上并做了 120ms 的收尾防抖：拖动窗口边缘时 resize
 * 一秒钟能来上百次，而每次都要把整棵树重新布局一遍。
 *
 * <p>⚠️ <b>防抖用 {@code setTimeout} 而不是 {@code requestAnimationFrame}</b>：
 * 页面被隐藏（后台标签页）时 rAF 回调<b>根本不会执行</b>，而 resize 事件照来 ——
 * 用 rAF 的话第一次隐藏期间的 resize 就会把「已排队」的标记永远留在那里，
 * 之后所有 resize 都被吞掉，界面大小从此定格在旧值上（且不报任何错）。
 */
export function installUiScale() {
  applyUiScale()

  let timer = 0
  const onViewportChange = () => {
    clearTimeout(timer)
    timer = setTimeout(applyUiScale, 120)
  }

  window.addEventListener('resize', onViewportChange)
  // 手机横竖屏切换在部分浏览器上不触发 resize，补一条
  window.addEventListener('orientationchange', onViewportChange)
}
