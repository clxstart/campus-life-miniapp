/**
 * 统一回归入口
 *
 * 用法：
 *   node tools/check-all.js          # 只跑静态检查（不需要开发者工具）
 *   node tools/check-all.js --runtime # 加上运行时检查（需要先开自动化端口）
 *
 * 静态检查项（快，随时可跑）：
 *   1. 静态完整性 —— 页面/JS语法/WXML平衡/组件引用/资源引用
 *   2. 绑定检查   —— WXML bind* 绑的方法在 JS 里是否存在
 *   3. 接口对账   —— 小程序调的接口后端是否都有
 *   4. 组件属性   —— 传给组件的属性是否已声明
 *   5. 弹窗传参   —— ai-chat 的 ui-popup 是否传全
 *
 * 运行时检查项（慢，需要开发者工具 + 自动化端口）：
 *   6. 冒烟测试   —— 页面渲染 + 交互
 *   7. 全页面渲染 —— 40 个页面逐个 reLaunch
 *   8. 首屏请求   —— 请求是否有重复
 */
const { spawnSync } = require('child_process')
const path = require('path')

const DIR = __dirname
const RUNTIME = process.argv.includes('--runtime')

const STATIC = [
  { file: 'check-static.js', name: '静态完整性' },
  { file: 'check-bindings.js', name: 'WXML 绑定' },
  { file: 'check-api.js', name: '接口对账' },
  { file: 'check-props.js', name: '组件属性' },
  { file: 'verify-ai-popup.js', name: '弹窗传参' }
]

const RUNTIME_CHECKS = [
  { file: 'smoke-test.js', name: '冒烟测试', timeout: 400000 },
  { file: 'test-flows.js', name: '业务流程', timeout: 900000 },
  { file: 'check-pages.js', name: '全页面渲染', timeout: 900000 },
  { file: 'check-requests.js', name: '首屏请求', timeout: 240000 }
]

function run(item, timeout) {
  const r = spawnSync(process.execPath, [path.join(DIR, item.file)], {
    encoding: 'utf8',
    timeout: timeout || 240000
  })
  const out = ((r.stdout || '') + (r.stderr || '')).trim()
  return { ok: r.status === 0, out, timedOut: r.status === null }
}

function show(item, res) {
  console.log('\n──────── ' + item.name + ' ────────')
  // 只显示最后 8 行，避免刷屏
  const lines = res.out.split('\n').filter(Boolean)
  const tail = lines.slice(-8)
  tail.forEach(l => console.log(l))
  if (res.timedOut) console.log('  ⚠ 执行超时')
}

let failed = []

console.log('========== 静态检查 ==========')
for (const item of STATIC) {
  const res = run(item)
  console.log(`\n[${res.ok ? '✅' : '❌'}] ${item.name}`)
  if (!res.ok) {
    failed.push(item.name)
    show(item, res)
  }
}

if (RUNTIME) {
  console.log('\n\n========== 运行时检查 ==========')
  console.log('（需要开发者工具已开自动化端口）')
  for (const item of RUNTIME_CHECKS) {
    const res = run(item, item.timeout)
    console.log(`\n[${res.ok ? '✅' : '❌'}] ${item.name}`)
    show(item, res)
    if (!res.ok) failed.push(item.name)
  }
}

console.log('\n\n========== 汇总 ==========')
const total = STATIC.length + (RUNTIME ? RUNTIME_CHECKS.length : 0)
if (failed.length === 0) {
  console.log(`✅ 全部 ${total} 项通过`)
  process.exit(0)
} else {
  console.log(`❌ ${failed.length} / ${total} 项失败：${failed.join('、')}`)
  process.exit(1)
}
