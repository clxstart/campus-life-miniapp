// AI 陪聊（关键词匹配）：后端规则引擎 + 接口
//
// 背景：用户要一个「关键词匹配就行」的 AI 聊天。不做真大模型，用规则匹配：
//   - 规则存数据库，管理端以后可维护
//   - 命中规则返回预设话术 + 快捷追问
//   - 部分意图接真实校园数据（吃什么 / 有什么活动 / 有什么兼职）
//   - 匹配不到走兜底话术，绝不报错
//
// 运行前需后端在 8080 运行：
//   node --test tests/backend-ai-chat.test.js
import { test } from 'node:test'
import assert from 'node:assert/strict'

const BASE = process.env.API_BASE || 'http://127.0.0.1:8080/api'

async function serverUp() {
  try {
    const r = await fetch(`${BASE}/circles?userId=1&page=1&size=1`, { signal: AbortSignal.timeout(4000) })
    return r.ok
  } catch { return false }
}

async function chat(message, extra = {}) {
  const r = await fetch(`${BASE}/ai/chat`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ userId: 1, message, ...extra }),
    signal: AbortSignal.timeout(8000)
  })
  const body = await r.json().catch(() => ({}))
  return { http: r.status, ...body }
}

// ================= 基础对话 =================

test('AI 聊天：无需登录即可对话（游客也能用）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await chat('你好')
  assert.equal(res.code, 0, `应成功：${JSON.stringify(res).slice(0, 160)}`)
  assert.ok(res.data.reply, '应返回 reply')
  assert.ok(res.data.reply.length > 0, 'reply 不能为空')
})

test('AI 聊天：返回结构稳定（reply + quickReplies + matched）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await chat('你好')
  const d = res.data
  assert.equal(typeof d.reply, 'string')
  assert.ok(Array.isArray(d.quickReplies), 'quickReplies 应是数组')
  assert.equal(typeof d.matched, 'boolean')
})

test('AI 聊天：空消息被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await chat('   ')
  assert.notEqual(res.code, 0, '空消息应被拒绝')
})

test('AI 聊天：超长消息被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await chat('啊'.repeat(500))
  assert.notEqual(res.code, 0, '超长消息应被拒绝')
})

// ================= 关键词匹配 =================

test('关键词：问候能命中', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  for (const msg of ['你好', '在吗', 'hi', '早上好']) {
    const res = await chat(msg)
    assert.equal(res.code, 0)
    assert.equal(res.data.matched, true, `「${msg}」应命中问候规则`)
  }
})

test('关键词：学习相关能命中，且给出学习建议', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await chat('我该背单词了')
  assert.equal(res.data.matched, true, '应命中学习规则')
  assert.ok(/单词|学习|复习|计划/.test(res.data.reply), `回复应贴合学习：${res.data.reply}`)
})

test('关键词：吃饭相关能命中', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await chat('我饿了，吃什么')
  assert.equal(res.data.matched, true, '应命中吃饭规则')
})

test('关键词：睡觉/熬夜相关能命中', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await chat('好困，想睡觉了')
  assert.equal(res.data.matched, true, '应命中作息规则')
})

test('关键词：情绪相关能命中，且是安抚语气', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await chat('我今天好难过')
  assert.equal(res.data.matched, true, '应命中情绪规则')
  assert.ok(res.data.reply.length > 4, '安抚回复不应太短')
})

test('关键词：匹配不到时走兜底，不报错', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await chat('zzzqqq不存在的话题xyz')
  assert.equal(res.code, 0, '兜底也要正常返回')
  assert.equal(res.data.matched, false, '应标记为未命中')
  assert.ok(res.data.reply.length > 0, '兜底话术不能为空')
})

// ================= 校园数据接入（差异化关键）=================

test('校园数据：问吃什么时回复里带真实店铺名', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const shopsRes = await fetch(`${BASE}/shops?schoolScope=local&schoolId=1&userId=1`, { signal: AbortSignal.timeout(6000) })
  const shops = await shopsRes.json()
  if (!shops.data || !shops.data.length) return t.skip('库里没有店铺')

  const res = await chat('中午吃什么好')
  assert.equal(res.code, 0)
  const names = shops.data.map(s => s.name)
  const hit = names.some(name => res.data.reply.includes(name))
  assert.ok(hit, `回复应提到真实店铺，实际回复：${res.data.reply}；库里店铺：${names.join('、')}`)
})

test('校园数据：问兼职时回复里带真实岗位', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const jobsRes = await fetch(`${BASE}/jobs?schoolScope=local&schoolId=1&userId=1`, { signal: AbortSignal.timeout(6000) })
  const jobs = await jobsRes.json()
  if (!jobs.data || !jobs.data.length) return t.skip('库里没有兼职')

  const res = await chat('有兼职推荐吗')
  assert.equal(res.code, 0)
  const titles = jobs.data.map(j => j.title)
  const hit = titles.some(title => res.data.reply.includes(title))
  assert.ok(hit, `回复应提到真实岗位，实际：${res.data.reply}；库里岗位：${titles.join('、')}`)
})

test('校园数据：数据为空时也不能崩', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  // 用一个不存在的学校，数据必然为空
  const res = await chat('中午吃什么', { schoolId: 99999 })
  assert.equal(res.code, 0, '数据为空也要正常回复')
  assert.ok(res.data.reply.length > 0)
})

// ================= 规则管理（管理端可维护）=================

// 注意：规则列表已改为管理端鉴权接口（/api/admin/ai-rules），
// 公开的 /api/ai/rules 已移除 —— 规则和话术属于运营配置，不应匿名可读。
async function adminToken() {
  const r = await fetch(`${BASE}/admin/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
    signal: AbortSignal.timeout(5000)
  })
  const body = await r.json()
  assert.equal(body.code, 0, '管理员登录失败')
  return body.data.token
}

test('规则表：存在且已播种', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await adminToken()
  const r = await fetch(`${BASE}/admin/ai-rules`, {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(6000)
  })
  const body = await r.json()
  assert.equal(body.code, 0, '管理端规则列表应可用')
  assert.ok(Array.isArray(body.data), 'data 应是数组')
  assert.ok(body.data.length >= 10, `种子规则应有至少 10 条，实际 ${body.data.length}`)
  const row = body.data[0]
  for (const key of ['id', 'category', 'keywords', 'reply']) {
    assert.ok(key in row, `规则应含字段 ${key}`)
  }
})

test('规则表：种子规则覆盖关键分类', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await adminToken()
  const r = await fetch(`${BASE}/admin/ai-rules`, {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(6000)
  })
  const body = await r.json()
  const cats = new Set(body.data.map(x => x.category))
  for (const need of ['greeting', 'study', 'food', 'sleep', 'mood']) {
    assert.ok(cats.has(need), `缺少分类 ${need}，现有：${[...cats].join(',')}`)
  }
})
