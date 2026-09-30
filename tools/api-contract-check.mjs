/**
 * 运营后台接口契约核对。
 *
 * <p><b>它防的是哪一类错</b>：前端把字段名写错<b>不会有任何报错</b>，
 * 页面只是显示空白。本项目已经因此栽过四次（`discounted` 写成 `reached`、
 * 机台的 `typeCode`、状况的 `statusLabel`、月卡的 `label`/`cardType`）——
 * 每一次都是靠事后人工核对才发现的。这个脚本把「事后核对」变成一条命令。
 *
 * <p>做法：用真实登录拿到的 token 逐个打后台接口，检查前端用到的每个字段
 * 是否真的存在，并顺带把几条**业务规则**跑成断言（自动公告改不动、
 * 不带 status 时状况保持原值、过去时段排不了包场……）——
 * 这些规则错了同样不报错，只是行为不对。
 *
 * <p>⚠️ 会往开发库里写少量数据，跑完自己清理（删公告、取消包场、
 * 还原机台状况与商品上下架）。**不要指向生产库。**
 *
 * 用法：{@code node tools/api-contract-check.mjs}（后端需在 8080 运行）
 */

import { endpoints } from './cdp.mjs'

const BASE = endpoints().api

let pass = 0
let fail = 0

function check(label, cond, extra = '') {
  if (cond) {
    pass++
    console.log(`  ✓ ${label}`)
  } else {
    fail++
    console.log(`  ✗ ${label} ${extra}`)
  }
}

/** 逐条检查字段是否存在（值可以是 null / 0 / false，但键必须存在）。 */
function checkFields(label, obj, fields) {
  if (!obj) {
    check(`${label} —— 记录存在`, false)
    return
  }
  const missing = fields.filter((f) => !(f in obj))
  check(`${label} 字段齐全`, missing.length === 0, missing.length ? `缺少: ${missing.join(', ')}` : '')
}

async function call(method, path, { token, body } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  })
  let json = null
  try {
    json = await res.json()
  } catch (e) {
    /* 无响应体 */
  }
  return { status: res.status, body: json }
}

