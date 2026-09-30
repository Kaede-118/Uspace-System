/**
 * 浏览器渲染冒烟测试（运营后台 + 用户端回归）。
 *
 * <p><b>它补的是哪一段</b>：接口契约对得上，<b>不代表页面能渲染出来</b>。
 * 一个字段名在 VO 里改了、一个组件 import 错了、一个模板里引用了不存在的函数 ——
 * 构建能过、接口也对，但页面要么空白要么报错。
 * 本项目前几轮验收一直缺这一段，这个脚本把它补上。
 *
 * <p>做法：真的起一个无头浏览器、真的登录、真的把页面打开，
 * 然后读回正文文字并收集控制台错误。比「看截图」可靠的地方在于
 * <b>它会把「零控制台错误」当成一条断言</b>。
 *
 * <p>⚠️ 它验的是「渲染出来了、没报错、关键文字在」，
 * <b>没验完整交互链路</b>（没有真的提交表单、没有走一遍支付）。
 *
 * 前置：后端 8080 与前端 5173 都在跑。
 * 用法：{@code node tools/browser-smoke.mjs}
 */

import { launchBrowser, openPage, loginInto, endpoints, sleep } from './cdp.mjs'

const { api, app } = endpoints()

let pass = 0
let fail = 0
const check = (label, cond, extra = '') => {
  if (cond) {
    pass++
    console.log(`  ✓ ${label}`)
  } else {
    fail++
    console.log(`  ✗ ${label} ${extra}`)
  }
}

