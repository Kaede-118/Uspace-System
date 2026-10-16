/**
 * 「添加到桌面」引导 —— 环境判定与触发安装。
 *
 * <p><b>为什么需要引导</b>：手机浏览器【不会】自动问「要不要添加到桌面」。
 * 唯一能弹出那个<b>系统原生窗口</b>的时机是 {@code beforeinstallprompt} 事件，
 * 而浏览器只在「站点可安装」时才会给出它（可安装条件 = manifest + 一个带
 * fetch 处理器的 Service Worker + HTTPS，前两者见 `public/` 下的
 * `manifest.webmanifest` 与 `sw.js`）。Chromium 早已不再自动弹那条小横幅，
 * 所以想引导就只能自己接住这个事件。
 *
 * <p><b>四种环境，三条不同的路</b>：
 *
 * <ul>
 *   <li><b>Android / 桌面 Chromium</b> —— 能弹原生窗口。事件到来时拦下它
 *       （{@code preventDefault}）存起来，等用户点「添加」再调 {@code prompt()}。
 *       不拦的话事件转瞬即逝，之后就没有任何办法触发安装了。</li>
 *   <li><b>iOS（Safari 与所有 iOS 浏览器）</b> —— 弹不了。iOS 至今没有对应
 *       的事件，<b>任何网站都一样</b>，只能教用户「分享 → 添加到主屏幕」。</li>
 *   <li><b>微信 / QQ 内置浏览器</b> —— 弹不了也加不了（两家的 WebView 都不提供
 *       该能力，也没有任何唤起外部浏览器的接口），得先引导用户「在浏览器中打开」。
 *       用户从群里点链接进来时就是这两种环境之一 —— 而 QQ 群恰恰是主要入口。</li>
 *   <li><b>其他（桌面 Firefox / Safari 等）</b> —— 没有可用的引导路径，返回 null、
 *       不显示提示条。对做不到的事给提示，只会让人白点一遍。</li>
 * </ul>
 *
 * <p>环境在<b>模块加载时判定一次</b>：用户不会在页面开着的时候换浏览器，
 * 每次重算 UA 正则纯属浪费。
 */

import { ref } from 'vue'
import { isInstallGuideDismissed, dismissInstallGuide } from '@/utils/storage'
import { isInAppBrowser, isIOS } from '@/utils/ua'

/* ---------------- 环境判定 ---------------- */

/**
 * UA 相关的判断（iOS / 微信 / QQ）一律走 `utils/ua.js` ——
 * 那里是全站唯一的 UA 判断出处，本文件不再各写一份正则。
 * 这里只留 {@link isStandalone}：它看的不是 UA，而是「页面以什么模式打开」。
 */

/**
 * 是否已经以「独立窗口」打开 —— 即已经添加到桌面过了。
 *
 * <p>两个判据缺一不可：Android / 桌面 Chromium 认 {@code display-mode} 媒体查询，
 * iOS 认私有属性 {@code navigator.standalone}（它不支持那个媒体查询）。
 */
function isStandalone() {
  if (window.navigator.standalone === true) return true
  return window.matchMedia?.('(display-mode: standalone)').matches === true
}

/* ---------------- 响应式状态 ---------------- */

/**
 * 提示条的形态：
 * {@code 'prompt'}（能弹原生窗）/ {@code 'ios'} / {@code 'inapp'}（App 内置浏览器）/
 * {@code null}（不显示）。
 *
 * @type {import('vue').Ref<'prompt'|'ios'|'inapp'|null>}
 */
export const installMode = ref(null)

/** 用户是否关过提示条（关过后永久不再显示）。 */
export const guideDismissed = ref(isInstallGuideDismissed())

/**
 * 浏览器给的安装事件。
 *
 * <p>必须是模块级变量、且事件一到就存下来 —— 它可能在任何时刻触发
 * （取决于浏览器什么时候判定完可安装），而组件那时未必已经挂载。
 * 用普通变量而不是 ref：它不直接驱动界面，驱动界面的是 {@link installMode}。
 */
let deferredPrompt = null

/**
 * 重新判定提示条形态。
 *
 * <p>所有会影响判定的变化（事件到来、用户关闭、安装完成）最终都汇到这一个函数，
 * 免得把同一套 if-else 在四处各写一遍。
 */
function refreshMode() {
  if (guideDismissed.value || isStandalone()) {
    installMode.value = null
  } else if (isInAppBrowser()) {
    // App 内置浏览器优先于 beforeinstallprompt：微信 / QQ 的 WebView
    // 即使给出事件也装不了（它们没有「添加到桌面」这个能力），
    // 先引导用户换浏览器才是有效路径
    installMode.value = 'inapp'
  } else if (deferredPrompt) {
    installMode.value = 'prompt'
  } else if (isIOS()) {
    installMode.value = 'ios'
  } else {
    installMode.value = null
  }
}

// 浏览器判定「可安装」后给出这个事件。⚠️ 必须在事件里 preventDefault：
// 一是拦下旧版 Chromium 自己弹的小横幅（时机由我们定），
// 二是事件的 prompt() 只有拦下并保存过之后才用得上 —— 它是一次性的。
window.addEventListener('beforeinstallprompt', (event) => {
  event.preventDefault()
  deferredPrompt = event
  refreshMode()
})

// 安装成功（用户从原生窗口里确认装好了）
window.addEventListener('appinstalled', () => {
  deferredPrompt = null
  // 走 dismissGuide 而不是另设一个「已安装」标记 —— 效果相同，
  // 而且下次进站（仍在浏览器里，不是 standalone）直接就查不到提示需求了
  dismissGuide()
})

refreshMode()

/* ---------------- 动作 ---------------- */

/**
 * 用户关掉提示条 —— 永久不再提示。
 *
 * <p>「建议」不是「通知」：反复出现只会让人烦，而想再看到它
 * 清一次浏览器数据即可（代价足够低）。
 */
export function dismissGuide() {
  guideDismissed.value = true
  dismissInstallGuide()
  refreshMode()
}

/**
 * 弹出系统原生的安装窗口（只有 {@code prompt} 形态可用）。
 *
 * <p>⚠️ {@code prompt()} 必须在<b>用户手势</b>的调用链里同步调用 ——
 * 本函数体内它之前不能有别的 {@code await}（组件里是点击事件直接调它，正好满足）。
 *
 * <p>无论用户选了「安装」还是取消，都收起提示条：他已经做过决定了，
 * 再弹一次就是骚扰。区别只在返回值，供调用方决定要不要说点什么。
 *
 * @returns {Promise<boolean>} 用户是否确认安装（false = 取消，或压根没有可用的安装事件）
 */
export async function promptInstall() {
  const event = deferredPrompt
  if (!event) return false
  // 事件是一次性的：用过即失效，要等浏览器重新给（比如用户清掉站点数据后）
  deferredPrompt = null

  event.prompt()
  const choice = await event.userChoice

  dismissGuide()
  return choice?.outcome === 'accepted'
}
