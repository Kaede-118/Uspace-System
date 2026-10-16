import { fileURLToPath, URL } from 'node:url'
import path from 'node:path'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 构建配置。
 *
 * <p><b>`/api` 不配 proxy，是刻意的</b>：后端 CORS 已放开所有来源，
 * 前端直连 `http://<hostname>:8080` 即可。给 API 走 proxy 会把跨域问题盖住 ——
 * 开发期一路顺畅，上线换到 nginx 托管时才发现预检或响应头没配对，
 * 而那时排障的线索比开发期少得多。
 *
 * <p>⚠️ <b>但 `/uploads` 必须配 proxy</b>，这与上面那条不矛盾，两件事的性质不同：
 * 用户上传的图在库里存的是【站内相对路径】（`/uploads/avatar/xxx.jpg`），
 * `<img :src="avatar">` 会被浏览器按【当前页面】的地址解析 ——
 * 页面在 5173，于是它去 5173 找图，而 Vite 上没有这个目录，
 * 返回的是 SPA 的 index.html（HTTP 200 + text/html）。
 * 结果就是：**上传提示成功、文件也确实躺在服务器上，但页面上什么都不显示**，
 * 控制台里还看不到 404（状态码是 200）。
 *
 * <p>静态资源走 proxy 不影响 CORS 的可见性 —— 它本来就是同源请求，
 * 生产环境由 nginx 直接托管 `/uploads/`（见 `docs/开发约定与设计说明.md` 的部署一节），
 * 与这里的配置是同一个意思。
 *
 * <p><b>`server.host = true`（监听 0.0.0.0）是给真机调试用的</b>：
 * 手机访问 `http://192.168.x.x:5173` 就能打开开发版页面。
 * 配合 `api/http.js` 里按 `location.hostname` 拼后端地址，手机连的就是本机的 8080，
 * 而不是手机自己的 8080。
 *
 * <p><b>⚡ `tunnel` 模式（`npm run dev:tunnel`）：内网穿透演示专用，只映射一个端口。</b>
 * 该模式下额外启用 `/api` 的 proxy，让前端与后端【同源】，请求链路变成：
 *
 * <pre>
 * 顾客浏览器 →（frp 隧道）→ Vite :5173 ── /api ──→ Spring Boot :8080
 * </pre>
 *
 * 只映射一条隧道即可，配合 `.env.tunnel` 里把 `VITE_API_BASE_URL` 设为空串
 * （axios 因此走相对路径，见 `api/http.js` 的 `??` 说明）。两者缺一不可：
 * 少了代理则 `/api/xxx` 打到 Vite 上没人接，少了空串则前端会去找 `:8080`。
 *
 * <p>⚠️ <b>为什么做成独立模式、而不是把 `/api` proxy 常开</b>：
 * 常开就永久失去上面第一条的收益（开发期不再暴露 CORS 配置错误）。
 * 独立模式下日常 `npm run dev` 的行为一个字节都没变。
 *
 * <p>⚠️ <b>生产环境不走这里</b>：那时这一层由 nginx 承担（托管 dist + 反代 `/api`），
 * 同样是同源、同样只需一个端口 —— 与本模式是同一个思路的两种落地。
 */
/**
 * 给构建产物里的带 hash 资源加强缓存（只在 `preview` 时起作用）。
 *
 * <p><b>为什么需要它</b>：`vite preview` 是预览工具，对**所有**文件一律发
 * {@code Cache-Control: no-cache}，浏览器每次打开页面都要拿着 ETag 回来问一遍
 * 「变了没」。经隧道一次往返一秒多，于是「明明加载过、怎么又要重新加载」。
 * 配了之后带 hash 的资源一年内直接用本地缓存，**一个请求都不发**。
 *
 * <p>⚠️ <b>只对 {@code /assets/} 生效，{@code index.html} 必须保持 no-cache</b> ——
 * HTML 引用的文件名带 hash，把它也强缓存的话，改版后用户永远拿不到新版本：
 * 旧资源还在、页面照跑，只是永远是旧的，**且不会报任何错**。
 *
 * <p>⚠️ <b>为什么要拦 {@code setHeader} 而不是直接设一次</b>：sirv（Vite 内部的
 * 静态服务）在响应时会**无条件**再设一遍 no-cache，把我们之前设的覆盖掉。
 * 所以这里把它的这一次写入挡掉，换成一年期强缓存。
 *
 * <p>生产环境由 nginx 承担同样的职责（`/assets/` 强缓存 + `index.html` no-cache），
 * 这里是同一个思路在 preview 上的落地，与 `tunnel` 模式让 Vite 顶替 nginx 是同一种做法。
 */
