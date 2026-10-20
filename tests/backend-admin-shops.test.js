// 外卖管理：店铺 + 菜品
//
// 背景：小程序有完整的外卖功能（店铺列表、菜单、下单），但管理端**完全没有外卖管理页面**。
// 店铺和菜品只能靠 SQL 维护，运营改不了价格、上不了新菜。
//
// 运行前需后端在 8080 运行：
//   node --test tests/backend-admin-shops.test.js
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

// ================= 店铺列表 =================

test('店铺列表：无 token 必须 401', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await call('GET', '/admin/shops')
  assert.equal(res.http, 401)
})

test('店铺列表：分页返回，字段够管理端展示', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await call('GET', '/admin/shops?page=1&size=20', token)
  assert.equal(res.code, 0, `应成功：${JSON.stringify(res).slice(0, 160)}`)
  const d = res.data
  assert.ok(Array.isArray(d.list), 'data.list 应是数组')
  assert.equal(typeof d.total, 'number')
  assert.equal(d.page, 1)
  assert.ok(d.list.length > 0, '库里应有店铺')
  const row = d.list[0]
  for (const key of ['id', 'name', 'category', 'status']) {
    assert.ok(key in row, `店铺应含字段 ${key}`)
  }
})

test('店铺列表：关键词搜索生效', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const all = await call('GET', '/admin/shops?page=1&size=100', token)
  const sample = all.data.list[0]
  const kw = String(sample.name).slice(0, 2)
  const res = await call('GET', `/admin/shops?page=1&size=100&keyword=${encodeURIComponent(kw)}`, token)
  assert.equal(res.code, 0)
  assert.ok(res.data.list.every(r => String(r.name).includes(kw)), '结果都应含关键词')
})

// ================= 店铺 CRUD =================

test('店铺：新建 → 编辑 → 停用 → 删除 全流程', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const name = `联调店铺${Date.now() % 100000}`

  const created = await call('POST', '/admin/shops', token, {
    name, category: '快餐便当', schoolId: 1,
    cover: '/assets/home/food-fresh.webp',
    minPrice: 12, deliveryFee: 2, deliveryTime: 30, notice: '联调测试'
  })
  assert.equal(created.code, 0, `新建应成功：${JSON.stringify(created).slice(0, 160)}`)
  const id = created.data.id
  assert.ok(id, '应返回 id')

  const updated = await call('PUT', `/admin/shops/${id}`, token, {
    name: `${name}改`, category: '奶茶饮品', minPrice: 15,
    deliveryFee: 3, deliveryTime: 25, notice: '已改'
  })
  assert.equal(updated.code, 0, `编辑应成功：${JSON.stringify(updated).slice(0, 160)}`)

  const list = await call('GET', '/admin/shops?page=1&size=100', token)
  const hit = list.data.list.find(r => Number(r.id) === Number(id))
  assert.equal(hit.name, `${name}改`)
  assert.equal(Number(hit.minPrice), 15)

  const off = await call('POST', `/admin/shops/${id}/status`, token, { status: 0 })
  assert.equal(off.code, 0)
  const afterOff = await call('GET', '/admin/shops?page=1&size=100', token)
  assert.equal(Number(afterOff.data.list.find(r => Number(r.id) === Number(id)).status), 0)

  const removed = await call('DELETE', `/admin/shops/${id}`, token)
  assert.equal(removed.code, 0, `删除应成功：${JSON.stringify(removed).slice(0, 160)}`)
  const afterDel = await call('GET', '/admin/shops?page=1&size=100', token)
  assert.ok(!afterDel.data.list.some(r => Number(r.id) === Number(id)), '删除后不应再出现')
})

test('店铺：名称空或超长被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const empty = await call('POST', '/admin/shops', token, { name: '  ', category: '快餐便当', schoolId: 1 })
  assert.notEqual(empty.code, 0, '空名应被拒绝')
  const long = await call('POST', '/admin/shops', token, { name: '店'.repeat(80), category: '快餐便当', schoolId: 1 })
  assert.notEqual(long.code, 0, '超长应被拒绝')
})

test('店铺：封面必须是服务端地址', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await call('POST', '/admin/shops', token, {
    name: `联调封面${Date.now() % 10000}`, category: '快餐便当', schoolId: 1,
    cover: 'wxfile://tmp_x.jpg'
  })
  assert.notEqual(res.code, 0, '本地临时路径应被拒绝')
})

