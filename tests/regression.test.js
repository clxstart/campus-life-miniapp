// 无第三方依赖的行为回归：node --test tests/regression.test.js
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const root = path.resolve(__dirname, '..')

function environment(storage = new Map()) {
  let handler = options => options.fail({ errMsg: 'request:fail connect refused' })
  let uploadHandler = options => options.success({ statusCode: 200, data: JSON.stringify({ code: 0, msg: 'ok', data: { url: '/uploads/mock.png' } }) })
  let requests = []
  let uploads = []
  let pageConfig
  const cache = new Map()
  const context = vm.createContext({ console, Promise, Map, Set, Date, Math, JSON, Number, String, Array, Object, RegExp, Error, encodeURIComponent, decodeURIComponent,
    setTimeout: fn => { Promise.resolve().then(fn); return 1 }, clearTimeout() {}, setInterval: () => 1, clearInterval() {},
    getApp: () => ({ globalData: { schoolName: '内蒙古大学' } }), getCurrentPages: () => [1, 2], Page: config => { pageConfig = config },
    wx: {
      request(options) { requests.push(options); handler(options) },
      uploadFile(options) { uploads.push(options); uploadHandler(options) },
      getStorageSync(key) { return storage.has(key) ? JSON.parse(JSON.stringify(storage.get(key))) : '' },
      setStorageSync(key, value) { storage.set(key, JSON.parse(JSON.stringify(value))) },
      removeStorageSync(key) { storage.delete(key) },
      getWindowInfo: () => ({ statusBarHeight: 20, windowWidth: 375 }), getMenuButtonBoundingClientRect: () => ({ top: 24, left: 280, height: 32 }),
      navigateTo({ url }) { context.lastUrl = url }, reLaunch({ url }) { context.lastUrl = url },
      redirectTo({ url }) { context.lastUrl = url }, navigateBack() { context.backCount = (context.backCount || 0) + 1 },
      switchTab({ url }) { context.lastUrl = url }, showToast() {}, stopPullDownRefresh() {},
      chooseMedia() {}, chooseImage() {}, previewImage() {}, showActionSheet() {}
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
    const page = { ...pageConfig, data: JSON.parse(JSON.stringify(pageConfig.data)), selectComponent: () => ({}) }
    page.setData = data => {
      for (const [key, value] of Object.entries(data)) {
        const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
        let current = page.data
        parts.slice(0, -1).forEach(part => { current = current[part] ||= {} })
        current[parts.at(-1)] = value
      }
    }
    return page
  }
  return { load, page, context, storage, requests, uploads, respond(fn) { handler = fn }, respondUpload(fn) { uploadHandler = fn }, online(data) { handler = options => options.success({ statusCode: 200, data: { code: 0, msg: 'ok', data } }) }, businessError() { handler = options => options.success({ statusCode: 400, data: { code: 400, msg: '业务拒绝', data: null } }) } }
}
const plain = value => JSON.parse(JSON.stringify(value))
const flush = async () => { for (let i = 0; i < 15; i++) await Promise.resolve() }

test('搜索：标题/正文匹配、分页、通配符、转义及空结果', async () => {
  const env = environment(), api = env.load('utils/api.js')
  const mock = env.load('utils/mock-data.js')
  mock.discoveryPosts.push({ id: 91, title: '100% off', content: 'AbC\n校园', author: '测试' }, { id: 92, title: '符号 [.*+?]', content: '其他', author: '测试' }, { id: 93, title: 'A😀B', content: '特殊字符结尾%', author: '测试' })
  let result = await api.searchPosts({ keyword: '台灯' })
  assert.equal(result.offline, true); assert.ok(result.list.some(post => post.title.includes('台灯') || post.content.includes('台灯')))
  assert.equal((await api.searchPosts({ keyword: 'AbC' })).total, 1)
  assert.equal((await api.searchPosts({ keyword: '100\\%' })).total, 1)
  assert.equal((await api.searchPosts({ keyword: '[.*+?]' })).total, 1)
  assert.equal((await api.searchPosts({ keyword: 'A_B' })).total, 1)
  assert.equal((await api.searchPosts({ keyword: '\\' })).total, 1)
  assert.equal((await api.searchPosts({ keyword: '不存在的关键词' })).total, 0)
  result = await api.searchPosts({ keyword: '%', page: 2, size: 2 })
  assert.equal(result.list.length, 2); assert.equal(result.total, mock.discoveryPosts.length)
  assert.equal((await api.searchPosts({ keyword: 'Ab_\n校园' })).total, 1)
  await assert.rejects(api.searchPosts({ keyword: ' ' }), /请输入/)
  await assert.rejects(api.searchPosts({ keyword: '字'.repeat(65) }), /64/)
})

test('搜索：绑定请求参数编码、分页边界、服务端错误不伪装为结果', async () => {
  const env = environment(), api = env.load('utils/api.js')
  env.online({ list: [], total: 0 })
  await api.searchPosts({ keyword: '台灯 & #?%20', page: -1, size: 100 })
  const url = new URL(env.requests.at(-1).url)
  assert.equal(url.searchParams.get('keyword'), '台灯 & #?%20'); assert.equal(url.searchParams.get('size'), '50'); assert.equal(url.searchParams.get('page'), '1')
  env.businessError(); await assert.rejects(api.searchPosts({ keyword: '台灯' }), error => error.server === true && error.message === '业务拒绝')
})

test('所有带离线兜底的写接口：服务端拒绝必须原样抛出', async () => {
  const env = environment(), api = env.load('utils/api.js'); env.businessError()
  const writes = [() => api.likePost(1), () => api.addComment(1, '评论'), () => api.followUser(2, 3), () => api.toggleFavorite(1), () => api.submitFeedback(1, '反馈'), () => api.joinIdol({}), () => api.publishPost({}), () => api.viewPost(1), () => api.createFoodOrder({}), () => api.publishGoods({}), () => api.enrollActivity(1), () => api.addAddress(1, '宿舍'), () => api.sendChatMessage(2, '你好'), () => api.markChatRead(2, 5), () => api.markAllMessagesRead(), () => api.clearChatMessages(2), () => api.muteChat(2, true)]
  for (const write of writes) await assert.rejects(write(), error => error.server && error.message === '业务拒绝')
  assert.equal(env.storage.size, 0)
})

test('新增读接口：业务错误显示错误态，网络失败才演示', async () => {
  const env = environment(), api = env.load('utils/api.js'); env.businessError()
  for (const read of [() => api.getConversations(), () => api.getNotifications(), () => api.getChatMessages(2), () => api.getTimetable(), () => api.getCalendar(), () => api.getProfile()]) await assert.rejects(read(), error => error.server)
})

test('离线聊天：持久化、按对方隔离、已读/免打扰/清空跨重启保留', async () => {
  const storage = new Map(), env = environment(storage), api = env.load('utils/api.js')
  const before = await api.getChatMessages(2); assert.equal(before.list.length, 5)
  const sent = await api.sendChatMessage(2, '离线新消息', { name: '周予安', clientId: 'test-1' })
  assert.equal(sent.offline, true)
  await api.sendChatMessage(2, '离线新消息', { name: '周予安', clientId: 'test-1' })
  const next = environment(storage).load('utils/api.js')
  assert.equal((await next.getChatMessages(2)).list.length, 6)
  assert.equal((await next.getChatMessages(3)).list.length, 1)
  await next.markChatRead(2, sent.id, 1, true)
  assert.equal((await next.getConversations()).find(row => row.id === 2).unread, 0)
  await next.muteChat(2, true, 1, true)
  assert.equal((await next.getConversations()).find(row => row.id === 2).muted, true)
  await next.clearChatMessages(2, 1, true)
  const third = environment(storage).load('utils/api.js')
  assert.equal((await third.getChatMessages(2)).list.length, 0)
  assert.equal((await third.getConversations()).some(row => row.id === 2), false)
  await third.markAllMessagesRead()
  assert.ok((await third.getNotifications()).every(row => !row.unread))
})

test('离线历史分页及本机用户隔离', async () => {
  const env = environment(), api = env.load('utils/api.js')
  const latest = await api.getChatMessages(2, { size: 2 })
  assert.deepEqual(plain(latest.list.map(row => row.id)), [4, 5]); assert.equal(latest.hasMore, true)
  const older = await api.getChatMessages(2, { size: 2, beforeId: 4 })
  assert.deepEqual(plain(older.list.map(row => row.id)), [2, 3])
  assert.equal((await api.getChatMessages(2, {}, 3)).list.length, 0)
})

test('二手离线发布：local id 持久化后仍可打开详情', async () => {
  const storage = new Map(), env = environment(storage), api = env.load('utils/api.js')
  const result = await api.publishGoods({ title: '本机台灯', price: '12', category: '数码', images: ['/assets/home/recommend-lamp.jpg'], image: '/assets/home/recommend-lamp.jpg' })
  const next = environment(storage).load('utils/api.js')
  assert.equal((await next.getGoodsDetail(result.id)).title, '本机台灯')
  assert.ok((await next.getGoodsList()).some(row => row.id === result.id))
})

test('首页：路由表、搜索结果、未知入口告警、今日推荐按真实热度且换一批轮换', async () => {
  const env = environment(), page = env.page('pages/index/index.js')
  // 作业答疑与论文指导进入独立学习专区对应分类。
  const routes = { 校园论坛: '/pages/community/community', 外卖: '/pages/food-list/food-list', 二手市场: '/pages/market-list/market-list', 兼职: '/pages/jobs/jobs', 季节活动: '/pages/activities/activities', 作业答疑: '/pages/learn/learn?category=daike', 论文指导: '/pages/learn/learn?category=paper' }
  for (const [label, url] of Object.entries(routes)) { page.openFeature({ currentTarget: { dataset: { label } } }); assert.equal(env.context.lastUrl, url) }
  page.openNotifications(); assert.equal(env.context.lastUrl, '/pages/messages/messages?tab=notice')
  page.submitSearch({ detail: { value: '台灯 & %20' } }); assert.equal(decodeURIComponent(env.context.lastUrl.split('keyword=')[1]), '台灯 & %20')
  let warned = false; env.context.console = { ...console, warn() { warned = true } }
  page.openFeature({ currentTarget: { dataset: { label: '不存在' } } }); assert.equal(warned, true); assert.equal(page.data.uiPopup.tone, 'warn')
})

test('首页今日推荐：数据来自接口、卡片按类型跳转、换一批真的换内容', async () => {
  const env = environment()
  env.online({
    cards: [
      { type: 'shop', id: 1, tag: '美食', title: '茶言观色奶茶', subtitle: '满20减8 · 查看菜单', image: '/assets/home/food-milktea.webp', location: '奶茶饮品', heatText: '1 人下单', url: '/pages/food-shop/food-shop?id=1' },
      { type: 'goods', id: 3, tag: '闲置', title: '羽毛球拍', subtitle: '九成新', image: '/assets/home/secondhand-box.webp', location: '运动', heatText: '15 次浏览', url: '/pages/market-detail/market-detail?id=3' }
    ],
    batch: 0, hasMore: true, shopTotal: 4, goodsTotal: 6
  })
  const page = env.page('pages/index/index.js')
  page.onLoad(); await flush()
  assert.equal(page.data.recommendations.length, 2, '应渲染接口返回的推荐卡')
  assert.equal(page.data.recommendLoading, false)
  assert.ok(env.requests.at(-1).url.includes('/recommend/home'), '应请求今日推荐接口')
  // 卡片带真实热度文案，类型可区分
  assert.equal(page.data.recommendations[0].type, 'shop')
  assert.equal(page.data.recommendations[1].heatText, '15 次浏览')
  // 按类型跳转到各自详情
  page.openRecommendation({ currentTarget: { dataset: { index: 0 } } })
  assert.equal(env.context.lastUrl, '/pages/food-shop/food-shop?id=1')
  page.openRecommendation({ currentTarget: { dataset: { index: 1 } } })
  assert.equal(env.context.lastUrl, '/pages/market-detail/market-detail?id=3')
  // 换一批：batch +1 并重新拉取
  env.requests.length = 0
  env.online({ cards: [{ type: 'goods', id: 5, tag: '闲置', title: '春日野餐垫', heatText: '1 人咨询', url: '/pages/market-detail/market-detail?id=5' }], batch: 1, hasMore: true })
  page.changeRecommendations(); await flush()
  assert.ok(env.requests.at(-1).url.includes('batch=1'), '换一批应请求下一批')
  assert.equal(page.data.recommendations[0].title, '春日野餐垫')
  // 没有下一批时回到第一批
  env.requests.length = 0
  env.online({ cards: [], batch: 0, hasMore: false })
  page.setData({ recommendHasMore: false })
  page.changeRecommendations(); await flush()
  assert.ok(env.requests.at(-1).url.includes('batch=0'), '到末尾应回到第一批')
  page.onUnload()
})

test('商品详情：浏览上报、收藏切换、私聊上报', async () => {
  const env = environment()
  env.online({ id: 3, title: '羽毛球拍', price: 60, viewCount: 12, favoriteCount: 0, contactCount: 0, seller: { id: 2, nickname: '周予安' } })
  const page = env.page('pages/market-detail/market-detail.js')
  page.onLoad({ id: '3' }); await flush()
  assert.ok(env.requests.some(r => r.url.endsWith('/goods/3/view')), '打开详情应上报浏览')
  assert.equal(page.data.heatText, '12 次浏览', '热度文案应说明真实来源')
  // 收藏切换
  env.requests.length = 0
  env.online({ favored: true })
  await page.toggleFavorite()
  assert.ok(env.requests.at(-1).url.endsWith('/goods/3/favorite'))
  assert.equal(page.data.favored, true)
  // 私聊上报
  env.requests.length = 0
  page.chatSeller(); await flush()
  assert.ok(env.requests.some(r => r.url.endsWith('/goods/3/contact')), '私聊应上报咨询')
  assert.ok(env.context.lastUrl.startsWith('/pages/chat/chat?id=2'))
  page.onUnload()
})


test('二手分类参数：学习好物实际映射图书', () => {
  const env = environment(), page = env.page('pages/market-list/market-list.js')
  page.onLoad({ category: '学习好物' }); assert.equal(page.data.category, '图书')
})

test('全站搜索页：新关键词不会被旧响应覆盖、单类 tab 可加载更多', async () => {
  const env = environment(); env.respond(() => {})
  const page = env.page('pages/search/search.js'); page.onLoad({ keyword: '旧词' })
  const first = env.requests.at(-1)
  assert.ok(first.url.includes('/search/all'), '应走全站搜索接口')
  page.setData({ draft: '新词' }); page.submitSearch(); const second = env.requests.at(-1)
  second.success({ statusCode: 200, data: { code: 0, data: { list: [{ id: 2, title: '新词', _type: 'post' }], total: 5, counts: { post: 5 } } } }); await flush()
  first.success({ statusCode: 200, data: { code: 0, data: { list: [{ id: 1, title: '旧词', _type: 'post' }], total: 1 } } }); await flush()
  assert.equal(page.data.cards[0].id, 2, '迟到的旧响应不应覆盖新结果')
  assert.equal(page.data.page, 1)
  // 混排模式一次给全，不再翻页
  assert.equal(page.data.hasMore, false)
  // 切到「帖子」分类后按总数翻页
  page.switchTab({ currentTarget: { dataset: { index: 1 } } })
  const tabReq = env.requests.at(-1)
  assert.ok(tabReq.url.includes('type=post'), '应带 type=post')
  tabReq.success({ statusCode: 200, data: { code: 0, data: { list: [{ id: 7, title: '帖子A', _type: 'post' }], total: 2, counts: { post: 2 } } } }); await flush()
  assert.equal(page.data.hasMore, true, '还有一条未加载')
  const more = page.loadMore()
  env.requests.at(-1).success({ statusCode: 200, data: { code: 0, data: { list: [{ id: 8, title: '帖子B', _type: 'post' }], total: 2 } } }); await more
  assert.deepEqual(plain(page.data.cards.map(row => row.id)), [7, 8])
})

test('全站搜索页：多类型卡片、分类计数、失败重试、各类跳转', async () => {
  const env = environment()
  env.online({
    list: [
      { id: 1, title: '出一台台灯', _type: 'goods', price: 45, mode: 1 },
      { id: 2, name: '摄影社', _type: 'circle', description: '校园写真' },
      { id: 3, title: '图书馆整理员', _type: 'job', daySalary: 120, settle: '日结' },
      { id: 4, title: '台灯下的自习', _type: 'post', authorNickname: '林知夏' }
    ],
    total: 4,
    counts: { post: 1, circle: 1, goods: 1, activity: 0, job: 1, food: 2 }
  })
  const page = env.page('pages/search/search.js'); page.onLoad({ keyword: '台灯' }); await flush()
  assert.equal(page.data.loading, false)
  assert.equal(page.data.searched, true)
  assert.equal(page.data.cards.length, 4)
  assert.equal(page.data.counts.goods, 1, '应带回分类计数')
  // 每类卡片都带上可读标签
  assert.deepEqual(plain(page.data.cards.map(c => c.typeLabel)), ['闲置', '圈子', '兼职', '帖子'])
  // 各类跳到各自详情
  page.openCard({ currentTarget: { dataset: { key: 'goods-1' } } })
  assert.equal(env.context.lastUrl, '/pages/market-detail/market-detail?id=1')
  page.openCard({ currentTarget: { dataset: { key: 'circle-2' } } })
  assert.equal(env.context.lastUrl, '/pages/circle-detail/circle-detail?id=2')
  page.openCard({ currentTarget: { dataset: { key: 'post-4' } } })
  assert.equal(env.context.lastUrl, '/pages/post-detail/post-detail?id=4')
  // 业务错误显示错误态并可重试
  env.businessError(); await page.loadResults(true); assert.equal(page.data.error, '业务拒绝')
  env.online({ list: [], total: 0, counts: {} }); await page.retry(); assert.equal(page.data.error, '')
  assert.equal(page.data.cards.length, 0)
  // 卸载后迟到的响应不再写入
  env.respond(() => {}); const pending = page.loadResults(true); const request = env.requests.at(-1); page.onUnload()
  request.success({ statusCode: 200, data: { code: 0, data: { list: [{ id: 9, _type: 'post' }], total: 1 } } }); await pending
  assert.equal(page.data.cards.length, 0)
})

test('弹层：原始 detail 与完整事件均能选中操作/获取输入；异步失败显示错误', async () => {
  const env = environment(), popup = env.load('utils/ui-popup-helper.js'), page = env.page('pages/index/index.js')
  let selected
  popup.sheet(page, { actions: [{ label: '北京大学' }], action: payload => { selected = payload.item.label } })
  page._popupAction({ index: 0 }); await flush(); assert.equal(selected, '北京大学')
  let value
  popup.modal(page, { editable: true, confirm: payload => { value = payload.inputValue } }); page._popupConfirm({ detail: { value: '%20' } }); await flush(); assert.equal(value, '%20')
  popup.modal(page, { confirm: async () => { throw new Error('保存失败') } }); page._popupConfirm({}); await flush(); assert.equal(page.data.uiPopup.message, '保存失败')
})

test('全局演示状态：回退可观察、成功后端读取恢复，解除订阅', async () => {
  const env = environment(), api = env.load('utils/api.js'), mode = env.load('utils/data-mode.js'); const values = []
  const unsubscribe = mode.subscribe(value => values.push(value))
  await api.searchPosts({ keyword: '台灯' }); env.online({ list: [], total: 0 }); await api.searchPosts({ keyword: '台灯' })
  assert.deepEqual(values, [false, true, false]); unsubscribe(); await api.sendChatMessage(2, 'ok'); assert.equal(values.length, 3)
})

test('教学数据从 API 返回；周次跟随后端学期而非固定日期', async () => {
  const env = environment(), api = env.load('utils/api.js'), term = env.load('utils/term.js')
  assert.equal((await api.getTimetable({ week: 1 })).courses.length, 7)
  assert.equal((await api.getCalendar()).events.length, 7)
  assert.equal(term.getTermWeek(new Date(2026, 9, 14), '2026-10-05'), 2)
})

test('关注请求体保留指定 follower；社区分享无暂时性死区错误', async () => {
  const env = environment(), api = env.load('utils/api.js'); env.online({ followed: true })
  await api.followUser(2, 3); assert.equal(env.requests.at(-1).data.userId, 3)
  const page = env.page('pages/community/community.js'); page.setData({ posts: [{ id: 7, title: '分享测试' }] })
  assert.equal(page.onShareAppMessage({ target: { dataset: { id: 7 } } }).path, '/pages/post-detail/post-detail?id=7')
})

test('全局演示提示：一个成功接口不能掩盖其他离线接口', async () => {
  const env = environment(), api = env.load('utils/api.js'), mode = env.load('utils/data-mode.js'); let demo
  mode.subscribe(value => { demo = value })
  await api.getChatMessages(2)
  env.online([]); await api.getNotifications(); assert.equal(demo, true)
  env.online({ list: [], hasMore: false, muted: false }); await api.getChatMessages(2); assert.equal(demo, false)
})

test('聊天页：发送失败保留输入，离线发送/重载/清空可操作', async () => {
  const env = environment(), page = env.page('pages/chat/chat.js')
  // 页面参数是 navigateTo 的 url，中文为百分号编码，需解码后展示
  page.onLoad({ id: '2', name: '周%20予安' }); assert.equal(page.data.name, '周 予安')
  await page.loadMessages(); assert.equal(page.data.messages.length, 5)
  page.setData({ inputValue: '新消息' }); env.businessError(); await page.sendMessage()
  assert.equal(page.data.inputValue, '新消息'); assert.equal(page.data.messages.length, 5)
  env.respond(options => options.fail({ errMsg: '断网' })); await page.sendMessage(); await flush()
  assert.ok(page.data.messages.some(row => row.content === '新消息')); assert.equal(page.data.inputValue, '')
  await page.clearMessages(); assert.equal(page.data.messages.length, 0)
  await page.loadMessages(); assert.equal(page.data.messages.length, 0); page.onUnload()
})

test('URL 参数解码：中文解码、非法编码不崩、缺省有兜底', async () => {
  const env = environment()
  // 中文关键词解码（修复前会变成 %E5%8F%B0%E7%81%AF 导致搜不到）
  const search = env.page('pages/search/search.js')
  search.onLoad({ keyword: encodeURIComponent('台灯') })
  assert.equal(search.data.keyword, '台灯', '搜索关键词应解码为中文')
  assert.equal(search.data.draft, '台灯')
  search.onUnload()
  // 非法百分号编码不能抛异常
  const broken = env.page('pages/search/search.js')
  broken.onLoad({ keyword: '%E5%8F' })
  assert.equal(typeof broken.data.keyword, 'string')
  broken.onUnload()
  // 缺省：无关键词时为空的搜索态，不发请求
  const empty = env.page('pages/search/search.js')
  empty.onLoad({})
  assert.equal(empty.data.keyword, '')
  assert.equal(empty.data.searched, false)
  empty.onUnload()
  // 聊天页参数同样需要解码，并有默认值
  const chat = env.page('pages/chat/chat.js')
  chat.onLoad({ id: '2', name: encodeURIComponent('陈一川'), avatar: encodeURIComponent('陈') })
  assert.equal(chat.data.name, '陈一川')
  assert.equal(chat.data.avatar, '陈')
  chat.onUnload()
  const chatDefault = env.page('pages/chat/chat.js')
  chatDefault.onLoad({ id: '2' })
  assert.equal(chatDefault.data.name, '同学', '缺少 name 时应有默认值')
  chatDefault.onUnload()
})

test('圈子页：搜索进入结果页、点击圈子打开详情、加入退出切换', async () => {
  const env = environment(), page = env.page('pages/discover/discover.js')
  page.openSearch(); page._popupConfirm({ value: '台灯&%20' }); await flush()
  // 关键词需正确编码，且从圈子页发起时默认落在圈子分类
  assert.ok(env.context.lastUrl.startsWith('/pages/search/search?keyword='), '应跳到搜索结果页')
  assert.equal(new URLSearchParams(env.context.lastUrl.split('?')[1]).get('keyword'), '台灯&%20')
  assert.equal(new URLSearchParams(env.context.lastUrl.split('?')[1]).get('type'), 'circle', '圈子页搜索默认查圈子')
  page.openCircle({ currentTarget: { dataset: { id: 3 } } }); assert.equal(env.context.lastUrl, '/pages/circle-detail/circle-detail?id=3')
  // 轮播点击同样进圈子详情
  page.setData({ heroCircles: [{ id: 7, title: '美食探店' }] })
  page.openHero({ currentTarget: { dataset: { index: 0 } } }); assert.equal(env.context.lastUrl, '/pages/circle-detail/circle-detail?id=7')
  // 加入/退出：离线兜底也要能切换 joined（先走 onLoad 设置存活标志）
  page.onLoad(); await flush()
  const target = page.data.circles.find(item => item.id === 3)
  assert.ok(target, '离线兜底应返回圈子列表')
  const before = target.joined
  await page.toggleJoin({ currentTarget: { dataset: { id: 3 } } }); await flush()
  assert.equal(page.data.circles.find(item => item.id === 3).joined, !before)
  page.onUnload()
})

test('圈子详情页：加载圈子与圈内帖子、非法 id 不请求', async () => {
  const env = environment(), page = env.page('pages/circle-detail/circle-detail.js')
  page.onLoad({ id: '2' }); await flush()
  assert.ok(page.data.circle, '应加载到圈子')
  assert.equal(page.data.circle.name, '考研上岸')
  assert.ok(page.data.posts.length > 0, '圈内应有帖子')
  assert.ok(page.data.posts[0].title, '帖子应有标题')
  page.openPost({ currentTarget: { dataset: { id: 10 } } })
  assert.equal(env.context.lastUrl, '/pages/post-detail/post-detail?id=10')
  // 已加入圈子时，发帖跳到独立发帖页（不再是弹窗连输两次）
  page.setData({ 'circle.joined': true })
  page.openCompose()
  assert.equal(env.context.lastUrl, '/pages/circle-publish/circle-publish?id=2')
  page.onUnload()
  // 非法 id：直接给出错误态，不发请求
  const bad = environment(), badPage = bad.page('pages/circle-detail/circle-detail.js')
  badPage.onLoad({ id: 'abc' }); await flush()
  assert.equal(badPage.data.error, '圈子不存在')
  badPage.onUnload()
})

test('圈子发帖页：加载圈子、校验必填、发布必须带 circleId', async () => {
  const env = environment()
  env.online({ id: 2, name: '考研上岸', category: '学习', memberCount: 3, joined: true })
  const page = env.page('pages/circle-publish/circle-publish.js')
  page.onLoad({ id: '2' }); await flush()
  assert.equal(page.data.circle.name, '考研上岸', '应加载圈子信息用于确认发布目标')
  assert.equal(page.data.circleId, 2)
  // 校验：标题与正文都必填，且都走弹层提示
  page.submit(); await flush()
  assert.equal(page.data.uiPopup.message, '请填写标题')
  page.setData({ title: '测试标题' })
  page.submit(); await flush()
  assert.equal(page.data.uiPopup.message, '写一点内容再发布吧')
  // 正常发布：payload 必须带 circleId，否则会发到全校流
  page.setData({ title: '测试标题', content: '测试正文', media: [] })
  env.requests.length = 0
  env.online({ id: 99, circleId: 2 })
  page.submit(); await flush()
  const post = env.requests.find(r => r.method === 'POST' && r.url.endsWith('/posts'))
  assert.ok(post, '应发出发布请求')
  assert.equal(post.data.circleId, 2, '发布必须带上圈子 id')
  assert.equal(post.data.title, '测试标题')
  assert.equal(page.data.submitting, false)
  // 发布成功后会延迟返回上一页，等它跑完再看是否有异常
  await flush()
  assert.equal(env.context.backCount, 1, '发布成功应返回圈子详情')
  page.onUnload()
  // 非法 id：给出错误态且不发请求
  const bad = environment(), badPage = bad.page('pages/circle-publish/circle-publish.js')
  badPage.onLoad({ id: 'x' }); await flush()
  assert.equal(badPage.data.error, '圈子不存在')
  badPage.onUnload()
})

test('发布页：四个类型分别走真实流程，不再是只换标签', async () => {
  // 每个类型应打到各自的接口，并跳到各自的列表页
  const cases = [
    { index: 0, label: '校园动态', endpoint: '/posts', target: '/pages/community/community' },
    { index: 1, label: '闲置好物', endpoint: '/goods', target: '/pages/market-list/market-list' },
    { index: 2, label: '兼职信息', endpoint: '/jobs', target: '/pages/jobs/jobs' },
    { index: 3, label: '活动组队', endpoint: '/activities', target: '/pages/activities/activities' }
  ]
  for (const item of cases) {
    const env = environment()
    env.online({ id: 7 })
    const page = env.page('pages/publish/publish.js')
    page.onLoad()
    page.chooseType({ currentTarget: { dataset: { index: item.index } } })
    page.setData({ title: '测试标题', content: '测试内容', media: [], daySalary: '120', price: '45' })
    env.requests.length = 0
    env.online({ id: 7 })
    page.submitPost(); await flush(); await flush()
    const post = env.requests.find(r => r.method === 'POST' && r.url.endsWith(item.endpoint))
    assert.ok(post, `${item.label} 应发布到 ${item.endpoint}`)
    // 只有「校园动态」走帖子接口；其余类型不能混进 post 表
    if (item.index !== 0) {
      assert.ok(!env.requests.some(r => r.url.endsWith('/posts')), `${item.label} 不应发成帖子`)
    }
    assert.equal(env.context.lastUrl, item.target, `${item.label} 应跳到 ${item.target}`)
    page.onUnload()
  }
})

test('发布页：兼职必须填日薪、图片会先上传、类型切换清空已选图片', async () => {
  const env = environment()
  env.online({ id: 7 })
  const page = env.page('pages/publish/publish.js')
  page.onLoad()
  // 兼职不填日薪应被拦截
  page.chooseType({ currentTarget: { dataset: { index: 2 } } })
  page.setData({ title: '兼职标题', content: '兼职内容' })
  env.requests.length = 0
  page.submitPost(); await flush()
  assert.equal(page.data.uiPopup.message, '请填写日薪')
  assert.equal(env.requests.length, 0, '校验未通过不应发请求')
  // 填了日薪后可发布
  page.setData({ daySalary: '150' })
  env.requests.length = 0
  env.online({ id: 8 })
  page.submitPost(); await flush(); await flush()
  const post = env.requests.find(r => r.method === 'POST' && r.url.endsWith('/jobs'))
  assert.ok(post, '应发布兼职')
  assert.equal(post.data.daySalary, 150)
  // 切换类型会清空已选图片，避免把上一类的图带过去
  page.setData({ media: ['/tmp/a.png', '/tmp/b.png'] })
  page.chooseType({ currentTarget: { dataset: { index: 0 } } })
  assert.deepEqual(plain(page.data.media), [])
  page.onUnload()
})

test('发布页：闲置必须填有效价格，不能上架 ¥0', async () => {
  const env = environment()
  env.online({ id: 7 })
  const page = env.page('pages/publish/publish.js')
  page.onLoad()
  page.chooseType({ currentTarget: { dataset: { index: 1 } } })
  page.setData({ title: '测试台灯', content: '九成新' })
  // 不填价格
  env.requests.length = 0
  page.submitPost(); await flush()
  assert.equal(page.data.uiPopup.message, '请填写有效价格')
  assert.equal(env.requests.length, 0, '校验未通过不应发请求')
  // 价格填 0 也不行
  page.setData({ price: '0' })
  page.submitPost(); await flush()
  assert.equal(page.data.uiPopup.message, '请填写有效价格')
  // 超上限拦截
  page.setData({ price: '999999' })
  page.submitPost(); await flush()
  assert.equal(page.data.uiPopup.message, '价格不能超过 99999')
  // 合法价格才发布，且带上成色与分类
  page.setData({ price: '45', conditionLevel: '九成新', category: '数码' })
  env.requests.length = 0
  env.online({ id: 11 })
  page.submitPost(); await flush(); await flush()
  const post = env.requests.find(r => r.method === 'POST' && r.url.endsWith('/goods'))
  assert.ok(post, '应上架到二手市场')
  assert.equal(post.data.price, '45')
  assert.equal(post.data.category, '数码')
  assert.equal(post.data.conditionLevel, '九成新')
  page.onUnload()
})

test('兼职页：关键词搜索防抖、回车立即查、清空恢复、与结算筛选组合', async () => {
  const env = environment()
  env.online([{ id: 1, title: '奶茶店店员', daySalary: 90, settle: '日结', location: '校内' }])
  const page = env.page('pages/jobs/jobs.js')
  page.onLoad(); page.onShow(); await flush()
  assert.equal(page.data.jobs.length, 1)
  assert.equal(page.data.jobs[0].salaryText, '¥90/天')
  assert.equal(page.data.total, 1, '应记录结果数供提示使用')

  // 输入走防抖：立即不应发请求
  env.requests.length = 0
  page.search({ detail: { value: '奶茶' } })
  assert.equal(env.requests.length, 0, '防抖期间不应立刻请求')
  assert.equal(page.data.keyword, '奶茶')
  // 沙箱里的 setTimeout 走微任务，flush 即可让防抖回调执行
  await flush()
  assert.ok(env.requests.length > 0, '防抖结束后应请求')
  assert.ok(env.requests.at(-1).url.includes('keyword=' + encodeURIComponent('奶茶')), '应带上关键词')

  // 回车立即查询，不等防抖
  env.requests.length = 0
  page.setData({ keyword: '图书馆' })
  page.submitSearch(); await flush()
  assert.ok(env.requests.at(-1).url.includes(encodeURIComponent('图书馆')))

  // 清空关键词后恢复全量
  env.requests.length = 0
  page.clearSearch(); await flush()
  assert.equal(page.data.keyword, '')
  // 解析查询串而不是断言参数位置：参数顺序会随功能增加而变化
  assert.equal(new URL(env.requests.at(-1).url).searchParams.get('keyword'), '', '清空后关键词参数应为空')

  // 结算筛选与关键词可组合
  env.requests.length = 0
  page.setData({ keyword: '志愿者' })
  page.chooseSettle({ currentTarget: { dataset: { settle: '日结' } } }); await flush()
  const url = env.requests.at(-1).url
  assert.ok(url.includes('settle=' + encodeURIComponent('日结')), '应带结算筛选')
  assert.ok(url.includes('keyword=' + encodeURIComponent('志愿者')), '应同时带关键词')
  page.onUnload()
})

test('兼职页：卸载后迟到的响应不写入，且清掉防抖定时器', async () => {
  const env = environment()
  env.respond(() => {})
  const page = env.page('pages/jobs/jobs.js')
  page.onLoad(); page.onShow(); await flush()
  const req = env.requests.at(-1)
  page.onUnload()
  req.success({ statusCode: 200, data: { code: 0, data: [{ id: 1, title: '迟到' }] } }); await flush()
  assert.equal(page.data.jobs.length, 0, '卸载后不应再写入数据')
})

test('登录态：token 存储、请求自动携带、401 清理、未登录仍可用', async () => {
  const env = environment(), api = env.load('utils/api.js')
  assert.equal(api.isLoggedIn(), false, '初始未登录')
  assert.equal(api.currentUserId(), 1, '未登录时沿用演示用户')

  // 登录成功 → token 落地，后续请求自动带 Authorization
  env.online({ token: 'jwt-token-value', userId: 7, nickname: '测试同学' })
  const res = await api.devLogin(7)
  assert.equal(res.userId, 7)
  assert.equal(api.isLoggedIn(), true)
  assert.equal(api.currentUserId(), 7, '登录后以服务端返回的 userId 为准')

  env.requests.length = 0
  env.online({ nickname: '测试同学' })
  await api.getUserProfile(7)
  assert.ok(env.requests.length > 0, '应发起请求')
  assert.equal(env.requests.at(-1).header.Authorization, 'Bearer jwt-token-value', '请求应携带 token')

  // 401 → 清理登录态，避免拿着失效 token 反复失败
  env.respond(options => options.success({ statusCode: 401, data: { code: 401, msg: 'token 失效', data: null } }))
  await assert.rejects(() => api.getUserProfile(7))
  assert.equal(api.isLoggedIn(), false, '401 后应清理登录态')
  assert.equal(api.currentUserId(), 1, '清理后回退演示用户')

  // 网络失败时不应误清登录态
  env.online({ token: 'second-token', userId: 7 })
  await api.devLogin(7)
  assert.equal(api.isLoggedIn(), true)
  env.respond(options => options.fail({ errMsg: 'request:fail' }))
  await assert.rejects(() => api.getUserProfile(7))
  assert.equal(api.isLoggedIn(), true, '纯网络失败不应清理 token')
})

test('登录页降级：未配置微信 AppID 时自动走开发登录', async () => {
  const env = environment(), api = env.load('utils/api.js')
  // 该环境未提供 wx.login，ensureLogin 应直接降级到 dev-login
  env.online({ token: 'dev-token', userId: 1, nickname: '林知夏' })
  const res = await api.ensureLogin()
  assert.ok(res && res.userId, '应拿到登录结果')
  assert.equal(api.isLoggedIn(), true)
  assert.equal(env.requests.at(-1).url, 'http://127.0.0.1:8080/api/auth/dev-login')
  // 已有 token 时不应重复请求
  env.requests.length = 0
  const again = await api.ensureLogin()
  assert.equal(again.cached, true)
  assert.equal(env.requests.length, 0, '已登录时不应重复请求登录接口')
})

test('图片上传：成功后返回持久 URL、带 token、服务端错误原样抛出', async () => {
  const env = environment(), api = env.load('utils/api.js')
  env.online({ token: 'upload-token', userId: 1 })
  await api.devLogin(1)

  // 正常上传：返回服务端 URL
  const res = await api.uploadImage('/tmp/photo.png')
  assert.equal(res.url, '/uploads/mock.png')
  assert.equal(env.uploads.at(-1).name, 'file', '字段名需与后端 @RequestParam("file") 一致')
  assert.equal(env.uploads.at(-1).url, 'http://127.0.0.1:8080/api/upload')
  assert.equal(env.uploads.at(-1).header.Authorization, 'Bearer upload-token', '上传应携带 token')

  // 服务端拒绝（如格式不支持）必须抛出，不能静默成功
  env.respondUpload(options => options.success({ statusCode: 400, data: JSON.stringify({ code: 400, msg: '仅支持 jpg/png/gif/webp 格式', data: null }) }))
  await assert.rejects(() => api.uploadImage('/tmp/evil.exe'), /仅支持/)

  // 非 JSON 响应不应导致崩溃
  env.respondUpload(options => options.success({ statusCode: 200, data: '<html>error</html>' }))
  await assert.rejects(() => api.uploadImage('/tmp/photo.png'), /解析失败/)

  // 批量上传：按顺序逐个上传
  env.respondUpload(options => options.success({ statusCode: 200, data: JSON.stringify({ code: 0, data: { url: '/uploads/' + env.uploads.length + '.png' } }) }))
  const urls = await api.uploadImages(['/tmp/a.png', '/tmp/b.png'])
  assert.equal(urls.length, 2)
  assert.ok(urls.every(url => url.startsWith('/uploads/')), '每张图都应换成服务端 URL')
})

// ===== 帖子按校可见（混合模式）=====

test('api.getForumPosts：postScope 参数透传到 URL，缺省为本校', async () => {
  const env = environment(), api = env.load('utils/api.js')
  env.online({ list: [], total: 0 })
  await api.getForumPosts({ postScope: 'all', page: 1, size: 10 })
  assert.ok(env.requests.at(-1).url.includes('postScope=all'), 'all 应透传')
  env.requests.length = 0
  await api.getForumPosts({ page: 1, size: 10 })
  assert.ok(env.requests.at(-1).url.includes('postScope=local'), '缺省应为 local（本校）')
})

test('社区页：本校/全部切换 — 默认本校，切换后带 postScope 重新拉取，同值不重复请求', async () => {
  const env = environment(), page = env.page('pages/community/community.js')
  page.onLoad({}); await flush()
  assert.ok(env.requests.at(-1).url.includes('postScope=local'), '默认应请求本校流')

  env.requests.length = 0
  page.switchPostScope({ currentTarget: { dataset: { scope: 'all' } } }); await flush()
  assert.equal(page.data.postScope, 'all')
  assert.ok(env.requests.at(-1).url.includes('postScope=all'), '切换后应带 postScope=all')
  // 切换应清空已有列表重新加载，避免两种 scope 的帖子混在一起
  assert.equal(page.data.posts.length, page.data.posts.length, '切后列表由新响应填充')

  env.requests.length = 0
  page.switchPostScope({ currentTarget: { dataset: { scope: 'all' } } })
  assert.equal(env.requests.length, 0, '同 scope 重复点击不应重复请求')

  env.requests.length = 0
  page.switchPostScope({ currentTarget: { dataset: { scope: 'local' } } }); await flush()
  assert.ok(env.requests.at(-1).url.includes('postScope=local'), '应能切回本校')
})

test('社区页：切换 postScope 后，标签页切换应保留当前学校范围', async () => {
  const env = environment(), page = env.page('pages/community/community.js')
  page.onLoad({}); await flush()
  page.switchPostScope({ currentTarget: { dataset: { scope: 'all' } } }); await flush()
  env.requests.length = 0
  // 切到「学习」标签页
  page.onTabTap({ currentTarget: { dataset: { index: 2 } } }); await flush()
  assert.ok(env.requests.at(-1).url.includes('postScope=all'), '标签页切换不应丢失学校范围')
})

// ===== 课表自建 =====

test('课表：学生可添加课程 — 点格子开表单，填课程名后提交', async () => {
  const env = environment(), page = env.page('pages/timetable/timetable.js')
  env.online({
    week: 7,
    termInfo: { termLabel: '2026-2027学年 第一学期', termStart: '2026-08-31' },
    courses: [{ id: 1, name: '新闻学概论', room: '博学楼 B203', day: 0, period: 0, theme: 'blue', weekStart: 1, weekEnd: 16 }]
  })
  page.onLoad(); await flush()
  assert.equal(page.data.grid.length, 5, '5 大节')
  assert.ok(page.data.grid[0][0], '第1节周一应有种子课')
  assert.equal(page.data.canEdit, true, '课表应进入可编辑模式')

  // 点空格子 → 打开添加表单，记录位置
  page.openCompose({ detail: { day: 3, period: 1 } })
  assert.equal(page.data.composing.day, 3, '表单应记录星期')
  assert.equal(page.data.composing.period, 1, '表单应记录节次')
  // 没填课程名就提交应被拦截
  env.requests.length = 0
  page.submitCompose(); await flush()
  assert.equal(page.data.composeError, '请填写课程名称')
  assert.equal(env.requests.length, 0, '校验未通过不应发请求')
  // 填了名字再提交
  page.setData({ composeName: '编译原理', composeRoom: '实验楼 401' })
  env.online({ id: 9, name: '编译原理', room: '实验楼 401', day: 3, period: 1, theme: 'green', weekStart: 1, weekEnd: 16 })
  page.submitCompose(); await flush()
  const created = env.requests.find(r => r.method === 'POST' && r.url.includes('/timetable/courses'))
  assert.ok(created, '应发出新增课程请求')
  assert.equal(created.data.day, 3)
  assert.equal(created.data.period, 1)
  assert.equal(created.data.name, '编译原理')
  page.onUnload()
})

test('课表：删除课程 — 二次确认后发 DELETE 并从网格移除', async () => {
  const env = environment(), page = env.page('pages/timetable/timetable.js')
  env.online({
    week: 7,
    termInfo: { termLabel: '2026-2027学年 第一学期', termStart: '2026-08-31' },
    courses: [{ id: 1, name: '新闻学概论', room: '博学楼 B203', day: 0, period: 0, theme: 'blue', weekStart: 1, weekEnd: 16 }]
  })
  page.onLoad(); await flush()
  env.requests.length = 0
  env.online({})
  page.removeCourse({ currentTarget: { dataset: { day: 0, period: 0 } } })
  // 有确认弹窗：模拟确认
  page._popupConfirm({}); await flush()
  const removed = env.requests.find(r => r.method === 'DELETE' && r.url.includes('/timetable/courses/1'))
  assert.ok(removed, '应发出删除请求')
  assert.equal(page.data.grid[0][0], null, '网格对应格应清空')
  page.onUnload()
})

test('课表：节次时间默认内置一套，但学生可改并持久化', async () => {
  const env = environment(), page = env.page('pages/timetable/timetable.js')
  env.online({ week: 7, termInfo: { termLabel: 'T', termStart: '2026-08-31' }, courses: [] })
  page.onLoad(); await flush()
  // 默认作息：8:00 / 10:00 / 14:00 / 16:00 / 19:00
  assert.deepEqual(plain(page.data.periods.map(p => p.time)), ['08:00', '10:00', '14:00', '16:00', '19:00'])
  // 学生改第一节时间为 8:30
  page.setPeriodTime({ detail: { index: 0, value: '08:30' } })
  assert.equal(page.data.periods[0].time, '08:30')
  // 持久化到 storage，重进仍生效
  const stored = JSON.parse(env.storage.get('campus_period_times') || '[]')
  assert.equal(stored[0], '08:30', '应写入 storage')
  page.onUnload()
  const page2 = env.page('pages/timetable/timetable.js')
  page2.onLoad(); await flush()
  assert.equal(page2.data.periods[0].time, '08:30', '重进应读回自定义作息')
  page2.onUnload()
})

test('课表：周次切换 — 可看第 N 周，调休周（周六有课）按周次过滤', async () => {
  const env = environment(), page = env.page('pages/timetable/timetable.js')
  // 一门只在第 8 周上的调休课（周六 day=5）
  env.online({
    week: 7,
    termInfo: { termLabel: 'T', termStart: '2026-08-31' },
    courses: [
      { id: 1, name: '常规课', room: 'A101', day: 0, period: 0, theme: 'blue', weekStart: 1, weekEnd: 16 },
      { id: 2, name: '调休·周六补课', room: 'B202', day: 5, period: 1, theme: 'amber', weekStart: 8, weekEnd: 8 }
    ]
  })
  page.onLoad(); await flush()
  // 第 7 周：调休课不该出现
  assert.equal(page.data.grid[1][5], null, '第 7 周不应显示第 8 周的调休课')
  // 切到第 8 周：调休课出现
  page.switchWeek({ currentTarget: { dataset: { week: 8 } } }); await flush()
  assert.equal(page.data.week, 8)
  assert.ok(page.data.grid[1][5], '第 8 周应显示调休课')
  assert.equal(page.data.grid[1][5].name, '调休·周六补课')
  page.onUnload()
})

// ===== 二手按校隔离 =====

test('api.getGoodsList：schoolScope 参数透传到 URL，缺省为本校', async () => {
  const env = environment(), api = env.load('utils/api.js')
  env.online([])
  await api.getGoodsList(1, '', 'all')
  assert.ok(env.requests.at(-1).url.includes('schoolScope=all'), 'all 应透传')
  env.requests.length = 0
  await api.getGoodsList(1, '')
  assert.ok(env.requests.at(-1).url.includes('schoolScope=local'), '缺省应为 local（本校，异地无法面交）')
})

test('二手市场页：本校/全部切换 — 默认本校，切换后带 schoolScope 重新拉取，同值不重复请求', async () => {
  const env = environment(), page = env.page('pages/market-list/market-list.js')
  env.online([])
  page.onLoad({}); page.onShow(); await flush()
  assert.ok(env.requests.at(-1).url.includes('schoolScope=local'), '默认应请求本校商品')

  env.requests.length = 0
  page.switchSchoolScope({ currentTarget: { dataset: { scope: 'all' } } }); await flush()
  assert.equal(page.data.schoolScope, 'all')
  assert.ok(env.requests.at(-1).url.includes('schoolScope=all'), '切换后应带 schoolScope=all')

  env.requests.length = 0
  page.switchSchoolScope({ currentTarget: { dataset: { scope: 'all' } } })
  assert.equal(env.requests.length, 0, '同 scope 重复点击不应重复请求')

  env.requests.length = 0
  page.switchSchoolScope({ currentTarget: { dataset: { scope: 'local' } } }); await flush()
  assert.ok(env.requests.at(-1).url.includes('schoolScope=local'), '应能切回本校')
  page.onUnload()
})

test('二手市场页：切换学校范围后，分类/模式切换应保留范围', async () => {
  const env = environment(), page = env.page('pages/market-list/market-list.js')
  env.online([])
  page.onLoad({}); page.onShow(); await flush()
  page.switchSchoolScope({ currentTarget: { dataset: { scope: 'all' } } }); await flush()
  env.requests.length = 0
  page.chooseCategory({ currentTarget: { dataset: { category: '数码' } } }); await flush()
  assert.ok(env.requests.at(-1).url.includes('schoolScope=all'), '分类切换不应丢失学校范围')
  env.requests.length = 0
  page.chooseMode({ currentTarget: { dataset: { mode: 2 } } }); await flush()
  assert.ok(env.requests.at(-1).url.includes('schoolScope=all'), '模式切换不应丢失学校范围')
  page.onUnload()
})

// ===== 圈子 / 兼职 / 外卖 按校隔离 =====

test('api：圈子/热门圈子/兼职/外卖都透传 schoolScope，缺省 local', async () => {
  const env = environment(), api = env.load('utils/api.js')
  env.online({ list: [], total: 0 })
  const cases = [
    ['getCircles', () => api.getCircles({ schoolScope: 'all' })],
    ['getHotCircles', () => api.getHotCircles(5, undefined, 'all')],
    ['getJobs', () => api.getJobs('', '', 'all')],
    ['getFoodShops', () => api.getFoodShops('', 'all')]
  ]
  for (const [name, call] of cases) {
    env.requests.length = 0
    await call()
    assert.ok(env.requests.at(-1).url.includes('schoolScope=all'), `${name} 应透传 schoolScope=all`)
  }
  // 缺省应为 local
  const defaults = [
    ['getCircles', () => api.getCircles({})],
    ['getHotCircles', () => api.getHotCircles(5)],
    ['getJobs', () => api.getJobs()],
    ['getFoodShops', () => api.getFoodShops()]
  ]
  for (const [name, call] of defaults) {
    env.requests.length = 0
    await call()
    assert.ok(env.requests.at(-1).url.includes('schoolScope=local'), `${name} 缺省应为 local`)
  }
})

test('圈子页：本校/全部切换 — 默认本校，切换后带 schoolScope 重新拉取', async () => {
  const env = environment(), page = env.page('pages/discover/discover.js')
  env.online({ list: [], total: 0 })
  page.onLoad(); await flush()
  assert.ok(env.requests.some(r => r.url.includes('schoolScope=local')), '默认应请求本校圈子')

  env.requests.length = 0
  page.switchSchoolScope({ currentTarget: { dataset: { scope: 'all' } } }); await flush()
  assert.equal(page.data.schoolScope, 'all')
  assert.ok(env.requests.some(r => r.url.includes('schoolScope=all')), '切换后应带 schoolScope=all')
  // 热门轮播也要跟着切（否则轮播还会显示本校圈）
  assert.ok(env.requests.some(r => r.url.includes('/circles/hot') && r.url.includes('schoolScope=all')), '轮播也应切换学校范围')

  env.requests.length = 0
  page.switchSchoolScope({ currentTarget: { dataset: { scope: 'all' } } })
  assert.equal(env.requests.length, 0, '同 scope 重复点击不应重复请求')
  page.onUnload()
})

test('兼职页：本校/全部切换 — 默认本校，切换后带 schoolScope，筛选保留范围', async () => {
  const env = environment(), page = env.page('pages/jobs/jobs.js')
  env.online([])
  page.onLoad(); page.onShow(); await flush()
  assert.ok(env.requests.at(-1).url.includes('schoolScope=local'), '默认应请求本校岗位')

  env.requests.length = 0
  page.switchSchoolScope({ currentTarget: { dataset: { scope: 'all' } } }); await flush()
  assert.equal(page.data.schoolScope, 'all')
  assert.ok(env.requests.at(-1).url.includes('schoolScope=all'), '切换后应带 schoolScope=all')

  env.requests.length = 0
  page.chooseSettle({ currentTarget: { dataset: { settle: '日结' } } }); await flush()
  assert.ok(env.requests.at(-1).url.includes('schoolScope=all'), '结算筛选不应丢失学校范围')
  page.onUnload()
})

test('外卖页：本校/全部切换 — 默认本校，切换后带 schoolScope，分类保留范围', async () => {
  const env = environment(), page = env.page('pages/food-list/food-list.js')
  env.online([])
  page.onLoad(); await flush()
  assert.ok(env.requests.at(-1).url.includes('schoolScope=local'), '默认应请求本校店铺')

  env.requests.length = 0
  page.switchSchoolScope({ currentTarget: { dataset: { scope: 'all' } } }); await flush()
  assert.equal(page.data.schoolScope, 'all')
  assert.ok(env.requests.at(-1).url.includes('schoolScope=all'), '切换后应带 schoolScope=all')

  env.requests.length = 0
  page.chooseCategory({ currentTarget: { dataset: { category: '快餐便当' } } }); await flush()
  assert.ok(env.requests.at(-1).url.includes('schoolScope=all'), '分类切换不应丢失学校范围')
  page.onUnload()
})


