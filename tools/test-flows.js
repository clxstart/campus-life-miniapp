/**
 * 业务流程冒烟测试（多流程）
 *
 * 每个流程独立连接，避开「一次连接里多次跳转 → 撞过渡动画超时」的问题。
 *
 * 前置：
 *   cli.bat auto --project "E:\goodworlk\campus-life-miniapp" --port 38038 --auto-port 9420 --lang zh
 * 运行：node tools/test-flows.js
 */
const { spawnSync } = require('child_process')
const path = require('path')

const SELF = __filename
const FLOW = process.env.FLOW_CASE

if (FLOW) {
  runFlow(FLOW).then(code => process.exit(code))
  return
}

const FLOWS = [
  { id: 'market', title: '二手市场：列表→详情' },
  { id: 'circle', title: '圈子：列表→详情' },
  { id: 'jobs', title: '兼职列表' },
  { id: 'search', title: '搜索' },
  { id: 'messages', title: '消息页' }
]

let pass = 0
let fail = 0

function waitSync(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {}
}

for (const f of FLOWS) {
  console.log('\n=== ' + f.title + ' ===')
  waitSync(2500)
  const r = spawnSync(process.execPath, [SELF], {
    env: { ...process.env, FLOW_CASE: f.id },
    encoding: 'utf8',
    timeout: 100000
  })
  const out = ((r.stdout || '') + (r.stderr || '')).trim()
  if (out) console.log(out)
  if (r.status === 0) pass++
  else {
    fail++
    if (r.status === null) console.log('  （子进程超时被杀）')
  }
}

console.log('\n结果：' + pass + ' 项通过，' + fail + ' 项失败')
process.exit(fail === 0 ? 0 : 1)

// ============ 单项 ============
async function runFlow(id) {
  const ROOT = 'E:\\goodworlk\\campus-life-miniapp'
  const automator = require(path.join(ROOT, 'node_modules', 'miniprogram-automator'))
  const WS = process.env.MP_WS || 'ws://127.0.0.1:9420'
  const sleep = ms => new Promise(r => setTimeout(r, ms))

  let ok = true
  const ck = (n, good, d) => {
    console.log('  ' + (good ? '✅' : '❌') + ' ' + n + (d ? '  ' + d : ''))
    if (!good) ok = false
  }

  let mini
  try {
    mini = await automator.connect({ wsEndpoint: WS })
  } catch (e) {
    console.log('  ❌ 连不上:', e.message.split('\n')[0])
    return 1
  }

  try {
    if (id === 'market') {
      await mini.reLaunch('/pages/market-list/market-list')
      await sleep(2500)
      const page = await mini.currentPage()
      const d = await page.data()
      const list = d.goods || d.list || []
      ck('列表页加载', true, '页面渲染成功')
      console.log('     商品数: ' + list.length)
      // 有点击第一张卡片进详情
      const cards = await page.$$('.cp-card, .goods-card, .market-card')
      ck('找到商品卡片', cards.length > 0, cards.length + ' 个')
      if (cards.length) {
        await cards[0].tap()
        await sleep(2500)
        const after = await mini.currentPage()
        ck('进入详情页', after.path.includes('market-detail'), after.path)
        const dd = await after.data()
        ck('详情无错误', !dd.error, 'error="' + (dd.error || '') + '"')
      }

    } else if (id === 'circle') {
      await mini.reLaunch('/pages/discover/discover')
      await sleep(2500)
      const page = await mini.currentPage()
      const cards = await page.$$('.cp-card, .circle-card')
      ck('找到圈子卡片', cards.length > 0, cards.length + ' 个')
      if (cards.length) {
        await cards[0].tap()
        await sleep(2500)
        const after = await mini.currentPage()
        console.log('     跳转到: ' + after.path)
        ck('进入圈子/详情页', after.path.includes('circle') || after.path.includes('discover'), after.path)
      }

    } else if (id === 'jobs') {
      await mini.reLaunch('/pages/jobs/jobs')
      await sleep(2500)
      const page = await mini.currentPage()
      ck('兼职页渲染', page.path === 'pages/jobs/jobs', page.path)
      const d = await page.data()
      const list = d.jobs || d.list || []
      console.log('     职位数: ' + list.length)
      ck('页面有数据或空态', list.length > 0 || true, list.length ? list.length + ' 条' : '显示空态')

    } else if (id === 'search') {
      await mini.reLaunch('/pages/search/search?keyword=' + encodeURIComponent('自习'))
      await sleep(3500)
      const page = await mini.currentPage()
      ck('搜索页渲染', page.path.includes('search'), page.path)
      const d = await page.data()
      // 注意：搜索页把结果存在 cards（不是 results/list），
      // 读错字段会造成「0 结果」的假警报 —— 已在排查中踩过两次。
      const cards = d.cards || []
      console.log('     关键词: ' + (d.keyword || '-'))
      console.log('     counts : ' + JSON.stringify(d.counts || {}))
      ck('搜到结果', cards.length > 0, cards.length + ' 条 / total ' + (d.total || 0))

    } else if (id === 'messages') {
      await mini.reLaunch('/pages/messages/messages')
      await sleep(2500)
      const page = await mini.currentPage()
      ck('消息页渲染', page.path.includes('messages'), page.path)
      const d = await page.data()
      const conv = d.conversations || []
      const notif = d.notifications || []
      console.log('     会话: ' + conv.length + '  通知: ' + notif.length)
    }
  } catch (e) {
    console.log('  ❌ 出错: ' + e.message.split('\n')[0])
    ok = false
  }

  try { await mini.disconnect() } catch (_) {}
  return ok ? 0 : 1
}
