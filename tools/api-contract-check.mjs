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
 * 删免费活动、还原机台状况与商品上下架）。**不要指向生产库。**
 *
 * 用法：{@code node tools/api-contract-check.mjs}（后端需在 8080 运行）
 */

import { endpoints } from './cdp.mjs'

const BASE = endpoints().api

let pass = 0
let fail = 0
let skipped = 0

function check(label, cond, extra = '') {
  if (cond) {
    pass++
    console.log(`  ✓ ${label}`)
  } else {
    fail++
    console.log(`  ✗ ${label} ${extra}`)
  }
}

/**
 * 逐条检查字段是否存在（值可以是 null / 0 / false，但键必须存在）。
 *
 * <p><b>列表为空时跳过，而不是判失败</b>：那说明库里还没有这类数据
 *（刚清空过，或是全新部署），而「列表为空」是真实的数据状态，不是接口的缺陷。
 * 但跳过会明确打印出来、并在最后的汇总里计数 —— 免得「全绿」掩盖了
 * 「其实一条都没检查」。
 *
 * <p>判失败那版来自一次真实的误判：2026-09-30 清空开发库之后，
 * 脚本报「公告记录 / 包场记录 —— 记录存在」两条失败，
 * 看起来像接口坏了，实际只是没有样本可查。
 */
