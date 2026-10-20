// 小程序选学校：公开学校列表 + 按选中学校过滤
//
// 背景：小程序首页的学校选择器是硬编码 ['内蒙古大学','北京大学','中国人民大学']，
// 而数据库只有 2 所学校；而且选完只写了 globalData.schoolName，没传给任何接口，
// 等于「切了学校但数据不变」。
//
// 目标：
//   1. 提供公开 GET /api/schools（小程序不需要管理端 token）
//   2. 各列表接口支持 schoolId 参数，按选中的学校过滤
//   3. schoolId 优先于用户自己所属学校
//
// 运行前需后端在 8080 运行：
//   node --test tests/backend-school-picker.test.js
import { test } from 'node:test'
import assert from 'node:assert/strict'

const BASE = process.env.API_BASE || 'http://127.0.0.1:8080/api'

async function serverUp() {
  // 用已有且稳定的接口探活，不能用正在实现的 /schools（否则永远是「后端未启动」）
  try {
    const r = await fetch(`${BASE}/circles?userId=1&page=1&size=1`, { signal: AbortSignal.timeout(4000) })
    return r.ok
  } catch { return false }
}

async function get(path) {
  const r = await fetch(`${BASE}${path}`, { signal: AbortSignal.timeout(6000) })
  const body = await r.json().catch(() => ({}))
  return { http: r.status, ...body }
}

// ================= 公开学校列表 =================

test('公开学校列表：无需登录即可访问', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await get('/schools')
  assert.equal(res.http, 200, '小程序未登录也要能读学校列表')
  assert.equal(res.code, 0)
  assert.ok(Array.isArray(res.data), 'data 应是数组')
})

test('公开学校列表：包含数据库里真实存在的学校', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await get('/schools')
  const names = res.data.map(s => s.name)
  assert.ok(names.includes('内蒙古大学'), `应包含内蒙古大学，实际：${names}`)
  assert.ok(names.includes('北京大学'), `应包含北京大学，实际：${names}`)
})

test('公开学校列表：不再出现硬编码的中国人民大学', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await get('/schools')
  const names = res.data.map(s => s.name)
  assert.ok(
    !names.includes('中国人民大学'),
    '中国人民大学是前端硬编码的，数据库里没有，不应出现'
  )
})

test('公开学校列表：每条都有 id 和 name（小程序要用 id 作为标识）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await get('/schools')
  assert.ok(res.data.length > 0)
  for (const s of res.data) {
    assert.equal(typeof s.id, 'number', `学校 ${JSON.stringify(s)} 的 id 应是数字`)
    assert.ok(s.id > 0)
    assert.ok(typeof s.name === 'string' && s.name.length > 0, '学校名不能为空')
  }
})

test('公开学校列表：只返回启用中的学校', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await get('/schools')
  // 若返回了 status 字段，则必须都是 1
  const withStatus = res.data.filter(s => s.status !== undefined)
  if (withStatus.length) {
    assert.ok(withStatus.every(s => Number(s.status) === 1), '停用的学校不应出现在小程序选择列表')
  }
})

// ================= 按选中学校过滤 =================

test('圈子：schoolId 生效，切到 2 号学校看不到 1 号学校的圈子', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const s1 = await get('/circles?userId=1&schoolId=1&page=1&size=100')
  const s2 = await get('/circles?userId=1&schoolId=2&page=1&size=100')
  assert.equal(s1.code, 0)
  assert.equal(s2.code, 0)
  const ids1 = s1.data.list.map(r => String(r.id))
  const ids2 = s2.data.list.map(r => String(r.id))
  assert.ok(ids1.length > 0, '1 号学校应有圈子')
  const overlap = ids1.filter(id => ids2.includes(id))
  assert.deepEqual(overlap, [], `两所学校的圈子不应重叠，实际重复：${overlap}`)
})

test('圈子：schoolId 优先于用户自己所属学校', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  // userId=1 属于 1 号学校；显式传 schoolId=2 应该看到 2 号学校的圈子
  const byUser = await get('/circles?userId=1&page=1&size=100')
  const byExplicit = await get('/circles?userId=1&schoolId=2&page=1&size=100')
  const userIds = byUser.data.list.map(r => String(r.id))
  const explicitIds = byExplicit.data.list.map(r => String(r.id))
  assert.ok(explicitIds.length > 0, 'schoolId=2 应有数据')
  const same = JSON.stringify(userIds) === JSON.stringify(explicitIds)
  assert.ok(!same, 'schoolId 显式指定后，结果应与「用户默认学校」不同')
})

