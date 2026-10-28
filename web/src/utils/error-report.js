// 前端错误上报（管理端）
//
// 管理端白屏 / 某次点击没反应时，浏览器控制台里只有当事人自己能看到。
// 这里把错误发到后端，之后在「系统状态 → 前端错误」里能看到。
//
// 注意：
//   1. 同一错误 10 秒内只报一次，避免连环报错刷爆后端；
//   2. 上报失败静默忽略 —— 上报本身不能影响用户使用；
//   3. 不采集用户输入内容，只记错误信息与当前路由。
const ENDPOINT = '/api/log/client'
let lastKey = ''
let lastAt = 0

function report(page, message, stack) {
  try {
    const text = String(message || '').trim()
    // 空消息没有排查价值（常见于某些资源加载失败事件），不上报，避免刷噪音
    if (!text) return
    const key = page + '|' + text.slice(0, 120)
    const now = Date.now()
    if (key === lastKey && now - lastAt < 10000) return
    lastKey = key
    lastAt = now

    const token = localStorage.getItem('campus_admin_token') || ''
    fetch(ENDPOINT, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        ...(token ? { Authorization: 'Bearer ' + token } : {})
      },
      body: JSON.stringify({
        client: 'web',
        page: String(page || location.hash || ''),
        message: text.slice(0, 500),
        stack: String(stack || '').slice(0, 2000)
      }),
      keepalive: true
    }).catch(() => {})
  } catch (e) {
    // 放弃
  }
}

export function reportError(page, error) {
  const message = (error && (error.message || String(error))) || '未知错误'
  report(page, message, error && error.stack)
}

export function installErrorReporting() {
  window.addEventListener('error', (event) => {
    // 资源加载失败（img/script）没有 message，单独标注一下
    const message = event.message || (event.target ? '资源加载失败: ' + (event.target.src || event.target.href || '') : '')
    report(location.hash || location.pathname, message, event.error && event.error.stack)
  })

  window.addEventListener('unhandledrejection', (event) => {
    const reason = event.reason
    report(
      location.hash || location.pathname,
      (reason && (reason.message || reason.toString && reason.toString())) || 'Promise 未捕获拒绝',
      reason && reason.stack
    )
  })
}
