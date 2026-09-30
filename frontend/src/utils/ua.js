/**
 * 浏览器环境探测，用于选择支付通道。
 *
 * <p><b>为什么必须自动探测、而不是让用户自己选通道</b>：
 * 微信内打不开支付宝、支付宝内也打不开微信（双方互相屏蔽外链）。
 * 让用户选的话，必然出现「选了却调不起来」的死路 ——
 * 而这个死路在技术人员手里不容易复现（我们多半两个 App 都装了，
 * 且习惯用系统浏览器测试）。
 *
 * <p>本模块只负责【给一个默认值】。用户仍可手动切换 ——
 * 微信里想用支付宝是常见需求（零钱不够、有红包），强制跳转会让这类用户完全没法付款。
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
