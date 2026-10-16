/**
 * 浏览器环境探测 —— 全站唯一的 UA 判断出处。
 *
 * <p>当前服务于两处：
 *
 * <ul>
 *   <li><b>选择支付通道</b>：微信内打不开支付宝、支付宝内也打不开微信（双方互相屏蔽外链），
 *       所以默认通道必须自动探测、而不是让用户自己选 —— 让用户选的话，
 *       必然出现「选了却调不起来」的死路，而这个死路在技术人员手里不容易复现
 *       （我们多半两个 App 都装了，且习惯用系统浏览器测试）。
 *       本模块只负责【给一个默认值】，用户仍可手动切换：微信里想用支付宝是常见需求
 *       （零钱不够、有红包），强制跳转会让这类用户完全没法付款。</li>
 *   <li><b>「添加到桌面」引导</b>：判哪些环境弹得了原生安装窗、哪些只能引导，
 *       见 `composables/useInstallPrompt.js`。</li>
 * </ul>
 *
 * <p>⚠️ UA 判断<b>集中在这一个文件</b>：散开写的话，同一条特征会变成好几份略有出入的
 * 正则，而它们的失败方式是静默的 —— 某个环境悄悄走进错误分支，不报任何错。
 */

/**
 * 判断是否在微信内置浏览器里。
 *
 * @returns {boolean}
 */
export function isWechat() {
  return /MicroMessenger/i.test(navigator.userAgent)
}

/**
 * 判断是否在 QQ 内置浏览器里（用户从 QQ 群里点开链接时就是它）。
 *
 * <p>特征：UA 里出现 {@code QQ/<版本号>}，例如
 * {@code ... V1_AND_SQ_8.9.63_3128_YYB_D QQ/8.9.63.10425 ...}。
 *
 * <p>⚠️ 刻意匹配「QQ 后跟斜杠与数字」而不是「含 QQ 两个字」——
 * 独立那个「QQ 浏览器」写作 {@code MQQBrowser/13.5} / {@code QQBrowser/11.0}
 * （QQ 后面是 B），不该被这条误伤；桌面 QQ 打开链接走的是系统默认浏览器，
 * UA 是浏览器自己的，也不会命中。
 *
 * @returns {boolean}
 */
export function isQqInApp() {
  return /QQ\/\d/.test(navigator.userAgent)
}

/**
 * 判断是否在 App 内置浏览器里（微信 / QQ）。
 *
 * <p>这两家的 WebView 都没有「添加到桌面」能力，也<b>都没有任何从网页侧
 * 唤起外部浏览器的接口</b> —— Android 的 {@code intent://}、iOS 的自定义 scheme
 * 都被专门拦掉，那是它们刻意筑的墙（硬绕有被封域名的风险）。
 * 所以这类环境里只能引导用户手动「在浏览器中打开」。
 *
 * <p>QQ 群是本系统用户的主要入口之一（机器人一直在群里发网页地址），
 * 所以它必须与微信同等对待 —— 少了它，那批用户会遇到「弹不了也没提示」。
 *
 * @returns {boolean}
 */
export function isInAppBrowser() {
  return isWechat() || isQqInApp()
}

/**
 * 判断是否 iOS / iPadOS。
 *
 * <p>⚠️ iPadOS 13+ 的 UA 伪装成 macOS，必须用「触摸点数 > 1」把它认出来 ——
 * 只看 UA 的话，iPad 会被当成桌面 Mac，而这两类在「添加到桌面」上的处境
 * 完全相反（iOS 系一律弹不了原生安装窗，桌面 Chromium 弹得了）。
 *
 * @returns {boolean}
 */
export function isIOS() {
  if (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1) return true
  return /iPad|iPhone|iPod/.test(navigator.userAgent)
}

/**
 * 判断是否在支付宝内置浏览器里。
 *
 * @returns {boolean}
 */
export function isAlipay() {
  return /AlipayClient/i.test(navigator.userAgent)
}

/**
 * 探测默认支付通道。
 *
 * @returns {string|null} 微信内返回 WXPAY_JSAPI、支付宝内返回 ALIPAY_WAP；
 *                        其余环境（含桌面浏览器）返回 null ——
 *                        此时收银台把微信 H5 与支付宝 WAP 并列给出，都不预选。
 *                        不预选是有意的：桌面上两条通道其实都调不起来，
 *                        随便选一个反而误导用户以为能用。
 */
export function defaultChannel() {
  if (isWechat()) return 'WXPAY_JSAPI'
  if (isAlipay()) return 'ALIPAY_WAP'
  return null
}

/**
 * 判断是否移动端。
 *
 * @returns {boolean} 用于给桌面浏览器一个「请用手机打开体验更好」的提示
 */
export function isMobile() {
  return /Android|iPhone|iPad|iPod|HarmonyOS|Mobile/i.test(navigator.userAgent)
}