const run = async () => {
  const browser = await launchBrowser()
  const page = await openPage()
  try {
    await loginInto(page, { api, app })
    console.log(`[准备] 已登录，后端 ${api} / 前端 ${app}\n`)

    /* ---------- 后台四个页面 ---------- */
    const adminPages = [
      { name: '公告管理', hash: '#/admin/notices', expect: ['公告', '发公告'] },
      { name: '机台管理', hash: '#/admin/devices', expect: ['机台', '新增机台', '拍拍机'] },
      { name: '包场排期', hash: '#/admin/bookings', expect: ['包场', '排一场'] },
      { name: '商品管理', hash: '#/admin/products', expect: ['商品', '新增商品', '商品订单'] }
    ]

    let round = 0
    for (const p of adminPages) {
      page.errors.length = 0
      // 换查询串强制整页重载：只改 hash 是同文档导航，store 里的状态不会重建
      await page.goto(`${app}/${p.hash}`, ++round)
      await page.waitForContent()

      const text = String((await page.eval('document.body.innerText')) || '')
      const url = await page.eval('location.hash')

      console.log(`[${p.name}] ${url}`)
      check('路由停在后台页（没被守卫弹走）', String(url).includes('/admin'), `实际 ${url}`)
      for (const word of p.expect) {
        check(`页面出现「${word}」`, text.includes(word))
      }
      check('无控制台错误', page.errors.length === 0, page.errors.slice(0, 2).join(' | '))
      console.log(`    预览：${text.replace(/\s+/g, ' ').slice(0, 120)}`)
    }

    /* ---------- 弹层与滚动锁 ---------- */
    console.log('\n[交互] 公告页弹层')
    page.errors.length = 0
    await page.goto(`${app}/#/admin/notices`, 90)
    await page.waitForContent()
    await page.clickText('发公告')
    await sleep(600)
    const sheetText = String((await page.eval('document.body.innerText')) || '')
    check('弹层弹出（含标题与确认按钮）', sheetText.includes('发布公告') && sheetText.includes('确定'))
    check('背景滚动被锁住', (await page.eval('document.body.style.overflow')) === 'hidden')
    await page.clickText('×')
    await sleep(400)
    check('关闭后滚动锁解开', (await page.eval('document.body.style.overflow')) === '')
    check('弹层交互无控制台错误', page.errors.length === 0, page.errors.slice(0, 2).join(' | '))

    /* ---------- 商品页：两个 tab、下架确认、补货、库存排序 ---------- */
    console.log('\n[交互] 商品页')
    page.errors.length = 0
    await page.goto(`${app}/#/admin/products`, 91)
    await page.waitForContent()

    const before = JSON.parse(
      (await page.eval(`
        JSON.stringify([...document.querySelectorAll('.product__item')].map(function (i) {
          var n = i.querySelector('.product__name'), s = i.querySelector('.product__stock-value')
          return (n ? n.textContent.trim() : '?') + ':' + (s ? s.textContent.trim() : '?')
        }))
      `)) || '[]'
    )
    check('商品行渲染出名称与库存', before.length > 0, JSON.stringify(before))

    // 库存少的排前面：排序必须由后端做（前端排只能排当前页）
    await page.clickText('库存少→多')
    await page.waitFor(`document.querySelectorAll('.product__item').length > 0`)
    await sleep(600)
    const after = JSON.parse(
      (await page.eval(`
        JSON.stringify([...document.querySelectorAll('.product__item')].map(function (i) {
          return Number((i.querySelector('.product__stock-value') || {}).textContent)
        }))
      `)) || '[]'
    )
    const ascending = after.every((s, i) => i === 0 || after[i - 1] <= s)
    check('「库存少→多」按钮让列表按库存升序', ascending && after.length > 0, JSON.stringify(after))
    await page.clickText('库存少→多')

    // 下架要二次确认
    await sleep(500)
    await page.clickText('下架')
    await sleep(600)
    const confirmText = String((await page.eval('document.body.innerText')) || '')
    check('「下架」弹出二次确认', confirmText.includes('确定下架'))
    await page.clickText('取消')
    await sleep(400)

    // 补货：只改库存，且要显示「当前 → 改为」
    await page.clickText('补货')
    await sleep(600)
    const restockText = String((await page.eval('document.body.innerText')) || '')
    check('「补货」弹层出现', restockText.includes('调整后的库存总数'))
    check('补货有「当前 X → 改为 Y」预览', /当前库存\s*\d+\s*→\s*改为\s*\d+/.test(restockText))
    await page.setInput('#p-restock', '123')
    await sleep(300)
    check('改动输入后预览跟着变',
      /当前库存\s*\d+\s*→\s*改为\s*123/.test(String(await page.eval('document.body.innerText'))))
    await page.clickText('取消')

    check('商品页无控制台错误', page.errors.length === 0, page.errors.slice(0, 2).join(' | '))

    /* ---------- 用户端：首页公告栏与「全部公告」页 ---------- */
    console.log('\n[用户端] 公告栏')
    page.errors.length = 0
    await page.goto(`${app}/#/home`, 93)
    await page.waitForContent()

    const homeText = String((await page.eval('document.body.innerText')) || '')
    const homeNoticeCount = await page.eval(
      "document.querySelectorAll('.notice').length"
    )
    check('首页公告栏最多显示 4 条', typeof homeNoticeCount === 'number' && homeNoticeCount <= 4,
      `实际 ${homeNoticeCount} 条`)
    check('首页有「查看全部」入口', homeText.includes('查看全部'))
    check('首页无控制台错误', page.errors.length === 0, page.errors.slice(0, 2).join(' | '))

    page.errors.length = 0
    await page.clickText('查看全部')
    await page.waitForContent()
    const noticeUrl = await page.eval('location.hash')
    check('点「查看全部」进到全部公告页', String(noticeUrl).includes('/notices'), `实际 ${noticeUrl}`)

    const listText = String((await page.eval('document.body.innerText')) || '')
    const listCount = await page.eval("document.querySelectorAll('.notice').length")
    check('全部公告页列出公告', typeof listCount === 'number' && listCount > 0, `实际 ${listCount} 条`)
    check('全部公告页标题正确', listText.includes('全部公告'))
    check('全部公告页无控制台错误', page.errors.length === 0, page.errors.slice(0, 2).join(' | '))
    console.log(`    首页 ${homeNoticeCount} 条 / 全部页 ${listCount} 条`)

    /* ---------- 权限守卫 ---------- */
    console.log('\n[权限] 路由守卫')
    await page.eval(`localStorage.setItem('uspace_user', JSON.stringify({ username: 'nobody', role: 'USER' }))`)
    await page.goto(`${app}/#/admin/notices`, 92)
    await page.waitForContent()
    const userHash = await page.eval('location.hash')
    check('非管理员被弹回首页', !String(userHash).includes('/admin'), `实际 ${userHash}`)

    console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
    return fail ? 1 : 0
  } finally {
    page.close()
    browser.close()
  }
}

run()
  .then((code) => process.exit(code))
  .catch((e) => {
    console.error('脚本异常：', e)
    process.exit(2)
  })
