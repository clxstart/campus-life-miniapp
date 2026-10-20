// 同类问题修复的回归测试
// 覆盖：图片必须上传后才提交、地点/分类无跨校污染、圈子分类动态化、作息三级优先级
// 运行：node --test tests/audit-fixes.test.js
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const root = path.resolve(__dirname, '..')

function environment(storage = new Map()) {
  let handler = options => options.fail({ errMsg: 'request:fail connect refused' })
  let uploadHandler = options => options.success({ statusCode: 200, data: JSON.stringify({ code: 0, msg: 'ok', data: { url: '/uploads/ok.jpg' } }) })
  let requests = []
  let uploads = []
  let pageConfig
  const cache = new Map()
  const context = vm.createContext({
    console, Promise, Map, Set, Date, Math, JSON, Number, String, Array, Object, RegExp, Error,
    encodeURIComponent, decodeURIComponent,
    setTimeout: fn => { Promise.resolve().then(fn); return 1 }, clearTimeout() {},
    setInterval: () => 1, clearInterval() {},
    getApp: () => ({ globalData: { schoolId: null, schoolName: '' } }),
    getCurrentPages: () => [1, 2],
    Page: config => { pageConfig = config },
    wx: {
      request(options) { requests.push(options); handler(options) },
      uploadFile(options) { uploads.push(options); uploadHandler(options) },
      getStorageSync(key) { return storage.has(key) ? JSON.parse(JSON.stringify(storage.get(key))) : '' },
      setStorageSync(key, value) { storage.set(key, JSON.parse(JSON.stringify(value))) },
      removeStorageSync(key) { storage.delete(key) },
      getWindowInfo: () => ({ statusBarHeight: 20, windowWidth: 375 }),
      getMenuButtonBoundingClientRect: () => ({ top: 24, left: 280, height: 32 }),
      navigateTo() {}, reLaunch() {}, redirectTo() {}, navigateBack() {},
      switchTab() {}, showToast() {}, stopPullDownRefresh() {}, showModal() {},
      showActionSheet(options) { context.lastSheet = options },
      chooseMedia() {}, previewImage() {}
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
    const instance = { ...pageConfig, data: JSON.parse(JSON.stringify(pageConfig.data)), selectComponent: () => ({}) }
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
    load, page, context, storage, requests, uploads,
    respond(fn) { handler = fn },
    respondUpload(fn) { uploadHandler = fn },
    online(data) { handler = options => options.success({ statusCode: 200, data: { code: 0, msg: 'ok', data } }) },
    lastUrl() { return (requests.at(-1) || {}).url || '' }
  }
}
const plain = value => JSON.parse(JSON.stringify(value))
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve() }

// ================= 问题 1：图片必须上传 =================

test('图片：uploadImages 上传失败时抛错，绝不退回本地临时路径', async () => {
  const env = environment()
  const api = env.load('utils/api.js')
  // 后端返回没有 url 的响应
  env.respondUpload(options => options.success({ statusCode: 200, data: JSON.stringify({ code: 0, msg: 'ok', data: {} }) }))
  await assert.rejects(
    () => api.uploadImages(['wxfile://tmp_a.jpg']),
    /上传失败/,
    '拿不到服务端地址时必须抛错，不能把本地路径当结果返回'
  )
})

test('图片：uploadImages 正常返回服务端地址', async () => {
  const env = environment()
  const api = env.load('utils/api.js')
  env.respondUpload(options => options.success({ statusCode: 200, data: JSON.stringify({ code: 0, msg: 'ok', data: { url: '/uploads/a.jpg' } }) }))
  const out = await api.uploadImages(['wxfile://tmp_a.jpg', 'wxfile://tmp_b.jpg'])
  assert.deepEqual(plain(out), ['/uploads/a.jpg', '/uploads/a.jpg'])
  assert.ok(out.every(url => url.startsWith('/uploads/')), '结果必须是服务端地址')
})

test('图片：二手发布页提交前会先上传（不再直接提交本地路径）', async () => {
  const env = environment()
  const page = env.page('pages/market-publish/market-publish.js')
  page.setData({ images: ['wxfile://tmp_local.jpg'], title: '测试台灯', price: '99', description: '九成新' })
  env.respondUpload(options => options.success({ statusCode: 200, data: JSON.stringify({ code: 0, msg: 'ok', data: { url: '/uploads/lamp.jpg' } }) }))
  env.online({ id: 99 })
  await page.submit()
  await flush()
  assert.equal(env.uploads.length, 1, '应该发生了一次图片上传')
  const body = env.requests.at(-1).data || {}   // mock 里 data 已是对象
  assert.equal(body.image, '/uploads/lamp.jpg', '提交的 image 必须是服务端地址')
  assert.ok(!String(body.image).startsWith('wxfile'), '不能提交 wxfile:// 本地路径')
  assert.deepEqual(plain(body.images), ['/uploads/lamp.jpg'])
})

test('图片：二手发布上传失败时中止发布，不会提交脏数据', async () => {
  const env = environment()
  const page = env.page('pages/market-publish/market-publish.js')
  page.setData({ images: ['wxfile://tmp_local.jpg'], title: '测试台灯', price: '99', description: '九成新' })
  env.respondUpload(options => options.success({ statusCode: 200, data: JSON.stringify({ code: 0, msg: 'ok', data: {} }) }))
  const before = env.requests.length
  await page.submit()
  await flush()
  assert.equal(env.requests.length, before, '上传失败时不应发出发布请求')
  assert.equal(page.data.submitting, false, '失败后应允许重试')
})

// ================= 问题 2 + 7：公共常量，无跨校污染 =================

test('常量：地点里不再有外校场所', () => {
  const env = environment()
  const c = env.load('utils/constants.js')
  const joined = c.CAMPUS_LOCATIONS.join(',')
  for (const bad of ['紫荆操场', '逸夫图书馆', '东区篮球场', '内蒙古大学']) {
    assert.ok(!joined.includes(bad), `地点里不应出现「${bad}」（外校场所或学校名）`)
  }
  assert.ok(c.CAMPUS_LOCATIONS.length >= 4, '至少要有几个通用校内场所')
})

test('常量：二手分类只有一处定义，列表页与发布页一致', () => {
  const env = environment()
  const c = env.load('utils/constants.js')
  assert.deepEqual(plain(c.GOODS_CATEGORIES), ['数码', '图书', '生活', '运动', '其他'])
  assert.deepEqual(plain(c.GOODS_CATEGORY_FILTERS), ['全部', '数码', '图书', '生活', '运动', '其他'])

  // 三个页面都应该用常量，而不是各自硬编码
  for (const file of [
    'pages/market-list/market-list.js',
    'pages/market-publish/market-publish.js',
    'pages/publish/publish.js'
  ]) {
    const src = fs.readFileSync(path.resolve(root, file), 'utf8')
    assert.ok(src.includes('utils/constants'), `${file} 应从 constants 引入分类`)
    assert.ok(!/数码', '图书'/.test(src), `${file} 不应再硬编码分类列表`)
  }
})

test('地点：发布页按当前学校动态拉取，通用地点只作离线兜底', () => {
  for (const file of ['pages/publish/publish.js', 'pages/circle-publish/circle-publish.js']) {
    const src = fs.readFileSync(path.resolve(root, file), 'utf8')
    assert.ok(src.includes('utils/school'), `${file} 应引入 school 模块`)
    assert.ok(src.includes('getSelectedLocations'), `${file} 应按当前学校拉取地点`)
    assert.ok(!src.includes('紫荆操场'), `${file} 不应残留外校地点`)
    assert.ok(!src.includes("location: '内蒙古大学'"), `${file} 不应把学校名当默认地点`)
  }
  const schoolSrc = fs.readFileSync(path.resolve(root, 'utils/school.js'), 'utf8')
  assert.ok(schoolSrc.includes('CAMPUS_LOCATIONS'), '通用地点应保留为离线兜底')
})

test('图片：后端校验逻辑覆盖微信本地临时路径的两种形态', () => {
  // 微信小程序本地临时路径有两种：wxfile://tmp_xxx 和 http://tmp/xxx
  // 后者看起来像 http，极易被「以 http 开头就放行」的规则误判放过
  const src = fs.readFileSync(
    path.resolve(root, 'backend/src/main/java/com/campuslife/service/BizService.java'),
    'utf8'
  )
  assert.ok(src.includes('isServableImage'), 'BizService 应有图片校验方法')
  assert.ok(src.includes('wxfile://'), '必须显式拒绝 wxfile:// 临时路径')
  assert.ok(src.includes('http://tmp/'), '必须显式拒绝 http://tmp/ 临时路径（不能只靠 http 前缀放行）')
  assert.ok(src.includes('/uploads/'), '必须放行上传接口返回的路径')

  const forum = fs.readFileSync(
    path.resolve(root, 'backend/src/main/java/com/campuslife/service/ForumService.java'),
    'utf8'
  )
  assert.ok(forum.includes('validImage'), '发帖也要校验图片')
  assert.ok(forum.includes('http://tmp/'), '发帖校验同样要拒绝 http://tmp/')
})

// ================= 问题 3：圈子分类动态化 =================

test('圈子分类：页面从接口拉取，不再只有硬编码', async () => {
  const env = environment()
  const page = env.page('pages/discover/discover.js')
  page._alive = true   // 页面存活标记由 onLoad 设置，测试里手动置位
  env.online([
    { category: '学习', total: 3 },
    { category: '运动', total: 3 },
    { category: '音乐', total: 1 }
  ])
  await page.loadCategories()
  await flush()
  assert.deepEqual(plain(page.data.categories), ['全部', '学习', '运动', '音乐'], '应采用后端返回的分类（含新建的「音乐」）')
  assert.ok(env.requests.some(r => r.url.includes('/circles/categories')), '应请求分类接口')
})

test('圈子分类：接口不可用时不崩，分类列表仍然可用', async () => {
  const env = environment()
  const page = env.page('pages/discover/discover.js')
  page._alive = true
  // 注意：api.getCircleCategories 内部有 mock 兜底（从 mock.circles 推导分类），
  // 所以「请求失败」不会抛到页面，页面拿到的是一个可用的分类数组。
  // 这里验证的是：不崩、第一个永远是「全部」、没有空项。
  await page.loadCategories()
  await flush()
  const categories = plain(page.data.categories)
  assert.ok(Array.isArray(categories) && categories.length >= 2, '分类列表应至少有两项')
  assert.equal(categories[0], '全部', '第一项必须是「全部」')
  assert.ok(categories.every(name => typeof name === 'string' && name.length > 0), '分类名不能为空')
  assert.equal(new Set(categories).size, categories.length, '分类不应重复')
})

// ================= 问题 5：作息三级优先级 =================

test('作息：解析学校配置（数组格式）', () => {
  const env = environment()
  const school = env.load('utils/school.js')
  const times = school.parsePeriodConfig('["07:40","09:40","13:40","15:40","18:40"]')
  assert.deepEqual(plain(times), ['07:40', '09:40', '13:40', '15:40', '18:40'])
})

test('作息：兼容对象格式，且脏数据返回 null', () => {
  const env = environment()
  const school = env.load('utils/school.js')
  assert.deepEqual(
    plain(school.parsePeriodConfig('{"1":"08:15","2":"10:15","3":"14:15","4":"16:15","5":"19:15"}')),
    ['08:15', '10:15', '14:15', '16:15', '19:15']
  )
  for (const bad of ['', null, '不是json', '[]', '["08:00"]']) {
    assert.equal(school.parsePeriodConfig(bad), null, `输入 ${JSON.stringify(bad)} 应返回 null`)
  }
})

test('作息：学生本地覆盖优先于学校配置', async () => {
  const storage = new Map()
  storage.set('campus_selected_school', JSON.stringify({ id: 1, name: '内蒙古大学' }))
  storage.set('campus_period_times', JSON.stringify(['06:00', '08:00', '10:00', '12:00', '14:00']))
  const env = environment(storage)
  const page = env.page('pages/timetable/timetable.js')
  page._alive = true
  env.online([{ id: 1, name: '内蒙古大学', periodConfig: '["07:40","09:40","13:40","15:40","18:40"]' }])
  await page.syncSchoolPeriods()
  await flush()
  const times = page.data.periods.map(p => p.time)
  assert.deepEqual(times, ['06:00', '08:00', '10:00', '12:00', '14:00'], '学生改过的作息不应被学校配置覆盖')
})

test('作息：学生没改过时采用学校配置', async () => {
  const storage = new Map()
  storage.set('campus_selected_school', JSON.stringify({ id: 1, name: '内蒙古大学' }))
  const env = environment(storage)
  const page = env.page('pages/timetable/timetable.js')
  page._alive = true
  env.online([{ id: 1, name: '内蒙古大学', periodConfig: '["07:40","09:40","13:40","15:40","18:40"]' }])
  await page.syncSchoolPeriods()
  await flush()
  assert.deepEqual(plain(page.data.periods.map(p => p.time)), ['07:40', '09:40', '13:40', '15:40', '18:40'])
})

test('作息：学校没配时保持内置默认', async () => {
  const storage = new Map()
  storage.set('campus_selected_school', JSON.stringify({ id: 1, name: '内蒙古大学' }))
  const env = environment(storage)
  const page = env.page('pages/timetable/timetable.js')
  page._alive = true
  env.online([{ id: 1, name: '内蒙古大学', periodConfig: '' }])
  await page.syncSchoolPeriods()
  await flush()
  assert.deepEqual(page.data.periods.map(p => p.time), ['08:00', '10:00', '14:00', '16:00', '19:00'])
})
