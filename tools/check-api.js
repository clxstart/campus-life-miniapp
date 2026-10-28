// 前后端接口对账 v2 —— 支持数组多路径映射
const fs = require('fs')
const path = require('path')
process.chdir('E:\\goodworlk\\campus-life-miniapp')

const norm = s => s.replace(/\{[^}]*\}/g, ':p').replace(/\/+$/, '') || '/'

const backend = new Set()
const ctrlDir = 'backend/src/main/java/com/campuslife/controller'
for (const name of fs.readdirSync(ctrlDir)) {
  if (!name.endsWith('.java')) continue
  const c = fs.readFileSync(path.join(ctrlDir, name), 'utf8')
  const clsM = c.match(/@RequestMapping\(\{?([^)}]*)\}?\)/)
  const clsPaths = clsM
    ? clsM[1].split(',').map(s => s.trim().replace(/^"|"$/g, '')).filter(Boolean)
    : ['']

  // @XxxMapping(...) 后面可能跟 {"/a","/b"} 或 "/a" 或 空
  const re = /@(Get|Post|Put|Delete|Patch)Mapping(?:\(\s*(\{[^}]*\}|"[^"]*")?\s*\))?/g
  let m
  while ((m = re.exec(c))) {
    const method = m[1].toUpperCase()
    const raw = (m[2] || '').trim()
    let subs = ['']
    if (raw.startsWith('{')) {
      subs = raw.slice(1, -1).split(',').map(s => s.trim().replace(/^"|"$/g, ''))
    } else if (raw.startsWith('"')) {
      subs = [raw.replace(/^"|"$/g, '')]
    }
    for (const cls of clsPaths) {
      for (const sub of subs) {
        let full = (cls + sub).replace(/\/+/g, '/')
        if (full.length > 1) full = full.replace(/\/+$/, '')
        backend.add(method + ' ' + norm(full))
      }
    }
  }
}
console.log(`后端端点(展开多路径后): ${backend.size}`)

const frontend = new Map()
for (const name of fs.readdirSync('utils/api')) {
  if (!name.endsWith('.js')) continue
  const c = fs.readFileSync(path.join('utils/api', name), 'utf8')
  const re = /request\(\s*[`'"]([^`'"]+)[`'"](?:\s*,\s*[`'"]([A-Za-z]+)[`'"])?/g
  let m
  while ((m = re.exec(c))) {
    const method = (m[2] || 'GET').toUpperCase()
    let p = m[1].split('?')[0].replace(/\$\{[^}]*\}/g, '/:p').replace(/\/+/g, '/')
    const full = norm('/api' + (p.startsWith('/') ? p : '/' + p))
    const key = method + ' ' + full
    if (!frontend.has(key)) frontend.set(key, new Set())
    frontend.get(key).add('api/' + name)
  }
}
console.log(`前端调用点: ${frontend.size}\n`)

const missing = []
let okCount = 0
for (const [key, files] of frontend) {
  if (backend.has(key)) okCount++
  else missing.push({ key, files: [...files] })
}
console.log(`✅ 后端已有: ${okCount}`)
console.log(`❌ 后端缺失: ${missing.length}\n`)
if (missing.length) {
  console.log('=== 前端调了但后端没有 ===')
  for (const x of missing.sort((a, b) => a.key.localeCompare(b.key))) {
    console.log(`  ${x.key.padEnd(56)} ${x.files.join(',')}`)
  }
} else {
  console.log('=== 结论：小程序调用的接口后端全部具备 ===')
}
