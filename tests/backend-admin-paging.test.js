// 管理端列表分页：圈子 + Banner
// 这两个端点原来返回裸数组（全量），数据量增长后会一次性拉全库。
// 目标：改成 {list,total,page,size}，并保证 page/size 真正生效。
//
// 运行前需后端在 8080 运行：
//   node --test tests/backend-admin-paging.test.js
import { test } from 'node:test'
import assert from 'node:assert/strict'

const BASE = process.env.API_BASE || 'http://127.0.0.1:8080/api'

async function serverUp() {
  try {
    const r = await fetch(`${BASE}/admin/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ username: 'admin', password: 'admin123' }),
      signal: AbortSignal.timeout(4000)
    })
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

async function get(path, token) {
  const r = await fetch(`${BASE}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(6000)
  })
  const body = await r.json()
  return { http: r.status, ...body }
}

// ================= 圈子 =================

test('圈子列表返回分页结构（list/total/page/size）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await get('/admin/circles?page=1&size=5', token)
  assert.equal(res.code, 0, `应成功：${JSON.stringify(res).slice(0, 160)}`)
  const d = res.data
  assert.ok(d && typeof d === 'object' && !Array.isArray(d), 'data 应是对象而不是裸数组')
  assert.ok(Array.isArray(d.list), 'data.list 应是数组')
  assert.equal(typeof d.total, 'number', 'data.total 应是数字')
  assert.equal(d.page, 1)
  assert.equal(d.size, 5)
})

test('圈子分页真正生效：size=3 只返回 3 条，且两页不重复', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const p1 = await get('/admin/circles?page=1&size=3', token)
  const p2 = await get('/admin/circles?page=2&size=3', token)
  assert.equal(p1.data.list.length, 3, '第 1 页应正好 3 条')
  assert.ok(p2.data.list.length > 0, '第 2 页应有数据（库里圈子超过 3 个）')
  const ids1 = p1.data.list.map(r => String(r.id))
  const ids2 = p2.data.list.map(r => String(r.id))
  const overlap = ids1.filter(id => ids2.includes(id))
  assert.deepEqual(overlap, [], `两页不应有重复项，实际重复：${overlap}`)
  // total 应一致（不随分页变化）
  assert.equal(p1.data.total, p2.data.total, 'total 应稳定，不随页码变化')
})

test('圈子分页 total 等于全量条数', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const all = await get('/admin/circles?page=1&size=200', token)
  const one = await get('/admin/circles?page=1&size=1', token)
  assert.equal(one.data.total, all.data.list.length, 'size=1 时的 total 应等于全量返回条数')
})

test('圈子分页与关键词搜索组合', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const all = await get('/admin/circles?page=1&size=200', token)
  const sample = all.data.list[0]
  assert.ok(sample, '库里应至少有一个圈子')
  const kw = String(sample.name).slice(0, 2)
  const res = await get(`/admin/circles?page=1&size=5&keyword=${encodeURIComponent(kw)}`, token)
  assert.equal(res.code, 0)
  assert.ok(res.data.list.length > 0, `关键词「${kw}」应能搜到圈子`)
  assert.ok(
    res.data.list.every(r => String(r.name).includes(kw)),
    '搜索结果都应包含关键词'
  )
  assert.ok(res.data.total <= all.data.total, '搜索后的 total 不应超过全量')
})

test('圈子分页：page 越界返回空列表但 total 不变', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await get('/admin/circles?page=999&size=10', token)
  assert.equal(res.code, 0)
  assert.deepEqual(res.data.list, [], '越界页应返回空数组')
  assert.ok(res.data.total > 0, 'total 仍应是真实总数')
})

test('圈子分页：size 超上限被收敛（防一次拉全库）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await get('/admin/circles?page=1&size=99999', token)
  assert.equal(res.code, 0)
  assert.ok(res.data.size <= 100, `size 应被收敛到 <=100，实际 ${res.data.size}`)
  assert.ok(res.data.list.length <= 100, '单页条数应 <= 100')
})

// ================= Banner =================

test('Banner 列表返回分页结构', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await get('/admin/banners?page=1&size=5', token)
  assert.equal(res.code, 0, `应成功：${JSON.stringify(res).slice(0, 160)}`)
  const d = res.data
  assert.ok(d && typeof d === 'object' && !Array.isArray(d), 'data 应是对象而不是裸数组')
  assert.ok(Array.isArray(d.list), 'data.list 应是数组')
  assert.equal(typeof d.total, 'number')
  assert.equal(d.page, 1)
  assert.equal(d.size, 5)
})

test('Banner 分页真正生效', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const all = await get('/admin/banners?page=1&size=200', token)
  const one = await get('/admin/banners?page=1&size=1', token)
  assert.equal(one.data.list.length, Math.min(1, all.data.list.length), 'size=1 最多返回 1 条')
  assert.equal(one.data.total, all.data.list.length, 'total 应等于全量条数')
})

// ================= 校管隔离不能被分页破坏 =================

test('校管圈子分页的 total 只算本校', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const superToken = await login()
  const schoolToken = await login('neimenggu', 'school123')
  const s = await get('/admin/circles?page=1&size=200', superToken)
  const c = await get('/admin/circles?page=1&size=200', schoolToken)
  assert.ok(
    c.data.total <= s.data.total,
    `校管 total(${c.data.total}) 不应超过超管 total(${s.data.total})`
  )
  // 校管看到的每个圈子都必须属于本校
  const foreign = c.data.list.filter(r => r.schoolId != null && Number(r.schoolId) !== 1)
  assert.deepEqual(foreign, [], '校管不应看到外校圈子')
})
