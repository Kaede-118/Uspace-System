/**
 * 无头浏览器驱动小工具（Chrome DevTools Protocol）。
 *
 * <p><b>为什么不用 Playwright / Puppeteer</b>：本项目的验收只需要「打开页面、
 * 点几下、读回文字、截个图」，为此装一个上百 MB 的浏览器自动化依赖，
 * 还要跟着它的版本升级 —— 不划算。Node 从 22 起内置了 {@code fetch} 与
 * {@code WebSocket}，CDP 本身就是一个 WebSocket 上的 JSON-RPC，
 * 于是这件事**零依赖**就能做：本地装了 Chrome 或 Edge 就能跑。
 *
 * <p>用法见 {@code tools/README.md}，各脚本里也各有一段说明。
 */

import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { writeFileSync } from 'node:fs'

/** 找本机的浏览器。可用环境变量 CHROME_PATH 覆盖。 */
export function findBrowser() {
  if (process.env.CHROME_PATH && existsSync(process.env.CHROME_PATH)) {
    return process.env.CHROME_PATH
  }
  const candidates = [
    // Windows
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
    // macOS
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
    // Linux
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser'
  ]
  const hit = candidates.find((p) => existsSync(p))
  if (!hit) {
    throw new Error('找不到 Chrome / Edge。请设置环境变量 CHROME_PATH 指向浏览器可执行文件。')
  }
  return hit
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/**
 * 起一个无头浏览器并等它的调试端口就绪。
 *
 * @param {object} [options]
 * @param {number} [options.port] 调试端口
 * @returns {Promise<{port: number, close: Function}>}
 */
export async function launchBrowser({ port = 9222 } = {}) {
  const exe = findBrowser()
  const profile = `${process.env.TEMP || process.env.TMPDIR || '/tmp'}/uspace-cdp-${port}`

  const child = spawn(
    exe,
    [
      '--headless=new',
      '--disable-gpu',
      '--no-first-run',
      '--no-default-browser-check',
      `--remote-debugging-port=${port}`,
      // 非浏览器客户端连 CDP 时，Chrome 会校验 Origin，不带这个参数会直接拒绝握手
      '--remote-allow-origins=*',
      `--user-data-dir=${profile}`,
      'about:blank'
    ],
    { stdio: 'ignore', detached: false }
  )

  // 等端口起来
  for (let i = 0; i < 50; i++) {
    await sleep(200)
    try {
      const res = await fetch(`http://localhost:${port}/json/version`)
      if (res.ok) {
        return {
          port,
          close() {
            try {
              child.kill()
            } catch (e) {
              /* 已经退了就算了 */
            }
          }
        }
      }
    } catch (e) {
      /* 还没起来，继续等 */
    }
  }
  child.kill()
  throw new Error(`浏览器调试端口 ${port} 一直没就绪`)
}

/**
 * 连上浏览器的第一个页面目标。
 *
 * @param {object} [options]
 * @param {number} [options.port] 调试端口
 * @returns {Promise<Page>}
 */
export async function openPage({ port = 9222 } = {}) {
  const targets = await (await fetch(`http://localhost:${port}/json/list`)).json()
  const target = targets.find((t) => t.type === 'page')
  if (!target) throw new Error('浏览器里没有可用的页面目标')

  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => {
    ws.onopen = resolve
    ws.onerror = (e) => reject(new Error('连接 CDP 失败：' + (e?.message || '未知')))
  })

  const page = new Page(ws)
  await page.send('Runtime.enable')
  await page.send('Page.enable')
  return page
}

/**
 * 一个页面目标。
 *
 * <p>{@code errors} 里攒着页面抛出的异常与 {@code console.error} ——
 * 本项目的验收里「零控制台错误」是一条硬指标：
 * 页面渲染对了但底下报了错，往往意味着某个分支已经坏了。
 */
export class Page {
  constructor(ws) {
    this.ws = ws
    this.seed = 0
    this.pending = new Map()
    this.errors = []

    ws.onmessage = (e) => {
      const msg = JSON.parse(e.data)
      if (msg.id && this.pending.has(msg.id)) {
        this.pending.get(msg.id)(msg)
        this.pending.delete(msg.id)
        return
      }
      if (msg.method === 'Runtime.exceptionThrown') {
        const d = msg.params?.exceptionDetails
        this.errors.push(d?.exception?.description || d?.text || '未知异常')
      }
      if (msg.method === 'Runtime.consoleAPICalled' && msg.params?.type === 'error') {
        this.errors.push(
          (msg.params.args || []).map((a) => a.value ?? a.description).join(' ')
        )
      }
    }
  }

  /** 发一条 CDP 命令，等它的响应。 */
  send(method, params = {}) {
    return new Promise((resolve) => {
      const id = ++this.seed
      this.pending.set(id, resolve)
      this.ws.send(JSON.stringify({ id, method, params }))
    })
  }

