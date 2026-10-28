/**
 * 运行时冒烟测试（miniprogram-automator）
 *
 * 用途：静态检查只能证明「代码结构正确」，这个脚本连开发者工具，
 * 真实跳页面、点元素、读 data，证明「跑起来是对的」。
 *
 * 前置：
 *   1. 微信开发者工具已打开本项目
 *   2. 另开一个终端执行：
 *      cd "D:\JavaStudy\微信web开发者工具"
 *      cli.bat auto --project "E:\goodworlk\campus-life-miniapp" --port 38038 --auto-port 9420 --lang zh
 *
 * 运行：
 *   node tools/smoke-test.js
 *
 * 注意：automator 的 checkVersion() 在版本串为空时会崩，这里直接 connect 绕过。
 */
const path = require('path')
const ROOT = 'E:\\goodworlk\\campus-life-miniapp'
const automator = require(path.join(ROOT, 'node_modules', 'miniprogram-automator'))

const WS = process.env.MP_WS || 'ws://127.0.0.1:9420'
const sleep = ms => new Promise(r => setTimeout(r, ms))

let pass = 0
let fail = 0
function check(name, ok, detail) {
  console.log(`  ${ok ? '✅' : '❌'} ${name}${detail ? '  ' + detail : ''}`)
  ok ? pass++ : fail++
}

;(async () => {
  let mini
  try {
    mini = await automator.connect({ wsEndpoint: WS })
  } catch (e) {
    console.log('❌ 连不上开发者工具。请先执行：')
    console.log('   cli.bat auto --project "' + ROOT + '" --port 38038 --auto-port 9420 --lang zh')
    console.log('   错误:', e.message)
    process.exit(1)
  }
  console.log('已连接开发者工具\n')

  // ---------- 1. 主要页面能否渲染 ----------
  console.log('=== 1. 页面渲染 ===')
  const pages = [
    'pages/index/index',
    'pages/community/community',
    'pages/food-list/food-list',
    'pages/profile/profile',
    'pages/ai-chat/ai-chat',
    'pages/market-list/market-list',
    'pages/discover/discover'
  ]
  for (const p of pages) {
    try {
      await mini.reLaunch('/' + p)
      await sleep(1200)
      const cur = await mini.currentPage()
      check(p, cur.path === p, cur.path === p ? '' : `实际 ${cur.path}`)
    } catch (e) {
      check(p, false, e.message)
    }
  }

  // ---------- 2. 个人中心统计行 ----------
  console.log('\n=== 2. 个人中心统计行（openItem）===')
  let page = await mini.reLaunch('/pages/profile/profile')
  await sleep(1500)
  let stats = await page.$$('.stat-item')
  check('找到 3 个统计行', stats.length === 3, `实际 ${stats.length}`)

  if (stats.length >= 1) {
    await stats[0].tap()
    await sleep(1200)
    const cur = await mini.currentPage()
    check('点「发布」跳个人主页', cur.path.includes('user-home'), cur.path)
  }

  page = await mini.reLaunch('/pages/profile/profile')
  await sleep(1200)
  stats = await page.$$('.stat-item')
  if (stats.length >= 2) {
    await stats[1].tap()
    await sleep(1000)
    page = await mini.currentPage()
    const d = await page.data()
    const p = d.uiPopup || {}
    check('点「关注」弹提示（不再无反应）', p.visible && /开发中/.test(p.message || ''), p.message || '')
  }

  // ---------- 3. AI 聊天弹窗 ----------
  console.log('\n=== 3. AI 聊天弹窗（ui-popup）===')
  page = await mini.reLaunch('/pages/ai-chat/ai-chat')
  await sleep(1500)

  try {
    const popup = await page.$('#ui-popup')
    check('selectComponent(#ui-popup) 能找到组件', !!popup)
  } catch (e) {
    check('selectComponent(#ui-popup) 能找到组件', false, e.message)
  }

  try {
    await mini.evaluate(() => {
      const pages = getCurrentPages()
      pages[pages.length - 1].setData({
        uiPopup: { visible: true, mode: 'modal', tone: 'info', title: '测试弹窗', message: '能看到我吗' }
      })
    })
    await sleep(700)
    page = await mini.currentPage()
    const d = await page.data()
    const up = d.uiPopup || {}
    check('设置 visible 后标题正确', up.visible === true && up.title === '测试弹窗')
    const nodes = await page.$$('[class*="popup"]')
    check('弹窗 DOM 真的渲染出来', nodes.length > 0, `${nodes.length} 个节点`)
  } catch (e) {
    check('弹窗渲染', false, e.message)
  }

  // ---------- 汇总 ----------
  console.log(`\n结果：${pass} 通过，${fail} 失败`)
  await mini.disconnect()
  process.exit(fail === 0 ? 0 : 1)
})()
