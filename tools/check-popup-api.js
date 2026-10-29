// 检查所有 popup.xxx 调用是否都是 ui-popup-helper 真实导出的方法
// （曾出现 popup.confirm 不存在、调用即抛 TypeError 的问题）
const fs = require('fs')
const path = require('path')
process.chdir('E:\\goodworlk\\campus-life-miniapp')

// 1. helper 导出的方法
const helper = fs.readFileSync('utils/ui-popup-helper.js', 'utf8')
const exported = new Set()
const block = helper.slice(helper.indexOf('module.exports'))
for (const m of block.matchAll(/^\s{2}([a-zA-Z_$][\w$]*)\s*[(:]/gm)) exported.add(m[1])
// hide 是简写形式 `hide`
for (const m of block.matchAll(/^\s{2}([a-zA-Z_$][\w$]*)\s*,?\s*$/gm)) exported.add(m[1])

console.log('helper 导出的方法:', [...exported].join(', '))
console.log('')

// 2. 全项目 popup.xxx 调用
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

const calls = new Map()
for (const f of files) {
  const c = fs.readFileSync(f, 'utf8')
  for (const m of c.matchAll(/\bpopup\.([a-zA-Z_$][\w$]*)\s*\(/g)) {
    const name = m[1]
    if (!calls.has(name)) calls.set(name, [])
    calls.get(name).push(path.relative('.', f))
  }
}

console.log('=== 调用统计 ===')
let bad = 0
for (const [name, where] of [...calls].sort((a, b) => b[1].length - a[1].length)) {
  const ok = exported.has(name)
  if (!ok) bad++
  console.log(`  ${ok ? '✅' : '❌'} popup.${name}()  ${where.length} 处` + (ok ? '' : '  ← 方法不存在！'))
}

console.log('')
if (bad === 0) console.log('✅ 所有 popup 调用都是有效方法')
else {
  console.log('❌ 有 ' + bad + ' 个方法不存在，运行时会抛 TypeError')
  for (const [name, where] of calls) {
    if (!exported.has(name)) where.forEach(w => console.log(`   ${name} @ ${w}`))
  }
}
