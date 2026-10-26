// 页面重新可见时自动刷新。
//
// 背景：管理端经常和小程序开发者工具并排开着。在小程序里点了个赞/下了个单，
// 切回浏览器看到的是「打开页面那一刻的快照」，很容易误判成「数据没同步」。
// 实际后端数据是对的，只是页面没重新拉。
//
// 用法：
//   import { useAutoRefresh } from '../utils/auto-refresh'
//   useAutoRefresh(() => load(page.value))
//
// 只做「重新拉数据」，不改任何业务逻辑；节流避免 focus/visibility 连续触发时打太多请求。
import { onMounted, onUnmounted } from 'vue'

export function useAutoRefresh(reload, { minIntervalMs = 1200 } = {}) {
  let lastRun = Date.now()
  let pending = null

  const trigger = () => {
    if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return
    const now = Date.now()
    const wait = minIntervalMs - (now - lastRun)
    if (wait > 0) {
      // 太密集就合并成一次，避免切窗口时连打好几个请求
      if (!pending) pending = setTimeout(() => { pending = null; run() }, wait)
      return
    }
    run()
  }

  const run = () => {
    lastRun = Date.now()
    try { reload() } catch (e) { /* 刷新失败不该打断页面 */ }
  }

  onMounted(() => {
    document.addEventListener('visibilitychange', trigger)
    window.addEventListener('focus', trigger)
  })
  onUnmounted(() => {
    document.removeEventListener('visibilitychange', trigger)
    window.removeEventListener('focus', trigger)
    if (pending) { clearTimeout(pending); pending = null }
  })

  return trigger
}
