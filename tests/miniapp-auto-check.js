/**
 * 微信小程序自动化验证（真实 IDE 运行）
 *
 * 为什么不用 automator.launcher.connect：
 *   0.13.0 的 connect() 内部会调 checkVersion()，对空的版本字符串 split 会崩，
 *   与项目代码无关。这里直接用底层 Connection + MiniProgram 绕过版本检查。
 *
 * 运行：node tests/miniapp-auto-check.js
 */
const path = require('path')
const Connection = require('miniprogram-automator/out/Connection').default
const MiniProgram = require('miniprogram-automator/out/MiniProgram').default

const AUTOMATOR_PORT = 9420

const PAGES = [
  { route: 'pages/campus-home/campus-home', name: '校园首页', expects: ['校园新鲜事'] },
  { route: 'pages/adopt/adopt', name: '领养页', expects: ['领一位校园搭子', '就选它啦'] },
  { route: 'pages/appearance/appearance', name: '捏脸页', expects: ['保存外观'] },
  { route: 'pages/wardrobe/wardrobe', name: '衣柜页', expects: ['积分'] },
  { route: 'pages/dispatch/dispatch', name: '派遣页', expects: ['派小云出去走走'] },
  { route: 'pages/companion/companion', name: '聊天页', expects: ['发送'] }
]

;(async () => {
  let miniProgram
  let pass = 0
  let fail = 0

  try {
    const conn = await Connection.create(`ws://127.0.0.1:${AUTOMATOR_PORT}`)
    miniProgram = new MiniProgram(conn)
  } catch (e) {
    console.error('❌ 连接自动化端口失败：', e.message)
    console.error('   请先执行：cli.bat auto --project <项目路径> --auto-port 9420')
    process.exit(1)
  }

  console.log('\n=== 页面渲染验证 ===')

  for (const p of PAGES) {
    try {
      const page = await miniProgram.reLaunch('/' + p.route)
      await page.waitFor(1500)
      const text = await page.text()

      const missing = p.expects.filter(k => !text || !text.includes(k))
      if (missing.length === 0) {
        console.log(`  ✅ ${p.name}  渲染正常（${(text || '').length} 字）`)
        pass++
      } else {
        console.log(`  ❌ ${p.name}  缺少：${missing.join('、')}`)
        console.log(`     实际：${(text || '(空白)').slice(0, 120)}`)
        fail++
      }
    } catch (e) {
      console.log(`  ❌ ${p.name}  异常：${e.message}`)
      fail++
    }
  }

  console.log(`\n=== 结果：${pass} 通过，${fail} 失败 ===\n`)
  try { await miniProgram.close() } catch (e) {}
  process.exit(fail === 0 ? 0 : 1)
})()
