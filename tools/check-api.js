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

  // 类级 @RequestMapping：按括号配平取参数块，再抽所有以 / 开头的字面量
  let clsPaths = ['']
  const clsIdx = c.indexOf('@RequestMapping')
  if (clsIdx >= 0) {
    let i = clsIdx + '@RequestMapping'.length
    while (i < c.length && /\s/.test(c[i])) i++
    if (c[i] === '(') {
      let depth = 0
      const s0 = i
      for (; i < c.length; i++) {
        if (c[i] === '(') depth++
        else if (c[i] === ')') { depth--; if (depth === 0) { i++; break } }
      }
      const lits = [...c.slice(s0 + 1, i - 1).matchAll(/"([^"]*)"/g)]
        .map(x => x[1]).filter(s => s.startsWith('/'))
      if (lits.length) clsPaths = lits
    }
  }

  // @XxxMapping(...) 支持三种写法：
  //   @GetMapping                     无括号
  //   @GetMapping("/a")               单路径
  //   @GetMapping({"/a", "/b"})       多路径（路径里可能含 {id}，不能用 [^}]* 粗暴切）
  const re = /@(Get|Post|Put|Delete|Patch)Mapping\b/g
  let m
  while ((m = re.exec(c))) {
    const method = m[1].toUpperCase()

    // 从 Mapping 之后找到 '('，按括号配平取出参数块
    let i = m.index + m[0].length
    while (i < c.length && /\s/.test(c[i])) i++
    let subs = ['']
    if (c[i] === '(') {
      let depth = 0
      const startIdx = i
      for (; i < c.length; i++) {
        if (c[i] === '(') depth++
        else if (c[i] === ')') {
          depth--
          if (depth === 0) { i++; break }
        }
      }
      const argBlock = c.slice(startIdx + 1, i - 1)
      // 取出参数块里所有双引号字面量；没有字面量（如只有 name=、consumes=）就是根路径
      const lits = [...argBlock.matchAll(/"([^"]*)"/g)]
        .map(x => x[1])
        .filter(s => s.startsWith('/') || s === '')
      if (lits.length) subs = lits
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
    // 顺序很重要：先把 ${...} 换成占位符，再切掉查询串。
    // 反过来（先 split('?')）会把 `/jobs${qs}` 里的 `?` 误当查询串起点，
    // 残留的 `${qs}` 再变成 /:p，凭空多出一个不存在的 `/jobs/:p`。
    let p = m[1].replace(/\$\{[^}]*\}/g, '\u0000')   // 模板变量 → 临时标记
    p = p.split('?')[0]                               // 去掉查询串
    // 标记前面是 "/" 才是路径参数（/posts/${id} → /posts/:p）；
    // 前面不是 "/" 说明拼的是查询串（/jobs${qs}），直接删掉
    p = p.replace(/\/\u0000/g, '/:p').replace(/\u0000/g, '')
    p = p.replace(/\/+/g, '/')
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
