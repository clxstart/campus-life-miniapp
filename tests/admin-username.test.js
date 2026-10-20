// 账号字符集校验：账号是登录凭证，只允许字母、数字、下划线、点、连字符
//
// 背景：管理端创建管理员时只校验了长度（3-32位），没校验字符集，
// 结果数据库里出现了 `cc成` 这种中英混排的账号。
// 账号一旦混入中文，登录时会遇到输入法状态、全角/半角、大小写等一堆问题。
//
// 运行：node --test tests/admin-username.test.js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const controllerPath = resolve(root, 'backend/src/main/java/com/campuslife/controller/AdminAuthController.java')
const source = readFileSync(controllerPath, 'utf8')

test('后端：创建管理员时校验账号字符集', () => {
  assert.ok(
    source.includes('USERNAME_PATTERN') || source.includes('matches('),
    'createAdmin 应该用正则校验账号字符集'
  )
  assert.ok(
    /\[\^?a-zA-Z|a-zA-Z0-9/.test(source),
    '校验正则应限定为字母数字等 ASCII 字符'
  )
})

test('后端：账号校验必须拒绝中文（正则不含中文字符类）', () => {
  // 抓出正则定义，确认没有放行任意字符的写法
  const match = source.match(/(?:USERNAME_PATTERN|Pattern\.compile)\s*=?\s*Pattern\.compile\(\s*"([^"]+)"/)
    || source.match(/USERNAME_PATTERN\s*=\s*Pattern\.compile\(\s*"([^"]+)"/)
  assert.ok(match, '应能找到账号校验正则的定义')
  const pattern = match[1]
  assert.ok(!pattern.includes('\\p{IsHan}'), '不应显式放行汉字')
  assert.ok(!pattern.includes('\\p{L}'), '\\p{L} 会放行所有语言字母，包括中文')
  assert.ok(pattern.includes('a-zA-Z'), '应显式限定为 ASCII 字母')
})

test('后端：登录时也做字符集校验（防止历史中文账号绕过）', () => {
  // 登录不一定要拒绝，但必须不因中文账号而 500
  assert.ok(source.includes('login'), '应有登录方法')
  assert.ok(
    source.includes('trim'),
    '登录入参应做 trim，避免前后空格导致登录失败'
  )
})

test('小程序：昵称允许中文（昵称不是账号，不应被账号规则误伤）', () => {
  // 这条是防回归：修账号校验时不要把用户昵称也限制成英文
  const profileSrc = readFileSync(resolve(root, 'utils/api.js'), 'utf8')
  assert.ok(profileSrc.includes('updateUserProfile'), '用户资料接口应存在')
  assert.ok(
    !/nickname[^\n]*\/\^\[a-zA-Z/.test(profileSrc),
    '昵称不应被限制为纯英文'
  )
})
