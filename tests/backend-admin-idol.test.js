// 校园评选管理：校草/校花/歌手参赛选手的增删改
//
// 背景：小程序有校园评选功能（选手列表、投票），但管理端**没有评选管理页面**，
// 选手照片和票数只能靠 SQL 维护。
//
// 注意：idol_contestant 没有 school_id 字段，评选是全平台活动，不按校隔离。
//
// 运行前需后端在 8080 运行：
//   node --test tests/backend-admin-idol.test.js
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

test('评选列表：无 token 必须 401', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await call('GET', '/admin/idol-contestants')
  assert.equal(res.http, 401)
})

test('评选列表：返回全部选手，含姓名/性别/学院/照片/票数', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await call('GET', '/admin/idol-contestants', token)
  assert.equal(res.code, 0, `应成功：${JSON.stringify(res).slice(0, 160)}`)
  assert.ok(Array.isArray(res.data), 'data 应是数组')
  assert.ok(res.data.length > 0, '库里应有选手')
  const row = res.data[0]
  for (const key of ['id', 'name', 'gender', 'voteCount', 'status']) {
    assert.ok(key in row, `选手应含字段 ${key}`)
  }
})

test('评选列表：可按性别筛选', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const male = await call('GET', '/admin/idol-contestants?gender=1', token)
  const female = await call('GET', '/admin/idol-contestants?gender=2', token)
  assert.equal(male.code, 0)
  assert.equal(female.code, 0)
  assert.ok(male.data.every(r => Number(r.gender) === 1), '男性筛选结果应全为 gender=1')
  assert.ok(female.data.every(r => Number(r.gender) === 2), '女性筛选结果应全为 gender=2')
})

test('评选：新建 → 编辑 → 删除 全流程', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const name = `联调选手${Date.now() % 100000}`

  const created = await call('POST', '/admin/idol-contestants', token, {
    name, gender: 1, college: '测试学院',
    photo: '/assets/social/basketball.webp', slogan: '联调测试'
  })
  assert.equal(created.code, 0, `新建应成功：${JSON.stringify(created).slice(0, 160)}`)
  const id = created.data.id
  assert.ok(id, '应返回 id')

  const updated = await call('PUT', `/admin/idol-contestants/${id}`, token, {
    name: `${name}改`, gender: 1, college: '改后学院',
    photo: '/assets/social/basketball.webp', slogan: '已改'
  })
  assert.equal(updated.code, 0, `编辑应成功：${JSON.stringify(updated).slice(0, 160)}`)

  const list = await call('GET', '/admin/idol-contestants', token)
  const hit = list.data.find(r => Number(r.id) === Number(id))
  assert.equal(hit.name, `${name}改`)
  assert.equal(hit.college, '改后学院')

  const removed = await call('DELETE', `/admin/idol-contestants/${id}`, token)
  assert.equal(removed.code, 0, `删除应成功：${JSON.stringify(removed).slice(0, 160)}`)
  const after = await call('GET', '/admin/idol-contestants', token)
  assert.ok(!after.data.some(r => Number(r.id) === Number(id)))
})

test('评选：姓名空或超长被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const empty = await call('POST', '/admin/idol-contestants', token, { name: '  ', gender: 1, photo: '/assets/social/basketball.webp' })
  assert.notEqual(empty.code, 0, '空名应被拒绝')
  const long = await call('POST', '/admin/idol-contestants', token, { name: '名'.repeat(50), gender: 1, photo: '/assets/social/basketball.webp' })
  assert.notEqual(long.code, 0, '超长应被拒绝')
})

test('评选：性别只接受 1 或 2', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await call('POST', '/admin/idol-contestants', token, {
    name: `性别测试${Date.now() % 10000}`, gender: 5, photo: '/assets/social/basketball.webp'
  })
  assert.notEqual(res.code, 0, '非法性别应被拒绝')
})

test('评选：照片必须是服务端地址', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await call('POST', '/admin/idol-contestants', token, {
    name: `照片测试${Date.now() % 10000}`, gender: 1, photo: 'wxfile://tmp_x.jpg'
  })
  assert.notEqual(res.code, 0, '本地临时路径应被拒绝')
})

test('评选：停用后小程序列表不再返回', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const name = `停用测试${Date.now() % 100000}`
  const created = await call('POST', '/admin/idol-contestants', token, {
    name, gender: 1, photo: '/assets/social/basketball.webp'
  })
  assert.equal(created.code, 0)
  const id = created.data.id

  const off = await call('POST', `/admin/idol-contestants/${id}/status`, token, { status: 0 })
  assert.equal(off.code, 0, `停用应成功：${JSON.stringify(off).slice(0, 160)}`)

  const pub = await call('GET', '/idol/contestants?gender=1')
  assert.ok(!pub.data.some(r => Number(r.id) === Number(id)), '停用的选手不应出现在小程序列表')

  await call('DELETE', `/admin/idol-contestants/${id}`, token)
})