test('店铺：超管建店必须指定学校', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await call('POST', '/admin/shops', token, { name: `联调无校${Date.now() % 10000}`, category: '快餐便当' })
  assert.notEqual(res.code, 0, '未指定学校应被拒绝')
  assert.ok(/学校/.test(String(res.msg)), `提示应提到学校：${res.msg}`)
})

// ================= 菜品 =================

test('菜品列表：按店铺返回', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const shops = await call('GET', '/admin/shops?page=1&size=100', token)
  const shop = shops.data.list.find(r => Number(r.status) === 1)
  assert.ok(shop, '应有启用中的店铺')
  const res = await call('GET', `/admin/shops/${shop.id}/goods`, token)
  assert.equal(res.code, 0, `应成功：${JSON.stringify(res).slice(0, 160)}`)
  assert.ok(Array.isArray(res.data), 'data 应是数组')
})

test('菜品：新建 → 编辑 → 删除 全流程', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const shops = await call('GET', '/admin/shops?page=1&size=100', token)
  const shop = shops.data.list.find(r => Number(r.status) === 1)

  const name = `联调菜品${Date.now() % 100000}`
  const created = await call('POST', '/admin/shop-goods', token, {
    shopId: shop.id, category: '主食', name, price: 18, image: '/assets/home/food-fresh.webp'
  })
  assert.equal(created.code, 0, `新建应成功：${JSON.stringify(created).slice(0, 160)}`)
  const id = created.data.id

  const updated = await call('PUT', `/admin/shop-goods/${id}`, token, {
    category: '主食', name: `${name}改`, price: 22, image: '/assets/home/food-fresh.webp', status: 1
  })
  assert.equal(updated.code, 0, `编辑应成功：${JSON.stringify(updated).slice(0, 160)}`)

  const list = await call('GET', `/admin/shops/${shop.id}/goods`, token)
  const hit = list.data.find(r => Number(r.id) === Number(id))
  assert.equal(hit.name, `${name}改`)
  assert.equal(Number(hit.price), 22)

  const removed = await call('DELETE', `/admin/shop-goods/${id}`, token)
  assert.equal(removed.code, 0, `删除应成功：${JSON.stringify(removed).slice(0, 160)}`)
  const after = await call('GET', `/admin/shops/${shop.id}/goods`, token)
  assert.ok(!after.data.some(r => Number(r.id) === Number(id)))
})

test('菜品：价格非法被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const shops = await call('GET', '/admin/shops?page=1&size=100', token)
  const shop = shops.data.list.find(r => Number(r.status) === 1)
  for (const price of [-1, 0, 100000, 'abc']) {
    const res = await call('POST', '/admin/shop-goods', token, {
      shopId: shop.id, category: '主食', name: `价格测试${price}`, price,
      image: '/assets/home/food-fresh.webp'
    })
    assert.notEqual(res.code, 0, `价格 ${price} 应被拒绝`)
  }
})

test('菜品：图片必须是服务端地址', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const shops = await call('GET', '/admin/shops?page=1&size=100', token)
  const shop = shops.data.list.find(r => Number(r.status) === 1)
  const res = await call('POST', '/admin/shop-goods', token, {
    shopId: shop.id, category: '主食', name: `图测试${Date.now() % 10000}`, price: 10,
    image: 'http://tmp/bad.jpg'
  })
  assert.notEqual(res.code, 0, 'http://tmp/ 应被拒绝')
})

// ================= 校管隔离 =================

test('校管：只能看到本校店铺', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login('neimenggu', 'school123')
  const res = await call('GET', '/admin/shops?page=1&size=100', token)
  assert.equal(res.code, 0)
  const foreign = res.data.list.filter(r => r.schoolId != null && Number(r.schoolId) !== 1)
  assert.deepEqual(foreign, [], '校管不应看到外校店铺')
})

test('校管：不能改别校店铺', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const superToken = await login()
  const schoolToken = await login('neimenggu', 'school123')
  const all = await call('GET', '/admin/shops?page=1&size=100', superToken)
  const foreign = all.data.list.find(r => r.schoolId != null && Number(r.schoolId) !== 1)
  if (!foreign) return t.skip('库里没有外校店铺')
  const res = await call('PUT', `/admin/shops/${foreign.id}`, schoolToken, { name: '越权', category: '快餐便当' })
  assert.equal(res.http, 403, '校管改别校店铺应 403')
})
