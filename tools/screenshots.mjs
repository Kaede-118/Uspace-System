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
  // 用户管理是后台的默认落地页（2026-10-01 加的那个页面），别漏
  { name: 'admin-users', hash: '#/admin/users' },
  { name: 'admin-notices', hash: '#/admin/notices' },
  { name: 'admin-devices', hash: '#/admin/devices' },
  { name: 'admin-bookings', hash: '#/admin/bookings' },
  // 门店（停业 / 免费时段两个 tab）。导航改成两行之后这里是第二行第一个
  { name: 'admin-store', hash: '#/admin/store' },
  { name: 'admin-payments', hash: '#/admin/payments' },
  { name: 'admin-products', hash: '#/admin/products' },
  { name: 'user-home', hash: '#/home' },
  { name: 'user-mall', hash: '#/mall' },
  { name: 'user-mine', hash: '#/mine' },
  // 计费规则（价目表）。内容与 docs/用户版计费与优惠说明.md 对应，可用来核对两处是否一致
  { name: 'user-pricing', hash: '#/pricing' },
  // 在店名册。卡片是半宽两列的栅格（2026-10-03 改），
  // 一屏能看到几张是这次改版的验收点之一
  { name: 'user-instore', hash: '#/instore' }
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
