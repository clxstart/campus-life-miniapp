// 静态 bug 扫描
// 1) WXML 里 bind*/catch* 绑定的方法，在页面/组件 JS 里是否存在
// 2) JS 里 setData 是否在页面卸载后仍可能执行（缺 _alive 保护）
const fs = require('fs')
const path = require('path')
process.chdir('E:\\goodworlk\\campus-life-miniapp')

const app = JSON.parse(fs.readFileSync('app.json', 'utf8'))

// 收集 JS 里定义的方法名（Page/Component 对象的方法 + 独立 function）
function definedMethods(js) {
  const set = new Set()
  // 形如 `  name(`, `  async name(`, `  name: function`, `  name: (`
  for (const m of js.matchAll(/^\s{2,4}(?:async\s+)?([A-Za-z_$][\w$]*)\s*\(/gm)) set.add(m[1])
  for (const m of js.matchAll(/^\s{2,4}([A-Za-z_$][\w$]*)\s*:\s*(?:async\s*)?(?:function|\()/gm)) set.add(m[1])
  // 组件 methods 内部的缩进更多，上面两条也能覆盖
  return set
}

let problems = 0
const report = []

// ---- 页面 ----
for (const p of app.pages) {
  const wxml = p + '.wxml'
  const js = p + '.js'
  if (!fs.existsSync(wxml) || !fs.existsSync(js)) continue
  const w = fs.readFileSync(wxml, 'utf8')
  const j = fs.readFileSync(js, 'utf8')
  const defs = definedMethods(j)

  // 绑定：bindtap="x" catchtap="x" bind:xxx="x" bindchange="x" 等
  const re = /\b(?:bind|catch)(?::)?([a-zA-Z]+)\s*=\s*"([A-Za-z_$][\w$]*)"/g
  let m
  const missing = new Set()
  while ((m = re.exec(w))) {
    const handler = m[2]
    if (!defs.has(handler)) missing.add(handler)
  }
  if (missing.size) {
    problems++
    report.push({ file: wxml, missing: [...missing] })
  }
}

// ---- 组件 ----
for (const d of fs.readdirSync('components')) {
  const base = path.join('components', d, 'index')
  if (!fs.existsSync(base + '.wxml') || !fs.existsSync(base + '.js')) continue
  const w = fs.readFileSync(base + '.wxml', 'utf8')
  const j = fs.readFileSync(base + '.js', 'utf8')
  const defs = definedMethods(j)
  // 组件还有 properties 里的 observer
  for (const m of j.matchAll(/observer\s*\(\s*([\w$]+)\s*\)/g)) defs.add(m[1])

  const re = /\b(?:bind|catch)(?::)?([a-zA-Z]+)\s*=\s*"([A-Za-z_$][\w$]*)"/g
  let m
  const missing = new Set()
  while ((m = re.exec(w))) {
    const handler = m[2]
    if (!defs.has(handler)) missing.add(handler)
  }
  if (missing.size) {
    problems++
    report.push({ file: base + '.wxml', missing: [...missing] })
  }
}

console.log('=== 检查 1：WXML 绑定但 JS 未定义的方法 ===')
if (!report.length) console.log('  ✅ 未发现')
else {
  for (const r of report) console.log(`  ❌ ${r.file}\n       未定义: ${r.missing.join(', ')}`)
}
console.log(`\n小计: ${problems} 个文件有问题`)

// ---- 检查 2：setData 是否有卸载保护 ----
console.log('\n=== 检查 2：异步回调里 setData 有无 _alive 保护（抽样统计）===')
let noGuard = []
for (const p of app.pages) {
  const js = p + '.js'
  if (!fs.existsSync(js)) continue
  const j = fs.readFileSync(js, 'utf8')
  const setDataCount = (j.match(/setData\(/g) || []).length
  const hasAlive = /_alive/.test(j)
  if (setDataCount > 0 && !hasAlive) noGuard.push({ page: p.replace('pages/', ''), setDataCount })
}
console.log(`  无 _alive 保护但有 setData 的页面: ${noGuard.length}`)
noGuard.slice(0, 15).forEach(x => console.log(`    ${x.page.padEnd(36)} setData ${x.setDataCount} 次`))
