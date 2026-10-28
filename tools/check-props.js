// 检查：页面/父组件传给子组件的属性，子组件是否声明
// （之前 pet-avatar 没有 size 属性却到处传 size，就是这个 bug）
const fs = require('fs')
const path = require('path')
process.chdir('E:\\goodworlk\\campus-life-miniapp')

// 收集每个组件声明的 properties
// 兼容两种写法：
//   多行 properties: {\n    a: {...},\n    b: {...}\n  },
//   单行 properties: { local: { type: Boolean, value: false } },
const compProps = {}
for (const d of fs.readdirSync('components')) {
  const js = path.join('components', d, 'index.js')
  if (!fs.existsSync(js)) continue
  const c = fs.readFileSync(js, 'utf8')
  const props = new Set()

  // 先定位 properties 块的起点，再按花括号配平取出整块
  const start = c.search(/properties\s*:\s*\{/)
  if (start >= 0) {
    let i = c.indexOf('{', start)
    let depth = 0
    let end = -1
    for (; i < c.length; i++) {
      const ch = c[i]
      if (ch === '{') depth++
      else if (ch === '}') {
        depth--
        if (depth === 0) { end = i; break }
      }
    }
    if (end > 0) {
      const block = c.slice(start + c.slice(start).indexOf('{') + 1, end)
      // 只取顶层（depth 0）的 key，避免把嵌套对象里的字段当属性
      let d2 = 0
      for (let k = 0; k < block.length; k++) {
        const ch = block[k]
        if (ch === '{' || ch === '[' || ch === '(') d2++
        else if (ch === '}' || ch === ']' || ch === ')') d2--
        else if (d2 === 0 && /[A-Za-z_$]/.test(ch)) {
          const m = block.slice(k).match(/^([A-Za-z_$][\w$]*)\s*:/)
          if (m && (k === 0 || /[\s,{]/.test(block[k - 1]))) {
            props.add(m[1])
            k += m[0].length - 1
          }
        }
      }
    }
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
        const raw = a[1].replace(/^[:@]/, '')
        // 先按原始名过滤内置/事件属性，再做 kebab→camel 转换，
        // 否则 data-id 会被转成 dataId 而躲过 data- 前缀判断
        if (raw.startsWith('data-') || raw.startsWith('bind') || raw.startsWith('catch')) continue
        if (BUILTIN.has(raw)) continue
        const name = raw.replace(/-([a-z])/g, (s, x) => x.toUpperCase())
        if (BUILTIN.has(name)) continue
        if (!compProps[tag].has(name)) {
          console.log(`  ❌ ${f}: <${tag} ${raw}="...">  组件未声明 ${name}`)
          found++
        }
      }
    }
  }
}
if (!found) console.log('  ✅ 未发现属性不匹配')


