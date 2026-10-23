const mock = require('./mock-data')
const dataMode = require('./data-mode')

const BASE_URL = 'http://127.0.0.1:8080/api'
const CURRENT_USER_ID = 1
const TOKEN_KEY = 'campus_auth_token'
const USER_KEY = 'campus_auth_user'

// ---- 登录态：token 存本地，请求自动携带；未登录时沿用 CURRENT_USER_ID 的演示行为 ----
function getToken() {
  try { return wx.getStorageSync(TOKEN_KEY) || '' } catch (error) { return '' }
}
function setAuth(token, user) {
  try {
    if (token) wx.setStorageSync(TOKEN_KEY, token)
    if (user) wx.setStorageSync(USER_KEY, user)
  } catch (error) {}
}
function clearAuth() {
  try { wx.removeStorageSync(TOKEN_KEY); wx.removeStorageSync(USER_KEY) } catch (error) {}
}
function currentUserId() {
  try {
    const user = wx.getStorageSync(USER_KEY)
    if (user && user.userId) return user.userId
  } catch (error) {}
  return CURRENT_USER_ID
}
function isLoggedIn() { return !!getToken() }

function clone(data) { return JSON.parse(JSON.stringify(data)) }
function wait(data, delay = 160) { dataMode.setDemo(true); return new Promise(resolve => setTimeout(() => resolve(clone(data)), delay)) }
function request(url, method = 'GET', data = {}, options = {}) {
  return new Promise((resolve, reject) => {
    const header = { 'content-type': 'application/json' }
    const token = getToken()
    if (token) header.Authorization = `Bearer ${token}`
    wx.request({
      url: `${BASE_URL}${url}`,
      method,
      data,
      timeout: options.timeout || 5000,
      header,
      success(res) {
        const body = res.data || {}
        if (res.statusCode >= 200 && res.statusCode < 300 && body.code === 0) { dataMode.connection(url, true); resolve(body.data) }
        else {
          dataMode.connection(url, true)
          // 401/403 时清理本地登录态，避免拿着失效 token 反复失败
          if (res.statusCode === 401 || res.statusCode === 403) clearAuth()
          reject(Object.assign(new Error(body.msg || `请求失败（${res.statusCode}）`), { server: true, status: res.statusCode }))
        }
      },
      fail(error) { dataMode.connection(url, false); reject(error) }
    })
  })
}

// ---- 登录接口 ----
function authStatus() {
  return request('/auth/status', 'GET', {}, { timeout: 3000 }).catch(() => ({ devLogin: false, offline: true }))
}
function loginWithWxCode(code) {
  return request('/auth/login', 'POST', { code }).then(res => { setAuth(res.token, res); return res })
}
// 开发登录：未配置微信 AppID 时使用，保证 touristappid 下也能拿到 token
function devLogin(userId = CURRENT_USER_ID) {
  return request('/auth/dev-login', 'POST', { userId }).then(res => { setAuth(res.token, res); return res })
}
// 登录包装：优先微信登录，失败时在开启开发登录的环境下自动降级
function ensureLogin() {
  if (isLoggedIn()) return Promise.resolve({ userId: currentUserId(), cached: true })
  return new Promise((resolve) => {
    if (typeof wx.login !== 'function') return resolve(devLogin().catch(() => null))
    wx.login({
      success: (res) => {
        if (!res || !res.code) return resolve(devLogin().catch(() => null))
        loginWithWxCode(res.code).catch(() => devLogin().catch(() => null)).then(resolve)
      },
      fail: () => { devLogin().catch(() => null).then(resolve) }
    })
  })
}

function localPosts() {
  try { return wx.getStorageSync('mock_posts') || [] } catch (error) { return [] }
}
// 本机发布的帖子并入论坛流（作者固定为当前用户），保证 发布 → 社区 → 详情 链路可见
function localForumPosts() {
  return localPosts().map(p => ({
    id: p.id, authorId: CURRENT_USER_ID, authorNickname: '林知夏', authorAvatar: '/assets/social/profile-avatar.webp',
    title: p.title, content: p.content, image: p.media && p.media[0] ? p.media[0] : '',
    tag: p.typeLabel || '校园日常', location: p.location || '',
    likeCount: p.likeCount || 0, commentCount: p.commentCount || 0, liked: false, followed: true,
    createdAt: '刚刚'
  }))
}
function mockForum(tag, page, size, scope) {
  const all = localForumPosts().concat((mock.discoveryPosts || []).map(p => ({
    id: p.id, authorId: p.authorId || p.id, authorNickname: p.author || '校园同学', authorAvatar: p.avatar || '', title: p.title,
    content: p.content, image: p.image || '', tag: p.tag || '校园日常', location: p.location || '',
    likeCount: p.likes || 0, commentCount: p.comments || 0, liked: !!p.liked, followed: !!p.followed,
    createdAt: p.time || '刚刚'
  }))).filter(p => {
    if (scope === 'followed') return !!p.followed
    return !tag || tag === '推荐' || p.tag.includes(tag) || (tag === '生活' && p.tag === '校园日常') || (tag === '社团' && p.tag === '社团活动') || (tag === '二手' && p.tag === '校园市集')
  })
  const start=(page-1)*size
  return { list: all.slice(start,start+size), total: all.length }
}


