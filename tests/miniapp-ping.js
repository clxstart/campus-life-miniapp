/**
 * 轻量探测：只用最基础的接口，确认自动化通道是否可用
 */
const Connection = require('miniprogram-automator/out/Connection').default
const MiniProgram = require('miniprogram-automator/out/MiniProgram').default

function withTimeout(p, ms, label) {
  return Promise.race([p, new Promise((_, r) => setTimeout(() => r(new Error(`超时 ${ms}ms: ${label}`)), ms))])
}

;(async () => {
  let mp
  try {
    const conn = await withTimeout(Connection.create('ws://127.0.0.1:9420'), 8000, 'connect')
    mp = new MiniProgram(conn)
    console.log('✅ 连接成功')
  } catch (e) { console.error('❌ 连接失败:', e.message); process.exit(1) }

  // 当前页面（不主动跳转）
  try {
    const page = await withTimeout(mp.currentPage(), 10000, 'currentPage')
    console.log('✅ currentPage:', page && page.path)
    const text = await withTimeout(page.text(), 8000, 'text')
    console.log('   字符数:', (text || '').length)
    console.log('   内容:', (text || '(空白)').slice(0, 150))
  } catch (e) {
    console.error('❌ currentPage 失败:', e.message)
  }

  // 系统信息
  try {
    const info = await withTimeout(mp.systemInfo(), 8000, 'systemInfo')
    console.log('✅ 系统:', info.platform, info.SDKVersion || info.version)
  } catch (e) {
    console.error('❌ systemInfo 失败:', e.message)
  }

  try { await mp.close() } catch (e) {}
  process.exit(0)
})()
