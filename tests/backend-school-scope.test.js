// 圈子 / 兼职 / 外卖 按校隔离集成测试
// 前置：后端已启动 + 已执行 tests/fixtures/school-isolation.sql
// 跑法：node --test tests/backend-school-scope.test.js
const test = require('node:test')
const assert = require('node:assert/strict')

const BASE = process.env.API_BASE || 'http://127.0.0.1:8080/api'

async function serverUp() {
  try {
    const res = await fetch(BASE + '/topics/hot', { signal: AbortSignal.timeout(2000) })
    return res.ok
  } catch { return false }
}

async function get(path, params) {
  const qs = new URLSearchParams({ userId: '1', ...params })
  const res = await fetch(`${BASE}${path}?${qs}`, { signal: AbortSignal.timeout(5000) })
  const body = await res.json()
  assert.equal(body.code, 0, `${path} 应成功：${JSON.stringify(body).slice(0, 120)}`)
  return body.data
}

// 外校内容的特征串（夹具里写死的名字）
const FOREIGN = {
  circle: '北大未名湖跑团',
  job: '北大图书馆助理',
  shop: '北大食堂窗口'
}

function names(list) {
  return (list || []).map(row => row.name || row.title || '')
}

// ================= 圈子 =================
test('圈子：local 不含外校圈子，all 包含', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const local = await get('/circles', { schoolScope: 'local' })
  const all = await get('/circles', { schoolScope: 'all' })
  const localNames = names(local.list || local)
  const allNames = names(all.list || all)
  assert.ok(localNames.length > 0, '本校应有圈子')
  assert.ok(!localNames.includes(FOREIGN.circle), `local 不应含外校圈子：${localNames.join(' / ')}`)
  assert.ok(allNames.includes(FOREIGN.circle), 'all 应含外校圈子')
})

test('圈子：缺省等同 local', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const implicit = await get('/circles', {})
  const local = await get('/circles', { schoolScope: 'local' })
  assert.equal(names(implicit.list || implicit).length, names(local.list || local).length)
})

test('圈子：热门轮播也遵守学校范围', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const hot = await get('/circles/hot', { limit: '10' })
  assert.ok(!names(hot).includes(FOREIGN.circle), '热门轮播不应出现外校圈子')
})

// ================= 兼职 =================
test('兼职：local 不含外校岗位，all 包含', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const local = await get('/jobs', { schoolScope: 'local' })
  const all = await get('/jobs', { schoolScope: 'all' })
  assert.ok(local.length > 0, '本校应有兼职')
  assert.ok(!names(local).includes(FOREIGN.job), `local 不应含外校岗位：${names(local).join(' / ')}`)
  assert.ok(names(all).includes(FOREIGN.job), 'all 应含外校岗位')
})

test('兼职：缺省等同 local', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const implicit = await get('/jobs', {})
  const local = await get('/jobs', { schoolScope: 'local' })
  assert.equal(implicit.length, local.length)
})

// ================= 外卖 =================
test('外卖：local 不含外校店铺，all 包含', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const local = await get('/shops', { schoolScope: 'local' })
  const all = await get('/shops', { schoolScope: 'all' })
  assert.ok(local.length > 0, '本校应有店铺')
  assert.ok(!names(local).includes(FOREIGN.shop), `local 不应含外校店铺：${names(local).join(' / ')}`)
  assert.ok(names(all).includes(FOREIGN.shop), 'all 应含外校店铺')
})

test('外卖：缺省等同 local', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const implicit = await get('/shops', {})
  const local = await get('/shops', { schoolScope: 'local' })
  assert.equal(implicit.length, local.length)
})

// ================= 发布路径必须写 school_id =================
// 这几条是同一类 bug：INSERT 漏了 school_id，新内容会变成 NULL，
// 在按校过滤下要么消失（本校视图）要么越权可见（跨校）。

async function post(path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(5000)
  })
  return res.json()
}

test('发布兼职必须记录发布者学校（否则新岗位在本校视图消失）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const title = `E2E兼职归属-${Date.now()}`
  const created = await post('/jobs', { userId: 1, title, daySalary: 100, settle: '日结', location: '校内', timeDesc: '周末', description: '测试' })
  assert.equal(created.code, 0, `发布应成功：${JSON.stringify(created).slice(0, 140)}`)
  const local = await get('/jobs', { schoolScope: 'local' })
  const hit = local.find(row => row.title === title)
  assert.ok(hit, '新发布的兼职应出现在本校视图（说明 school_id 已写入）')
  assert.equal(Number(hit.schoolId), 1)
})

test('发布活动必须记录发布者学校', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const title = `E2E活动归属-${Date.now()}`
  const created = await post('/activities', { userId: 1, title, timeDesc: '本周六', location: '操场', description: '测试' })
  assert.equal(created.code, 0, `发布应成功：${JSON.stringify(created).slice(0, 140)}`)
  const list = await get('/activities', {})
  const hit = list.find(row => row.title === title)
  assert.ok(hit, '新发布的活动应出现在列表（说明 school_id 已写入）')
  assert.equal(Number(hit.schoolId), 1)
})

test('发帖默认归本校（不应默认变成跨校）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const title = `E2E发帖归属-${Date.now()}`
  const created = await post('/posts', { userId: 1, title, content: '测试内容', tag: '校园日常' })
  assert.equal(created.code, 0, `发帖应成功：${JSON.stringify(created).slice(0, 140)}`)
  const id = created.data.id
  const detailRes = await fetch(`${BASE}/posts/${id}?userId=1`, { signal: AbortSignal.timeout(5000) })
  const detail = await detailRes.json()
  assert.equal(detail.code, 0)
  assert.equal(Number(detail.data.schoolId), 1, '新帖应归发布者学校')
  assert.equal(detail.data.crossSchool, false, '默认不应是跨校帖')
})

test('发帖可显式设为跨校（crossSchool=true → schoolId 为空）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const title = `E2E跨校帖-${Date.now()}`
  const created = await post('/posts', { userId: 1, title, content: '跨校内容', tag: '学习', crossSchool: true })
  assert.equal(created.code, 0, `发帖应成功：${JSON.stringify(created).slice(0, 140)}`)
  const id = created.data.id
  const detailRes = await fetch(`${BASE}/posts/${id}?userId=1`, { signal: AbortSignal.timeout(5000) })
  const detail = await detailRes.json()
  assert.equal(detail.data.crossSchool, true, '显式跨校帖应无学校归属')
  assert.equal(detail.data.schoolId, null)
})
