// 群聊重连补量（finding-12）：先让最新消息可见并恢复交互，再在后台分段补齐断线期间的历史。
//
// 接口语义（CircleChatService.history）：
//   无游标     → 最近 N 条（升序返回）
//   beforeId   → 早于该 id 的 N 条（升序返回）
//   afterId    → 晚于该 id 的 N 条（升序返回）
//
// 本模块只含纯逻辑，便于 node 直接测试（tools/acceptance/chat-sync.test.js）。

function numericId(message) {
  const id = Number(message && message.id)
  return Number.isSafeInteger(id) && id > 0 ? id : 0
}

// 按 id 去重并升序；本地未确认（无数字 id）的消息排在最后，并按 clientId 与已确认消息去重
function mergeMessages(existing, incoming) {
  const byId = new Map()
  const locals = []
  const confirmedClientIds = new Set()
  const all = (existing || []).concat(incoming || [])
  for (const message of all) {
    const id = numericId(message)
    if (id) {
      byId.set(id, Object.assign({}, byId.get(id) || {}, message))
      if (message.clientId) confirmedClientIds.add(message.clientId)
    }
  }
  for (const message of all) {
    if (numericId(message)) continue
    if (message.clientId && confirmedClientIds.has(message.clientId)) continue
    if (message.clientId && locals.some(item => item.clientId === message.clientId)) continue
    locals.push(message)
  }
  const confirmed = Array.from(byId.values()).sort((a, b) => numericId(a) - numericId(b))
  return confirmed.concat(locals)
}

function minId(rows) {
  let min = 0
  for (const row of rows || []) { const id = numericId(row); if (id && (!min || id < min)) min = id }
  return min
}

// 补量结束后，若与旧消息之间仍有缺口，丢弃缺口以前的旧消息，保证列表连续；
// 用户上拉加载更早消息时会从 gapBeforeId 往前继续取，不会跳过断线期间的消息。
function dropBeforeGap(messages, gapBeforeId) {
  if (!gapBeforeId) return messages
  return (messages || []).filter(message => !numericId(message) || numericId(message) >= gapBeforeId)
}

/**
 * 补量流程。
 * @param {object} o
 * @param {(params) => Promise<{list, hasMore}>} o.fetchPage 拉一页
 * @param {number} o.knownLastId 断线前已展示的最新消息 id（0 表示首次进入，无需补缺口）
 * @param {(rows, info) => void} o.onPage 每到一页立即回调；第一页 info.first=true，调用方据此结束 loading
 * @param {() => boolean} o.isCancelled 页面卸载或开始了新一轮同步时返回 true
 * @param {number} [o.pageSize=30]
 * @param {number} [o.budgetMs=8000] 后台补量总时间预算
 * @param {number} [o.maxMessages=300] 后台补量最多条数
 * @param {() => number} [o.now]
 * @returns {Promise<{status: 'complete'|'partial'|'cancelled'|'failed', gapBeforeId: number, fetched: number, hasMore: boolean, error?: any}>}
 *   gapBeforeId>0 表示缺口未补完：调用方应丢弃 id 小于它的旧消息并允许上拉继续加载
 */
async function catchUp(o) {
  const pageSize = o.pageSize || 30
  const budgetMs = o.budgetMs == null ? 8000 : o.budgetMs
  const maxMessages = o.maxMessages == null ? 300 : o.maxMessages
  const now = o.now || (() => Date.now())
  const started = now()
  const known = Number(o.knownLastId) || 0

  // 1. 最新一页：拿到即渲染（失败交给调用方按首屏错误处理）
  const latest = await o.fetchPage({ size: pageSize })
  if (o.isCancelled()) return { status: 'cancelled', gapBeforeId: 0, fetched: 0, hasMore: !!latest.hasMore }
  const first = latest.list || []
  o.onPage(first, { first: true, hasMore: !!latest.hasMore, offline: !!latest.offline })

  let cursor = minId(first)
  // 首次进入，或最新一页已与旧消息衔接：无需补量
  if (!known || !cursor || cursor <= known + 1 || !latest.hasMore) {
    return { status: 'complete', gapBeforeId: 0, fetched: first.length, hasMore: !!latest.hasMore }
  }

  // 2. 后台向前补齐 (known, cursor) 之间的缺口
  let fetched = 0
  while (true) {
    if (o.isCancelled()) return { status: 'cancelled', gapBeforeId: cursor, fetched, hasMore: true }
    if (now() - started > budgetMs || fetched >= maxMessages) return { status: 'partial', gapBeforeId: cursor, fetched, hasMore: true }
    let page
    try { page = await o.fetchPage({ beforeId: cursor, size: pageSize }) }
    catch (error) { return { status: 'failed', gapBeforeId: cursor, fetched, hasMore: true, error } }
    if (o.isCancelled()) return { status: 'cancelled', gapBeforeId: cursor, fetched, hasMore: true }
    const rows = (page.list || []).filter(row => numericId(row) > known)
    if (rows.length) o.onPage(rows, { first: false })
    fetched += rows.length
    const pageMin = minId(page.list || [])
    // 已经取到旧消息之前（或没有更早消息）：缺口补完
    if (!pageMin || pageMin <= known + 1 || !page.hasMore || rows.length < (page.list || []).length) {
      return { status: 'complete', gapBeforeId: 0, fetched, hasMore: true }
    }
    cursor = pageMin
  }
}

module.exports = { mergeMessages, catchUp, dropBeforeGap, numericId }