test('兼职：schoolId 生效', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const s1 = await get('/jobs?userId=1&schoolId=1')
  const s2 = await get('/jobs?userId=1&schoolId=2')
  assert.equal(s1.code, 0)
  assert.equal(s2.code, 0)
  const t1 = (s1.data || []).map(r => String(r.id))
  const t2 = (s2.data || []).map(r => String(r.id))
  const overlap = t1.filter(id => t2.includes(id))
  assert.deepEqual(overlap, [], `两所学校的兼职不应重叠，实际：${overlap}`)
})

test('外卖：schoolId 生效', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const s1 = await get('/shops?userId=1&schoolId=1')
  const s2 = await get('/shops?userId=1&schoolId=2')
  assert.equal(s1.code, 0)
  assert.equal(s2.code, 0)
  const t1 = (s1.data || []).map(r => String(r.id))
  const t2 = (s2.data || []).map(r => String(r.id))
  const overlap = t1.filter(id => t2.includes(id))
  assert.deepEqual(overlap, [], `两所学校的店铺不应重叠，实际：${overlap}`)
})

test('二手：schoolId 生效', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const s1 = await get('/goods?mode=0&userId=1&schoolId=1')
  const s2 = await get('/goods?mode=0&userId=1&schoolId=2')
  assert.equal(s1.code, 0)
  assert.equal(s2.code, 0)
  const t1 = (s1.data || []).map(r => String(r.id))
  const t2 = (s2.data || []).map(r => String(r.id))
  const overlap = t1.filter(id => t2.includes(id))
  assert.deepEqual(overlap, [], `两所学校的二手不应重叠，实际：${overlap}`)
})

test('活动：schoolId 生效', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const s1 = await get('/activities?userId=1&schoolId=1')
  const s2 = await get('/activities?userId=1&schoolId=2')
  assert.equal(s1.code, 0)
  assert.equal(s2.code, 0)
  const t1 = (s1.data || []).map(r => String(r.id))
  const t2 = (s2.data || []).map(r => String(r.id))
  const overlap = t1.filter(id => t2.includes(id))
  assert.deepEqual(overlap, [], `两所学校的活动不应重叠，实际：${overlap}`)
})

test('帖子：schoolId 过滤但仍保留跨校帖', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const s1 = await get('/posts?userId=1&schoolId=1&page=1&size=100')
  assert.equal(s1.code, 0)
  const rows = s1.data.list || []
  assert.ok(rows.length > 0, '1 号学校应有帖子')
  // 每条要么属于 1 号学校，要么是跨校帖（schoolId 为空）
  const bad = rows.filter(r => r.schoolId != null && Number(r.schoolId) !== 1)
  assert.deepEqual(bad, [], '不应出现其他学校的帖子')
})

// ================= 边界 =================

test('非法 schoolId（不存在）返回空数据而不是 500', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await get('/circles?userId=1&schoolId=99999&page=1&size=100')
  assert.equal(res.code, 0, `应正常返回而不是报错：${JSON.stringify(res).slice(0, 160)}`)
  assert.deepEqual(res.data.list, [], '不存在的学校应返回空列表')
})

test('schoolScope=all 仍可看全部（不被 schoolId 破坏）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const all = await get('/circles?userId=1&schoolScope=all&page=1&size=100')
  const one = await get('/circles?userId=1&schoolId=1&page=1&size=100')
  assert.equal(all.code, 0)
  assert.ok(
    all.data.list.length >= one.data.list.length,
    `all 的条数(${all.data.list.length}) 不应少于单校(${one.data.list.length})`
  )
})

test('不传 schoolId 时保持原有行为（向后兼容）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await get('/circles?userId=1&page=1&size=100')
  assert.equal(res.code, 0)
  assert.ok(res.data.list.length > 0, '不传 schoolId 仍应按用户所属学校返回数据')
})
