// 管理端上传接口：必须鉴权
//
// 背景：POST /api/upload 完全无鉴权（任何人可往服务器磁盘写文件），
// 但小程序为了「未登录也能演示」刻意保持宽松，不能直接收紧。
// 因此新增 POST /api/admin/upload 走 AdminInterceptor，管理端只用这个。
//
// 运行前需后端在 8080 运行：
//   node --test tests/backend-admin-upload.test.js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const BASE = process.env.API_BASE || 'http://127.0.0.1:8080/api'
const root = resolve(import.meta.dirname, '..')

// 1x1 透明 PNG
const PNG_BYTES = new Uint8Array([
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
  0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
  0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, 0x89, 0x00, 0x00, 0x00,
  0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
  0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49,
  0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82
])

async function serverUp() {
  try {
    const r = await fetch(`${BASE}/circles?userId=1&page=1&size=1`, { signal: AbortSignal.timeout(4000) })
    return r.ok
  } catch { return false }
}

async function login() {
  const r = await fetch(`${BASE}/admin/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
    signal: AbortSignal.timeout(5000)
  })
  const body = await r.json()
  assert.equal(body.code, 0, `登录失败：${JSON.stringify(body).slice(0, 140)}`)
  return body.data.token
}

function pngFile(name = 'test.png') {
  return new File([PNG_BYTES], name, { type: 'image/png' })
}

async function upload(path, token, file = pngFile()) {
  const form = new FormData()
  form.append('file', file)
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  const r = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers,
    body: form,
    signal: AbortSignal.timeout(10000)
  })
  const body = await r.json().catch(() => ({}))
  return { http: r.status, ...body }
}

// ================= 管理端上传接口 =================

test('管理端上传：无 token 必须 401', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await upload('/admin/upload', null)
  assert.equal(res.http, 401, `无 token 应 401，实际 ${res.http}`)
  assert.equal(res.code, 401)
})

test('管理端上传：伪造 token 必须 401', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await upload('/admin/upload', 'eyJhbGciOiJIUzI1NiJ9.fake.sig')
  assert.equal(res.http, 401, '伪造 token 应 401')
})

test('管理端上传：带有效 token 成功并返回可用地址', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await upload('/admin/upload', token)
  assert.equal(res.code, 0, `应上传成功：${JSON.stringify(res).slice(0, 160)}`)
  assert.ok(res.data.url, '应返回 url')
  assert.ok(res.data.path, '应返回 path')
  assert.ok(res.data.url.includes('/uploads/'), `url 应含 /uploads/，实际：${res.data.url}`)

  // 上传后的图片必须能访问
  const img = await fetch(res.data.url, { signal: AbortSignal.timeout(6000) })
  assert.equal(img.status, 200, '上传后的图片应可访问')
})

test('管理端上传：非图片格式被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await upload('/admin/upload', token, new File(['hello'], 'bad.txt', { type: 'text/plain' }))
  assert.notEqual(res.code, 0, 'txt 应被拒绝')
  assert.ok(/jpg|png|格式/.test(String(res.msg)), `提示应说明格式：${res.msg}`)
})

test('管理端上传：超过 5MB 被拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const big = new Uint8Array(6 * 1024 * 1024)
  const res = await upload('/admin/upload', token, new File([big], 'big.png', { type: 'image/png' }))
  assert.notEqual(res.code, 0, '超大文件应被拒绝')
  assert.ok(/5MB|大小|超过/.test(String(res.msg)), `提示应说明大小：${res.msg}`)
})

test('小程序上传接口保持可用（不能因为收紧管理端而破坏演示）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await upload('/upload', null)
  assert.equal(res.code, 0, '小程序端无 token 也应能上传（保持无后端也能演示）')
})

// ================= 代码层面：管理端必须走鉴权接口 =================

test('管理端前端上传必须走 /admin/upload，不能走公开接口', () => {
  const src = readFileSync(resolve(root, 'web/src/components/NoImageUpload.vue'), 'utf8')
  assert.ok(src.includes('/admin/upload'), '上传组件应调用管理端鉴权接口')
  assert.ok(!/['"`]\/upload['"`]/.test(src), '不应直接调用公开的 /upload')
})

test('上传组件：校验格式与大小，先在前端拦一道', () => {
  const src = readFileSync(resolve(root, 'web/src/components/NoImageUpload.vue'), 'utf8')
  assert.ok(/5\s*\*\s*1024\s*\*\s*1024|5242880/.test(src), '应校验 5MB 上限')
  assert.ok(/image\//.test(src) || /jpg|png|webp/.test(src), '应校验图片类型')
})

test('学校管理：表单有 logo 字段并接上传组件', () => {
  const src = readFileSync(resolve(root, 'web/src/views/Schools.vue'), 'utf8')
  assert.ok(src.includes('NoImageUpload'), '学校管理应使用上传组件')
  assert.ok(/logo/.test(src), '学校表单应包含 logo 字段')
  assert.ok(/form\.logo/.test(src), 'logo 应绑定到表单')
})

test('圈子管理：表单有 cover 字段并接上传组件', () => {
  const src = readFileSync(resolve(root, 'web/src/views/Circles.vue'), 'utf8')
  assert.ok(src.includes('NoImageUpload'), '圈子管理应使用上传组件')
  assert.ok(/cover/.test(src), '圈子表单应包含 cover 字段')
  assert.ok(/form\.cover/.test(src), 'cover 应绑定到表单')
})
