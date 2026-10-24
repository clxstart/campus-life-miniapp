// AI 机器人配置（Web 管理端）：规则 CRUD + 未命中统计 + 欢迎语
//
// 背景：ai_chat_rule 表已有 24 条规则，但管理端只有 GET /api/ai/rules 只读接口，
// 而且这个接口是公开的（不在 /api/admin/** 拦截范围）。运营改不了话术，
// 也不知道哪些问题没被接住。
//
// 本次要做的：
//   1. 管理端规则 CRUD（鉴权 + 校验）
//   2. 未命中统计（哪些问题没接住）
//   3. 欢迎语搬进数据库
//
// 运行前需后端在 8080 运行：
//   node --test tests/backend-ai-admin.test.js
import { test } from 'node:test'
import assert from 'node:assert/strict'

const BASE = process.env.API_BASE || 'http://127.0.0.1:8080/api'

async function serverUp() {
  try {
    const r = await fetch(`${BASE}/circles?userId=1&page=1&size=1`, { signal: AbortSignal.timeout(4000) })
    return r.ok
  } catch { return false }
}

async function login(username = 'admin', password = 'admin123') {
  const r = await fetch(`${BASE}/admin/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ username, password }),
    signal: AbortSignal.timeout(5000)
  })
  const body = await r.json()
  assert.equal(body.code, 0, `登录失败：${JSON.stringify(body).slice(0, 140)}`)
  return body.data.token
}

async function call(method, path, token, body) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body) headers['content-type'] = 'application/json'
  const r = await fetch(`${BASE}${path}`, {
    method, headers,
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(8000)
  })
  const data = await r.json().catch(() => ({}))
  return { http: r.status, ...data }
}

async function chat(message, extra = {}) {
  const r = await fetch(`${BASE}/ai/chat`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ userId: 1, message, ...extra }),
    signal: AbortSignal.timeout(8000)
  })
  return r.json()
}

// ================= 规则列表（管理端鉴权）=================

test('规则列表：无 token 必须 401', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await call('GET', '/admin/ai-rules')
  assert.equal(res.http, 401, '管理端规则列表必须鉴权')
})

test('规则列表：带 token 能拿到全部规则', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await call('GET', '/admin/ai-rules', token)
  assert.equal(res.code, 0, `应成功：${JSON.stringify(res).slice(0, 160)}`)
  assert.ok(Array.isArray(res.data), 'data 应是数组')
  assert.ok(res.data.length >= 20, `规则应有 20+ 条，实际 ${res.data.length}`)
  const row = res.data[0]
  for (const key of ['id', 'category', 'keywords', 'reply', 'priority', 'status']) {
    assert.ok(key in row, `规则应含字段 ${key}`)
  }
})

test('规则列表：可按分类筛选', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await call('GET', '/admin/ai-rules?category=study', token)
  assert.equal(res.code, 0)
  assert.ok(res.data.length > 0, 'study 分类应有规则')
  assert.ok(res.data.every(r => r.category === 'study'), '筛选结果应全是 study')
})

test('规则列表：可按关键词搜索', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await call('GET', '/admin/ai-rules?keyword=考研', token)
  assert.equal(res.code, 0)
  assert.ok(res.data.length > 0, '应能搜到含「考研」的规则')
})

// ================= 规则 CRUD =================

test('规则：新建 → 编辑 → 停用 → 删除 全流程', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const kw = `测试关键词${Date.now() % 100000}`

  const created = await call('POST', '/admin/ai-rules', token, {
    category: 'study',
    keywords: `${kw},联调测试词`,
    reply: '这是联调测试的回复',
    quickReplies: '选项A,选项B',
    priority: 50
  })
  assert.equal(created.code, 0, `新建应成功：${JSON.stringify(created).slice(0, 160)}`)
  const id = created.data.id
  assert.ok(id, '应返回 id')

  const updated = await call('PUT', `/admin/ai-rules/${id}`, token, {
    category: 'study',
    keywords: `${kw}改`,
    reply: '改后的回复',
    quickReplies: '选项C',
    priority: 60,
    status: 1
  })
  assert.equal(updated.code, 0, `编辑应成功：${JSON.stringify(updated).slice(0, 160)}`)

  const list = await call('GET', '/admin/ai-rules', token)
  const hit = list.data.find(r => Number(r.id) === Number(id))
  assert.equal(hit.reply, '改后的回复')
  assert.equal(Number(hit.priority), 60)

  const off = await call('POST', `/admin/ai-rules/${id}/status`, token, { status: 0 })
  assert.equal(off.code, 0)

  const removed = await call('DELETE', `/admin/ai-rules/${id}`, token)
  assert.equal(removed.code, 0, `删除应成功：${JSON.stringify(removed).slice(0, 160)}`)
  const after = await call('GET', '/admin/ai-rules', token)
  assert.ok(!after.data.some(r => Number(r.id) === Number(id)))
})

test('规则：新建后立刻生效（对话能命中）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const kw = `独特词${Date.now() % 100000}`

  const created = await call('POST', '/admin/ai-rules', token, {
    category: 'help', keywords: kw, reply: `命中自定义规则：${kw}`, priority: 200
  })
  assert.equal(created.code, 0)
  const id = created.data.id

  const res = await chat(`我想问${kw}的事`)
  assert.equal(res.data.matched, true, '新建规则应立刻生效')
  assert.ok(res.data.reply.includes(kw), `回复应来自新规则：${res.data.reply}`)

  await call('DELETE', `/admin/ai-rules/${id}`, token)
})

test('规则：停用后不再命中', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const kw = `停用词${Date.now() % 100000}`

  const created = await call('POST', '/admin/ai-rules', token, {
    category: 'help', keywords: kw, reply: '这条应该被停用', priority: 200
  })
  const id = created.data.id
  await call('POST', `/admin/ai-rules/${id}/status`, token, { status: 0 })

  const res = await chat(kw)
  assert.ok(!String(res.data.reply).includes('这条应该被停用'), '停用的规则不应命中')

  await call('DELETE', `/admin/ai-rules/${id}`, token)
})

test('规则：关键词为空被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await call('POST', '/admin/ai-rules', token, {
    category: 'study', keywords: '   ', reply: '回复'
  })
  assert.notEqual(res.code, 0, '空关键词应被拒绝')
})

test('规则：回复为空被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await call('POST', '/admin/ai-rules', token, {
    category: 'study', keywords: '有词', reply: '  '
  })
  assert.notEqual(res.code, 0, '空回复应被拒绝')
})

test('规则：非法分类被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await call('POST', '/admin/ai-rules', token, {
    category: '不存在的分类', keywords: '词', reply: '回复'
  })
  assert.notEqual(res.code, 0, '非法分类应被拒绝')
})

test('规则：超长字段被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const longReply = await call('POST', '/admin/ai-rules', token, {
    category: 'study', keywords: '词', reply: '回'.repeat(600)
  })
  assert.notEqual(longReply.code, 0, '超长回复应被拒绝')
})

// ================= 未命中统计 =================

test('未命中统计：无 token 必须 401', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await call('GET', '/admin/ai-stats')
  assert.equal(res.http, 401)
})

test('未命中统计：未命中的问题会被记录并按频次排序', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const stamp = Date.now() % 100000
  const phrase = `从未命中问题${stamp}`

  // 同一个问题问 3 次
  for (let i = 0; i < 3; i += 1) await chat(phrase)

  const res = await call('GET', '/admin/ai-stats', token)
  assert.equal(res.code, 0, `应成功：${JSON.stringify(res).slice(0, 160)}`)
  const d = res.data
  assert.ok(Array.isArray(d.unmatched), '应返回 unmatched 数组')
  const hit = d.unmatched.find(x => String(x.message).includes(phrase))
  assert.ok(hit, `未命中列表应包含刚问的「${phrase}」`)
  assert.ok(Number(hit.count) >= 3, `该问题应被记录至少 3 次，实际 ${hit.count}`)
})

test('未命中统计：命中率与总数正确', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await call('GET', '/admin/ai-stats', token)
  assert.equal(res.code, 0)
  const d = res.data
  assert.equal(typeof d.total, 'number', '应返回总对话数')
  assert.equal(typeof d.matched, 'number', '应返回命中数')
  assert.equal(typeof d.unmatchedCount, 'number', '应返回未命中数')
  assert.ok(d.matched + d.unmatchedCount === d.total, '命中 + 未命中 应等于总数')
  assert.ok(d.total > 0, '跑过对话后总数应大于 0')
})

test('未命中统计：分类分布可用', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  await chat('你好')
  const res = await call('GET', '/admin/ai-stats', token)
  assert.ok(Array.isArray(res.data.byCategory), '应返回 byCategory 数组')
  assert.ok(res.data.byCategory.length > 0, '分类分布不应为空')
  const row = res.data.byCategory[0]
  assert.ok('category' in row && 'count' in row, '每项应含 category 和 count')
})

// ================= 欢迎语（搬进数据库）=================

test('欢迎语：公开接口返回可配置的欢迎语', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const r = await fetch(`${BASE}/ai/welcome`, { signal: AbortSignal.timeout(6000) })
  const body = await r.json()
  assert.equal(body.code, 0, '欢迎语接口应可用')
  assert.ok(body.data.reply, '应返回欢迎语文本')
  assert.ok(Array.isArray(body.data.quickReplies), '应返回快捷追问')
})

test('欢迎语：管理端能改，改完公开接口立刻生效', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const before = await (await fetch(`${BASE}/ai/welcome`, { signal: AbortSignal.timeout(6000) })).json()

  const text = `联调欢迎语${Date.now() % 100000}`
  const set = await call('PUT', '/admin/ai-welcome', token, {
    reply: text, quickReplies: '测试A,测试B'
  })
  assert.equal(set.code, 0, `设置欢迎语应成功：${JSON.stringify(set).slice(0, 160)}`)

  const after = await (await fetch(`${BASE}/ai/welcome`, { signal: AbortSignal.timeout(6000) })).json()
  assert.equal(after.data.reply, text)

  // 还原
  await call('PUT', '/admin/ai-welcome', token, {
    reply: before.data.reply, quickReplies: (before.data.quickReplies || []).join(',')
  })
})

// ================= 代码层面防回归 =================

test('代码：管理端 AI 接口必须落在 /api/admin/ 下（受拦截器保护）', async () => {
  const { readFileSync } = await import('node:fs')
  const { resolve } = await import('node:path')
  const root = resolve(import.meta.dirname, '..')
  const src = readFileSync(resolve(root, 'backend/src/main/java/com/campuslife/controller/AiAdminController.java'), 'utf8')
  assert.ok(src.includes('"/api/admin"'), 'AiAdminController 必须映射到 /api/admin')
})
