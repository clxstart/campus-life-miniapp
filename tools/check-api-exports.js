/**
 * 检查 api.xxx() 调用是否都真实导出
 *
 * 背景：曾出现 api.deleteGoods is not a function ——
 * 函数在 utils/api/market.js 里定义了，但 utils/api.js 是
 * 「选择性重新导出」，漏加一行就会运行时抛 TypeError。
 *
 * 这类错误静态语法检查发现不了，必须比对「调用」与「导出」。
 */
const fs = require('fs')
const path = require('path')
process.chdir('E:\\goodworlk\\campus-life-miniapp')

// ---------- 收集 api 实际导出的方法 ----------
const apiFiles = ['utils/api.js']
for (const n of fs.readdirSync('utils/api')) {
  if (n.endsWith('.js')) apiFiles.push('utils/api/' + n)
}

// 用 node 直接 require 拿到真实导出，最可靠
// 注意：require('./x') 是相对脚本文件解析的，必须用绝对路径
const ROOT = process.cwd()
const api = require(path.join(ROOT, 'utils/api.js'))
const exported = new Set(Object.keys(api).filter(k => typeof api[k] === 'function'))
// 属性（非函数）也允许，如 BASE_URL / DEMO_USER_ID
const exportedAll = new Set(Object.keys(api))

console.log('utils/api.js 导出函数 ' + exported.size + ' 个')

// 子模块导出（页面有时直接用 utils/api/xxx）
const subExports = {}
for (const f of apiFiles.slice(1)) {
  const mod = require(path.join(ROOT, f))
  subExports[f] = new Set(Object.keys(mod))
}

// ---------- 扫描所有 api.xxx( 调用 ----------
const files = []
function walk(dir) {
  for (const n of fs.readdirSync(dir)) {
    if (n === 'node_modules' || n === 'miniprogram_npm') continue
    const f = path.join(dir, n)
    if (fs.statSync(f).isDirectory()) walk(f)
    else if (n.endsWith('.js')) files.push(f)
  }
}
walk('pages'); walk('components'); walk('utils')

const missing = new Map()
for (const f of files) {
  // api.js 自身和 api/ 子目录内部调用不算
  if (f.startsWith('utils' + path.sep + 'api')) continue
  const c = fs.readFileSync(f, 'utf8')
  for (const m of c.matchAll(/\bapi\.([a-zA-Z_$][\w$]*)\s*\(/g)) {
    const name = m[1]
    if (!exportedAll.has(name)) {
      if (!missing.has(name)) missing.set(name, [])
      missing.get(name).push(path.relative('.', f))
    }
  }
}

console.log('')
if (missing.size === 0) {
  console.log('✅ 所有 api.xxx() 调用都能在 utils/api.js 找到对应导出')
} else {
  console.log('❌ ' + missing.size + ' 个方法未导出（运行时会抛 TypeError）')
  for (const [name, where] of missing) {
    console.log(`  api.${name}()`)
    where.forEach(w => console.log('     ' + w))
  }
  process.exit(1)
}