function immutableAssets() {
  return {
    name: 'uspace:immutable-assets',
    configurePreviewServer(server) {
      server.middlewares.use((req, res, next) => {
        if (!req.url.startsWith('/assets/')) return next()
        const setHeader = res.setHeader.bind(res)
        res.setHeader = (name, value) => {
          if (String(name).toLowerCase() === 'cache-control') return res
          return setHeader(name, value)
        }
        setHeader('Cache-Control', 'public, max-age=31536000, immutable')
        next()
      })
    }
  }
}

/**
 * 在产物里写下「这次是哪种构建」的标记（`dist/.build-mode`）。
 *
 * <p><b>为什么需要它</b>：穿透服役时前端产物必须由 `npm run build:tunnel` 生成
 *（`VITE_API_BASE_URL=` 空串 → 走相对路径 → 同源 → 由 preview 的 `/api` 反代转给后端）。
 * 而普通的 `npm run build` 会落回 `http://${location.hostname}:8080`
 *（见 `src/api/http.js` 那行 `??`）—— 于是**内网照跑、只有外网全挂**：
 * 外网那个域名上并没有 8080 端口。
 *
 * <p>⚠️ **2026-10-10 真的踩了**：改完前端顺手跑了一次普通构建覆盖了穿透产物，
 * 外网页面报「后端未启动」、内网一切正常，排查绕了一圈才想到是产物不对。
 * 所以这里把模式写进产物，由 {@link requireTunnelBuild} 在启动时校验 ——
 * 那种错要在**起服务时**响，而不是等外网用户报障。
 *
 * @param {boolean} isTunnel 本次构建是不是穿透模式
 * @returns {object} Vite 插件
 */
function buildModeMarker(isTunnel) {
  let outDir = 'dist'
  return {
    name: 'uspace:build-mode-marker',
    configResolved(config) {
      // 产物目录从配置里取，不在两处各写一遍
      outDir = config.build.outDir
    },
    closeBundle() {
      // 第一行是给校验读的，后面几行是给顺手点开这个文件的人看的
      writeFileSync(path.resolve(outDir, '.build-mode'),
        (isTunnel ? 'tunnel' : 'default') + '\n'
        + '# 本文件由 vite.config.js 的 uspace:build-mode-marker 写入，别手改。\n'
        + '# tunnel  = npm run build:tunnel（穿透 / 生产服役用的，走相对路径）\n'
        + '# default = npm run build（日常预览用，直连 http://<hostname>:8080）\n'
        + '# preview:tunnel 遇到 default 会拒绝启动。\n')
    }
  }
}

/**
 * 用 `preview:tunnel` 托管之前，先确认产物真的是「穿透构建」。
 *
 * <p>不是的话**直接拒绝启动**，并写清怎么修。这个不变式（穿透预览必须配穿透产物）
 * 一旦破了，症状是「内网一切正常、外网数据加载不出来，还报『后端未启动』」——
 * 一句指错方向的话，最难查的一类。宁可在这里响亮地失败。
 *
 * <p>只在穿透模式校验：日常 `npm run preview`（预览普通构建的产物）本来就该配 default。
 *
 * @param {boolean} isTunnel 本次 preview 是不是穿透模式
 * @returns {object} Vite 插件
 */
