// 小程序静态完整性检查（替代截图验证）
const fs = require('fs')
const path = require('path')

const ROOT = 'E:\\goodworlk\\campus-life-miniapp'
process.chdir(ROOT)

let fail = 0
const problems = []

function bad(msg) { fail++; problems.push(msg) }

// ---------- 1. app.json 页面文件齐全 ----------
const app = JSON.parse(fs.readFileSync('app.json', 'utf8'))
for (const p of app.pages) {
  for (const ext of ['.js', '.json', '.wxml', '.wxss']) {
    if (!fs.existsSync(p + ext)) bad(`缺文件: ${p}${ext}`)
  }
}
console.log(`1. 页面文件齐全: ${app.pages.length} 个页面`)

// ---------- 2. 组件引用可解析 ----------
function checkJson(dir) {
  for (const name of fs.readdirSync(dir)) {
    if (name === 'node_modules' || name === 'miniprogram_npm') continue
    const full = path.join(dir, name)
    const st = fs.statSync(full)
    if (st.isDirectory()) { checkJson(full); continue }
    if (!name.endsWith('.json')) continue
    let j
    try { j = JSON.parse(fs.readFileSync(full, 'utf8')) } catch (e) { bad(`JSON 解析失败: ${full}`); continue }
    const uc = j.usingComponents || {}
    for (const [k, v] of Object.entries(uc)) {
      if (/^(tdesign|miniprogram)/.test(v)) continue
      const target = v.startsWith('/') ? v.slice(1) : path.join(path.dirname(full), v)
      if (!fs.existsSync(target + '.js')) bad(`组件不存在: ${full} → ${k}=${v}`)
    }
  }
}
checkJson('pages'); checkJson('components')
console.log('2. 组件引用可解析')

// ---------- 3. JS 语法 ----------
const { execSync } = require('child_process')
let jsCount = 0
function checkJs(dir) {
  for (const name of fs.readdirSync(dir)) {
    if (name === 'node_modules' || name === 'miniprogram_npm') continue
    const full = path.join(dir, name)
    const st = fs.statSync(full)
    if (st.isDirectory()) { checkJs(full); continue }
    if (!name.endsWith('.js')) continue
    jsCount++
    try { execSync(`node -c "${full}"`, { stdio: 'pipe' }) } catch (e) { bad(`JS 语法错误: ${full}`) }
  }
}
for (const d of ['pages', 'components', 'utils']) checkJs(d)
for (const f of fs.readdirSync('.').filter(x => x.endsWith('.js'))) {
  jsCount++
  try { execSync(`node -c "${f}"`, { stdio: 'pipe' }) } catch (e) { bad(`JS 语法错误: ${f}`) }
}
console.log(`3. JS 语法正确: ${jsCount} 个文件`)

// ---------- 4. WXML 标签平衡 ----------
let wxmlCount = 0
function checkWxml(dir) {
  for (const name of fs.readdirSync(dir)) {
    if (name === 'node_modules' || name === 'miniprogram_npm') continue
    const full = path.join(dir, name)
    const st = fs.statSync(full)
    if (st.isDirectory()) { checkWxml(full); continue }
    if (!name.endsWith('.wxml')) continue
    wxmlCount++
    const c = fs.readFileSync(full, 'utf8')
    // 只检查常用标签
    for (const tag of ['view', 'text', 'scroll-view', 'swiper', 'swiper-item', 'block']) {
      const o = (c.match(new RegExp(`<${tag}[\\s>]`, 'g')) || []).length
      const cl = (c.match(new RegExp(`</${tag}>`, 'g')) || []).length
      // block 可能自闭合
      if (o !== cl && !(tag === 'block' && c.includes('<block'))) {
        bad(`标签不平衡: ${full} <${tag}> ${o}/${cl}`)
      }
    }
  }
}
checkWxml('pages'); checkWxml('components')
console.log(`4. WXML 标签平衡: ${wxmlCount} 个文件`)

// ---------- 5. hover-class 值都有样式 ----------
const appWxss = fs.readFileSync('app.wxss', 'utf8')
const definedGlobal = new Set()
for (const m of appWxss.matchAll(/\.([a-z-]+)\s*\{/g)) definedGlobal.add(m[1])

const usedHover = new Map()
function collectHover(dir) {
  for (const name of fs.readdirSync(dir)) {
    if (name === 'node_modules' || name === 'miniprogram_npm') continue
    const full = path.join(dir, name)
    const st = fs.statSync(full)
    if (st.isDirectory()) { collectHover(full); continue }
    if (!name.endsWith('.wxml')) continue
    const c = fs.readFileSync(full, 'utf8')
    for (const m of c.matchAll(/hover-class="([^"]*)"/g)) {
      const v = m[1]
      if (!usedHover.has(v)) usedHover.set(v, [])
      usedHover.get(v).push(path.relative('.', full))
    }
  }
}
collectHover('pages'); collectHover('components')

let hoverOk = 0
for (const [cls, files] of usedHover) {
  // 全局类 或 页面自定义类
  const inGlobal = definedGlobal.has(cls)
  const inLocal = files.some(f => {
    const wxss = f.replace('.wxml', '.wxss')
    if (!fs.existsSync(wxss)) return false
    return fs.readFileSync(wxss, 'utf8').includes('.' + cls)
  })
  if (inGlobal || inLocal) hoverOk++
  else bad(`hover-class 无对应样式: .${cls} (用在 ${files[0]})`)
}
console.log(`5. hover-class 样式齐备: ${hoverOk}/${usedHover.size} 个类`)

// ---------- 6. 资源引用存在 ----------
const assetRefs = new Set()
function collectAssets(dir) {
  for (const name of fs.readdirSync(dir)) {
    if (name === 'node_modules' || name === 'miniprogram_npm') continue
    const full = path.join(dir, name)
    const st = fs.statSync(full)
    if (st.isDirectory()) { collectAssets(full); continue }
    if (!/\.(wxml|js|wxss)$/.test(name)) continue
    const c = fs.readFileSync(full, 'utf8')
    for (const m of c.matchAll(/["'](\/assets\/[^"']+)["']/g)) assetRefs.add(m[1])
  }
}
collectAssets('pages'); collectAssets('components'); collectAssets('utils')
let assetMiss = 0
for (const a of assetRefs) {
  if (!fs.existsSync(a.slice(1))) { bad(`资源不存在: ${a}`); assetMiss++ }
}
console.log(`6. 资源引用存在: ${assetRefs.size} 个引用，缺失 ${assetMiss}`)

// ---------- 汇总 ----------
console.log('')
if (fail === 0) {
  console.log('=== 结果：全部通过 ✅ ===')
} else {
  console.log(`=== 结果：${fail} 个问题 ❌ ===`)
  problems.slice(0, 20).forEach(p => console.log('  ' + p))
}
process.exit(fail === 0 ? 0 : 1)
