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
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // 用 fileURLToPath 而不是 path.resolve(__dirname) ——
      // package.json 里是 "type": "module"，__dirname 在 ESM 下不存在
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    host: true,
    port: 5173,
    proxy: {
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
