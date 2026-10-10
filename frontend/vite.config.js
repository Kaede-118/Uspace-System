import { fileURLToPath, URL } from 'node:url'
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
    server.allowedHosts = ['nsfwonly.fans', '.natappfree.cc']
  }

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        // 用 fileURLToPath 而不是 path.resolve(__dirname) ——
        // package.json 里是 "type": "module"，__dirname 在 ESM 下不存在
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server
  }
})
