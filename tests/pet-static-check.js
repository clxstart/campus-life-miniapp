/**
 * 前端静态检查（不依赖微信运行时）
 *
 * 检查项：
 * 1. 每个页面/组件四件套齐全
 * 2. JSON 合法、usingComponents 路径存在
 * 3. JS 语法正确（node --check）
 * 4. WXML 标签闭合基本平衡
 * 5. WXSS @import 目标存在
 * 6. 主题变量：页面用到的 var(--x) 都在 theme.wxss 里定义
 */
const fs = require('fs')
const path = require('path')
const { execFileSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..')
let pass = 0, fail = 0
const problems = []

function ok(msg) { pass++; console.log('  ✅', msg) }
function bad(msg, detail) { fail++; problems.push(msg + (detail ? ' — ' + detail : '')); console.log('  ❌', msg, detail ? '\n     ' + detail : '') }

/** 1. 四件套 */
console.log('\n=== 1. 页面/组件文件齐全 ===')
const pages = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8')).pages
let missing = []
for (const p of pages) {
  for (const ext of ['js', 'json', 'wxml', 'wxss']) {
    if (!fs.existsSync(path.join(ROOT, `${p}.${ext}`))) missing.push(`${p}.${ext}`)
  }
}
missing.length === 0 ? ok(`${pages.length} 个页面四件套齐全`) : bad('页面文件缺失', missing.join(', '))

const comps = fs.readdirSync(path.join(ROOT, 'components'))
missing = []
for (const c of comps) {
  if (!fs.existsSync(path.join(ROOT, 'components', c, 'index.json'))) continue
  for (const ext of ['js', 'json', 'wxml', 'wxss']) {
    if (!fs.existsSync(path.join(ROOT, 'components', c, `index.${ext}`))) missing.push(`${c}/index.${ext}`)
  }
}
missing.length === 0 ? ok(`${comps.length} 个组件文件齐全`) : bad('组件文件缺失', missing.join(', '))

/** 2. JSON + 组件路径 */
console.log('\n=== 2. JSON 合法性与组件引用 ===')
let jsonBad = [], refBad = []
function checkJson(file, isPage) {
  let j
  try { j = JSON.parse(fs.readFileSync(file, 'utf8')) } catch (e) { jsonBad.push(file + ': ' + e.message); return }
  const uc = j.usingComponents || {}
  for (const [tag, p] of Object.entries(uc)) {
    if (p.startsWith('tdesign-miniprogram/')) {
      const t = path.join(ROOT, 'miniprogram_npm', p + '.json')
      if (!fs.existsSync(t) && !fs.existsSync(path.join(ROOT, 'miniprogram_npm', p + '.js'))) refBad.push(`${file}: ${tag} -> ${p}`)
    } else if (p.startsWith('/')) {
      const t = path.join(ROOT, p.replace(/^\//, '') + '.json')
      if (!fs.existsSync(t)) refBad.push(`${file}: ${tag} -> ${p}`)
    } else if (p.startsWith('.')) {
      const t = path.join(path.dirname(file), p + '.json')
      if (!fs.existsSync(t)) refBad.push(`${file}: ${tag} -> ${p}`)
    }
  }
}
for (const p of pages) checkJson(path.join(ROOT, `${p}.json`), true)
for (const c of comps) {
  const f = path.join(ROOT, 'components', c, 'index.json')
  if (fs.existsSync(f)) checkJson(f, false)
}
jsonBad.length === 0 ? ok('全部 JSON 合法') : bad('JSON 非法', jsonBad.join('; '))
refBad.length === 0 ? ok('全部 usingComponents 路径可解析') : bad('组件路径失效', refBad.join('; '))

/** 3. JS 语法 */
console.log('\n=== 3. JS 语法 ===')
const jsFiles = []
for (const p of pages) jsFiles.push(path.join(ROOT, `${p}.js`))
for (const c of comps) {
  const f = path.join(ROOT, 'components', c, 'index.js')
  if (fs.existsSync(f)) jsFiles.push(f)
}
for (const f of ['utils/pet-engine.js', 'utils/pet-appearance.js', 'utils/pet-api.js', 'utils/pet-chat.js']) {
  const p = path.join(ROOT, f)
  if (fs.existsSync(p)) jsFiles.push(p)
}
const synBad = []
for (const f of jsFiles) {
  try { execFileSync(process.execPath, ['--check', f], { stdio: 'pipe' }) }
  catch (e) { synBad.push(path.relative(ROOT, f) + ': ' + String(e.stderr || e.message).split('\n')[0]) }
}
synBad.length === 0 ? ok(`${jsFiles.length} 个 JS 文件语法正确`) : bad('JS 语法错误', synBad.join('; '))

/** 4. WXML 标签平衡（粗略） */
console.log('\n=== 4. WXML 标签平衡 ===')
const wxmlBad = []
const wxmlFiles = []
for (const p of pages) wxmlFiles.push(path.join(ROOT, `${p}.wxml`))
for (const c of comps) {
  const f = path.join(ROOT, 'components', c, 'index.wxml')
  if (fs.existsSync(f)) wxmlFiles.push(f)
}
for (const f of wxmlFiles) {
  const src = fs.readFileSync(f, 'utf8').replace(/<!--[\s\S]*?-->/g, '')
  const opens = (src.match(/<([a-zA-Z][\w-]*)(?:\s[^>]*)?>/g) || [])
    .map(t => t.match(/^<([a-zA-Z][\w-]*)/)[1])
    .filter(t => !/^(import|include|wxs)$/.test(t))
  const closes = (src.match(/<\/([a-zA-Z][\w-]*)>/g) || []).map(t => t.replace(/<\/|>/g, ''))
  const starts = (src.match(/<([a-zA-Z][\w-]*)[^>]*\/>/g) || []).map(t => t.match(/^<([a-zA-Z][\w-]*)/)[1])
  const openCount = {}
  opens.forEach(t => openCount[t] = (openCount[t] || 0) + 1)
  starts.forEach(t => openCount[t] = (openCount[t] || 0) - 1)
  closes.forEach(t => openCount[t] = (openCount[t] || 0) - 1)
  const unbalanced = Object.entries(openCount).filter(([, v]) => v !== 0)
  if (unbalanced.length) wxmlBad.push(path.relative(ROOT, f) + ': ' + JSON.stringify(unbalanced))
}
wxmlBad.length === 0 ? ok(`${wxmlFiles.length} 个 WXML 标签平衡`) : bad('WXML 标签不平衡', wxmlBad.slice(0, 3).join('; '))

/** 5. WXSS @import */
console.log('\n=== 5. WXSS @import 目标存在 ===')
const wxssBad = []
const wxssFiles = []
for (const p of pages) wxssFiles.push(path.join(ROOT, `${p}.wxss`))
for (const c of comps) {
  const f = path.join(ROOT, 'components', c, 'index.wxss')
  if (fs.existsSync(f)) wxssFiles.push(f)
}
for (const f of wxssFiles) {
  const src = fs.readFileSync(f, 'utf8')
  const imports = src.match(/@import\s+["']([^"']+)["']/g) || []
  for (const imp of imports) {
    const rel = imp.match(/["']([^"']+)["']/)[1]
    const target = path.resolve(path.dirname(f), rel)
    if (!fs.existsSync(target)) wxssBad.push(path.relative(ROOT, f) + ' -> ' + rel)
  }
}
wxssBad.length === 0 ? ok('全部 @import 可解析') : bad('@import 失效', wxssBad.join('; '))

/** 6. 主题变量一致性 */
console.log('\n=== 6. 主题变量定义完整 ===')
const themeSrc = fs.readFileSync(path.join(ROOT, 'styles/theme.wxss'), 'utf8')
const defined = new Set((themeSrc.match(/--[\w-]+(?=\s*:)/g) || []))
const used = new Set()
const commonSrc = fs.readFileSync(path.join(ROOT, 'styles/common.wxss'), 'utf8')
for (const src of [commonSrc, ...wxssFiles.map(f => fs.readFileSync(f, 'utf8'))]) {
  for (const m of src.match(/var\((--[\w-]+)/g) || []) used.add(m.replace('var(', ''))
}
const undefinedVars = [...used].filter(v => !defined.has(v))
undefinedVars.length === 0
  ? ok(`用到 ${used.size} 个变量，全部已定义`)
  : bad('使用了未定义的主题变量', undefinedVars.join(', '))

console.log(`\n=== 结果：${pass} 通过，${fail} 失败 ===`)
if (problems.length) console.log('\n问题清单：\n' + problems.map((p, i) => `${i + 1}. ${p}`).join('\n'))
console.log()
process.exit(fail === 0 ? 0 : 1)