// AI 欢迎语 + 常驻关键词（公开接口，管理端可改）
function getAiWelcome() {
  return request('/ai/welcome', 'GET').catch(error => {
    if (error.server) throw error
    return wait(null, 80)
  })
}
// AI 陪聊（关键词匹配）：读接口，后端挂了回落本地话术，保证永远有回应
const AI_FALLBACK_REPLY = '网络好像不太好，我先记住你的话了。等连上了再聊。'
function aiChat(message, options = {}) {
  const body = {
    userId: options.userId || CURRENT_USER_ID,
    schoolId: options.schoolId || null,
    message: String(message || '')
  }
  return request('/ai/chat', 'POST', body).catch(error => {
    if (error.server) throw error
    return wait({
      reply: AI_FALLBACK_REPLY,
      quickReplies: ['今天吃什么', '有什么活动', '陪我聊聊'],
      matched: false,
      category: 'offline',
      offline: true
    }, 120)
  })
}
// 学校地点（公开接口，小程序发帖选地点用）
function getSchoolLocations(schoolId) {
  if (!schoolId) return Promise.resolve([])
  return request(`/schools/${encodeURIComponent(schoolId)}/locations`, 'GET').catch(error => {
    if (error.server) throw error
    return wait([], 80)
  })
}
// 学校列表（公开接口，小程序选学校用）：只返回启用中的学校
function getSchools() {
  return request('/schools', 'GET').catch(error => {
    if (error.server) throw error
    return wait(null, 80)
  })
}
function getForumPosts(params={}) {
  // postScope：帖子按校可见范围。local=本校+跨校帖（默认），all=全部学校
  // schoolId：小程序里选中的学校，优先级高于账号所属学校
  const {tag='', scope='', postScope='local', page=1, size=10, userId=CURRENT_USER_ID, schoolId=null} = params
  const school = schoolId ? `&schoolId=${encodeURIComponent(schoolId)}` : ''
  return request(`/posts?tag=${encodeURIComponent(tag)}&scope=${encodeURIComponent(scope)}&postScope=${encodeURIComponent(postScope)}&userId=${userId}&page=${page}&size=${size}${school}`, 'GET')
    .catch(() => wait({...mockForum(tag,page,size,scope), offline:true}))
}
// LIKE %keyword%：与 MySQL 一致支持 % / _ 通配符和反斜线转义；标题与正文分别匹配。
function keywordMatcher(keyword) {
  let pattern = '^'
  const text = '%' + keyword + '%'
  const escape = char => char.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  for (let i = 0; i < text.length; i++) {
    const char = text[i]
    if (char === '\\' && i + 1 < text.length) pattern += escape(text[++i])
    else if (char === '%') pattern += '[\\s\\S]*'
    else if (char === '_') pattern += '[\\s\\S]'
    else pattern += escape(char)
  }
  return new RegExp(pattern + '$', 'iu')
}
// 全站搜索：帖子/圈子/商品/活动/兼职/外卖。type='all' 时混排并返回各类计数。
const SEARCH_TYPES = ['all', 'post', 'circle', 'goods', 'activity', 'job', 'food']
function searchAll(params = {}) {
  const keyword = String(params.keyword || '').trim()
  if (!keyword || keyword.length > 64) return Promise.reject(new Error(!keyword ? '请输入搜索关键词' : '关键词不能超过64字'))
  const type = SEARCH_TYPES.includes(params.type) ? params.type : 'all'
  const page = Math.max(1, Math.floor(Number(params.page) || 1))
  const size = Math.min(50, Math.max(1, Math.floor(Number(params.size) || 10)))
  const userId = params.userId || CURRENT_USER_ID
  return request(`/search/all?keyword=${encodeURIComponent(keyword)}&type=${type}&page=${page}&size=${size}&userId=${userId}`, 'GET')
    .catch(error => {
      if (error.server) throw error
      return wait(localSearchAll(keyword, type, page, size))
    })
}
// 离线兜底：在后端不可用时仍能按分类给出结果，语义与 keywordMatcher 一致
function localSearchAll(keyword, type, page, size) {
  const matcher = keywordMatcher(keyword)
  const hit = (text) => matcher.test(String(text == null ? '' : text))
  const buckets = {
    post: (mockForum('', 1, Number.MAX_SAFE_INTEGER, '').list || []).filter(row => hit(row.title) || hit(row.content)),
    circle: (mock.circles || []).filter(row => hit(row.name) || hit(row.description) || hit(row.category)),
    goods: (mock.goods || []).filter(row => hit(row.title) || hit(row.description) || hit(row.category)),
    activity: (mock.activities || []).filter(row => hit(row.title) || hit(row.location)),
    job: (mock.jobs || []).filter(row => hit(row.title) || hit(row.location) || hit(row.status)),
    food: []
      .concat((mock.foodShops || []).filter(row => hit(row.name) || hit(row.category)).map(row => ({ ...row, _type: 'shop' })))
      .concat(
        Object.keys(mock.foodMenus || {}).reduce((acc, shopId) => acc.concat(
          (mock.foodMenus[shopId] || []).reduce((inner, group) => inner.concat(
            (group.goods || []).filter(row => hit(row.name) || hit(group.category)).map(row => ({ ...row, shopId: Number(shopId), _type: 'shopGoods' }))
          ), [])
        ), [])
      )
  }
  const counts = {}
  for (const key of ['post', 'circle', 'goods', 'activity', 'job', 'food']) counts[key] = buckets[key].length
  const list = type === 'all'
    ? ['post', 'circle', 'goods', 'activity', 'job', 'food'].reduce((acc, key) => acc.concat(buckets[key].slice(0, Math.max(2, size / 3)).map(row => ({ ...row, _type: key }))), [])
    : buckets[type].slice((page - 1) * size, page * size).map(row => ({ ...row, _type: row._type || type }))
  const total = type === 'all' ? Object.values(counts).reduce((a, b) => a + b, 0) : counts[type] || 0
  return { keyword, type, counts, list, total, hasMore: type !== 'all' && page * size < total, offline: true }
}
function searchPosts(params = {}) {
  const keyword = String(params.keyword || '').trim()
  if (!keyword || keyword.length > 64) return Promise.reject(new Error(!keyword ? '请输入搜索关键词' : '关键词不能超过64字'))
  const page = Math.max(1, Math.floor(Number(params.page) || 1))
  const size = Math.min(50, Math.max(1, Math.floor(Number(params.size) || 10)))
  const userId = params.userId || CURRENT_USER_ID
  return request(`/search?keyword=${encodeURIComponent(keyword)}&page=${page}&size=${size}&userId=${userId}`, 'GET').catch(error => {
    if (error.server) throw error
    const matcher = keywordMatcher(keyword)
    const all = mockForum('', 1, Number.MAX_SAFE_INTEGER, '').list.filter(post => matcher.test(post.title || '') || matcher.test(post.content || ''))
    return wait({ list: all.slice((page - 1) * size, page * size), total: all.length, offline: true })
  })
}
// 单帖详情：优先走后端 /posts/{id}，失败时退化为在列表里查找
function getPost(id, userId=CURRENT_USER_ID) {
  return request(`/posts/${id}?userId=${userId}`, 'GET').then(p => {
    if (!p) throw new Error('帖子不存在')
    return p
  }).catch(() =>
    getForumPosts({ page:1, size:50, userId }).then(res => {
      const found = (res.list || []).find(x => String(x.id) === String(id))
      if (!found) throw new Error('帖子不存在')
      return { ...found, offline: !!res.offline }
    })
  )
}
function likePost(id, userId=CURRENT_USER_ID) { return request(`/posts/${id}/like`, 'POST', {userId}).catch(error => { throw error }) }
function mockComments(id) {
  const pool = [
    { authorNickname: '林知夏', content: '求清单！正好考前复习用 🙋' },
    { authorNickname: '陈嘉树', content: '已留言，谢谢楼主整理' },
    { authorNickname: '苏晚', content: '收藏了，回头来取资料' }
  ]
  return pool.map((c, i) => ({ id: `${id}-${i + 1}`, ...c, createdAt: '刚刚' }))
}
function getPostComments(id) { return request(`/posts/${id}/comments`, 'GET').catch(() => wait(mockComments(id))) }
function addComment(id, content, userId=CURRENT_USER_ID) { return request(`/posts/${id}/comments`, 'POST', {content,userId}).catch(error => { throw error }) }
function followUser(id, userId=CURRENT_USER_ID) { return request(`/users/${id}/follow`, 'POST', {userId}).catch(error => { if (error.server) throw error; return wait({followed:true}) }) }
function getHotTopics() { return request('/topics/hot').catch(() => wait([{tag:'校园日常',count:18},{tag:'学习',count:12},{tag:'社团活动',count:9}])) }
// 活动轮播位：运营在 banner 表加行即可上线，无需发版；失败返回空数组，页面用本地默认
function getBanners(slot='discover') { return request(`/banners?slot=${encodeURIComponent(slot)}`, 'GET').catch(() => wait([])) }
// 浏览上报：详情页打开时调用，后端 view_count +1 并写浏览历史
function viewPost(id, userId=CURRENT_USER_ID) { return request(`/posts/${id}/view?userId=${userId}`, 'POST').catch(error => { if (error.server) throw error; return wait({}) }) }

