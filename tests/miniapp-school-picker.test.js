// 小程序选学校：选中态持久化 + 动态列表 + 各接口带上 schoolId
// 运行：node --test tests/miniapp-school-picker.test.js
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
      showActionSheet(options) { context.lastSheet = options; if (context.sheetPick != null) options.success({ tapIndex: context.sheetPick }) },
      showModal() {}, chooseMedia() {}, previewImage() {}
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
    lastUrl() { return (requests.at(-1) || {}).url || '' }
  }
}
const flush = async () => { for (let i = 0; i < 15; i++) await Promise.resolve() }
// vm 里创建的对象原型与外层不同，deepEqual 前要先转成普通对象
const plain = value => JSON.parse(JSON.stringify(value))

// ================= 选中态持久化 =================

test('选学校：未选过时 getSelected 返回 null', () => {
  const env = environment()
  const school = env.load('utils/school.js')
  assert.equal(school.getSelected(), null)
  assert.equal(school.hasSelected(), false)
  assert.equal(school.getSelectedId(), null)
})

test('选学校：setSelected 后能读回，且写入 storage', () => {
  const env = environment()
  const school = env.load('utils/school.js')
  const saved = school.setSelected({ id: 2, name: '北京大学' })
  assert.deepEqual(plain(saved), { id: 2, name: '北京大学' })
  assert.deepEqual(plain(school.getSelected()), { id: 2, name: '北京大学' })
  assert.equal(school.getSelectedId(), 2)
  assert.equal(school.getSelectedName(), '北京大学')
  assert.equal(school.hasSelected(), true)
  assert.ok(env.storage.has('campus_selected_school'), '应写入 storage')
})

test('选学校：id 统一转成数字（避免字符串 id 导致接口过滤失效）', () => {
  const env = environment()
  const school = env.load('utils/school.js')
  school.setSelected({ id: '2', name: '北京大学' })
  assert.equal(school.getSelectedId(), 2)
  assert.equal(typeof school.getSelectedId(), 'number')
})

test('选学校：非法数据不会让程序崩，按未选处理', () => {
  const storage = new Map()
  storage.set('campus_selected_school', '{不是json')
  const env = environment(storage)
  const school = env.load('utils/school.js')
  assert.equal(school.getSelected(), null)
  assert.equal(school.hasSelected(), false)
})

test('选学校：缺 id 的脏数据按未选处理', () => {
  const storage = new Map()
  storage.set('campus_selected_school', '{"name":"某大学"}')
  const env = environment(storage)
  const school = env.load('utils/school.js')
  assert.equal(school.getSelected(), null)
})

test('选学校：setSelected 传空值不覆盖已有选择', () => {
  const env = environment()
  const school = env.load('utils/school.js')
  school.setSelected({ id: 1, name: '内蒙古大学' })
  assert.equal(school.setSelected(null), null)
  assert.equal(school.setSelected({}), null)
  assert.equal(school.getSelectedId(), 1, '原有选择应保持不变')
})

// ================= 动态列表 =================

test('选学校：列表来自接口，不是硬编码', async () => {
  const env = environment()
  const school = env.load('utils/school.js')
  env.online([
    { id: 1, name: '内蒙古大学', city: '呼和浩特' },
    { id: 2, name: '北京大学', city: '北京' },
    { id: 3, name: '清华大学', city: '北京' }
  ])
  const list = await school.fetchSchools()
  assert.equal(list.length, 3, '应使用接口返回的 3 所，而不是硬编码的 3 个名字')
  assert.deepEqual(list.map(s => s.name), ['内蒙古大学', '北京大学', '清华大学'])
  assert.ok(env.lastUrl().includes('/schools'), '应请求 /schools 接口')
})

test('选学校：接口挂了回落到兜底列表，不抛异常', async () => {
  const env = environment()
  const school = env.load('utils/school.js')
  // handler 默认就是失败
  const list = await school.fetchSchools()
  assert.ok(Array.isArray(list) && list.length >= 2, '离线也要能选学校')
  assert.ok(list.every(s => s.id && s.name))
})

test('选学校：接口返回空数组时也回落兜底', async () => {
  const env = environment()
  const school = env.load('utils/school.js')
  env.online([])
  const list = await school.fetchSchools()
  assert.ok(list.length >= 2, '空列表应回落兜底，避免选择器里什么都没有')
})

// ================= 各接口带 schoolId =================

test('接口：圈子/热门圈子带上 schoolId', async () => {
  const env = environment()
  const api = env.load('utils/api.js')
  env.online({ list: [], total: 0, page: 1, size: 20 })
  await api.getCircles({ schoolId: 2 })
  assert.ok(env.lastUrl().includes('schoolId=2'), `圈子应带 schoolId，实际：${env.lastUrl()}`)

  env.online([])
  await api.getHotCircles(5, 1, 'local', 2)
  assert.ok(env.lastUrl().includes('schoolId=2'), `热门圈子应带 schoolId，实际：${env.lastUrl()}`)
})

test('接口：兼职/外卖/活动/二手/帖子带上 schoolId', async () => {
  const env = environment()
  const api = env.load('utils/api.js')

  env.online([])
  await api.getJobs('', '', 'local', 2)
  assert.ok(env.lastUrl().includes('schoolId=2'), `兼职应带 schoolId，实际：${env.lastUrl()}`)

  await api.getFoodShops('', 'local', 2)
  assert.ok(env.lastUrl().includes('schoolId=2'), `外卖应带 schoolId，实际：${env.lastUrl()}`)

  await api.getActivities(1, 'local', 2)
  assert.ok(env.lastUrl().includes('schoolId=2'), `活动应带 schoolId，实际：${env.lastUrl()}`)

  env.online({ list: [], total: 0, page: 1, size: 20 })
  await api.getGoodsList(1, '', 'local', 2)
  assert.ok(env.lastUrl().includes('schoolId=2'), `二手应带 schoolId，实际：${env.lastUrl()}`)

  await api.getForumPosts({ schoolId: 2 })
  assert.ok(env.lastUrl().includes('schoolId=2'), `帖子应带 schoolId，实际：${env.lastUrl()}`)
})

test('接口：不传 schoolId 时不出现该参数（向后兼容）', async () => {
  const env = environment()
  const api = env.load('utils/api.js')
  env.online([])
  await api.getJobs('', '', 'local')
  assert.ok(!env.lastUrl().includes('schoolId'), `不该凭空加 schoolId，实际：${env.lastUrl()}`)
})
