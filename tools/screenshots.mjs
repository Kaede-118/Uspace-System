/**
 * 给页面截图（手机 390px + 桌面 1280px）。
 *
 * <p>用途有两个：改完样式后自己看一眼版式对不对；
 * 以及答辩 / 论文里需要界面图时，一键出一套整齐的。
 *
 * <p>⚠️ 截图是给人看的，<b>不是验收</b> —— 它能证明「页面长得是那样」，
 * 证明不了「点下去对不对」。逻辑上的验收看 {@code browser-smoke.mjs}。
 *
 * 输出目录：环境变量 {@code SHOT_DIR}，默认系统临时目录下的 {@code uspace-shots}。
 * 用法：{@code node tools/screenshots.mjs}
 */

import { mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { launchBrowser, openPage, loginInto, endpoints } from './cdp.mjs'

const { api, app } = endpoints()
const OUT = process.env.SHOT_DIR || join(tmpdir(), 'uspace-shots')

/** 要截的页面。hash 是路由，name 是文件名前缀。 */
const PAGES = [
  { name: 'admin-notices', hash: '#/admin/notices' },
  { name: 'admin-devices', hash: '#/admin/devices' },
  { name: 'admin-bookings', hash: '#/admin/bookings' },
  { name: 'admin-products', hash: '#/admin/products' },
  { name: 'user-home', hash: '#/home' },
  { name: 'user-mall', hash: '#/mall' },
  { name: 'user-mine', hash: '#/mine' }
]

const SIZES = [
  { tag: 'phone', width: 390, height: 844, mobile: true },
  { tag: 'desktop', width: 1280, height: 800, mobile: false }
]

const run = async () => {
  mkdirSync(OUT, { recursive: true })
  const browser = await launchBrowser()
  const page = await openPage()
  try {
    await loginInto(page, { api, app })
    console.log(`已登录，开始截图 → ${OUT}`)

    let n = 0
    for (const size of SIZES) {
      await page.setViewport(size.width, size.height, size.mobile)
      for (const p of PAGES) {
        await page.goto(`${app}/${p.hash}`, ++n)
        await page.waitForContent()
        console.log('  ' + (await page.screenshot(`${OUT}/${p.name}-${size.tag}.png`)))
      }
    }
    console.log('\n完成。')
  } finally {
    page.close()
    browser.close()
  }
}

run().catch((e) => {
  console.error('脚本异常：', e)
  process.exit(1)
})
