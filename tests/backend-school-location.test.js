// 每校自定义地点：管理端 CRUD + 小程序按校拉取
//
// 背景：发帖地点原来硬编码 ['内蒙古大学','紫荆操场','逸夫图书馆','东区篮球场','学生公寓']，
// 「紫荆操场」是清华的、「内蒙古大学」是学校名不是地点。
// 改成 school_location 表，管理端维护、小程序按选中学校拉。
//
// 运行前需后端在 8080 运行：
//   node --test tests/backend-school-location.test.js
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
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(6000)
  })
  const data = await r.json().catch(() => ({}))
  return { http: r.status, ...data }
}

// ================= 公开接口：小程序按校拉地点 =================

test('公开地点接口：无需登录即可访问', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await call('GET', '/schools/1/locations')
  assert.equal(res.http, 200, '小程序未登录也要能读地点')
  assert.equal(res.code, 0)
  assert.ok(Array.isArray(res.data), 'data 应是数组')
})

test('公开地点接口：返回该校自己的地点，且不含外校地点', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const s1 = await call('GET', '/schools/1/locations')
  const s2 = await call('GET', '/schools/2/locations')
  assert.ok(s1.data.length > 0, '1 号学校应有地点')
  assert.ok(s2.data.length > 0, '2 号学校应有地点')

  const names1 = s1.data.map(r => r.name)
  // 每所学校有独立记录，id 不应重叠
  const ids1 = s1.data.map(r => String(r.id))
  const ids2 = s2.data.map(r => String(r.id))
  const overlap = ids1.filter(id => ids2.includes(id))
  assert.deepEqual(overlap, [], '两所学校的地点记录不应重叠')
  assert.ok(names1.includes('图书馆'), `默认种子应含「图书馆」，实际：${names1}`)
})

test('公开地点接口：不再出现外校地点与学校名', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await call('GET', '/schools/1/locations')
  const names = res.data.map(r => r.name)
  for (const bad of ['紫荆操场', '逸夫图书馆', '东区篮球场', '内蒙古大学']) {
    assert.ok(!names.includes(bad), `地点里不应出现「${bad}」`)
  }
})

test('公开地点接口：按 sort 排序', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await call('GET', '/schools/1/locations')
  const sorts = res.data.map(r => Number(r.sort))
  const sorted = [...sorts].sort((a, b) => a - b)
  assert.deepEqual(sorts, sorted, '地点应按 sort 升序返回')
})

test('公开地点接口：不存在的学校返回空数组而不是 500', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await call('GET', '/schools/99999/locations')
  assert.equal(res.code, 0, `应正常返回：${JSON.stringify(res).slice(0, 140)}`)
  assert.deepEqual(res.data, [])
})

// ================= 管理端 CRUD =================

test('管理端地点列表：需要 token', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await call('GET', '/admin/school-locations?schoolId=1')
  assert.equal(res.http, 401, '无 token 应 401')
})

test('管理端地点列表：能看到指定学校的地点', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await call('GET', '/admin/school-locations?schoolId=1', token)
  assert.equal(res.code, 0)
  assert.ok(Array.isArray(res.data))
  assert.ok(res.data.length > 0)
  assert.ok(res.data.every(r => Number(r.schoolId) === 1), '应只返回 1 号学校的地点')
})

test('管理端新建地点', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const name = `联调地点${Date.now() % 100000}`
  const created = await call('POST', '/admin/school-locations', token, { schoolId: 1, name, sort: 99 })
  assert.equal(created.code, 0, `新建应成功：${JSON.stringify(created).slice(0, 160)}`)
  assert.ok(created.data.id, '应返回新记录 id')

  const list = await call('GET', '/admin/school-locations?schoolId=1', token)
  assert.ok(list.data.some(r => r.name === name), '新建的地点应出现在列表里')

  // 清理
  await call('DELETE', `/admin/school-locations/${created.data.id}`, token)
})

test('管理端新建地点：同名应被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const dup = await call('POST', '/admin/school-locations', token, { schoolId: 1, name: '图书馆' })
  assert.notEqual(dup.code, 0, '同校重名地点应被拒绝')
  assert.ok(
    /同名|已存在|重复/.test(String(dup.msg)),
    `提示应说明重复：${dup.msg}`
  )
})

test('管理端新建地点：空名或超长应被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const empty = await call('POST', '/admin/school-locations', token, { schoolId: 1, name: '   ' })
  assert.notEqual(empty.code, 0, '空名应被拒绝')

  const long = await call('POST', '/admin/school-locations', token, { schoolId: 1, name: '很'.repeat(40) })
  assert.notEqual(long.code, 0, '超长应被拒绝')
})

test('管理端编辑地点', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const name = `联调改名${Date.now() % 100000}`
  const created = await call('POST', '/admin/school-locations', token, { schoolId: 1, name, sort: 99 })
  assert.equal(created.code, 0)

  const updated = await call('PUT', `/admin/school-locations/${created.data.id}`, token, {
    name: `${name}改`, sort: 88, status: 1
  })
  assert.equal(updated.code, 0, `编辑应成功：${JSON.stringify(updated).slice(0, 160)}`)

  const list = await call('GET', '/admin/school-locations?schoolId=1', token)
  const hit = list.data.find(r => Number(r.id) === Number(created.data.id))
  assert.equal(hit.name, `${name}改`)
  assert.equal(Number(hit.sort), 88)

  await call('DELETE', `/admin/school-locations/${created.data.id}`, token)
})

test('管理端删除地点', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const name = `联调删除${Date.now() % 100000}`
  const created = await call('POST', '/admin/school-locations', token, { schoolId: 1, name })
  assert.equal(created.code, 0)

  const removed = await call('DELETE', `/admin/school-locations/${created.data.id}`, token)
  assert.equal(removed.code, 0, `删除应成功：${JSON.stringify(removed).slice(0, 160)}`)

  const list = await call('GET', '/admin/school-locations?schoolId=1', token)
  assert.ok(!list.data.some(r => Number(r.id) === Number(created.data.id)), '删除后不应再出现')
})

test('管理端停用地点：公开接口不再返回', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const name = `联调停用${Date.now() % 100000}`
  const created = await call('POST', '/admin/school-locations', token, { schoolId: 1, name })
  assert.equal(created.code, 0)

  await call('PUT', `/admin/school-locations/${created.data.id}`, token, { name, sort: 0, status: 0 })
  const pub = await call('GET', '/schools/1/locations')
  assert.ok(!pub.data.some(r => r.name === name), '停用的地点不应出现在小程序列表')

  await call('DELETE', `/admin/school-locations/${created.data.id}`, token)
})

// ================= 校管隔离 =================

test('校管：只能看本校地点，看不了别校', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const schoolToken = await login('neimenggu', 'school123')
  const own = await call('GET', '/admin/school-locations', schoolToken)
  assert.equal(own.code, 0)
  assert.ok(own.data.length > 0, '校管应能看到本校地点')
  assert.ok(own.data.every(r => Number(r.schoolId) === 1), '校管只能看到本校地点')

  // 显式请求别校 → 应 403
  const foreign = await call('GET', '/admin/school-locations?schoolId=2', schoolToken)
  assert.equal(foreign.http, 403, '校管请求别校地点应 403')
})

test('校管：不能给别校新建地点', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const schoolToken = await login('neimenggu', 'school123')
  const res = await call('POST', '/admin/school-locations', schoolToken, { schoolId: 2, name: `越权${Date.now() % 10000}` })
  assert.equal(res.http, 403, '校管给别校建地点应 403')
})
