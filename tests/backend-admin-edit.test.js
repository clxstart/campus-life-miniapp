// 管理端内容编辑：帖子 / 二手 / 活动 的编辑接口 + 用户头像
//
// 背景：这三个实体原来管理端只能「下架/删除」，改不了内容，更改不了配图。
// 运营想修正一条写错的标题或换张图，只能让用户删了重发。
//
// 运行前需后端在 8080 运行：
//   node --test tests/backend-admin-edit.test.js
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

// 取一条本校内容的 id，避免依赖固定 id
async function firstPostId(token) {
  const res = await call('GET', '/admin/posts?page=1&size=100', token)
  const row = res.data.list.find(r => r.schoolId != null)
  assert.ok(row, '库里应有本校帖子')
  return row
}
async function firstGoodsId(token) {
  const res = await call('GET', '/admin/goods?page=1&size=100', token)
  const row = res.data.list.find(r => r.schoolId != null)
  assert.ok(row, '库里应有本校商品')
  return row
}
async function firstActivityId(token) {
  const res = await call('GET', '/admin/activities?page=1&size=100', token)
  const row = res.data.list.find(r => r.schoolId != null)
  assert.ok(row, '库里应有本校活动')
  return row
}

// ================= 帖子编辑 =================

test('帖子编辑：无 token 必须 401', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await call('PUT', '/admin/posts/1', null, { title: 'x' })
  assert.equal(res.http, 401)
})

test('帖子编辑：能改标题/正文/标签并落库', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const row = await firstPostId(token)
  const original = { title: row.title, content: row.content, tag: row.tag }

  const stamp = Date.now() % 100000
  const res = await call('PUT', `/admin/posts/${row.id}`, token, {
    title: `改标题${stamp}`,
    content: `改正文${stamp}`,
    tag: '校园日常',
    image: ''
  })
  assert.equal(res.code, 0, `编辑应成功：${JSON.stringify(res).slice(0, 160)}`)

  const list = await call('GET', '/admin/posts?page=1&size=100', token)
  const hit = list.data.list.find(r => Number(r.id) === Number(row.id))
  assert.equal(hit.title, `改标题${stamp}`)

  // 还原，避免污染基线
  await call('PUT', `/admin/posts/${row.id}`, token, original)
})

test('帖子编辑：图片必须是服务端地址（拒绝本地临时路径）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const row = await firstPostId(token)
  const bad = await call('PUT', `/admin/posts/${row.id}`, token, {
    title: row.title, content: row.content, image: 'wxfile://tmp_x.jpg'
  })
  assert.notEqual(bad.code, 0, '本地临时路径应被拒绝')
  assert.ok(/上传|图片/.test(String(bad.msg)), `提示应说明图片问题：${bad.msg}`)
})

test('帖子编辑：标题空或超长被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const row = await firstPostId(token)
  const empty = await call('PUT', `/admin/posts/${row.id}`, token, { title: '  ', content: 'x' })
  assert.notEqual(empty.code, 0, '空标题应被拒绝')
  const long = await call('PUT', `/admin/posts/${row.id}`, token, { title: '标'.repeat(80), content: 'x' })
  assert.notEqual(long.code, 0, '超长标题应被拒绝')
})

test('帖子编辑：不存在的 id 返回可读错误', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await call('PUT', '/admin/posts/999999', token, { title: 'x', content: 'y' })
  assert.notEqual(res.code, 0)
  assert.ok(/不存在/.test(String(res.msg)), `应提示不存在：${res.msg}`)
})

test('帖子编辑：校管不能改别校帖子', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const superToken = await login()
  const schoolToken = await login('neimenggu', 'school123')
  const all = await call('GET', '/admin/posts?page=1&size=100', superToken)
  const foreign = all.data.list.find(r => r.schoolId != null && Number(r.schoolId) !== 1)
  if (!foreign) return t.skip('库里没有外校帖子')
  const res = await call('PUT', `/admin/posts/${foreign.id}`, schoolToken, { title: '越权', content: 'x' })
  assert.equal(res.http, 403, '校管改别校帖子应 403')
})

// ================= 二手编辑 =================