// ---- 「我的」页用户域 ----
function getUserProfile(userId=CURRENT_USER_ID) { return request(`/users/${userId}/profile`, 'GET') }
function updateUserProfile(userId=CURRENT_USER_ID, nickname='') { return request(`/users/${userId}/profile`, 'POST', {nickname}) }
function toggleFavorite(postId, userId=CURRENT_USER_ID) { return request(`/users/${userId}/favorites`, 'POST', {postId}).catch(error => { if (error.server) throw error; return wait({favored:true}) }) }
function getFavorites(userId=CURRENT_USER_ID) { return request(`/users/${userId}/favorites`, 'GET').catch(() => wait([])) }
function getMyViews(userId=CURRENT_USER_ID) { return request(`/users/${userId}/views`, 'GET').catch(() => wait([])) }
function getCoupons(userId=CURRENT_USER_ID) { return request(`/users/${userId}/coupons`, 'GET').catch(() => wait([])) }
function getAddresses(userId=CURRENT_USER_ID) { return request(`/users/${userId}/addresses`, 'GET').then(list => { cacheFoodAddresses(userId,list); return list }).catch(() => wait(localFoodAddresses(userId))) }
function addAddress(userId=CURRENT_USER_ID, detail='') { return request(`/users/${userId}/addresses`, 'POST', {detail}).then(address => { cacheFoodAddresses(userId,[address].concat(localFoodAddresses(userId).filter(a => String(a.id)!==String(address.id)))); return address }).catch(error => { if (error.server) throw error; const address=mock.makeFoodAddress(detail); cacheFoodAddresses(userId,[address].concat(localFoodAddresses(userId))); return wait(address) }) }
function submitFeedback(userId=CURRENT_USER_ID, content='') { return request('/feedback', 'POST', {userId, content}).catch(error => { if (error.server) throw error; return wait({received:true}) }) }

// ---- 校草校花评选 ----
function getIdolActivity() { return request('/idol/activity', 'GET').catch(() => wait({})) }
function getIdolContestants(gender, userId=CURRENT_USER_ID) {
  return request(`/idol/contestants?gender=${gender}&userId=${userId}`, 'GET')
    .catch(() => wait((mock.idolContestants || []).filter(c => c.gender === Number(gender))))
}
// 投票：每人为同一位选手限投 1 票，重复投后端返回错误
function voteIdol(id, userId=CURRENT_USER_ID) { return request(`/idol/contestants/${id}/vote`, 'POST', {userId}) }
function joinIdol(payload) {
  return request('/idol/contestants', 'POST', { userId: CURRENT_USER_ID, ...payload })
    .catch(error => { if (error.server) throw error; return wait({ ...payload, id: Date.now(), offline: true }) })
}
function getConversations(userId = CURRENT_USER_ID) {
  return request(`/conversations?userId=${userId}`).then(rows => rows.map(row => ({ ...row, time: row.createdAt || '' }))).catch(error => {
    if (error.server) throw error
    const rows = readLocal('chat_conversations_' + userId, userId === CURRENT_USER_ID ? mock.conversations : [])
    return wait(rows)
  })
}
function getNotifications(userId = CURRENT_USER_ID) {
  return request(`/notifications?userId=${userId}`).then(rows => rows.map(row => ({ ...row, time: row.createdAt || '' }))).catch(error => {
    if (error.server) throw error
    return wait(readLocal('chat_notifications_' + userId, userId === CURRENT_USER_ID ? mock.notifications : []))
  })
}
function getProfile() {
  return getUserProfile().then(profile => ({ ...profile, name: profile.nickname || '校园同学', major: '学院信息未填写', studentId: '校园号 ' + profile.id })).catch(error => {
    if (error.server) throw error
    return wait(mock.profile)
  })
}
// 发帖仍复用原接口与签名；学习分类和当前学校是可选字段，网络写失败直接抛出。
function publishPost(payload, userId=CURRENT_USER_ID) {
  return request('/posts', 'POST', {
    userId,
    title: payload.title,
    content: payload.content,
    image: payload.media && payload.media[0] ? payload.media[0] : '',
    tag: payload.learnCategory ? '学习' : (payload.typeLabel || '校园日常'),
    learnCategory: payload.learnCategory || null,
    schoolId: payload.learnCategory ? (payload.schoolId || null) : null,
    crossSchool: !!payload.crossSchool,
    location: payload.location || '',
    circleId: payload.circleId || null
  }).catch(error => { throw error })
}
module.exports = { BASE_URL, request, searchPosts, searchAll, getConversations, getNotifications, getProfile, publishPost, getForumPosts, getPost, likePost, addComment, getPostComments, followUser, getHotTopics, getBanners, viewPost, getIdolActivity, getIdolContestants, voteIdol, joinIdol, getUserProfile, updateUserProfile, toggleFavorite, getFavorites, getMyViews, getCoupons, getAddresses, addAddress, submitFeedback, getToken, setAuth, clearAuth, currentUserId, isLoggedIn, authStatus, loginWithWxCode, devLogin, ensureLogin }