/** 后端只认 yyyy-MM-dd HH:mm:ss（见 JacksonConfig）。 */
function fmt(d) {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const run = async () => {
  /* ---------- 0. 登录 ---------- */
  console.log('\n[0] 管理员登录')
  const login = await call('POST', '/api/auth/login', {
    body: {
      username: process.env.ADMIN_USER || 'admin',
      password: process.env.ADMIN_PASSWORD || 'admin123'
    }
  })
  check('登录成功', login.status === 200 && login.body?.data?.token, JSON.stringify(login.body))
  const token = login.body?.data?.token
  check('角色是 ADMIN', login.body?.data?.user?.role === 'ADMIN')
  if (!token) {
    console.log('\n拿不到 token，后面的检查没有意义，退出。')
    process.exit(2)
  }

  /* ---------- 1. 公告 ---------- */
  console.log('\n[1] 公告管理 /api/admin/notices')
  const notices = await call('GET', '/api/admin/notices?page=1&size=10', { token })
  check('列表可访问', notices.status === 200)
  check('分页字段 total/records', 'total' in (notices.body?.data || {}) && Array.isArray(notices.body?.data?.records))
  checkFields('公告记录', notices.body?.data?.records?.[0], [
    'id', 'title', 'content', 'publishMode', 'publishModeText',
    'sourceType', 'sourceTypeText', 'sourceId', 'createdBy', 'createdAt', 'editable'
  ])

  const created = await call('POST', '/api/admin/notices', {
    token,
    body: { title: '【契约核对】临时公告', content: '这条会被脚本删掉' }
  })
  check('新建手写公告', created.status === 200, JSON.stringify(created.body))
  check('新建的是手写', created.body?.data?.publishMode === 'MANUAL' && created.body?.data?.editable === true)
  const noticeId = created.body?.data?.id

  const updated = await call('PUT', `/api/admin/notices/${noticeId}`, {
    token,
    body: { title: '【契约核对】临时公告（已改）', content: '改过了' }
  })
  check('改手写公告', updated.status === 200 && updated.body?.data?.title.includes('已改'))

  const autoNotice = notices.body?.data?.records?.find((r) => !r.editable)
  if (autoNotice) {
    const denied = await call('PUT', `/api/admin/notices/${autoNotice.id}`, {
      token,
      body: { title: 'x', content: 'y' }
    })
    check('改自动公告被拒（40929）', denied.status === 409 && denied.body?.code === 40929,
      `实际 ${denied.status}/${denied.body?.code}`)
  } else {
    console.log('  - 库里没有自动公告，跳过 40929 那条（改一次机台状况就会产生一条）')
  }

  const deleted = await call('DELETE', `/api/admin/notices/${noticeId}`, { token })
  check('删手写公告', deleted.status === 200)

  /* ---------- 1.5 公告置顶与分页 ---------- */
  console.log('\n[1.5] 公告置顶 / 分页 /api/store/notices')
  const pinned = await call('POST', '/api/admin/notices', {
    token,
    body: { title: '【契约核对】置顶公告', content: '这条会被脚本删掉', pinned: 1 }
  })
  check('发一条置顶公告', pinned.status === 200 && pinned.body?.data?.pinned === true,
    JSON.stringify(pinned.body))
  const pinnedId = pinned.body?.data?.id

  const userList = await call('GET', '/api/store/notices?page=1&size=4')
  check('用户端公告列表可访问（匿名）', userList.status === 200)
  check('用户端公告返回分页结构', 'total' in (userList.body?.data || {})
    && Array.isArray(userList.body?.data?.records))
  checkFields('用户端公告记录', userList.body?.data?.records?.[0],
    ['id', 'title', 'content', 'publishMode', 'publishModeText', 'createdAt', 'pinned'])
  check('每页条数生效（size=4）', (userList.body?.data?.records || []).length <= 4)
  check('置顶的排在最前', userList.body?.data?.records?.[0]?.id === pinnedId,
    `首条是 ${userList.body?.data?.records?.[0]?.title}`)

  const tooBig = await call('GET', '/api/store/notices?page=1&size=99')
  check('每页条数超上限被拒（400）', tooBig.status === 400, `实际 ${tooBig.status}`)

  if (pinnedId) {
    await call('DELETE', `/api/admin/notices/${pinnedId}`, { token })
  }

  // 「取消置顶后回到原位」必须拿一条**既有**公告来验：
  // 刚发的那条本来就最新，取消置顶后照样排第一，看不出任何变化（这个坑踩过一次）。
  // ⚠️ 会临时改一条既有手写公告的置顶标记，验完立刻改回去。
  const existing = await call('GET', '/api/store/notices?page=1&size=20')
  const oldOne = (existing.body?.data?.records || [])
    .filter((n) => n.publishMode === 'MANUAL' && !n.pinned)
    .pop()
  if (oldOne) {
    await call('PUT', `/api/admin/notices/${oldOne.id}`, {
      token,
      body: { title: oldOne.title, content: oldOne.content, pinned: 1 }
    })
    const afterPin = await call('GET', '/api/store/notices?page=1&size=20')
    check('把一条旧公告置顶后它排到最前',
      afterPin.body?.data?.records?.[0]?.id === oldOne.id,
      `首条是「${afterPin.body?.data?.records?.[0]?.title}」`)

    await call('PUT', `/api/admin/notices/${oldOne.id}`, {
      token,
      body: { title: oldOne.title, content: oldOne.content, pinned: 0 }
    })
    const afterUnpin = await call('GET', '/api/store/notices?page=1&size=20')
    check('取消置顶后它回到原位（不再排第一）',
      afterUnpin.body?.data?.records?.[0]?.id !== oldOne.id)
  } else {
    console.log('  - 库里没有可改的手写公告，跳过「取消置顶」那两条')
  }

  /* ---------- 2. 机台 ---------- */
  console.log('\n[2] 机台管理 /api/admin/devices')
  const devices = await call('GET', '/api/admin/devices', { token })
  check('列表可访问（不分页）', devices.status === 200 && Array.isArray(devices.body?.data))
  checkFields('机台记录', devices.body?.data?.[0], [
    'id', 'name', 'deviceNo', 'typeId', 'typeCode', 'typeName',
    'location', 'status', 'statusLabel', 'sort', 'remark', 'updatedAt'
  ])

  const types = await call('GET', '/api/devices/types')
  checkFields('类型字典', types.body?.data?.[0], ['id', 'code', 'name'])

  const dev = devices.body?.data?.[0]
  if (dev) {
    const origStatus = dev.status
    const next = origStatus === 'MAINTAINING' ? 'NORMAL' : 'MAINTAINING'
    const changed = await call('PUT', `/api/admin/devices/${dev.id}/status`, { token, body: { status: next } })
    check('单独改状况', changed.status === 200, JSON.stringify(changed.body))

    const after = await call('GET', '/api/admin/devices', { token })
    const row = after.body?.data?.find((d) => d.id === dev.id)
    check('状况已生效', row?.status === next)
    check('statusLabel 随后端更新', row?.statusLabel && row.statusLabel !== dev.statusLabel,
      `实际 "${row?.statusLabel}"`)

    // 全量替换：只改名称，其余字段必须原样带回去，否则会被清空 / 归零
    const full = await call('PUT', `/api/admin/devices/${dev.id}`, {
      token,
      body: {
        name: dev.name,
        deviceNo: dev.deviceNo,
        typeId: dev.typeId,
        location: dev.location,
        sort: dev.sort,
        remark: dev.remark
      }
    })
    check('全量替换（不带 status）', full.status === 200, JSON.stringify(full.body))
    check('不带 status 时状况保持原值', full.body?.data?.status === next, `实际 ${full.body?.data?.status}`)
    check('排序未被清零', full.body?.data?.sort === dev.sort)

    const restored = await call('PUT', `/api/admin/devices/${dev.id}/status`, { token, body: { status: origStatus } })
    check('状况已还原', restored.status === 200)
  }

  /* ---------- 3. 包场 ---------- */
  console.log('\n[3] 包场排期 /api/admin/bookings')
  const bookings = await call('GET', '/api/admin/bookings?page=1&size=10', { token })
  check('列表可访问', bookings.status === 200)
  checkFields('包场记录', bookings.body?.data?.records?.[0], [
    'id', 'bookingNo', 'hostUserId', 'startAt', 'endAt', 'price', 'status', 'paidAt', 'remark', 'createdAt'
  ])
  check('包场没有 statusLabel（前端自己映射）',
    !('statusLabel' in (bookings.body?.data?.records?.[0] || {})))

  const users = await call('GET', '/api/admin/users?page=1&size=10', { token })
  check('用户列表可访问', users.status === 200)
  const someUser = users.body?.data?.records?.find((u) => u.role === 'USER')
  checkFields('用户记录', someUser || users.body?.data?.records?.[0], ['id', 'username', 'nickname', 'status'])

  if (someUser) {
    const start = new Date(Date.now() + 3 * 24 * 3600 * 1000)
    start.setHours(10, 0, 0, 0)
    const end = new Date(start.getTime() + 2 * 3600 * 1000)

    const made = await call('POST', '/api/admin/bookings', {
      token,
      body: { hostUserId: someUser.id, startAt: fmt(start), endAt: fmt(end), price: 0, remark: '【契约核对】临时' }
    })
    check('排一场包场', made.status === 200, JSON.stringify(made.body))
    check('新建即待付款', made.body?.data?.status === 'PENDING_PAYMENT')
    const bookingId = made.body?.data?.id

    const userDetail = await call('GET', `/api/admin/users/${someUser.id}`, { token })
    check('按 ID 查用户（后台包场列表里包场人昵称的来源）',
      userDetail.status === 200 && ('nickname' in (userDetail.body?.data || {})))

    if (bookingId) {
      const moved = await call('PUT', `/api/admin/bookings/${bookingId}`, {
        token,
        body: {
          startAt: fmt(new Date(start.getTime() + 3600 * 1000)),
          endAt: fmt(new Date(end.getTime() + 3600 * 1000)),
          price: 0,
          remark: '【契约核对】改期'
        }
      })
      check('改期待付款的场次', moved.status === 200, JSON.stringify(moved.body))

      const past = await call('POST', '/api/admin/bookings', {
        token,
        body: {
          hostUserId: someUser.id,
          startAt: fmt(new Date(Date.now() - 3600 * 1000)),
          endAt: fmt(new Date()),
          price: 0
        }
      })
      check('新建过去时段被拒（40911）', past.status === 409 && past.body?.code === 40911,
        `实际 ${past.status}/${past.body?.code}`)

      const cancelled = await call('DELETE', `/api/admin/bookings/${bookingId}`, { token })
      check('取消待付款的场次', cancelled.status === 200, JSON.stringify(cancelled.body))
    }
  }

  /* ---------- 3.5 撤销包场并退款 ---------- */
  console.log('\n[3.5] 撤销退款 /api/admin/bookings/{id}/revoke')
  {
    // ⚠️ 包场人必须用管理员自己：发起支付要校验归属（`loadForPay` 会比对
    // 「这单是不是你的」），借别人的场是付不了款的。管理员给自己排一场
    // 完全合理 —— 店主拿自家场地招待朋友就是这种情形
    const adminId = login.body?.data?.user?.id
    // ⚠️ 日期按当前时间错开：脚本失败时可能留下一场没撤销掉的包场，
    // 那个时段就被占住了 —— 固定在「5 天后」的话，第二次跑会静默撞上
    // 40912 而整段跳过（这个坑真踩过一次）
    const dayOffset = 5 + (Date.now() % 20)
    const start = new Date(Date.now() + dayOffset * 24 * 3600 * 1000)
    start.setHours(14, 0, 0, 0)
    const end = new Date(start.getTime() + 2 * 3600 * 1000)

    const made = await call('POST', '/api/admin/bookings', {
      token,
      body: {
        hostUserId: adminId,
        startAt: fmt(start),
        endAt: fmt(end),
        price: 1,
        remark: '【契约核对】退款用，跑完脚本会撤销掉'
      }
    })
    const revokeId = made.body?.data?.id
    // 显式 check 一下：不然失败会静默跳过下面整段（测试脚本最怕这种「全绿但没跑」）
    check('排一场退款用的包场', !!revokeId, JSON.stringify(made.body))

    if (revokeId) {
      // 待付款的不能退款 —— 那是「取消」，没有钱的事
      const tooEarly = await call('POST', `/api/admin/bookings/${revokeId}/revoke`, {
        token,
        body: { refundMode: 'MANUAL' }
      })
      check('待付款的不能撤销退款（40934）', tooEarly.status === 409 && tooEarly.body?.code === 40934,
        `实际 ${tooEarly.status}/${tooEarly.body?.code}`)

      // 走一遍真实的模拟支付：下单 → 模拟收银台付款 → 回调
      const pay = await call('POST', '/api/payments', {
        token,
        body: { targetType: 'BOOKING', targetId: revokeId, channel: 'WXPAY_JSAPI' }
      })
      check('包场能发起支付', pay.status === 200, JSON.stringify(pay.body))
      const outTradeNo = pay.body?.data?.outTradeNo
      if (outTradeNo) {
        const paid = await call('POST', '/api/payments/mock/pay', {
          token,
          body: { outTradeNo, channel: 'WXPAY_JSAPI' }
        })
        check('模拟收银台付款成功', paid.status === 200, JSON.stringify(paid.body))
      }

      const afterPay = await call('GET', `/api/admin/bookings?page=1&size=50`, { token })
      const paidRow = afterPay.body?.data?.records?.find((b) => b.id === revokeId)
      check('包场已转已付款', paidRow?.status === 'PAID', `实际 ${paidRow?.status}`)

      // 原路退回
      const revoked = await call('POST', `/api/admin/bookings/${revokeId}/revoke`, {
        token,
        body: { refundMode: 'ONLINE' }
      })
      check('撤销并原路退回成功', revoked.status === 200, JSON.stringify(revoked.body))
      const r = revoked.body?.data || {}
      check('状态转已退款', r.status === 'REFUNDED', `实际 ${r.status}`)
      check('退款字段齐全', ['refundMode', 'refundAmount', 'refundedAt', 'refundedBy', 'refundNo']
        .every((f) => f in r))
      check('退款方式是 ONLINE', r.refundMode === 'ONLINE')
      // ⚠️ 比数字而不是比字符串：BigDecimal 序列化成 JSON 数字会丢尾零
      // （1.00 变成 1），前端 formatMoney 正是为这件事存在的
      check('退款金额是全额', Number(r.refundAmount) === 1, `实际 ${r.refundAmount}`)
      check('退款单号由包场单号派生（重试才幂等）',
        typeof r.refundNo === 'string' && r.refundNo.startsWith('RF'), `实际 ${r.refundNo}`)

      // 再撤一次必须被挡下 —— 这是「不会把钱退两遍」的那道守卫
      const again = await call('POST', `/api/admin/bookings/${revokeId}/revoke`, {
        token,
        body: { refundMode: 'ONLINE' }
      })
      check('重复撤销被挡下（40934）', again.status === 409 && again.body?.code === 40934,
        `实际 ${again.status}/${again.body?.code}`)

      // 非法退款方式
      const badMode = await call('POST', `/api/admin/bookings/${revokeId}/revoke`, {
        token,
        body: { refundMode: 'WECHAT' }
      })
      check('非法退款方式被挡下（400）', badMode.status === 400, `实际 ${badMode.status}`)

      // 撤销后时段释放：同一个时段能重新排一场
      const rebook = await call('POST', '/api/admin/bookings', {
        token,
        body: { hostUserId: adminId, startAt: fmt(start), endAt: fmt(end), price: 0, remark: '【契约核对】占位检查' }
      })
      check('撤销后时段重新开放（能再排一场）', rebook.status === 200, JSON.stringify(rebook.body))
      if (rebook.body?.data?.id) {
        await call('DELETE', `/api/admin/bookings/${rebook.body.data.id}`, { token })
      }
    }
  }

  /* ---------- 4. 商品 ---------- */
  console.log('\n[4] 商品管理 /api/admin/products')
  const products = await call('GET', '/api/admin/products?pageNum=1&pageSize=10', { token })
  check('列表可访问（⚠️ 分页参数是 pageNum/pageSize）', products.status === 200)
  checkFields('商品记录', products.body?.data?.records?.[0], [
    'id', 'name', 'cover', 'description', 'price', 'stock', 'availableStock', 'soldOut', 'enabled', 'sortNo'
  ])
  check('enabled 是布尔（提交却要 1/0）', typeof products.body?.data?.records?.[0]?.enabled === 'boolean')

  const p = products.body?.data?.records?.[0]
  if (p) {
    const body = {
      name: p.name,
      cover: p.cover,
      description: p.description,
      price: p.price,
      stock: p.stock,
      sortNo: p.sortNo,
      enabled: p.enabled ? 0 : 1
    }
    const toggled = await call('PUT', `/api/admin/products/${p.id}`, { token, body })
    check('全量 PUT 改上下架', toggled.status === 200, JSON.stringify(toggled.body))
    check('enabled 已翻转', toggled.body?.data?.enabled === !p.enabled)
    check('封面未被清空', (toggled.body?.data?.cover || '') === (p.cover || ''),
      `原 "${p.cover}" → 现 "${toggled.body?.data?.cover}"`)
    check('描述未被清空', (toggled.body?.data?.description || '') === (p.description || ''))

    const back = await call('PUT', `/api/admin/products/${p.id}`, {
      token,
      body: { ...body, enabled: p.enabled ? 1 : 0 }
    })
    check('已还原', back.body?.data?.enabled === p.enabled)

    // 只发 enabled 会被非空校验挡下来 —— 这正是「商品没有单独上下架接口」的代价
    const onlyEnabled = await call('PUT', `/api/admin/products/${p.id}`, { token, body: { enabled: 0 } })
    check('只发 enabled 会被 400 挡下（说明必须走全量 PUT）', onlyEnabled.status === 400,
      `实际 ${onlyEnabled.status}`)
  }

  // 排序参数：库存从少到多
  const byStock = await call('GET', '/api/admin/products?pageNum=1&pageSize=10&stockAsc=true', { token })
  const stocks = (byStock.body?.data?.records || []).map((x) => x.stock)
  const ascending = stocks.every((s, i) => i === 0 || stocks[i - 1] <= s)
  check('stockAsc=true 时库存升序（排序做在 SQL 里，跨页有效）', ascending, `实际 ${JSON.stringify(stocks)}`)

  const orders = await call('GET', '/api/admin/products/orders?pageNum=1&pageSize=10', { token })
  check('商品订单列表可访问', orders.status === 200)
  checkFields('商品订单记录', orders.body?.data?.records?.[0], [
    'id', 'orderNo', 'productName', 'unitPrice', 'quantity', 'amount', 'status', 'statusLabel', 'paidAt', 'createdAt'
  ])

  /* ---------- 5. 分页参数传错的表现 ---------- */
  console.log('\n[5] 边界：分页参数传错')
  const wrongPage = await call('GET', '/api/admin/products?page=2&size=1', { token })
  check('商品接口收到 page/size 时仍返回 200（参数被忽略）', wrongPage.status === 200)
  check('  → current 仍是 1（说明参数确实被忽略、永远第一页）', wrongPage.body?.data?.current === 1)

  console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
  process.exit(fail ? 1 : 0)
}

run().catch((e) => {
  console.error('脚本异常：', e)
  process.exit(2)
})