test('二手编辑：能改标题/价格/分类/成色并落库', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const row = await firstGoodsId(token)
  const original = {
    title: row.title, price: row.price, category: row.category,
    conditionLevel: row.conditionLevel, description: row.description, image: row.image
  }

  const stamp = Date.now() % 100000
  const res = await call('PUT', `/admin/goods/${row.id}`, token, {
    title: `改商品${stamp}`, price: 88, category: '数码', conditionLevel: '全新', description: '管理端改的'
  })
  assert.equal(res.code, 0, `编辑应成功：${JSON.stringify(res).slice(0, 160)}`)

  const list = await call('GET', '/admin/goods?page=1&size=100', token)
  const hit = list.data.list.find(r => Number(r.id) === Number(row.id))
  assert.equal(hit.title, `改商品${stamp}`)

  await call('PUT', `/admin/goods/${row.id}`, token, original)
})

test('二手编辑：价格非法被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const row = await firstGoodsId(token)
  for (const price of [-1, 100000, 'abc']) {
    const res = await call('PUT', `/admin/goods/${row.id}`, token, { title: row.title, price })
    assert.notEqual(res.code, 0, `价格 ${price} 应被拒绝`)
  }
})

test('二手编辑：图片必须是服务端地址', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const row = await firstGoodsId(token)
  const res = await call('PUT', `/admin/goods/${row.id}`, token, { title: row.title, image: 'http://tmp/x.jpg' })
  assert.notEqual(res.code, 0, 'http://tmp/ 应被拒绝')
})

// ================= 活动编辑 =================

test('活动编辑：能改标题/时间/地点/海报并落库', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const row = await firstActivityId(token)
  const original = {
    title: row.title, timeDesc: row.timeDesc, location: row.location,
    description: row.description, poster: row.poster
  }

  const stamp = Date.now() % 100000
  const res = await call('PUT', `/admin/activities/${row.id}`, token, {
    title: `改活动${stamp}`, timeDesc: '本周日 14:00', location: '操场', description: '管理端改的'
  })
  assert.equal(res.code, 0, `编辑应成功：${JSON.stringify(res).slice(0, 160)}`)

  const list = await call('GET', '/admin/activities?page=1&size=100', token)
  const hit = list.data.list.find(r => Number(r.id) === Number(row.id))
  assert.equal(hit.title, `改活动${stamp}`)

  await call('PUT', `/admin/activities/${row.id}`, token, original)
})

test('活动编辑：海报必须是服务端地址', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const row = await firstActivityId(token)
  const res = await call('PUT', `/admin/activities/${row.id}`, token, { title: row.title, poster: 'wxfile://tmp.jpg' })
  assert.notEqual(res.code, 0)
})

// ================= 用户头像 =================

test('用户头像：无 token 必须 401', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await call('PUT', '/admin/users/1/avatar', null, { avatar: '/uploads/x.jpg' })
  assert.equal(res.http, 401)
})

test('用户头像：能设置并落库', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const list = await call('GET', '/admin/users?page=1&size=100', token)
  const row = list.data.list.find(r => Number(r.schoolId) === 1)
  assert.ok(row, '应有本校用户')
  const original = row.avatar || ''

  const url = '/uploads/avatar-test.png'
  const res = await call('PUT', `/admin/users/${row.id}/avatar`, token, { avatar: url })
  assert.equal(res.code, 0, `设置头像应成功：${JSON.stringify(res).slice(0, 160)}`)

  const after = await call('GET', '/admin/users?page=1&size=100', token)
  const hit = after.data.list.find(r => Number(r.id) === Number(row.id))
  assert.equal(hit.avatar, url)

  await call('PUT', `/admin/users/${row.id}/avatar`, token, { avatar: original })
})

test('用户头像：非法地址被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const list = await call('GET', '/admin/users?page=1&size=100', token)
  const row = list.data.list.find(r => Number(r.schoolId) === 1)
  const res = await call('PUT', `/admin/users/${row.id}/avatar`, token, { avatar: 'wxfile://tmp.jpg' })
  assert.notEqual(res.code, 0, '本地临时路径应被拒绝')
})

test('用户头像：清空头像（传空串）应允许', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const list = await call('GET', '/admin/users?page=1&size=100', token)
  const row = list.data.list.find(r => Number(r.schoolId) === 1)
  const original = row.avatar || ''
  const res = await call('PUT', `/admin/users/${row.id}/avatar`, token, { avatar: '' })
  assert.equal(res.code, 0, '清空头像应成功')
  await call('PUT', `/admin/users/${row.id}/avatar`, token, { avatar: original })
})