// 外卖：读取接口均离线兜底；写入接口仅在网络失败时演示，保留服务端拒单消息。
function localFoodAddresses(userId) { try { const rows=wx.getStorageSync('food_addresses_'+userId); return Array.isArray(rows)?rows:(userId===CURRENT_USER_ID?mock.foodAddresses:[]) } catch(error) { return userId===CURRENT_USER_ID?mock.foodAddresses:[] } }
function cacheFoodAddresses(userId, rows) { try { wx.setStorageSync('food_addresses_'+userId,rows) } catch(error) {} }
function getFoodShops(category='', schoolScope='local', schoolId=null) {
  const school = schoolId ? `&schoolId=${encodeURIComponent(schoolId)}` : ''
  return request(`/shops?category=${encodeURIComponent(category)}&schoolScope=${encodeURIComponent(schoolScope)}${school}&userId=${CURRENT_USER_ID}`, 'GET').catch(() => wait(mock.foodShops.filter(s => !category || s.category===category)))
}
function getFoodMenu(id) { return request(`/shops/${id}/menu`, 'GET').catch(() => wait(mock.foodMenus[id] || [])) }
function createFoodOrder(payload) { return request('/orders', 'POST', {...payload,userId:CURRENT_USER_ID}).catch(error => { if (error.server) throw error; return wait(mock.makeFoodOrder(payload)) }) }
Object.assign(module.exports, { CURRENT_USER_ID, getFoodShops, getFoodMenu, createFoodOrder })

// 模块二至四：只在断网时使用本地兜底；后端返回的拒单/校验错误必须直接透传。

