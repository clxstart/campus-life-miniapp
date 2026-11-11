// finding-12 回归测试：node tools/acceptance/chat-sync.test.js
const assert = require('node:assert')
const { catchUp, mergeMessages, dropBeforeGap } = require('../../utils/chat-sync')

// 模拟服务端：messages 为 1..total 升序；语义与 CircleChatService.history 一致
function server(total, { delayMs = 0, failOnCall = 0 } = {}) {
  let calls = 0
  const all = Array.from({ length: total }, (_, i) => ({ id: i + 1, content: 'm' + (i + 1) }))
  return {
    get calls() { return calls },
    fetchPage: async ({ beforeId, afterId, size }) => {
      calls += 1
      if (failOnCall && calls === failOnCall) throw new Error('offline')
      if (delayMs) await new Promise(r => setTimeout(r, delayMs))
      let rows
      if (afterId != null) rows = all.filter(m => m.id > afterId).slice(0, size + 1)
      else rows = all.filter(m => !beforeId || m.id < beforeId).slice(-(size + 1))
      const hasMore = rows.length > size
      if (hasMore) rows = afterId != null ? rows.slice(0, size) : rows.slice(1)
      return { list: rows, hasMore }
    }
  }
}

async function run(name, fn) { await fn(); console.log('ok -', name) }

;(async () => {
  await run('首屏先于补量完成渲染', async () => {
    const s = server(500, { delayMs: 5 })
    const events = []
    const result = catchUp({ fetchPage: s.fetchPage, knownLastId: 100, isCancelled: () => false, onPage: (rows, info) => events.push({ first: info.first, at: Date.now(), n: rows.length }) })
    // 等第一页到达：此时补量仍在进行
    await new Promise(r => setTimeout(r, 15))
    assert.ok(events.length >= 1 && events[0].first === true, '第一页应最先回调')
    const done = await result
    assert.ok(events.length > 1, '应分多段回调，而不是一次性合并')
    assert.strictEqual(done.status, 'partial') // 400 条缺口超过 300 条预算
    assert.ok(done.gapBeforeId > 101)
  })

  await run('缺口补齐后列表连续且不重复', async () => {
    const s = server(200)
    let list = Array.from({ length: 80 }, (_, i) => ({ id: i + 1 }))
    const done = await catchUp({ fetchPage: s.fetchPage, knownLastId: 80, isCancelled: () => false, onPage: rows => { list = mergeMessages(list, rows) } })
    assert.strictEqual(done.status, 'complete')
    assert.deepStrictEqual(list.map(m => m.id), Array.from({ length: 200 }, (_, i) => i + 1))
  })

  await run('补量期间收到重复推送不产生重复消息', async () => {
    const s = server(120)
    let list = Array.from({ length: 50 }, (_, i) => ({ id: i + 1 }))
    await catchUp({
      fetchPage: s.fetchPage, knownLastId: 50, isCancelled: () => false,
      onPage: (rows, info) => {
        list = mergeMessages(list, rows)
        if (info.first) list = mergeMessages(list, [{ id: 120, content: 'push' }, { id: 119 }]) // websocket 重复推送
      }
    })
    const ids = list.map(m => m.id)
    assert.strictEqual(new Set(ids).size, ids.length)
    assert.deepStrictEqual(ids, Array.from({ length: 120 }, (_, i) => i + 1))
  })

  await run('乱序页合并后仍按 id 升序；本地待确认消息按 clientId 去重', () => {
    const merged = mergeMessages([{ id: 5 }, { id: 'local-a', clientId: 'a', pending: true }], [{ id: 3 }, { id: 9, clientId: 'a' }, { id: 4 }])
    assert.deepStrictEqual(merged.map(m => m.id), [3, 4, 5, 9])
  })

  await run('长积压（1000 条）在条数预算内停止', async () => {
    const s = server(1100)
    let got = 0
    const done = await catchUp({ fetchPage: s.fetchPage, knownLastId: 100, isCancelled: () => false, onPage: (rows, info) => { if (!info.first) got += rows.length } })
    assert.strictEqual(done.status, 'partial')
    assert.ok(got <= 300 + 30)
    assert.ok(s.calls <= 13, '请求次数应有上限，实际 ' + s.calls)
  })

  await run('时间预算耗尽即停止', async () => {
    let t = 0
    const s = server(1000)
    const done = await catchUp({ fetchPage: async p => { t += 3000; return s.fetchPage(p) }, now: () => t, budgetMs: 8000, knownLastId: 10, isCancelled: () => false, onPage: () => {} })
    assert.strictEqual(done.status, 'partial')
    assert.ok(s.calls <= 4)
  })

  await run('补量中途离线：已合并的消息保留，返回缺口', async () => {
    const s = server(300, { failOnCall: 3 })
    let list = Array.from({ length: 20 }, (_, i) => ({ id: i + 1 }))
    const done = await catchUp({ fetchPage: s.fetchPage, knownLastId: 20, isCancelled: () => false, onPage: rows => { list = mergeMessages(list, rows) } })
    assert.strictEqual(done.status, 'failed')
    assert.ok(list.some(m => m.id === 300), '最新一页应已展示')
    const kept = dropBeforeGap(list, done.gapBeforeId)
    // 丢弃缺口前的旧消息后列表连续，可从 gapBeforeId 继续上拉
    const ids = kept.map(m => m.id)
    for (let i = 1; i < ids.length; i++) assert.strictEqual(ids[i], ids[i - 1] + 1)
    assert.strictEqual(ids[0], done.gapBeforeId)
  })

  await run('新一轮同步开始后旧一轮作废', async () => {
    const s = server(400, { delayMs: 2 })
    let generation = 1
    let late = 0
    const done = catchUp({ fetchPage: s.fetchPage, knownLastId: 10, isCancelled: () => generation !== 1, onPage: (rows, info) => { if (!info.first) late += 1; generation = 2 } })
    const result = await done
    assert.strictEqual(result.status, 'cancelled')
    assert.strictEqual(late, 0)
  })

  await run('首次进入只取最新一页', async () => {
    const s = server(500)
    const done = await catchUp({ fetchPage: s.fetchPage, knownLastId: 0, isCancelled: () => false, onPage: () => {} })
    assert.strictEqual(done.status, 'complete')
    assert.strictEqual(s.calls, 1)
    assert.strictEqual(done.hasMore, true)
  })

  console.log('chat-sync: all passed')
})().catch(error => { console.error(error); process.exit(1) })
