/**
 * 单页诊断：只打开一个页面，带超时，避免整轮挂死
 * 用法：node tests/miniapp-probe.js pages/adopt/adopt
 */
const Connection = require('miniprogram-automator/out/Connection').default
const MiniProgram = require('miniprogram-automator/out/MiniProgram').default

const route = process.argv[2]
if (!route) { console.error('用法: node tests/miniapp-probe.js <route>'); process.exit(2) }

function withTimeout(promise, ms, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`超时(${ms}ms): ${label}`)), ms))
  ])
}

;(async () => {
  let mp
  try {
    const conn = await withTimeout(Connection.create('ws://127.0.0.1:9420'), 8000, 'connect')
    mp = new MiniProgram(conn)
    console.log('已连接 IDE')
  } catch (e) {
    console.error('连接失败:', e.message); process.exit(1)
  }

  try {
    console.log('打开:', route)
    const page = await withTimeout(mp.reLaunch('/' + route), 20000, 'reLaunch')
    console.log('页面已打开, path =', page.path)
    await new Promise(r => setTimeout(r, 1500))
    const text = await withTimeout(page.text(), 8000, 'text')
    console.log('渲染字符数:', (text || '').length)
    console.log('内容前 200 字:', (text || '(空白)').slice(0, 200))
    try {
      const data = await withTimeout(page.data(), 5000, 'data')
      console.log('data keys:', Object.keys(data || {}).join(', '))
    } catch (e) { console.log('(读取 data 失败:', e.message, ')') }
  } catch (e) {
    console.error('打开页面失败:', e.message)
    process.exit(1)
  }

  try { await mp.close() } catch (e) {}
  process.exit(0)
})()