// 上传图片的路径规范化：小程序把以 / 开头的路径当本地资源，只有 http(s) 才走网络。
// 老数据里存的是 /uploads/xxx.png（相对路径），这里补全成完整地址；/assets/ 是项目内置图，保持原样。
const UPLOAD_BASE = BASE_URL.replace(/\/api\/?$/, '')
function assetUrl(path) {
  const value = String(path == null ? '' : path)
  if (!value) return ''
  if (/^https?:\/\//i.test(value)) return value
  if (value.startsWith('/uploads/')) return UPLOAD_BASE + value
  return value
}
function assetUrls(list) {
  return Array.isArray(list) ? list.map(assetUrl) : list
}
// 商品对象里的图片字段统一补全（列表与详情共用）
function normalizeGoods(row) {
  if (!row || typeof row !== 'object') return row
  return { ...row, image: assetUrl(row.image), images: assetUrls(row.images) }
}

function getGoodsList(mode = 1, category = '', schoolScope = 'local', schoolId = null) {
  // schoolScope：二手按校隔离（异地无法面交）。local=只看本校（默认），all=全部学校
  // schoolId：小程序里选中的学校，优先级高于账号所属学校
  const school = schoolId ? `&schoolId=${encodeURIComponent(schoolId)}` : ''
  const query = `/goods?mode=${Number(mode) === 2 ? 2 : 1}&category=${encodeURIComponent(category || '')}`
    + `&schoolScope=${encodeURIComponent(schoolScope)}&userId=${CURRENT_USER_ID}${school}`
  return request(query, 'GET').then(rows => (rows || []).map(normalizeGoods)).catch(error => {
    if (error.server) throw error
    return wait(mock.goods.filter(item => Number(item.mode) === (Number(mode) === 2 ? 2 : 1) && (!category || item.category === category)))
  })
}
function getGoodsDetail(id) {
  if (/^local-\d+$/.test(String(id))) {
    const item = mock.goods.find(row => String(row.id) === String(id))
    return item ? wait({ ...item, offline: true }) : Promise.reject(new Error('物品不存在'))
  }
  return request(`/goods/${id}`, 'GET').then(normalizeGoods).catch(error => {
    if (error.server) throw error
    const item = mock.goods.find(row => String(row.id) === String(id))
    if (!item) throw new Error('物品不存在')
    return wait(item)
  })
}
function publishGoods(payload) {
  return request('/goods', 'POST', { userId: CURRENT_USER_ID, ...payload }).catch(error => {
    if (error.server) throw error
    return wait({ id: mock.makeGoods(payload).id, offline: true })
  })
}
// 兼职列表：settle 结算方式筛选 + keyword 关键词（标题/地点/描述）
function getJobs(settle = '', keyword = '', schoolScope = 'local', schoolId = null) {
  const kw = String(keyword || '').trim()
  const school = schoolId ? `&schoolId=${encodeURIComponent(schoolId)}` : ''
  const qs = `?settle=${encodeURIComponent(settle || '')}&keyword=${encodeURIComponent(kw)}&schoolScope=${encodeURIComponent(schoolScope)}${school}&userId=${CURRENT_USER_ID}`
  return request(`/jobs${qs}`, 'GET').catch(error => {
    if (error.server) throw error
    const matcher = kw ? keywordMatcher(kw) : null
    return wait(mock.jobs.filter(item => {
      if (settle && item.settle !== settle) return false
      if (!matcher) return true
      return matcher.test(item.title || '') || matcher.test(item.location || '') || matcher.test(item.description || '')
    }))
  })
}
function getActivities(userId = CURRENT_USER_ID, schoolScope = 'local', schoolId = null) {
  const school = schoolId ? `&schoolId=${encodeURIComponent(schoolId)}` : ''
  return request(`/activities?userId=${userId}&schoolScope=${encodeURIComponent(schoolScope)}${school}`, 'GET').catch(error => {
    if (error.server) throw error
    return wait(mock.activities)
  })
}
function enrollActivity(id, userId = CURRENT_USER_ID) {
  return request(`/activities/${id}/enroll`, 'POST', { userId }).catch(error => {
    if (error.server) throw error
    return wait(mock.enrollActivity(id))
  })
}
Object.assign(module.exports, { getGoodsList, getGoodsDetail, publishGoods, getJobs, getActivities, enrollActivity, assetUrl })

// ---- 兼职 / 活动发布（对应「发布」页的后两个选项）----
function publishJob(payload) {
  const body = {
    userId: CURRENT_USER_ID,
    title: (payload.title || '').trim(),
    daySalary: Number(payload.daySalary) || 0,
    settle: payload.settle || '日结',
    location: payload.location || '',
    timeDesc: payload.timeDesc || '',
    description: payload.description || ''
  }
  return request('/jobs', 'POST', body).catch(error => {
    if (error.server) throw error
    return wait({ id: Date.now(), ...body, offline: true })
  })
}
function publishActivity(payload) {
  const body = {
    userId: CURRENT_USER_ID,
    title: (payload.title || '').trim(),
    poster: payload.poster || '',
    timeDesc: payload.timeDesc || '',
    location: payload.location || '',
    description: payload.description || ''
  }
  return request('/activities', 'POST', body).catch(error => {
    if (error.server) throw error
    return wait({ id: Date.now(), ...body, offline: true })
  })
}
function getMyPublishCount(userId = CURRENT_USER_ID) {
  return request(`/users/${userId}/publish-count`).catch(error => {
    if (error.server) throw error
    return wait({ post: 0, goods: 0, job: 0, activity: 0, offline: true })
  })
}
Object.assign(module.exports, { publishJob, publishActivity, getMyPublishCount })


// 消息离线数据与真实服务分开保存，恢复网络后不会冒充已送达或自动补发。
function readLocal(key, fallback) {
  try { const data = wx.getStorageSync(key); return Array.isArray(data) ? data : clone(fallback) } catch (error) { return clone(fallback) }
}
function saveLocal(key, value) {
  try { wx.setStorageSync(key, value) } catch (error) { throw new Error('本机保存失败，请检查存储空间') }
}
function chatKey(peerId, userId) { return `chat_messages_${userId}_${peerId}` }
function localMessages(peerId, userId) { return readLocal(chatKey(peerId, userId), userId === CURRENT_USER_ID ? (mock.chatMessages[peerId] || []) : []) }
function rememberConversation(peerId, userId, name, message, remove = false) {
  const key = 'chat_conversations_' + userId
  let rows = readLocal(key, userId === CURRENT_USER_ID ? mock.conversations : [])
  const previous = rows.find(row => String(row.id) === String(peerId))
  rows = rows.filter(row => String(row.id) !== String(peerId))
  if (!remove) rows.unshift({ ...(previous || {}), id: Number(peerId), name: name || (previous && previous.name) || '校园同学', avatarText: (name || (previous && previous.name) || '同')[0], color: '#7EB6F6', online: false, unread: 0, preview: message.type === 'image' ? '[图片]' : message.content, time: '本机演示', offline: true })
  saveLocal(key, rows)
}
function getChatMessages(peerId, params = {}, userId = CURRENT_USER_ID) {
  const size = Math.min(50, Math.max(1, Number(params.size) || 30))
  const cursor = params.beforeId ? `&beforeId=${encodeURIComponent(params.beforeId)}` : ''
  return request(`/conversations/${peerId}/messages?userId=${userId}&size=${size}${cursor}`).catch(error => {
    if (error.server) throw error
    const all = localMessages(peerId, userId).filter(row => !params.beforeId || Number(row.id) < Number(params.beforeId))
    let muted = false
    try { muted = !!wx.getStorageSync(`chat_muted_${userId}_${peerId}`) } catch (error) {}
    return wait({ list: all.slice(-size), hasMore: all.length > size, muted, offline: true })
  })
}
function sendChatMessage(peerId, content, params = {}, userId = CURRENT_USER_ID) {
  const text = String(content || '').trim()
  if (!text || text.length > 1000) return Promise.reject(new Error(!text ? '请输入消息' : '消息不能超过1000字'))
  const clientId = params.clientId || `chat-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  return request(`/conversations/${peerId}/messages`, 'POST', { userId, content: text, clientId }).catch(error => {
    if (error.server) throw error
    const rows = localMessages(peerId, userId)
    const existing = rows.find(row => row.clientId === clientId)
    if (existing) return wait(existing)
    const message = { id: Math.max(Date.now(), Number(rows.length ? rows[rows.length - 1].id : 0) + 1), senderId: userId, receiverId: Number(peerId), mine: true, type: 'text', content: text, createdAt: new Date().toISOString(), clientId, offline: true }
    saveLocal(chatKey(peerId, userId), rows.concat(message))
    rememberConversation(peerId, userId, params.name, message)
    return wait(message)
  })
}
function markChatRead(peerId, upToId, userId = CURRENT_USER_ID, offline = false) {
  const local = () => {
    const key = 'chat_conversations_' + userId
    const rows = readLocal(key, userId === CURRENT_USER_ID ? mock.conversations : []).map(row => String(row.id) === String(peerId) ? { ...row, unread: 0 } : row)
    saveLocal(key, rows)
    return wait({ offline: true })
  }
  if (offline) return local()
  return request(`/conversations/${peerId}/read`, 'POST', { userId, upToId }).catch(error => { if (error.server) throw error; return local() })
}
function markAllMessagesRead(userId = CURRENT_USER_ID) {
  return request('/messages/read-all', 'POST', { userId }).catch(error => {
    if (error.server) throw error
    saveLocal('chat_conversations_' + userId, readLocal('chat_conversations_' + userId, userId === CURRENT_USER_ID ? mock.conversations : []).map(row => ({ ...row, unread: 0 })))
    saveLocal('chat_notifications_' + userId, readLocal('chat_notifications_' + userId, userId === CURRENT_USER_ID ? mock.notifications : []).map(row => ({ ...row, unread: false })))
    return wait({ offline: true })
  })
}
function clearChatMessages(peerId, userId = CURRENT_USER_ID, offline = false) {
  const local = () => {
    saveLocal(chatKey(peerId, userId), [])
    rememberConversation(peerId, userId, '', {}, true)
    return wait({ offline: true })
  }
  if (offline) return local()
  return request(`/conversations/${peerId}/clear`, 'POST', { userId }).catch(error => { if (error.server) throw error; return local() })
}
function muteChat(peerId, muted, userId = CURRENT_USER_ID, offline = false) {
  const local = () => {
    saveLocal(`chat_muted_${userId}_${peerId}`, !!muted)
    const key = 'chat_conversations_' + userId
    saveLocal(key, readLocal(key, userId === CURRENT_USER_ID ? mock.conversations : []).map(row => String(row.id) === String(peerId) ? { ...row, muted: !!muted } : row))
    return wait({ offline: true })
  }
  if (offline) return local()
  return request(`/conversations/${peerId}/mute?userId=${userId}&muted=${!!muted}`, 'POST').catch(error => { if (error.server) throw error; return local() })
}
function getTimetable(params = {}, userId = CURRENT_USER_ID) {
  const week = params.week ? `&week=${encodeURIComponent(params.week)}` : ''
  return request(`/timetable?userId=${userId}${week}`).catch(error => {
    if (error.server) throw error
    const currentWeek = params.week || require('./term').getTermWeek(new Date(), mock.termInfo.termStart)
    return wait({ termInfo: mock.termInfo, week: currentWeek, courses: currentWeek <= 21 ? mock.timetableCourses : [], offline: true })
  })
}
function getCalendar(userId = CURRENT_USER_ID) {
  return request(`/calendar?userId=${userId}`).catch(error => {
    if (error.server) throw error
    return wait({ termInfo: mock.termInfo, events: mock.calendarEvents, offline: true })
  })
}
// 学生自建课程：写入自己名下（挂当前学期），同格同名视为更新
function addCourse(payload) {
  const body = {
    userId: CURRENT_USER_ID,
    name: String(payload.name || '').trim(),
    room: String(payload.room || '').trim(),
    day: Number(payload.day) || 0,
    period: Number(payload.period) || 0,
    theme: payload.theme || 'blue',
    weekStart: Number(payload.weekStart) || 1,
    weekEnd: Number(payload.weekEnd) || 21
  }
  if (!body.name) return Promise.reject(new Error('请填写课程名称'))
  return request('/timetable/courses', 'POST', body).catch(error => {
    if (error.server) throw error
    return wait({ id: Date.now(), ...body, offline: true })
  })
}
// 删除自己的课程
function removeCourse(id, userId = CURRENT_USER_ID) {
  return request(`/timetable/courses/${encodeURIComponent(id)}?userId=${userId}`, 'DELETE').catch(error => {
    if (error.server) throw error
    return wait({ offline: true })
  })
}
// 个人主页：按作者分页查帖子 + 真实统计（替代原先「取前 50 条本地筛」与写死的粉丝数）
function getUserPosts(userId, params = {}) {
  const { page = 1, size = 10, viewerId = CURRENT_USER_ID } = params
  return request(`/users/${userId}/posts?viewerId=${viewerId}&page=${page}&size=${size}`)
    .catch(error => {
      if (error.server) throw error
      const all = (mock.discoveryPosts || []).filter(p => String(p.userId || p.id) === String(userId))
      const start = (page - 1) * size
      return wait({ list: all.slice(start, start + size), total: all.length, page, size, offline: true })
    })
}
function getUserStats(userId = CURRENT_USER_ID) {
  return request(`/users/${userId}/stats`).catch(error => {
    if (error.server) throw error
    const p = mock.profile || {}
    return wait({ id: userId, nickname: p.name || '校园同学', school: p.school || '', posts: 0, following: 0, followers: 0, likedTotal: 0, offline: true })
  })
}
Object.assign(module.exports, { getUserPosts, getUserStats })

// ---- 图片上传 ----
// wx.uploadFile 与 wx.request 是两套 API，需单独包装；返回 { url } 供页面持久化。
function uploadImage(filePath) {
  return new Promise((resolve, reject) => {
    if (!filePath) return reject(new Error('请先选择图片'))
    if (typeof wx.uploadFile !== 'function') return reject(new Error('当前环境不支持上传'))
    const header = {}
    const token = getToken()
    if (token) header.Authorization = `Bearer ${token}`
    wx.uploadFile({
      url: `${BASE_URL}/upload`,
      filePath,
      name: 'file',
      header,
      timeout: 30000,
      success(res) {
        let body = res.data
        try { body = typeof body === 'string' ? JSON.parse(body) : body } catch (error) { body = null }
        if (!body) return reject(new Error('上传响应解析失败'))
        if (res.statusCode >= 200 && res.statusCode < 300 && body.code === 0) return resolve(body.data)
        reject(Object.assign(new Error(body.msg || `上传失败（${res.statusCode}）`), { server: true }))
      },
      fail() { reject(Object.assign(new Error('网络异常，图片上传失败'), { offline: true })) }
    })
  })
}
// 批量上传：任一失败即中断，避免出现半截数据
// 批量上传：任一失败就抛出，绝不退回本地临时路径。
// 退回本地路径会导致 wxfile://tmp_xxx 被存进数据库，用户永远看不到图。
function uploadImages(paths = []) {
  const list = (paths || []).filter(Boolean)
  if (!list.length) return Promise.resolve([])
  return list.reduce((chain, path) => chain.then(async acc => {
    const res = await uploadImage(path)
    const url = res && res.url
    if (!url) throw new Error('图片上传失败，请重试')
    return acc.concat(url)
  }), Promise.resolve([]))
}
Object.assign(module.exports, { uploadImage, uploadImages })

// 首页今日推荐：外卖最火 + 二手最火（真实热度）。batch 用于「换一批」轮换。
function getHomeRecommend(params = {}) {
  const batch = Math.max(0, Math.floor(Number(params.batch) || 0))
  const perType = Math.min(10, Math.max(1, Math.floor(Number(params.perType) || 1)))
  const userId = params.userId || CURRENT_USER_ID
  return request(`/recommend/home?userId=${userId}&batch=${batch}&perType=${perType}`, 'GET')
    .catch(error => {
      if (error.server) throw error
      return wait(localRecommend(batch, perType), 120)
    })
}
// 离线兜底：用本地数据按同样的热度公式排序，保证无后端也能换一批
function localRecommend(batch, perType) {
  const heatOf = (row) => (Number(row.viewCount) || 0) * 3 + (Number(row.favoriteCount) || 0) * 5 + (Number(row.contactCount) || 0) * 8
  const shopHeat = (row) => (Number(row.orderCount) || 0) * 5 + (Number(row.shopViewCount) || 0) * 1
  const shops = (mock.foodShops || []).slice().sort((a, b) => shopHeat(b) - shopHeat(a) || (b.rating || 0) - (a.rating || 0))
  const goods = (mock.goods || []).filter(row => row.status !== 0).slice().sort((a, b) => heatOf(b) - heatOf(a))
  const pick = (rows, offset) => rows.length ? rows.slice(offset % rows.length).concat(rows.slice(0, offset % rows.length)).slice(0, perType) : []
  const cards = []
  for (const shop of pick(shops, batch * perType)) {
    cards.push({
      type: 'shop', id: shop.id, tag: '美食', title: shop.name,
      subtitle: shop.discount ? shop.discount + ' · 查看菜单' : '查看店铺菜单与优惠',
      image: shop.cover, location: shop.category,
      heatText: Number(shop.orderCount) > 0 ? `${shop.orderCount} 人下单` : '新入驻',
      url: `/pages/food-shop/food-shop?id=${shop.id}`
    })
  }
  for (const item of pick(goods, batch * perType)) {
    cards.push({
      type: 'goods', id: item.id, tag: Number(item.mode) === 2 ? '求购' : '闲置', title: item.title,
      subtitle: item.description || '', image: item.image, location: item.category, price: item.price,
      heatText: Number(item.contactCount) > 0 ? `${item.contactCount} 人咨询`
        : Number(item.favoriteCount) > 0 ? `${item.favoriteCount} 人收藏`
        : Number(item.viewCount) > 0 ? `${item.viewCount} 次浏览` : '新上架',
      url: `/pages/market-detail/market-detail?id=${item.id}`
    })
  }
  return { cards, batch, hasMore: shops.length > perType || goods.length > perType, offline: true }
}
// 商品行为上报：浏览/收藏/私聊，供真实热度统计
function viewGoods(id) { return request(`/goods/${id}/view`, 'POST').catch(() => wait({})) }
function contactGoods(id) { return request(`/goods/${id}/contact`, 'POST').catch(() => wait({})) }
function favoriteGoods(id, userId = CURRENT_USER_ID) {
  return request(`/goods/${id}/favorite`, 'POST', { userId }).then(res => { markGoodsFavorite(id, res && res.favored); return res })
    .catch(error => {
      if (error.server) throw error
      const next = !isGoodsFavorite(id)
      markGoodsFavorite(id, next)
      return wait({ favored: next, offline: true })
    })
}
function isGoodsFavorite(id) {
  try { return (wx.getStorageSync('goods_favorites') || []).map(String).includes(String(id)) } catch (error) { return false }
}
function markGoodsFavorite(id, favored) {
  try {
    const rows = (wx.getStorageSync('goods_favorites') || []).map(String).filter(x => x !== String(id))
    if (favored) rows.push(id)
    wx.setStorageSync('goods_favorites', rows)
  } catch (error) {}
}
Object.assign(module.exports, { getHomeRecommend, viewGoods, contactGoods, favoriteGoods, isGoodsFavorite })

// ---- 圈子 ----
// 圈子离线兜底：mock.circles 为准，本地记录用户加入状态，保证无后端也能演示加入/退出。
function localCircles() { return readLocal('circles_joined', []) }
function markJoined(circleId, joined) {
  const ids = localCircles().filter(id => String(id) !== String(circleId))
  if (joined) ids.push(circleId)
  saveLocal('circles_joined', ids)
}
function decorateCircles(rows) {
  const joined = localCircles().map(String)
  return (rows || []).map(row => ({ ...row, joined: joined.includes(String(row.id)) || !!row.joined }))
}
function getCircles(params = {}) {
  const { category = '', hot = false, page = 1, size = 20, userId = currentUserId(), schoolScope = 'local', schoolId = null } = params
  const school = schoolId ? `&schoolId=${encodeURIComponent(schoolId)}` : ''
  const qs = `?userId=${userId}&category=${encodeURIComponent(category)}&hot=${!!hot}&schoolScope=${encodeURIComponent(schoolScope)}${school}&page=${page}&size=${size}`
  return request(`/circles${qs}`).catch(error => {
    if (error.server) throw error
    let rows = (mock.circles || []).filter(c => (!category || c.category === category) && (!hot || c.hot))
    rows = decorateCircles(rows)
    const start = (page - 1) * size
    return wait({ list: rows.slice(start, start + size), total: rows.length, page, size, offline: true })
  })
}
function getHotCircles(limit = 5, userId = currentUserId(), schoolScope = 'local', schoolId = null) {
  const school = schoolId ? `&schoolId=${encodeURIComponent(schoolId)}` : ''
  return request(`/circles/hot?userId=${userId}&limit=${limit}&schoolScope=${encodeURIComponent(schoolScope)}${school}`).catch(error => {
    if (error.server) throw error
    return wait(decorateCircles((mock.circles || []).filter(c => c.hot).slice(0, limit)), 120)
  })
}
function getCircle(id, userId = currentUserId()) {
  return request(`/circles/${id}?userId=${userId}`).catch(error => {
    if (error.server) throw error
    const row = (mock.circles || []).find(c => String(c.id) === String(id))
    if (!row) throw new Error('圈子不存在或已解散')
    return wait({ ...decorateCircles([row])[0], offline: true })
  })
}
function getCirclePosts(id, params = {}) {
  const { page = 1, size = 10, userId = currentUserId() } = params
  return request(`/circles/${id}/posts?userId=${userId}&page=${page}&size=${size}`).catch(error => {
    if (error.server) throw error
    const all = (mock.discoveryPosts || []).filter(p => String(p.circleId) === String(id))
    const start = (page - 1) * size
    return wait({ list: all.slice(start, start + size), total: all.length, page, size, offline: true })
  })
}
function joinCircle(id, userId = currentUserId()) {
  return request(`/circles/${id}/join`, 'POST', { userId }).then(res => { markJoined(id, true); return res })
    .catch(error => {
      if (error.server) throw error
      markJoined(id, true)
      const row = (mock.circles || []).find(c => String(c.id) === String(id))
      return wait({ joined: true, memberCount: ((row && row.memberCount) || 0) + 1, offline: true })
    })
}
function leaveCircle(id, userId = currentUserId()) {
  return request(`/circles/${id}/leave`, 'POST', { userId }).then(res => { markJoined(id, false); return res })
    .catch(error => {
      if (error.server) throw error
      markJoined(id, false)
      const row = (mock.circles || []).find(c => String(c.id) === String(id))
      return wait({ joined: false, memberCount: Math.max(((row && row.memberCount) || 1) - 1, 0), offline: true })
    })
}
function getCircleCategories() {
  return request('/circles/categories').catch(error => {
    if (error.server) throw error
    const map = {}
    for (const c of mock.circles || []) map[c.category] = (map[c.category] || 0) + 1
    return wait(Object.keys(map).map(category => ({ category, total: map[category] })))
  })
}
// 圈子群聊：历史消息使用 beforeId 游标；afterId 只用于 WebSocket 重连后的增量补量。
function getCircleMessages(circleId, params = {}) {
  const size = Math.min(50, Math.max(1, Number(params.size) || 30))
  const before = params.beforeId ? `&beforeId=${encodeURIComponent(params.beforeId)}` : ''
  const after = params.afterId != null ? `&afterId=${encodeURIComponent(params.afterId)}` : ''
  return request(`/circle-messages?circleId=${encodeURIComponent(circleId)}&limit=${size}${before}${after}`, 'GET')
    .catch(error => {
      if (error.server) throw error
      return wait({ list: [], hasMore: false, nextBeforeId: null, offline: true })
    })
}
function sendCircleMessage(circleId, payload = {}) {
  const type = payload.type || 'text'
  const content = String(payload.content || '').trim()
  const image = String(payload.image || '').trim()
  if (type === 'text' && !content) return Promise.reject(new Error('请输入消息'))
  if (type === 'text' && content.length > 1000) return Promise.reject(new Error('消息不能超过1000字'))
  const clientId = payload.clientId || `circle-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  return request('/circle-messages', 'POST', { circleId, type, content, image, clientId })
}
function markCircleRead(circleId, lastReadId) {
  if (!lastReadId) return Promise.resolve({})
  return request('/circle-messages/read', 'POST', { circleId, lastReadId })
}
function getCircleUnread() {
  return request('/circle-messages/unread', 'GET')
    .catch(error => { if (error.server) throw error; return wait([]) })
}
// 发帖：新增可选 circleId，帖子归属到圈子
function publishCirclePost(payload, userId = CURRENT_USER_ID) {
  return publishPost({ ...payload, circleId: payload.circleId }, userId)
}
Object.assign(module.exports, { getCircles, getHotCircles, getCircle, getCirclePosts, joinCircle, leaveCircle, getCircleCategories, publishCirclePost, getCircleMessages, sendCircleMessage, markCircleRead, getCircleUnread, getSchools, getSchoolLocations, aiChat, getAiWelcome })

Object.assign(module.exports, { getChatMessages, sendChatMessage, markChatRead, markAllMessagesRead, clearChatMessages, muteChat, getTimetable, getCalendar, addCourse, removeCourse })

// 学习列表使用主键游标，列表加载期间有新帖或删帖也不造成 offset 错位。
function getLearnTopics(params = {}) {
  const { category = 'daike', schoolId = null, userId = currentUserId(), beforeId = '' } = params
  const parsedSize = Number(params.size)
  const size = Number.isFinite(parsedSize) ? Math.min(50, Math.max(1, Math.floor(parsedSize))) : 10
  const school = schoolId == null ? '' : `&schoolId=${encodeURIComponent(schoolId)}`
  const cursor = beforeId ? `&beforeId=${encodeURIComponent(beforeId)}` : ''
  return request(`/learn/topics?category=${encodeURIComponent(category)}&userId=${encodeURIComponent(userId)}&size=${size}${school}${cursor}`, 'GET')
    .catch(error => {
      if (error.server) throw error
      return wait({ list: [], total: 0, hasMore: false, nextCursor: '', offline: true })
    })
}
function getLearnCounts(schoolId = null, userId = currentUserId()) {
  const school = schoolId == null ? '' : `&schoolId=${encodeURIComponent(schoolId)}`
  return request(`/learn/counts?userId=${encodeURIComponent(userId)}${school}`, 'GET').catch(error => {
    if (error.server) throw error
    return wait({ daike: 0, paper: 0, job: 0, kaoyan: 0, kaogong: 0 }, 80)
  })
}
Object.assign(module.exports, { getLearnTopics, getLearnCounts })
