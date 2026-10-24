// 上传安全加固：频率限制 + 文件头魔数校验 + 孤儿文件清理 + 私有目录
//
// 背景（来自 docs/IMAGE_UPLOAD_ANALYSIS.md 的已知风险）：
//   1. 上传无频率限制 —— 可脚本刷爆磁盘
//   2. 只校验扩展名 —— 把 .jpg 改成 .php 也能传
//   3. 无清理机制 —— 删了内容图片还在
//   4. /uploads/** 完全公开 —— 证件照类敏感图会泄露
//
// 运行前需后端在 8080 运行：
//   node --test tests/backend-upload-security.test.js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const BASE = process.env.API_BASE || 'http://127.0.0.1:8080/api'
const root = resolve(import.meta.dirname, '..')

// 1x1 透明 PNG（真实文件头）
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

async function upload(path, token, file, extraHeaders = {}) {
  const form = new FormData()
  form.append('file', file)
  const headers = { ...extraHeaders }
  if (token) headers.Authorization = `Bearer ${token}`
  const r = await fetch(`${BASE}${path}`, {
    method: 'POST', headers, body: form,
    signal: AbortSignal.timeout(12000)
  })
  const body = await r.json().catch(() => ({}))
  return { http: r.status, ...body }
}

const pngFile = (name = 'test.png') => new File([PNG_BYTES], name, { type: 'image/png' })

// ================= ① 文件头魔数校验 =================

test('魔数校验：扩展名是 .png 但内容不是图片 → 拒绝', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  // 内容是一段 PHP/HTML，但文件名伪装成 .png
  const fake = new File(['<?php echo "pwned"; ?>'], 'evil.png', { type: 'image/png' })
  const res = await upload('/admin/upload', token, fake)
  assert.notEqual(res.code, 0, '伪装成 png 的非图片内容必须被拒绝')
  assert.ok(/图片|格式|内容/.test(String(res.msg)), `提示应说明内容问题：${res.msg}`)
})

test('魔数校验：真实 PNG 正常通过', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await upload('/admin/upload', token, pngFile())
  assert.equal(res.code, 0, `真实图片应通过：${JSON.stringify(res).slice(0, 160)}`)
})

test('魔数校验：JPEG / GIF / WEBP 文件头都能识别', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()

  const jpeg = new File([new Uint8Array([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46])], 'a.jpg', { type: 'image/jpeg' })
  const r1 = await upload('/admin/upload', token, jpeg)
  assert.equal(r1.code, 0, `JPEG 应通过：${r1.msg}`)

  const gif = new File([new Uint8Array([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00])], 'a.gif', { type: 'image/gif' })
  const r2 = await upload('/admin/upload', token, gif)
  assert.equal(r2.code, 0, `GIF 应通过：${r2.msg}`)

  const webp = new File([new Uint8Array([0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50])], 'a.webp', { type: 'image/webp' })
  const r3 = await upload('/admin/upload', token, webp)
  assert.equal(r3.code, 0, `WEBP 应通过：${r3.msg}`)
})

// ================= ② 上传频率限制 =================

test('频率限制：短时间连续上传超过阈值后返回 429', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  // 用一个独立的标识头模拟不同来源，避免和其他测试互相干扰
  const who = `ratelimit-${Date.now()}`
  let limited = false
  let lastStatus = 0
  for (let i = 0; i < 40; i += 1) {
    const res = await upload('/admin/upload', token, pngFile(`r${i}.png`), { 'X-Upload-Client': who })
    lastStatus = res.http
    if (res.http === 429) { limited = true; break }
  }
  assert.ok(limited, `连续上传应触发限流，最后一次 HTTP=${lastStatus}`)
})

test('频率限制：不会误伤正常使用（前几次一定成功）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const who = `ratelimit-ok-${Date.now()}`
  for (let i = 0; i < 3; i += 1) {
    const res = await upload('/admin/upload', token, pngFile(`ok${i}.png`), { 'X-Upload-Client': who })
    assert.equal(res.code, 0, `第 ${i + 1} 次上传应成功：${JSON.stringify(res).slice(0, 120)}`)
  }
})

// ================= ③ 孤儿文件清理 =================

test('孤儿清理：需要管理端 token', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const r = await fetch(`${BASE}/admin/uploads/orphans`, { signal: AbortSignal.timeout(6000) })
  assert.equal(r.status, 401, '无 token 应 401')
})

test('孤儿清理：只预览不删除时返回可清理数量', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const r = await fetch(`${BASE}/admin/uploads/orphans?dryRun=true`, {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(10000)
  })
  const body = await r.json()
  assert.equal(body.code, 0, `应成功：${JSON.stringify(body).slice(0, 160)}`)
  assert.equal(typeof body.data.orphans, 'number', '应返回孤儿文件数')
  assert.equal(typeof body.data.total, 'number', '应返回文件总数')
  assert.ok(body.data.orphans <= body.data.total, '孤儿数不应超过总数')
})

// ================= ④ 私有目录：敏感图片隔离 =================

test('私有上传：公开入驻可无 token 上传，但只返回私有路径', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await upload('/admin/upload/private', null, pngFile())
  assert.equal(res.code, 0, `公开申请上传应成功：${JSON.stringify(res).slice(0, 160)}`)
  assert.ok(res.data.path.startsWith('/private/'), `应返回私有路径，实际：${res.data.path}`)
  assert.equal(res.data.url, '', '私有上传不应返回可直接访问的 URL')

  const pub = await fetch(`http://127.0.0.1:8080${res.data.path}`, { signal: AbortSignal.timeout(6000) })
  assert.notEqual(pub.status, 200, '私有文件不应能通过公开静态路径访问')
})

test('私有上传：伪造 token 仍必须 401', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const res = await upload('/admin/upload/private', 'eyJhbGciOiJIUzI1NiJ9.fake.sig', pngFile())
  assert.equal(res.http, 401)
})

test('私有上传：带 token 成功，但返回的是站内路径且不能公开访问', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await upload('/admin/upload/private', token, pngFile('id-card.png'))
  assert.equal(res.code, 0, `应上传成功：${JSON.stringify(res).slice(0, 160)}`)
  assert.ok(res.data.path, '应返回 path')
  assert.ok(res.data.path.startsWith('/private/'), `私有路径应以 /private/ 开头，实际：${res.data.path}`)

  // 关键：私有文件不能通过公开静态路径访问
  const pub = await fetch(`http://127.0.0.1:8080${res.data.path}`, { signal: AbortSignal.timeout(6000) })
  assert.notEqual(pub.status, 200, '私有文件不应能直接公开访问')
})

test('私有上传：公开上传仍返回 /uploads/ 路径', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const token = await login()
  const res = await upload('/admin/upload', token, pngFile())
  assert.ok(res.data.path.startsWith('/uploads/'), `公开上传应落 /uploads/，实际：${res.data.path}`)
})

// ================= 代码层面防回归 =================

test('代码：UploadService 必须做文件头校验，不能只看扩展名', () => {
  const src = readFileSync(resolve(root, 'backend/src/main/java/com/campuslife/service/UploadService.java'), 'utf8')
  assert.ok(/magic|signature|文件头|0x89|PNG/i.test(src), '应有文件头/魔数校验逻辑')
})

test('代码：必须有上传频率限制组件', () => {
  const src = readFileSync(resolve(root, 'backend/src/main/java/com/campuslife/service/UploadRateLimiter.java'), 'utf8')
  assert.ok(src.length > 0, '频率限制类应存在')
  assert.ok(/429|TOO_MANY|频繁/.test(src), '应返回 429 或明确提示')
})
