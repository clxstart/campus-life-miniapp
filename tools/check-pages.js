/**
 * 全页面渲染检查
 *
 * 逐个 reLaunch 全部页面，确认都能正常渲染。
 *
 * 为什么单独一个脚本：
 *   开发者工具里每次 reLaunch 要 4-5 秒，
 *   automator 单条命令约 30 秒超时，
 *   一次连接连几十个页面会累积到超时。
 *   这个脚本每页之间重新建立连接前会检查，超时就跳过并继续。
 *
 * 用法：
 *   node tools/check-pages.js            # 全部页面
 *   node tools/check-pages.js 10         # 只测前 10 个
 *   node tools/check-pages.js --start 20 # 从第 20 个开始
 */
const fs = require('fs')
const path = require('path')
const ROOT = 'E:\\goodworlk\\campus-life-miniapp'
const automator = require(path.join(ROOT, 'node_modules', 'miniprogram-automator'))
const sleep = ms => new Promise(r => setTimeout(r, ms))

const WS = process.env.MP_WS || 'ws://127.0.0.1:9420'

const args = process.argv.slice(2)
let limit = Infinity
let start = 0
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--start') start = Number(args[++i]) || 0
  else if (/^\d+$/.test(args[i])) limit = Number(args[i])
}

;(async () => {
  const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'))
  const all = app.pages.slice(start, start + limit)

  let mini = await automator.connect({ wsEndpoint: WS })
  console.log(`测试 ${all.length} 个页面（总 ${app.pages.length}）\n`)

  let ok = 0
  const broken = []

  for (let i = 0; i < all.length; i++) {
    const p = all[i]
    try {
      const page = await mini.reLaunch('/' + p)
      await sleep(1200)
      const cur = await mini.currentPage()
      if (cur.path === p) {
        ok++
        console.log(`  ✅ ${p}`)
      } else {
        broken.push({ p, note: `实际 ${cur.path}` })
        console.log(`  ❌ ${p}  → ${cur.path}`)
      }
    } catch (e) {
      const msg = (e.message || '').split('\n')[0]
      broken.push({ p, note: msg })
      console.log(`  ❌ ${p}  → ${msg}`)
      // 连接超时就重连，继续后面的
      if (/timeout|Connection closed/i.test(msg)) {
        try { await mini.disconnect() } catch (_) {}
        await sleep(1500)
        try { mini = await automator.connect({ wsEndpoint: WS }) } catch (_) {}
      }
    }
  }

  console.log(`\n结果：${ok} / ${all.length} 渲染正常`)
  if (broken.length) {
    console.log('失败：')
    broken.forEach(b => console.log(`  ${b.p} — ${b.note}`))
  }
  try { await mini.disconnect() } catch (_) {}
  process.exit(broken.length ? 1 : 0)
})()