function requireTunnelBuild(isTunnel) {
  let outDir = 'dist'
  return {
    name: 'uspace:require-tunnel-build',
    configResolved(config) {
      outDir = config.build.outDir
    },
    configurePreviewServer() {
      if (!isTunnel) return
      const marker = path.resolve(outDir, '.build-mode')
      const mode = existsSync(marker)
        ? readFileSync(marker, 'utf8').split('\n')[0].trim()
        : ''
      if (mode !== 'tunnel') {
        throw new Error(
          '\n[uspace] 这份产物不是「穿透构建」（' + marker + ' = ' + (mode || '缺失') + '）\n'
          + '        穿透服役必须用 npm run build:tunnel 构建：普通 npm run build 的产物\n'
          + '        会让前端直连 http://<当前域名>:8080，而外网那个端口不存在 ——\n'
          + '        表现是「内网一切正常、外网数据加载不出来」，报的还是「后端未启动」。\n'
          + '        修：cd frontend && npm run build:tunnel\n')
      }
    }
  }
}

export default defineConfig(({ mode }) => {
  // 穿透模式：npm run dev:tunnel（等价于 vite --mode tunnel）
  const isTunnel = mode === 'tunnel'

  const proxy = {
    '/uploads': {
      target: 'http://localhost:8080',
      changeOrigin: true
    }
  }

  const server = {
    host: true,
    port: 5173,
    proxy
  }

  // ⚡ 构建产物预览（`npm run preview:tunnel`）—— 穿透长期服役时的生产形态：
  // 托管 dist + 反代 `/api`，与 dev server 是同一个思路的两种落地（见文件顶部说明）。
  // 端口不写死在这里：由 script 传 `--port 5173`，避免与 server 的端口两处分叉。
  const preview = {
    host: true,
    proxy
  }

  // 仅穿透模式启用 —— 日常开发保持「直连 8080」，让 CORS 问题照常暴露
  if (isTunnel) {
    proxy['/api'] = {
      target: 'http://localhost:8080',
      changeOrigin: true
    }

    // ⚠️ 必须放行隧道域名，否则隧道通了也只有一个 403。
    // Vite 4.0+ 起有 allowedHosts 防护（防 DNS rebinding）：默认只接受
    // localhost 与 IP 的 Host 头，【带域名的请求一律拒掉】，响应体写着
    // 「Blocked request. This host "xxx" is not allowed.」并直接提示改这里。
    // 而外层隧道正是按域名转发的 —— 于是本地直连一切正常、DNS 对、证书对、
    // frpc 连接对，页面却打不开（2026-10-09 实测踩过，排查绕了一圈）。
    // 用数组精确白名单，不用 `true` —— 后者是整个关掉这道防护。
    // ⚠️ 换域名时改这里；后端 `uspace.web.base-url` 是同一件事的另一端。
    // 樱花 frp 写精确域名；natapp 写【通配】—— 前导点表示放行该后缀的全部
    // 子域（Vite 5.4.12+ 支持）。natapp 免费隧道重建后会换域名，用通配就
    // 不必每次回来改这一行；范围仍有限（只放行 natapp 这一个后缀）。
    // preview 走同一套 host 校验，同样要放行
    server.allowedHosts = ['nsfwonly.fans', '.natappfree.cc']
    preview.allowedHosts = ['nsfwonly.fans', '.natappfree.cc']

    // ⚠️ 端口被占时【直接报错】，不要自动换端口（Vite 默认会换到 5174、5175…）。
    // 隧道固定指向 5173，静默换端口会造成「服务在跑、但隧道里是另一个」——
    // 改代码怎么都不生效，而且不会有任何提示。2026-10-10 踩过：
    // preview 占着 5173 时另起的 dev 悄悄跑到 5174，排查绕了一圈。
    // 只在穿透模式下这样要求：日常 `npm run dev` 保留自动换端口（起两个不冲突）。
    server.strictPort = true
    preview.strictPort = true
  }

  return {
    plugins: [vue(), immutableAssets(), buildModeMarker(isTunnel), requireTunnelBuild(isTunnel)],
    resolve: {
      alias: {
        // 用 fileURLToPath 而不是 path.resolve(__dirname) ——
        // package.json 里是 "type": "module"，__dirname 在 ESM 下不存在
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server,
    preview
  }
})
