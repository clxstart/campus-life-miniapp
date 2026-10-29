/**
 * 运行时冒烟测试（进程隔离版）
 *
 * 背景：开发者工具里 reLaunch 要 4-5 秒，automator 单条命令约 30 秒超时。
 * 实测发现：同一次连接里做「跳转 → 点击 → 再跳转」，
 * 后一步容易撞超时（页面还在过渡动画时又被 reLaunch）。
 *
 * 解法：每个检查项 fork 一个独立子进程，
 * 子进程自己连、自己测、测完就退。
 * 状态彻底隔离，任一项失败不影响其他项。
 *
 * 用法：node tools/smoke-test.js
 */
const { spawnSync } = require('child_process')
const path = require('path')

const SELF = __filename

// 子进程模式：只跑一项
const CASE = process.env.SMOKE_CASE
if (CASE) {
  runCase(CASE).then(code => process.exit(code))
  return
}

const CASES = [
  { id: 'index', title: '首页渲染' },
  { id: 'profile-publish', title: '个人中心 · 点「发布」' },
  { id: 'profile-follow', title: '个人中心 · 点「关注」' },
  { id: 'ai-popup', title: 'AI 聊天弹窗' }
]

let pass = 0
let fail = 0

// 同步 sleep：顶层是 CommonJS，不能用 await
function waitSync(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) { /* 忙等，阻塞主流程 */ }
}

for (const c of CASES) {
  console.log('\n=== ' + c.title + ' ===')
  // 每项之间留出间隔：上一项可能把页面停在某个页面，
  // 紧接着 reLaunch 会撞上开发者工具的页面过渡动画而超时。
  waitSync(2500)
  const r = spawnSync(process.execPath, [SELF], {
    env: { ...process.env, SMOKE_CASE: c.id },
    encoding: 'utf8',
    timeout: 90000
  })
  const out = (r.stdout || '').trim()
  if (out) console.log(out)
  if (r.status === 0) pass++
  else {
    fail++
    if (r.stderr) console.log('  stderr:', String(r.stderr).split('\n')[0])
    if (r.status === null) console.log('  （子进程超时被杀）')
  }
}

console.log('\n结果：' + pass + ' 项通过，' + fail + ' 项失败')
process.exit(fail === 0 ? 0 : 1)

// ============ 以下是子进程执行的单项 ============
async function runCase(id) {
  const ROOT = 'E:\\goodworlk\\campus-life-miniapp'
  const automator = require(path.join(ROOT, 'node_modules', 'miniprogram-automator'))
  const WS = process.env.MP_WS || 'ws://127.0.0.1:9420'
  const sleep = ms => new Promise(r => setTimeout(r, ms))

  let ok = true
  const ck = (name, good, detail) => {
    console.log('  ' + (good ? '✅' : '❌') + ' ' + name + (detail ? '  ' + detail : ''))
    if (!good) ok = false
  }

  let mini
  try {
    mini = await automator.connect({ wsEndpoint: WS })
  } catch (e) {
    console.log('  ❌ 连不上开发者工具:', e.message.split('\n')[0])
    return 1
  }

  try {
    if (id === 'index') {
      await mini.reLaunch('/pages/index/index')
      await sleep(2200)
      const cur = await mini.currentPage()
      ck('首页正常渲染', cur.path === 'pages/index/index', cur.path)

    } else if (id === 'profile-publish') {
      await mini.reLaunch('/pages/profile/profile')
      await sleep(2200)
      const page = await mini.currentPage()
      const stats = await page.$$('.stat-item')
      ck('找到 3 个统计行', stats.length === 3, '实际 ' + stats.length)
      if (stats.length >= 1) {
        await stats[0].tap()
        await sleep(2000)
        const after = await mini.currentPage()
        ck('跳转到个人主页', after.path.includes('user-home'), after.path)
      }

    } else if (id === 'profile-follow') {
      await mini.reLaunch('/pages/profile/profile')
      await sleep(2500)
      const page = await mini.currentPage()
      const stats = await page.$$('.stat-item')
      if (stats.length >= 2) {
        await stats[1].tap()
        // 等 toast 完全弹出后再读；toast 1600ms 后自动关，
        // 所以判据用「message 是否被写入」，不用 visible
        await sleep(900)
        // 重新取 currentPage：tap 后页面对象可能已刷新
        const after = await mini.currentPage()
        const d = await after.data()
        const p = d.uiPopup || {}
        ck('触发「开发中」提示', /开发中/.test(p.message || ''), p.message || '')
      } else {
        ck('触发「开发中」提示', false, '统计行未渲染')
      }

    } else if (id === 'ai-popup') {
      await mini.reLaunch('/pages/ai-chat/ai-chat')
      await sleep(2200)
      const page = await mini.currentPage()
      const popup = await page.$('#ui-popup')
      ck("selectComponent('#ui-popup') 能找到", !!popup)

      await mini.evaluate(() => {
        const pages = getCurrentPages()
        pages[pages.length - 1].setData({
          uiPopup: { visible: true, mode: 'modal', tone: 'info', title: '测试弹窗', message: '能看到我吗' }
        })
      })
      await sleep(900)
      const after = await mini.currentPage()
      const d = await after.data()
      const up = d.uiPopup || {}
      ck('设置 visible 后标题正确', up.visible === true && up.title === '测试弹窗')
      const nodes = await after.$$('[class*="popup"]')
      ck('弹窗 DOM 渲染出来', nodes.length > 0, nodes.length + ' 个节点')
    }
  } catch (e) {
    console.log('  ❌ 执行出错:', e.message.split('\n')[0])
    ok = false
  }

  try { await mini.disconnect() } catch (_) {}
  return ok ? 0 : 1
}
