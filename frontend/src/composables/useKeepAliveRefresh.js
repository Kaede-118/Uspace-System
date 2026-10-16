/**
 * keep-alive 页面的加载策略。
 *
 * <p>被缓存的页面切回来时【不重新加载】—— 那正是它「秒开」的原因；
 * 但数据会随时间变旧，所以距上次加载超过 {@code maxAgeMs} 就再拉一次。
 *
 * <p>那次刷新走【静默】（{@code setSilentMode}）：不弹全局遮罩。
 * 用户看到的是缓存内容立刻出现、数据在后台追上来（列表原地更新），
 * 而不是先盖一层「加载中」—— 后者会把「秒开」变成「又转一次圈」，
 * 白费了 keep-alive。经隧道时每个请求一秒多，这个差别很明显。
 *
 * <p>首次进入仍然由 {@code onMounted} 触发，<b>不静默</b>：
 * 那时页面本来就是空的，该让用户看见「在加载」。
 *
 * <p>⚠️ 页面必须同时配上 {@code defineOptions({ name: 'XxxView' })}，
 * 且名字要出现在 `App.vue` 的 keep-alive include 列表里 ——
 * 少了任何一处，本 composable 的 {@code onActivated} 不会触发
 *（组件每次都是新挂载的，onMounted 会照常跑），表现是「什么都没坏，
 * 只是没变快」，不会有任何报错。
 */
import { onActivated, onMounted } from 'vue'
import { setSilentMode } from './useLoading'

/** 默认保鲜时长：超过它才在返回页面时刷新。 */
const DEFAULT_MAX_AGE = 60000

/**
 * 给 keep-alive 页面接上「首次加载 + 过期静默刷新」。
 *
 * @param {Function} load     加载函数，需返回 Promise（页面自己的 loadAll）
 * @param {number}  [maxAgeMs] 数据保鲜时长（毫秒）
 */
export function useKeepAliveRefresh(load, maxAgeMs = DEFAULT_MAX_AGE) {
  /**
   * 上次加载完成的时刻。
   *
   * <p>⚠️ 初值取「当下」而不是 0：keep-alive 组件的 {@code onMounted} 与
   * {@code onActivated} 会【连着】触发（首次进入时两个都走），
   * 初值取 0 的话那次 activated 会算出「早已过期」，于是首次进入请求两遍。
   */
  let lastLoadedAt = Date.now()

  onMounted(() => {
    load().finally(() => {
      lastLoadedAt = Date.now()
    })
  })

  onActivated(() => {
    if (Date.now() - lastLoadedAt < maxAgeMs) return
    setSilentMode(true)
    load().finally(() => {
      setSilentMode(false)
      lastLoadedAt = Date.now()
    })
  })
}
