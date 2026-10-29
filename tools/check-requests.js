/**
 * 首屏请求去重验证（用官方 mockWxMethod，不用自己替换 wx.request）
 *
 * 背景：手动替换 wx.request 在页面重建时会失效或重复计数，
 * 实测出现过「每个接口 2 次」的假象。
 * mockWxMethod 是 automator 官方拦截方式，计数可靠。
 *
 * 前置：
 *   cli.bat auto --project "E:\goodworlk\campus-life-miniapp" --port 38038 --auto-port 9420 --lang zh
 * 运行：node tools/check-requests.js
 */
const path = require('path')
const ROOT = 'E:\\goodworlk\\campus-life-miniapp'
const automator = require(path.join(ROOT, 'node_modules', 'miniprogram-automator'))
const sleep = ms => new Promise(r => setTimeout(r, ms))
const WS = process.env.MP_WS || 'ws://127.0.0.1:9420'

;(async () => {
  const mini = await automator.connect({ wsEndpoint: WS })
  console.log('已连接\n')

  // 拦截 wx.request：记录 URL 并返回空数据（不发真实请求）
  await mini.evaluate(() => {
    const app = getApp()
    app.__reqs = []
  })
  await mini.mockWxMethod('request', function (opt) {
    const app = getApp()
    try { (app.__reqs = app.__reqs || []).push(String((opt && opt.url) || '?')) } catch (e) {}
    // 回调成功，返回空数据，避免页面因请求失败而重试
    if (opt && typeof opt.success === 'function') {
      opt.success({ statusCode: 200, data: { code: 0, data: [] } })
    }
    if (opt && typeof opt.complete === 'function') opt.complete({})
    return { abort() {} }
  })

  // 进首页
  await mini.evaluate(() => wx.redirectTo({ url: '/pages/index/index' }))
  await sleep(5000)

  const urls = await mini.evaluate(() => {
    const app = getApp()
    return (app.__reqs || []).join('\n')
  })
  const list = String(urls || '').split('\n').filter(Boolean)

  await mini.restoreWxMethod('request')

  console.log('=== 首页首屏请求 ===')
  list.forEach((u, i) => console.log(`  ${i + 1}. ${u.replace(/^https?:\/\/[^/]+/, '').slice(0, 64)}`))

  const groups = {}
  list.forEach(u => { const k = u.split('?')[0].replace(/^https?:\/\/[^/]+/, ''); groups[k] = (groups[k] || 0) + 1 })

  console.log('\n=== 按接口归类 ===')
  Object.entries(groups).forEach(([k, v]) => console.log(`  ${v} 次  ${k}`))

  const dup = Object.entries(groups).filter(([, v]) => v > 1)
  console.log('\n' + (dup.length === 0
    ? '✅ 无重复请求，每个接口只调 1 次'
    : '⚠ 重复: ' + dup.map(([k, v]) => k + ' x' + v).join(', ')))

  await mini.disconnect()
  process.exit(0)
})()