  /**
   * 在页面里求值。
   *
   * <p>⚠️ 返回**复杂对象**（数组等）时 CDP 的序列化会给出 undefined，
   * 所以要在表达式里自己 {@code JSON.stringify}，拿到字符串再解析。
   */
  async eval(expression) {
    const res = await this.send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true
    })
    if (res.result?.exceptionDetails) {
      this.errors.push(res.result.exceptionDetails.exception?.description || '求值异常')
      return undefined
    }
    return res.result?.result?.value
  }

  /**
   * 打开一个地址。
   *
   * @param {string} url 完整地址
   * @param {string} [bust] 查询串。⚠️ 只改 hash 是**同文档导航**，
   *   页面不会重新加载、内存里的状态也不会重建 —— 要「真的重新进一次」
   *   就得换一个查询串（或调用方自己 reload）
   */
  async goto(url, bust) {
    const target = bust === undefined ? url : url.replace(/#/, `?r=${bust}#`)
    await this.send('Page.navigate', { url: target })
  }

  /** 等某个条件成立（在页面里求值，返回真即通过）。 */
  async waitFor(expression, { timeout = 12000, interval = 300 } = {}) {
    const rounds = Math.ceil(timeout / interval)
    for (let i = 0; i < rounds; i++) {
      await sleep(interval)
      if (await this.eval(expression)) return true
    }
    return false
  }

  /**
   * 等页面渲染出内容（正文长度达阈值）。
   *
   * <p>固定 sleep 在这台机器上不够可靠 —— Vite 首次编译、后端冷启动都能拖几秒，
   * 结果就是截出一张白屏或者对着空白页面点按钮。
   */
  async waitForContent(minLength = 30) {
    return this.waitFor(`(document.body.innerText || '').trim().length >= ${minLength}`)
  }

  /**
   * 点一个文字匹配的按钮或链接（前缀匹配，便于连图标一起写）。
   *
   * <p>⚠️ <b>必须同时找 {@code <a>}</b>：vue-router 的 {@code <router-link>}
   * 渲染出来是链接不是按钮，只找 {@code button} 的话会「点了没反应」——
   * 而脚本还以为自己点到了（曾经因此误判过一次）。
   */
  async clickText(label) {
    return this.eval(`
      (() => {
        const el = [...document.querySelectorAll('button, a')]
          .find(x => x.textContent.trim().startsWith(${JSON.stringify(label)}))
        if (el) el.click()
        return !!el
      })()
    `)
  }

  /**
   * 给输入框赋值。
   *
   * <p>⚠️ 不能只写 {@code el.value = x}：Vue 的 {@code v-model} 监听的是
   * {@code input} 事件，而直接赋值不会触发它 —— 表单看着填了，提交时是空的。
   */
  async setInput(selector, value) {
    return this.eval(`
      (() => {
        const el = document.querySelector(${JSON.stringify(selector)})
        if (!el) return false
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
        setter.call(el, ${JSON.stringify(value)})
        el.dispatchEvent(new Event('input', { bubbles: true }))
        return true
      })()
    `)
  }

  /** 按手机 / 桌面尺寸模拟。 */
  async setViewport(width, height, mobile = false) {
    await this.send('Emulation.setDeviceMetricsOverride', {
      width,
      height,
      deviceScaleFactor: 1,
      mobile
    })
  }

  /** 截图存到磁盘。 */
  async screenshot(file) {
    const shot = await this.send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(file, Buffer.from(shot.result.data, 'base64'))
    return file
  }

  close() {
    try {
      this.ws.close()
    } catch (e) {
      /* 忽略 */
    }
  }
}

/**
 * 登录取一个真 token，并把它写进页面的 localStorage。
 *
 * <p>走真实登录接口而不是伪造 token —— 脚本验的必须是真链路，
 * 假 token 连「签名对不对」都验不出来。
 *
 * <p>⚠️ <b>会先把页面导航到应用本身</b>：{@code localStorage} 按来源隔离，
 * 停在 {@code about:blank} 上写进去的凭证，应用那一侧根本读不到 ——
 * 表现是「明明登录了，一进后台就被守卫弹回登录页」。
 *
 * @param {Page} page 已连上的页面
 * @param {object} [options] 覆盖 {@link endpoints} 的地址与账号
 * @returns {Promise<object>} 登录响应的 data：{token, user}
 */
export async function loginInto(page, options = {}) {
  const { api, app, username, password } = { ...endpoints(), ...options }
  const user = username || process.env.ADMIN_USER || 'admin'
  const pass = password || process.env.ADMIN_PASSWORD || 'admin123'

  const res = await fetch(`${api}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: user, password: pass })
  })
  const body = await res.json()
  if (!body?.data?.token) {
    throw new Error(`登录失败（${user}）：` + JSON.stringify(body))
  }

  await page.goto(`${app}/#/login`, 0)
  await page.waitForContent()

  // 两个 key 与 utils/storage.js 保持一致
  await page.eval(`localStorage.setItem('uspace_token', ${JSON.stringify(body.data.token)})`)
  await page.eval(`localStorage.setItem('uspace_user', ${JSON.stringify(JSON.stringify(body.data.user))})`)
  return body.data
}

/** 读环境变量里的地址，给各脚本统一默认值。 */
export function endpoints() {
  return {
    api: process.env.API_URL || 'http://localhost:8080',
    app: process.env.APP_URL || 'http://localhost:5173'
  }
}

export { sleep }
