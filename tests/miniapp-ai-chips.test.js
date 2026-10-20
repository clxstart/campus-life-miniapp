// AI 聊天页：底部常驻关键词
//
// 背景：快捷追问只在「首次进入」时出现（从欢迎语里取），
// 第二次进入页面从 storage 恢复历史时没有恢复 quickReplies，
// 导致底部一片空白，用户没有可点的关键词。
//
// 运行：node --test tests/miniapp-ai-chips.test.js
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const root = path.resolve(__dirname, '..')

function environment(storage = new Map()) {
  let handler = options => options.fail({ errMsg: 'request:fail connect refused' })
  let requests = []
  const cache = new Map()
  const context = vm.createContext({
    console, Promise, Map, Set, Date, Math, JSON, Number, String, Array, Object, RegExp, Error,
    encodeURIComponent, decodeURIComponent,
    setTimeout: fn => { Promise.resolve().then(fn); return 1 }, clearTimeout() {},
    setInterval: () => 1, clearInterval() {},
    getApp: () => ({ globalData: { schoolId: null, schoolName: '' } }),
    getCurrentPages: () => [1, 2],
    Page: config => { context.pageConfig = config },
    wx: {
      request(options) { requests.push(options); handler(options) },
      getStorageSync(key) { return storage.has(key) ? JSON.parse(JSON.stringify(storage.get(key))) : '' },
      setStorageSync(key, value) { storage.set(key, JSON.parse(JSON.stringify(value))) },
      removeStorageSync(key) { storage.delete(key) },
      getWindowInfo: () => ({ statusBarHeight: 20, windowWidth: 375 }),
      getMenuButtonBoundingClientRect: () => ({ top: 24, left: 280, height: 32 }),
      navigateTo() {}, reLaunch() {}, redirectTo() {}, navigateBack() {},
      switchTab() {}, showToast() {}, stopPullDownRefresh() {},
      showActionSheet() {}
    }
  })
  function load(filename) {
    const full = path.resolve(root, filename)
    if (cache.has(full)) return cache.get(full).exports
    const module = { exports: {} }; cache.set(full, module)
    const source = fs.readFileSync(full, 'utf8')
    const run = vm.runInContext(`(function(require,module,exports){${source}\n})`, context, { filename: full })
    run(specifier => load(path.resolve(path.dirname(full), specifier + (path.extname(specifier) ? '' : '.js'))), module, module.exports)
    return module.exports
  }
  function page(filename) {
    load(filename)
    const config = context.pageConfig
    const instance = { ...config, data: JSON.parse(JSON.stringify(config.data)), selectComponent: () => ({}) }
    instance.setData = data => {
      for (const [key, value] of Object.entries(data)) {
        const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
        let current = instance.data
        parts.slice(0, -1).forEach(part => { current = current[part] ||= {} })
        current[parts.at(-1)] = value
      }
    }
    return instance
  }
  return {
    load, page, context, storage, requests,
    respond(fn) { handler = fn },
    online(data) { handler = options => options.success({ statusCode: 200, data: { code: 0, msg: 'ok', data } }) }
  }
}
const plain = value => JSON.parse(JSON.stringify(value))
const flush = async () => { for (let i = 0; i < 20; i += 1) await Promise.resolve() }

test('常驻关键词：首次进入底部有关键词', async () => {
  const env = environment()
  const page = env.page('pages/ai-chat/ai-chat.js')
  page.onLoad()
  await flush()
  const chips = page.data.quickReplies.length ? page.data.quickReplies : page.data.starterChips
  assert.ok(chips.length >= 4, `底部应有至少 4 个关键词，实际 ${chips.length}`)
})

test('常驻关键词：第二次进入（有历史）仍然有关键词', async () => {
  const storage = new Map()
  // 模拟上次会话留下的历史：最后一条 AI 消息没有 quickReplies
  storage.set('campus_ai_history', JSON.stringify([
    { id: '1', role: 'ai', text: '你好呀', quickReplies: [] },
    { id: '2', role: 'user', text: '今天吃什么', quickReplies: [] },
    { id: '3', role: 'ai', text: '去食堂吧', quickReplies: [] }
  ]))
  const env = environment(storage)
  const page = env.page('pages/ai-chat/ai-chat.js')
  page.onLoad()
  await flush()
  const chips = page.data.quickReplies.length ? page.data.quickReplies : page.data.starterChips
  assert.ok(chips.length >= 4, '有历史时底部也不能空，应回落到常驻关键词')
})

test('常驻关键词：从历史里恢复上次的快捷追问', async () => {
  const storage = new Map()
  storage.set('campus_ai_history', JSON.stringify([
    { id: '1', role: 'ai', text: '你好呀', quickReplies: ['A选项', 'B选项'] }
  ]))
  const env = environment(storage)
  const page = env.page('pages/ai-chat/ai-chat.js')
  page.onLoad()
  await flush()
  assert.deepEqual(plain(page.data.quickReplies), ['A选项', 'B选项'], '应恢复历史里的快捷追问')
})

test('常驻关键词：后端欢迎语配置生效（管理端可改）', async () => {
  const env = environment()
  const page = env.page('pages/ai-chat/ai-chat.js')
  env.online({ reply: '后端配置的欢迎语', quickReplies: ['词一', '词二', '词三'] })
  page.onLoad()
  await flush()
  assert.ok(page.data.messages.some(m => m.text === '后端配置的欢迎语'), '应使用后端欢迎语')
  assert.deepEqual(plain(page.data.starterChips), ['词一', '词二', '词三'], '常驻关键词应来自后端配置')
})

test('常驻关键词：后端挂了用本地兜底，不能空', async () => {
  const env = environment()
  const page = env.page('pages/ai-chat/ai-chat.js')
  // handler 默认失败
  page.onLoad()
  await flush()
  const chips = page.data.quickReplies.length ? page.data.quickReplies : page.data.starterChips
  assert.ok(chips.length >= 4, '后端不可用时也要有关键词')
  assert.ok(chips.includes('今天吃什么'), '兜底关键词应包含高频问法')
})

test('WXML：关键词行不再依赖 quickReplies 非空', () => {
  const wxml = fs.readFileSync(path.resolve(root, 'pages/ai-chat/ai-chat.wxml'), 'utf8')
  assert.ok(
    /wx:for="\{\{quickReplies\.length \? quickReplies : starterChips\}\}"/.test(wxml),
    '关键词行应在 quickReplies 为空时回落到 starterChips'
  )
  assert.ok(
    !/wx:if="\{\{quickReplies\.length && !sending\}\}"/.test(wxml),
    '不应再要求 quickReplies 非空才显示关键词'
  )
})
