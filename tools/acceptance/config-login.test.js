// 小程序生产配置/登录降级回归：node tools/acceptance/config-login.test.js
const assert = require('node:assert')
const config = require('../../utils/config')

function run(name, fn) {
  try { fn(); console.log('ok -', name) } catch (error) { console.error('not ok -', name); throw error }
}

run('develop 使用本机地址且允许开发登录', () => {
  const c = config.resolveRuntimeConfig('develop')
  assert.strictEqual(c.ENV, 'dev')
  assert.strictEqual(c.IS_PRODUCTION, false)
  assert.strictEqual(c.ALLOW_DEV_LOGIN, true)
  assert.strictEqual(c.CONFIG_ERROR, '')
  assert.ok(c.BASE_URL.startsWith('http://127.0.0.1:8080'))
})

run('trial/release 一律进入生产模式并禁用开发登录', () => {
  for (const version of ['trial', 'release']) {
    const c = config.resolveRuntimeConfig(version)
    assert.strictEqual(c.ENV, 'prod')
    assert.strictEqual(c.IS_PRODUCTION, true)
    assert.strictEqual(c.ALLOW_DEV_LOGIN, false)
  }
})

run('占位生产域名会被明确阻断，而不是回落本机', () => {
  const c = config.resolveRuntimeConfig('release')
  assert.ok(c.CONFIG_ERROR.includes('占位'))
  assert.ok(!c.BASE_URL.includes('127.0.0.1'))
})

run('HTTPS 真实域名通过生产配置校验', () => {
  const error = config.productionConfigError({
    api: 'https://api.campus-life.cn/api',
    asset: 'https://api.campus-life.cn'
  })
  assert.strictEqual(error, '')
})

run('HTTP 或 localhost 生产地址被拒绝', () => {
  assert.ok(config.productionConfigError({ api: 'http://api.campus-life.cn/api', asset: 'https://api.campus-life.cn' }).includes('HTTPS'))
  assert.ok(config.productionConfigError({ api: 'https://localhost/api', asset: 'https://api.campus-life.cn' }).includes('本机'))
})

console.log('config-login: 5 项通过')