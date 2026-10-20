// 每校自定义地点：小程序端接入
// 运行：node --test tests/miniapp-school-location.test.js
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const root = path.resolve(__dirname, '..')

function environment(storage = new Map()) {
  let handler = options => options.fail({ errMsg: 'request:fail connect refused' })
  let requests = []
  let sheet = null
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
      showActionSheet(options) { sheet = options }
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
    online(data) { handler = options => options.success({ statusCode: 200, data: { code: 0, msg: 'ok', data } }) },
    getSheet() { return sheet },
    lastUrl() { return (requests.at(-1) || {}).url || '' }
  }
}
const plain = value => JSON.parse(JSON.stringify(value))
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve() }

test('地点：接口按选中学校拉取', async () => {
  const storage = new Map()
  storage.set('campus_selected_school', JSON.stringify({ id: 2, name: '北京大学' }))
  const env = environment(storage)
  const school = env.load('utils/school.js')
  env.online([{ id: 21, name: '未名湖', sort: 10 }, { id: 22, name: '博雅塔', sort: 20 }])
  const list = await school.getSelectedLocations()
  assert.deepEqual(plain(list), ['未名湖', '博雅塔'])
  assert.ok(env.lastUrl().includes('/schools/2/locations'), `应请求该校地点，实际：${env.lastUrl()}`)
})

test('地点：没选学校时用通用地点兜底', async () => {
  const env = environment()
  const school = env.load('utils/school.js')
  const list = await school.getSelectedLocations()
  assert.ok(list.length >= 4, '未选学校也要有可选项')
  assert.ok(list.includes('图书馆'))
})

test('地点：接口失败时回落到通用地点，不抛异常', async () => {
  const storage = new Map()
  storage.set('campus_selected_school', JSON.stringify({ id: 1, name: '内蒙古大学' }))
  const env = environment(storage)
  const school = env.load('utils/school.js')
  // handler 默认失败
  const list = await school.getSelectedLocations()
  assert.ok(list.length >= 4, '接口挂了也要能选地点')
  assert.ok(list.includes('食堂'))
})

test('地点：学校没配地点（返回空数组）时回落通用', async () => {
  const storage = new Map()
  storage.set('campus_selected_school', JSON.stringify({ id: 1, name: '内蒙古大学' }))
  const env = environment(storage)
  const school = env.load('utils/school.js')
  env.online([])
  const list = await school.getSelectedLocations()
  assert.ok(list.length >= 4, '空列表应回落，避免选择器里什么都没有')
})

test('地点：通用地点里没有外校场所与学校名', () => {
  const env = environment()
  const c = env.load('utils/constants.js')
  const joined = c.CAMPUS_LOCATIONS.join(',')
  for (const bad of ['紫荆操场', '逸夫图书馆', '东区篮球场', '内蒙古大学', '北京大学']) {
    assert.ok(!joined.includes(bad), `通用地点不应含「${bad}」`)
  }
})

test('发布页：地点选择器用学校地点而不是常量', async () => {
  const storage = new Map()
  storage.set('campus_selected_school', JSON.stringify({ id: 2, name: '北京大学' }))
  const env = environment(storage)
  const page = env.page('pages/publish/publish.js')
  page._alive = true
  env.online([{ id: 21, name: '未名湖', sort: 10 }])
  await page.chooseLocation()
  await flush()
  // 弹层走页面内的 ui-popup 组件（page.data.uiPopup），不是 wx.showActionSheet
  const ui = page.data.uiPopup
  assert.ok(ui && ui.visible, '应弹出地点选择器')
  assert.equal(ui.mode, 'sheet')
  assert.deepEqual(plain(ui.actions.map(a => a.label)), ['未名湖'], '应使用学校配置的地点')
})

test('发帖页：地点选择器同样用学校地点', async () => {
  const storage = new Map()
  storage.set('campus_selected_school', JSON.stringify({ id: 2, name: '北京大学' }))
  const env = environment(storage)
  const page = env.page('pages/circle-publish/circle-publish.js')
  page._alive = true
  env.online([{ id: 21, name: '未名湖', sort: 10 }])
  await page.chooseLocation()
  await flush()
  const ui = page.data.uiPopup
  assert.ok(ui && ui.visible, '应弹出地点选择器')
  assert.deepEqual(plain(ui.actions.map(a => a.label)), ['未名湖'])
})

test('发布页：默认地点不再是学校名（避免把校名当地点）', () => {
  for (const file of ['pages/publish/publish.js', 'pages/circle-publish/circle-publish.js']) {
    const src = fs.readFileSync(path.resolve(root, file), 'utf8')
    assert.ok(!src.includes("location: '内蒙古大学'"), `${file} 默认地点不应是学校名`)
    assert.ok(/location:\s*''/.test(src), `${file} 默认地点应为空`)
  }
})
