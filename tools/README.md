# tools —— 前端 / 接口的验证小工具

这三个脚本是**给本项目自己用的验收工具**，不是交付物的一部分，也不是自动化测试套件。
它们补的是「测试覆盖不到、而出错又不报错」的那一段空白。

> 背景：本项目的前端**没有单元测试**（页面逻辑量还撑不起一套测试框架）。
> 于是那些「写错了不报错、只是显示空白或行为不对」的问题，
> 一直靠人工核对 —— 而人工核对每次都要重来一遍。这几个脚本就是把那几步固化下来。

## 为什么不用 Playwright / Puppeteer

只需要「打开页面、点几下、读回文字、截图」这四件事。
为此装一个上百 MB 的浏览器自动化依赖、再跟着它升级，不划算。
Node 从 22 起内置了 `fetch` 与 `WebSocket`，而 CDP（Chrome DevTools Protocol）
本身就是 WebSocket 上的 JSON-RPC —— 所以**零依赖**就能驱动浏览器。
本机装了 Chrome 或 Edge 即可（找不到时用环境变量 `CHROME_PATH` 指定）。

## 三个脚本

| 脚本 | 验什么 | 需要后端 | 需要前端 |
|---|---|---|---|
| `api-contract-check.mjs` | **接口契约**：前端用到的字段名是否真的存在；几条业务规则（自动公告只读、全量替换的例外、过去时段排不了包场……） | ✅ 8080 | — |
| `browser-smoke.mjs` | **页面渲染**：后台四页能渲染出来、零控制台错误、弹层能弹、守卫拦得住越权 | ✅ | ✅ 5173 |
| `screenshots.mjs` | **版式截图**：手机 390 / 桌面 1280 两套，供人眼看 | ✅ | ✅ |

### 怎么跑

```bash
# 前置：MySQL 在跑，后端与前端各自起着
cd backend && mvn spring-boot:run          # 8080
cd frontend && npm run dev                 # 5173

# 然后（仓库根目录）
node tools/api-contract-check.mjs
node tools/browser-smoke.mjs
node tools/screenshots.mjs
```

地址与账号都可以用环境变量覆盖：`API_URL` / `APP_URL` / `ADMIN_USER` / `ADMIN_PASSWORD` / `CHROME_PATH` / `SHOT_DIR`。

### 它们各自防的是哪一类错

- **`api-contract-check`** —— 前端把字段名写错，**不会有任何报错**，页面只是空白。
  本项目为此栽过四次：`discounted` 写成 `reached`、机台分组的 `typeCode`、
  状况的 `statusLabel`、月卡的 `label`/`cardType`。每一次都是人工核对才发现的
- **`browser-smoke`** —— 接口全对、构建也过，**页面照样可能白屏或报错**。
  脚本把「零控制台错误」当成一条硬断言
- **`screenshots`** —— 没有断言，就是给人看的。⚠️ 它能证明「页面长得是那样」，
  证明不了「点下去对不对」

### 已知不做的事

- **不验完整交互链路**：没有真的提交表单、没有走一遍支付。
  `browser-smoke` 验的是「渲染出来了、没报错、关键文字在」
- **不是 CI**：脚本要连真后端与真数据库，跑一次几十秒，适合改完某个模块后手动跑一遍
- **`api-contract-check` 会往开发库写少量数据**（一条测试公告、一场包场、改一次机台状况），
  **跑完自己清理**。**不要指向生产库**
