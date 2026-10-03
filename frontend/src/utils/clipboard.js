/**
 * 剪贴板写入（含降级路径）。
 *
 * <p><b>为什么要留一条降级路径</b>：{@code navigator.clipboard} 只在
 * <b>安全上下文</b>里可用 —— HTTPS 或 localhost。本机开发走 localhost 没问题，
 * 但这两种真实场景里它是 {@code undefined}：
 * <ul>
 *   <li>手机连局域网调试（{@code http://192.168.x.x:5173}）</li>
 *   <li>部分内嵌 WebView（用户端的套壳外壳）把它禁掉了</li>
 * </ul>
 * 不降级的话，用户点「复制」只会看到一个失败提示，而他毫无办法。
 *
 * <p>两个调用点：包场邀请链接、扫码转账的「复制金额」。两者都要给用户
 * 一个成功 / 失败的反馈，所以本函数在失败时<b>抛错</b>，
 * 由调用方决定怎么提示（而不是自己吞掉）。
 */

/**
 * 把文本写进剪贴板。
 *
 * @param {string} text 要复制的文本
 * @returns {Promise<void>} 成功时 resolve；两条路径都失败时 reject
 */
export async function copyText(text) {
  if (navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(text)
    return
  }
  copyFallback(text)
}

/**
 * 降级方案：临时 textarea + {@code execCommand}。
 *
 * <p>{@code execCommand} 已废弃，但它是非安全上下文里唯一还能用的办法 ——
 * 留着它比让那两类用户完全复制不了要好。
 *
 * <p>⚠️ <b>必须检查它的返回值</b>：{@code execCommand('copy')} 失败时返回
 * {@code false} 而<b>不抛异常</b>。不检查的话，函数会静默返回，
 * 调用方以为复制成功了，于是提示「已复制」—— 而剪贴板里什么都没有，
 * 用户粘出来的是上一次复制的东西。
 *
 * @param {string} text 要复制的文本
 * @throws {Error} 浏览器拒绝执行复制时
 */
function copyFallback(text) {
  const ta = document.createElement('textarea')
  ta.value = text
  // 不能用 display:none —— 隐藏的元素选不中，execCommand 会直接失败
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } finally {
    document.body.removeChild(ta)
  }
  if (!ok) {
    throw new Error('浏览器不允许自动复制')
  }
}
