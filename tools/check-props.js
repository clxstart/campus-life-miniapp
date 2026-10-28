// 检查：页面/父组件传给子组件的属性，子组件是否声明
// （之前 pet-avatar 没有 size 属性却到处传 size，就是这个 bug）
const fs = require('fs')
const path = require('path')
process.chdir('E:\\goodworlk\\campus-life-miniapp')

// 收集每个组件声明的 properties
const compProps = {}
for (const d of fs.readdirSync('components')) {
  const js = path.join('components', d, 'index.js')
  if (!fs.existsSync(js)) continue
  const c = fs.readFileSync(js, 'utf8')
  const props = new Set()
  const m = c.match(/properties\s*:\s*\{([\s\S]*?)\n  \},/)
  if (m) {
    for (const p of m[1].matchAll(/^\s{4}([A-Za-z_$][\w$]*)\s*:/gm)) props.add(p[1])
  }
  compProps[d] = props
}

console.log('=== 各组件声明的 properties ===')
for (const [k, v] of Object.entries(compProps)) {
  console.log(`  ${k.padEnd(20)} ${[...v].join(', ') || '(无)'}`)
}

// 扫描所有 wxml 里的组件标签，检查传入属性
console.log('\n=== 传入但组件未声明的属性 ===')
const files = []
function walk(dir) {
  for (const n of fs.readdirSync(dir)) {
    if (n === 'node_modules' || n === 'miniprogram_npm') continue
    const f = path.join(dir, n)
    if (fs.statSync(f).isDirectory()) walk(f)
    else if (n.endsWith('.wxml')) files.push(f)
  }
}
walk('pages'); walk('components')

const BUILTIN = new Set(['class', 'style', 'id', 'wx:if', 'wx:else', 'wx:for', 'wx:key', 'wx:elif',
  'slot', 'hidden', 'data-', 'bind:', 'catch:', 'bindtap', 'catchtap', 'hover-class', 'hover-stay-time',
  'aria-label', 'mode', 'src', 'lazy-load'])

let found = 0
for (const f of files) {
  const c = fs.readFileSync(f, 'utf8')
  for (const tag of Object.keys(compProps)) {
    const re = new RegExp(`<${tag}\\b([^>]*)>`, 'g')
    let m
    while ((m = re.exec(c))) {
      const attrs = m[1]
      for (const a of attrs.matchAll(/([:@]?[A-Za-z][\w:-]*)\s*=/g)) {
        const name = a[1].replace(/^[:@]/, "").replace(/-([a-z])/g, (s, x) => x.toUpperCase())
        if (BUILTIN.has(name) || name.startsWith('data-') || name.startsWith('bind') || name.startsWith('catch')) continue
        if (!compProps[tag].has(name)) {
          console.log(`  ❌ ${f}: <${tag} ${name}="...">  组件未声明 ${name}`)
          found++
        }
      }
    }
  }
}
if (!found) console.log('  ✅ 未发现属性不匹配')