function checkFields(label, obj, fields) {
  if (!obj) {
    skipped++
    console.log(`  ⏭ ${label} —— 列表为空，跳过字段检查（不计失败）`)
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

/**
 * 用 multipart/form-data 传一个内存里合成的文件。
 *
 * <p>零依赖不变：{@code FormData} 与 {@code Blob} 都是 Node 18+ 的原生实现。
 *
 * <p>⚠️ <b>不要手写 Content-Type</b>：fetch 认出 FormData 会自己补上
 * {@code boundary=...}，而手写的那个字符串没有 boundary ——
 * 后端会解析不出任何字段，返回一个与预期完全不相干的错误。
 *（前端 {@code api/admin.js} 与 {@code api/upload.js} 里踩的是同一个坑。）
 *
 * @param {string} path 接口路径
 * @param {object} opts
 * @param {string} opts.token       登录令牌
 * @param {string} opts.file        文件内容（文本）
 * @param {string} opts.fileName    文件名
 * @param {string} [opts.contentType] 内容类型，默认 text/csv
 * @returns {Promise<{status:number, body:object|null}>}
 */
async function callMultipart(path, { token, file, fileName, contentType = 'text/csv' } = {}) {
  const form = new FormData()
  form.append('file', new Blob([file], { type: contentType }), fileName)

  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: form
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
  // cardType 与 totalStayMinutes 是这一版新加的两个【算出来的】列 ——
  // 前者看有没有生效中的月卡，后者对 biz_order.stay_minutes 求和。
  // 字段名写错的话前端那一列会静默显示空，所以在这里钉一下
  checkFields('用户记录', someUser || users.body?.data?.records?.[0],
    ['id', 'username', 'nickname', 'status', 'cardType', 'totalStayMinutes'])

  // 筛选与排序：三个排序键都要能走通
  for (const key of ['createdAt', 'totalPaid', 'stayMinutes']) {
    const r = await call('GET', `/api/admin/users?page=1&size=5&sortBy=${key}&desc=true`, { token })
    check(`按 ${key} 排序不报错`, r.status === 200)
  }

  // ⚠️ 排序键只作白名单查表的键，绝不拼进 SQL。这条用一个明显的注入串去试 ——
  // 期望的是「被丢掉并回落到默认排序」，而不是 500，更不是真的执行了它
  const injected = await call('GET',
    '/api/admin/users?page=1&size=5&sortBy=' + encodeURIComponent("1;DROP TABLE sys_user;--"),
    { token })
  check('白名单外的排序键被安静丢掉（不是 500，更不是注入）', injected.status === 200)
  const stillThere = await call('GET', '/api/admin/users?page=1&size=1', { token })
  check('上一条顺带证明 sys_user 表还在', stillThere.status === 200)

  // 月卡筛选的两个方向都要能走 —— 只测一个方向的话，条件写反了也照样绿
  for (const hasCard of ['true', 'false']) {
    const r = await call('GET', `/api/admin/users?page=1&size=5&hasCard=${hasCard}`, { token })
    check(`按月卡（hasCard=${hasCard}）筛选不报错`, r.status === 200)
  }

  if (someUser) {
    // 编辑不存在的人：该 404，而不是 500
    const missing = await call('PUT', '/api/admin/users/99999999', {
      token,
      body: { nickname: '不存在的用户' }
    })
    check('管理员改不存在的用户返回 404', missing.status === 404,
      `实际 ${missing.status} ${JSON.stringify(missing.body)}`)
  }

  if (someUser) {
    const start = new Date(Date.now() + 3 * 24 * 3600 * 1000)
    start.setHours(10, 0, 0, 0)
    const end = new Date(start.getTime() + 2 * 3600 * 1000)

    // ⚠️ price 不能是 0：0 元的包场在 BookingService#create 里直接落成 PAID
    //（店主拿自家场地招待朋友，不需要付款），而改期与取消都要求「待付款」——
    // 于是那一场既改不了也删不掉，永久占着「3 天后 10:00」那个时段。
    //
    // 这个坑的表现极具误导性：下一次跑脚本时报的是 40912「该时段已有其他包场」，
    // 看起来像「别人排了场」，实际是自己上一次留下的、删不掉的场次。
    // 而且它会自我累积 —— 每跑一次多留一场，失败信息里完全不提真正的原因
    const made = await call('POST', '/api/admin/bookings', {
      token,
      body: { hostUserId: someUser.id, startAt: fmt(start), endAt: fmt(end), price: 1, remark: '【契约核对】临时' }
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
      // price 同样不能是 0 —— 理由见上面 [3] 段那条注释：
      // 0 元包场落库即 PAID，删不掉，会把这一段的日期永久占住
      const rebook = await call('POST', '/api/admin/bookings', {
        token,
        body: { hostUserId: adminId, startAt: fmt(start), endAt: fmt(end), price: 1, remark: '【契约核对】占位检查' }
      })
      check('撤销后时段重新开放（能再排一场）', rebook.status === 200, JSON.stringify(rebook.body))
      if (rebook.body?.data?.id) {
        await call('DELETE', `/api/admin/bookings/${rebook.body.data.id}`, { token })
      }
    }
  }

  /* ---------- 3.8 免费时段（活动） ---------- */
  /*
   * 活动时段取在 2027-03-01 的凌晨 —— 那是个肯定没有既有活动的时刻，
   * 所以「新建成功」「与它重叠被拒」两条都能稳定判定，
   * 不会因为开发库里恰好排了什么而变成偶发失败。
   */
  console.log('\n[3.8] 免费时段 /api/admin/store/free-periods')
  const freeList = await call('GET', '/api/admin/store/free-periods?page=1&size=10', { token })
  check('后台列表可访问', freeList.status === 200)
  check('分页字段 total/records',
    'total' in (freeList.body?.data || {}) && Array.isArray(freeList.body?.data?.records))
  checkFields('活动记录', freeList.body?.data?.records?.[0], [
    'id', 'startAt', 'endAt', 'reason', 'createdBy', 'createdAt'
  ])

  const freeOne = await call('POST', '/api/admin/store/free-periods', {
    token,
    body: { startAt: '2027-03-01 02:00:00', endAt: '2027-03-01 06:00:00', reason: '【契约核对】临时活动' }
  })
  check('新增活动', freeOne.status === 200, JSON.stringify(freeOne.body))
  const freeId = freeOne.body?.data?.id

  const freeOverlap = await call('POST', '/api/admin/store/free-periods', {
    token,
    body: { startAt: '2027-03-01 04:00:00', endAt: '2027-03-01 08:00:00', reason: '重叠的' }
  })
  check('与已有活动重叠被拒（40945）',
    freeOverlap.status === 409 && freeOverlap.body?.code === 40945,
    `实际 ${freeOverlap.status}/${freeOverlap.body?.code}`)

  // 半开区间 [start, end)：06:00 起的那一场与 02:00–06:00 那一场不算重叠。
  // 写成闭区间的话这条会红 —— 而那种错会让运营连办两场活动时被系统挡住
  const freeAdjacent = await call('POST', '/api/admin/store/free-periods', {
    token,
    body: { startAt: '2027-03-01 06:00:00', endAt: '2027-03-01 08:00:00', reason: '【契约核对】临时活动二' }
  })
  check('首尾相接不算重叠', freeAdjacent.status === 200, JSON.stringify(freeAdjacent.body))
  const adjacentId = freeAdjacent.body?.data?.id

  const freeReversed = await call('POST', '/api/admin/store/free-periods', {
    token,
    body: { startAt: '2027-03-01 08:00:00', endAt: '2027-03-01 02:00:00', reason: '倒置的' }
  })
  check('起止倒置被拒（40944）',
    freeReversed.status === 409 && freeReversed.body?.code === 40944,
    `实际 ${freeReversed.status}/${freeReversed.body?.code}`)

  const freePublic = await call('GET', '/api/store/free-periods?limit=3')
  check('用户端匿名可访问', freePublic.status === 200)
  check('用户端返回的是数组而不是分页对象', Array.isArray(freePublic.body?.data))
  checkFields('用户端活动', (freePublic.body?.data || [])[0], ['id', 'startAt', 'endAt', 'reason'])

  const freeUpdated = await call('PUT', `/api/admin/store/free-periods/${freeId}`, {
    token,
    body: { startAt: '2027-03-01 02:00:00', endAt: '2027-03-01 05:00:00', reason: '【契约核对】改过了' }
  })
  check('改活动（含名称清空之外的常规改动）',
    freeUpdated.status === 200 && String(freeUpdated.body?.data?.reason || '').includes('改过了'))

  await call('DELETE', `/api/admin/store/free-periods/${freeId}`, { token })
  await call('DELETE', `/api/admin/store/free-periods/${adjacentId}`, { token })
  const freeAfter = await call('GET', '/api/admin/store/free-periods?page=1&size=100', { token })
  const leftOver = (freeAfter.body?.data?.records || []).filter((r) => r.id === freeId || r.id === adjacentId)
  check('删掉的两条不再出现在列表里', leftOver.length === 0, `还剩 ${leftOver.length} 条`)

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

  /* ---------- 5. 收款：通道、收款码与付款凭证 ---------- */
  console.log('\n[5] 收款：通道、收款码与付款凭证')

  const channels = await call('GET', '/api/payments/channels?targetType=ORDER', { token })
  check('通道列表可访问', channels.status === 200)
  const channelList = channels.body?.data || []
  check('每条通道都有用户口径的中文名与「要不要传凭证」标记',
    channelList.length > 0 && channelList.every((c) => c.label && typeof c.needProof === 'boolean'),
    JSON.stringify(channelList))
  check('  → 扫码转账在列（投产时它是唯一收款方式）',
    channelList.some((c) => c.channel === 'QR_UPLOAD'))

  const qrs = await call('GET', '/api/payments/qr', { token })
  check('收银台收款码接口可访问', qrs.status === 200)
  checkFields('收款码记录', qrs.body?.data?.[0], ['id', 'channel', 'channelLabel', 'name', 'imageUrl'])

  const proofs = await call('GET', '/api/admin/payment-proofs?page=1&size=10', { token })
  check('凭证列表可访问', proofs.status === 200)
  // 三个 ocr* 字段可能是 null（默认 provider=mock 时恒为 null），
  // 但只要 key 在，契约就算成立 —— checkFields 查的正是「字段在不在」
  checkFields('凭证记录', proofs.body?.data?.records?.[0], [
    'id', 'targetType', 'targetTypeLabel', 'targetId', 'orderNo', 'userId', 'userNickname',
    'amount', 'proofUrl', 'verifyStatus', 'verifyStatusLabel', 'risk', 'delivered', 'createdAt',
    'ocrPaymentNo', 'ocrAmount', 'ocrText'
  ])

  const pendingProofs = await call('GET', '/api/admin/payment-proofs?page=1&size=10&verifyStatus=SUBMITTED', { token })
  const allProofs = await call('GET', '/api/admin/payment-proofs?page=1&size=10&verifyStatus=', { token })
  check('凭证列表接受状态筛选', pendingProofs.status === 200 && allProofs.status === 200)
  check('  → 认不出的状态返回空列表（而不是当成「不过滤」把全部捞回来）',
    (await call('GET', '/api/admin/payment-proofs?page=1&size=10&verifyStatus=NOPE', { token }))
      .body?.data?.total === 0)
  check('  → 传空串时不过滤，总数不少于只看待复核的',
    (allProofs.body?.data?.total ?? 0) >= (pendingProofs.body?.data?.total ?? 0))

  /* ---------- 6. 分页参数传错的表现 ---------- */
  console.log('\n[6] 边界：分页参数传错')
  const wrongPage = await call('GET', '/api/admin/products?page=2&size=1', { token })
  check('商品接口收到 page/size 时仍返回 200（参数被忽略）', wrongPage.status === 200)
  check('  → current 仍是 1（说明参数确实被忽略、永远第一页）', wrongPage.body?.data?.current === 1)

  /* ---------- 7. 对账（支付改造 Phase 5） ---------- */
  console.log('\n[7] 对账：上传账单、批次与差异')

  /*
   * 用<b>系统标准模板</b>合成一份账单 —— 它的表头最短，也不依赖任何真实账号。
   *
   * 单号特意取「检查专用」的前缀，好让开发库里留下的那条批次一眼能认出来：
   * 批次表是 append-only 的（没有删除接口），这份脚本**无法自清理**，
   * 详见 tools/README.md 的「已知不做的事」。
   */
  const billTime = fmt(new Date(Date.now() - 3600 * 1000))
  const billCsv = [
    '交易时间,交易单号,金额(元),收/支,交易状态,备注',
    `${billTime},CHECK-4200-0001,8.00,收入,交易成功,契约检查`
  ].join('\n')

  const uploaded = await callMultipart('/api/admin/reconcile-batches', {
    token,
    file: billCsv,
    fileName: 'api-contract-check.csv'
  })
  check('上传账单并执行对账', uploaded.status === 200, JSON.stringify(uploaded.body))
  check('  → 解析出 1 笔、金额是 8.00',
    uploaded.body?.data?.billCount === 1 && Number(uploaded.body?.data?.billAmount) === 8,
    JSON.stringify(uploaded.body?.data))
  check('  → 系统里没人认领这笔 → 记 1 条「账单无对应凭证」',
    uploaded.body?.data?.diffCount === 1, JSON.stringify(uploaded.body?.data))
  checkFields('批次记录', uploaded.body?.data, [
    'id', 'channel', 'channelLabel', 'fileName', 'periodStart', 'periodEnd',
    'windowStart', 'windowEnd', 'billCount', 'billAmount', 'billExcludedCount',
    'billSkippedCount', 'proofCount', 'proofAmount', 'proofSkippedCount',
    'matchedCount', 'matchedAmount', 'diffCount', 'unhandledCount', 'hasBillFile'
  ])

  const batchId = uploaded.body?.data?.id
  check('  → 渠道由表头认出来（标准模板，不是人工选的）',
    uploaded.body?.data?.channel === 'STANDARD', uploaded.body?.data?.channel)

  const batches = await call('GET', '/api/admin/reconcile-batches?page=1&size=10', { token })
  check('批次列表可访问 + 分页结构',
    batches.status === 200 && typeof batches.body?.data?.total === 'number')

  const detail = await call('GET', `/api/admin/reconcile-batches/${batchId}`, { token })
  check('批次详情可访问 + 带 diffTypeCounts',
    detail.status === 200 && detail.body?.data?.diffTypeCounts !== undefined)
  check('  → 今天新造的那条差异算在 BILL_ONLY 里',
    (detail.body?.data?.diffTypeCounts?.BILL_ONLY ?? 0) >= 1,
    JSON.stringify(detail.body?.data?.diffTypeCounts))

  const diffs = await call('GET', `/api/admin/reconcile-batches/${batchId}/diffs?page=1&size=20`, { token })
  check('差异列表可访问', diffs.status === 200)
  checkFields('差异记录', diffs.body?.data?.records?.[0], [
    'id', 'batchId', 'diffType', 'diffTypeLabel', 'diffTypeHint', 'proofId',
    'paymentNo', 'orderNo', 'targetType', 'targetTypeLabel',
    'billAmount', 'proofAmount', 'billTime', 'billSummary', 'handled', 'createdAt'
  ])

  const byType = await call('GET',
    `/api/admin/reconcile-batches/${batchId}/diffs?page=1&size=20&diffType=BILL_ONLY`, { token })
  check('  → 能按类型筛', byType.status === 200 && byType.body?.data?.total >= 1)
  check('  → 认不出的类型返回空列表（而不是当成「不过滤」把全部捞回来）',
    (await call('GET',
      `/api/admin/reconcile-batches/${batchId}/diffs?page=1&size=20&diffType=NOPE`, { token }))
      .body?.data?.total === 0)

  const badHeader = await callMultipart('/api/admin/reconcile-batches', {
    token,
    file: '姓名,电话\n张三,13800000000',
    fileName: 'bad-header.csv'
  })
  check('  → 认不出表头被拒（40004，且提示里带着表头）',
    badHeader.status === 400 && badHeader.body?.code === 40004
      && (badHeader.body?.message || '').includes('姓名'),
    JSON.stringify(badHeader.body))

  const emptyBill = await callMultipart('/api/admin/reconcile-batches', {
    token,
    file: '交易时间,交易单号,金额(元),收/支,交易状态,备注\n',
    fileName: 'empty-bill.csv'
  })
  check('  → 空账单被拒（40005，而不是静默建一个 0 笔的批次）',
    emptyBill.status === 400 && emptyBill.body?.code === 40005,
    JSON.stringify(emptyBill.body))

  const anonymous = await call('GET', `/api/admin/reconcile-batches/${batchId}/file`)
  check('  → 不带 token 下载账单被拒（401）', anonymous.status === 401)

  const missingBatch = await call('GET', '/api/admin/reconcile-batches/99999999', { token })
  check('  → 批次不存在返回 404', missingBatch.status === 404)

  const diffId = diffs.body?.data?.records?.[0]?.id
  if (diffId) {
    const handled = await call('POST', `/api/admin/reconcile-diffs/${diffId}/handle`, {
      token,
      body: { note: '契约检查' }
    })
    check('标记差异已处理', handled.status === 200, JSON.stringify(handled.body))

    const again = await call('POST', `/api/admin/reconcile-diffs/${diffId}/handle`, {
      token,
      body: { note: '再来一次' }
    })
    check('  → 重复标记返回 409（40943，状态守卫生效）',
      again.status === 409 && again.body?.code === 40943, JSON.stringify(again.body))
  } else {
    check('差异列表里有可标记的条目', false, '列表为空，跳过了「标记」与「重复标记」两条')
  }

  /* ---------- 8. 订单详情：分段账单 ---------- */
  console.log('\n[8] 订单详情：分段账单 /api/admin/orders')

  const orderPage = await call('GET', '/api/admin/orders?page=1&size=20', { token })
  check('订单列表可访问 + 分页结构',
    orderPage.status === 200 && typeof orderPage.body?.data?.total === 'number')
  checkFields('订单记录', orderPage.body?.data?.records?.[0], [
    'id', 'orderNo', 'status', 'statusText', 'stayMinutes', 'dayMinutes', 'dayAmount',
    'totalAmount', 'payableAmount', 'paymentMethodLabel'
  ])
  check('  → 列表不携带分段账单（bill 恒为空，账单只在详情里给）',
    !orderPage.body?.data?.records?.[0]?.bill)

  /*
   * 挑一条「已结算」的订单看详情 —— 只有结算过的才有账单（使用中的没有），
   * 且老订单在重算与落库对不上时也可能没有。所以多试几条，找到一条带账单的为止；
   * 一条都没有就跳过结构检查（开发库里全是老订单时会这样）。
   */
  const settledOrders = (orderPage.body?.data?.records || [])
    .filter((o) => o.endTime && o.status !== 'IN_USE')
    .slice(0, 5)

  let orderDetail = null
  for (const candidate of settledOrders) {
    const detail = (await call('GET', `/api/admin/orders/${candidate.id}`, { token })).body?.data
    if (!orderDetail) orderDetail = detail
    if (detail?.bill) {
      orderDetail = detail
      break
    }
  }
  checkFields('订单详情', orderDetail,
    ['id', 'orderNo', 'bill', 'freeByBooking', 'stayMinutes'])

  if (orderDetail?.bill) {
    checkFields('分段账单', orderDetail.bill, ['segments', 'totalMinutes', 'totalAmount',
      'discountAmount', 'cardFreeAmount', 'activityFreeAmount'])
    check('  → 每段都带档数、单价与封顶（账单能解释钱是怎么算的）',
      orderDetail.bill.segments.length > 0
        && orderDetail.bill.segments.every((s) =>
          s.period && s.minutes !== undefined && s.units !== undefined
          && s.unitPrice !== undefined && s.capAmount !== undefined && s.amount !== undefined),
      JSON.stringify(orderDetail.bill.segments?.[0]))
  } else {
    skipped++
    console.log('  ⏭ 分段账单 —— 这条订单没有可展示的账单（老订单重算对不上时就没有），跳过结构检查')
  }

  const skipNote = skipped ? ` / ${skipped} 跳过（列表为空，无样本可查）` : ''
  console.log(`\n结果：${pass} 通过 / ${fail} 失败${skipNote}`)
  process.exit(fail ? 1 : 0)
}

run().catch((e) => {
  console.error('脚本异常：', e)
  process.exit(2)
})
