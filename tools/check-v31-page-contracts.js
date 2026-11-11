#!/usr/bin/env node
/**
 * V3.1 学生认证 · 页面契约检查（独立脚本，**不属于** tools/check-all.js 的 9 项门禁）
 *
 * 为什么要有：学生认证设计稿第七节用例 11（个人页显示「已认证」）与用例 13
 * （表单按 require 动态渲染）没有真机自动化，之前只能靠人工读码。这个脚本把
 * 「页面与后端之间的契约」固化成可跑的检查：字段是否按配置渲染、提交体是否带全、
 * 个人页菜单是否用后端 label。它不验证像素与交互，只验证代码契约。
 *
 * 用法：
 *   node tools/check-v31-page-contracts.js
 * 退出码 0 = 全部通过；非 0 = 有失败项（失败原因逐条打印）。
 */
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..')

function read(rel) {
  const file = path.join(ROOT, rel)
  if (!fs.existsSync(file)) throw new Error(`文件不存在：${rel}`)
  return fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n')
}

function must(condition, message) {
  if (!condition) throw new Error(message)
}

const checks = []
function check(name, run) {
  try {
    const detail = run()
    checks.push({ ok: true, name, detail })
  } catch (error) {
    checks.push({ ok: false, name, detail: error.message })
  }
}

const wxml = read('pages/verify/verify.wxml')
const verifyJs = read('pages/verify/verify.js')
const profileJs = read('pages/profile/profile.js')

const REQUIRE_FIELDS = ['realName', 'studentNo', 'college', 'phone']
const FORM_FIELDS = ['schoolId', 'college', 'realName', 'studentNo', 'phone']

// ---------- 用例 11：个人页菜单显示真实认证状态 ----------
check('profile.js：已认证状态来自 /me 的 label，并写入「学生认证」菜单 value', () => {
  must(/patchMenuValue\(\s*'cert'\s*,\s*verify\.label/.test(profileJs),
    "没找到 patchMenuValue('cert', verify.label ...)：菜单 value 可能仍写死")
  must(/getVerification\(/.test(profileJs),
    '没找到 getVerification() 调用：认证状态没有从后端读')
  must(!/value:\s*'未开通'/.test(profileJs),
    "菜单仍写死「未开通」")
  return "cert 菜单 value ← verify.label（来自 GET /api/verification/me）"
})

// ---------- 用例 13：表单按 require 动态渲染 ----------
check('verify.wxml：姓名/学号/学院/手机号四个输入块各自用 config.require.<字段> 控制渲染', () => {
  for (const field of REQUIRE_FIELDS) {
    const token = `data-field="${field}"`
    const at = wxml.indexOf(token)
    must(at >= 0, `找不到 ${token} 的输入块（字段被删或 data-field 改名）`)
    // 输入块前的 600 个字符内必须能读到对应的 wx:if（防止四个块共用一个条件）
    const before = wxml.slice(Math.max(0, at - 600), at)
    const marker = `wx:if="{{config.require.${field}}}"`
    must(before.includes(marker),
      `${token} 所在字段块没有 ${marker}，表单不会按配置渲染`)
  }
  return 'realName / studentNo / college / phone 均按 /config 的 require 动态渲染'
})

// ---------- 提交请求体契约 ----------
check('verify.js：提交时把学校/学院/姓名/学号/手机号全部放进请求体', () => {
  const call = verifyJs.match(/applyVerification\(\s*\{([\s\S]*?)\}\s*\)/)
  must(call, '没找到 api.applyVerification({...}) 调用')
  const body = call[1]
  for (const field of FORM_FIELDS) {
    must(new RegExp(`\\b${field}\\s*:`).test(body), `请求体缺少 ${field} 字段`)
  }
  return `请求体字段齐全：${FORM_FIELDS.join(' / ')}`
})

// ---------- 手机号脱敏回填契约（防止把 138****1234 再提交回去） ----------
check('verify.js：手机号不回填脱敏值（表单值固定为空串，让用户重输明文）', () => {
  must(/phone\s*:\s*''/.test(verifyJs),
    'formData 里没找到 phone 固定空串：脱敏值可能被回填进表单')
  must(!/phone\s*:\s*\(me\s*&&\s*me\.phone\)/.test(verifyJs),
    '发现 phone 仍从 me.phone 回填：/me 返回的是脱敏值，提交会被后端判为格式错误')
  return "form.phone 固定 ''；/me 的脱敏值只用于只读展示"
})

// ---------- 隐私声明随字段同步 ----------
check('verify.wxml：隐私声明包含手机号（新增采集字段必须如实告知）', () => {
  must(/我们只保存姓名、学号、学院、手机号/.test(wxml),
    '隐私声明仍只写姓名/学号/学院，与新增的 phone 字段不一致')
  return '隐私声明已覆盖姓名/学号/学院/手机号'
})

// ---------- 输出 ----------
console.log('V3.1 学生认证 · 页面契约检查（独立脚本，不计入 check-all 9 项门禁）\n')
for (const item of checks) {
  console.log(`${item.ok ? '✅' : '❌'} ${item.name}`)
  console.log(`   ${item.detail}`)
}

const failed = checks.filter(item => !item.ok)
console.log(`\n汇总：${checks.length - failed.length} 项通过 / ${failed.length} 项失败`)
if (failed.length) {
  console.log('失败项：' + failed.map(item => item.name).join('；'))
  process.exit(1)
}
console.log('契约级通过：wxml 绑定与请求体由本脚本断言；真机像素级未验证')