/**
 * 小程序接口聚合出口。
 *
 * 页面一律 `require('../../utils/api')`，拿到的还是同一组函数名 —— 这一层现在只做两件事：
 *   1. 抛出口径统一的对外 API；
 *   2. 直接实现「全局」级别的少数接口：AI 陪聊、学校列表、全站搜索。
 *
 * 具体业务在 utils/api/ 下按域拆分：
 *   core.js    请求内核：地址、登录态、鉴权门、401/403 处理、离线兜底规则
 *   local.js   跨域共用：localStorage 读写、LIKE 关键词匹配、图片地址补全
 *   forum.js   论坛：帖子流、详情、点赞评论关注、搜索、热门话题、轮播、发帖
 *   chat.js    消息 + 课表：会话、通知、私聊、课表、校历
 *   market.js  交易：外卖、二手、兼职、活动、发布、图片上传、首页推荐
 *   circle.js  圈子：列表、详情、加入退出、圈子群聊
 *   learn.js   学习专区：话题、分类计数
 *   pet.js     宠物：查询、喂食、摸摸、签到
 */
const mock = require('./mock-data')
// 请求内核（地址 / 登录态 / 鉴权门 / 统一请求入口）已抽到 utils/api/core.js
const core = require('./api/core')

const {
  BASE_URL, DEMO_USER_ID,
  getToken, setAuth, clearAuth, currentUserId, isLoggedIn,
  request, wait, clone, fallback,
  authStatus, loginWithWxCode, devLogin, ensureLogin
} = core

// 论坛域（帖子流/详情/互动/搜索/热门/轮播/发帖）已抽到 utils/api/forum.js
const forum = require('./api/forum')
const {
  localPosts, localForumPosts, mockForum,
  getForumPosts, searchPosts, getPost, likePost,
  getPostComments, addComment, followUser,
  getHotTopics, getBanners, viewPost, publishPost
} = forum


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
    userId: options.userId || currentUserId(),
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
// LIKE %keyword% 匹配、localStorage 读写、图片地址补全都在 utils/api/local.js
const { readLocal, saveLocal, keywordMatcher, assetUrl, assetUrls, normalizeGoods } = require('./api/local')


// 全站搜索：帖子/圈子/商品/活动/兼职/外卖。type='all' 时混排并返回各类计数。
const SEARCH_TYPES = ['all', 'post', 'circle', 'goods', 'activity', 'job', 'food']
function searchAll(params = {}) {
  const keyword = String(params.keyword || '').trim()
  if (!keyword || keyword.length > 64) return Promise.reject(new Error(!keyword ? '请输入搜索关键词' : '关键词不能超过64字'))
  const type = SEARCH_TYPES.includes(params.type) ? params.type : 'all'
  const page = Math.max(1, Math.floor(Number(params.page) || 1))
  const size = Math.min(50, Math.max(1, Math.floor(Number(params.size) || 10)))
  const userId = params.userId || currentUserId()
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

// ---- 「我的」页用户域 ----
function getUserProfile(userId=currentUserId()) { return request(`/users/${userId}/profile`, 'GET') }
function updateUserProfile(userId=currentUserId(), nickname='') { return request(`/users/${userId}/profile`, 'POST', {nickname}) }
function toggleFavorite(postId, userId=currentUserId()) { return request(`/users/${userId}/favorites`, 'POST', {postId}).catch(error => { if (error.server) throw error; return wait({favored:true}) }) }
// 「我的数据」四个读接口：只在断网时用本地兜底；401/403 必须让页面看见
// （否则未登录时「我的收藏」会显示成空列表，看起来像「你确实没有收藏」）
function getFavorites(userId=currentUserId()) { return fallback(request(`/users/${userId}/favorites`, 'GET'), () => wait([])) }
function getMyViews(userId=currentUserId()) { return fallback(request(`/users/${userId}/views`, 'GET'), () => wait([])) }
function getCoupons(userId=currentUserId()) { return fallback(request(`/users/${userId}/coupons`, 'GET'), () => wait([])) }
function getAddresses(userId=currentUserId()) { return fallback(request(`/users/${userId}/addresses`, 'GET').then(list => { cacheFoodAddresses(userId,list); return list }), () => wait(localFoodAddresses(userId))) }
function addAddress(userId=currentUserId(), detail='') { return request(`/users/${userId}/addresses`, 'POST', {detail}).then(address => { cacheFoodAddresses(userId,[address].concat(localFoodAddresses(userId).filter(a => String(a.id)!==String(address.id)))); return address }).catch(error => { if (error.server) throw error; const address=mock.makeFoodAddress(detail); cacheFoodAddresses(userId,[address].concat(localFoodAddresses(userId))); return wait(address) }) }
function submitFeedback(userId=currentUserId(), content='') { return request('/feedback', 'POST', {userId, content}).catch(error => { if (error.server) throw error; return wait({received:true}) }) }
// 个人主页：按作者分页查帖子 + 真实统计（替代原先「取前 50 条本地筛」与写死的粉丝数）
function getUserPosts(userId, params = {}) {
  const { page = 1, size = 10, viewerId = currentUserId() } = params
  return request(`/users/${userId}/posts?viewerId=${viewerId}&page=${page}&size=${size}`)
    .catch(error => {
      if (error.server) throw error
      const all = (mock.discoveryPosts || []).filter(p => String(p.userId || p.id) === String(userId))
      const start = (page - 1) * size
      return wait({ list: all.slice(start, start + size), total: all.length, page, size, offline: true })
    })
}
function getUserStats(userId = currentUserId()) {
  return request(`/users/${userId}/stats`).catch(error => {
    if (error.server) throw error
    const p = mock.profile || {}
    return wait({ id: userId, nickname: p.name || '校园同学', school: p.school || '', posts: 0, following: 0, followers: 0, likedTotal: 0, offline: true })
  })
}

// ---- 校草校花评选 ----
function getIdolActivity() { return request('/idol/activity', 'GET').catch(() => wait({})) }
function getIdolContestants(gender, userId=currentUserId()) {
  return request(`/idol/contestants?gender=${gender}&userId=${userId}`, 'GET')
    .catch(() => wait((mock.idolContestants || []).filter(c => c.gender === Number(gender))))
}
// 投票：每人为同一位选手限投 1 票，重复投后端返回错误
function voteIdol(id, userId=currentUserId()) { return request(`/idol/contestants/${id}/vote`, 'POST', {userId}) }
function joinIdol(payload) {
  return request('/idol/contestants', 'POST', { userId: currentUserId(), ...payload })
    .catch(error => { if (error.server) throw error; return wait({ ...payload, id: Date.now(), offline: true }) })
}
function getProfile() {
  return getUserProfile().then(profile => ({ ...profile, name: profile.nickname || '校园同学', major: '学院信息未填写', studentId: '校园号 ' + profile.id })).catch(error => {
    if (error.server) throw error
    return wait(mock.profile)
  })
}
// 消息 + 课表域已抽到 utils/api/chat.js
const chat = require('./api/chat')
const {
  getConversations, getNotifications,
  getChatMessages, sendChatMessage, markChatRead, markAllMessagesRead, clearChatMessages, muteChat,
  getTimetable, getCalendar, addCourse, removeCourse
} = chat

module.exports = { BASE_URL, DEMO_USER_ID, request, searchPosts, searchAll, getConversations, getNotifications, getProfile, publishPost, getForumPosts, getPost, likePost, addComment, getPostComments, followUser, getHotTopics, getBanners, viewPost, getIdolActivity, getIdolContestants, voteIdol, joinIdol, getUserProfile, updateUserProfile, toggleFavorite, getFavorites, getMyViews, getCoupons, getAddresses, addAddress, submitFeedback, getToken, setAuth, clearAuth, currentUserId, isLoggedIn, authStatus, loginWithWxCode, devLogin, ensureLogin }


// 交易域（外卖 / 二手 / 兼职 / 活动 / 发布 / 上传 / 首页推荐）已抽到 utils/api/market.js
const market = require('./api/market')
const {
  localFoodAddresses, cacheFoodAddresses,
  getFoodShops, getFoodMenu, createFoodOrder, getFoodOrders, getFoodOrderDetail, payOrder, getOrderDetail, getFoodOrder,
  getGoodsList, getGoodsDetail, publishGoods, viewGoods, contactGoods, favoriteGoods, isGoodsFavorite,
  getJobs, getActivities, enrollActivity, publishJob, publishActivity, getMyPublishCount,
  uploadImage, uploadImages,
  getHomeRecommend
} = market
Object.assign(module.exports, {
  getUserPosts, getUserStats, assetUrl,
  localFoodAddresses, cacheFoodAddresses,
  getFoodShops, getFoodMenu, createFoodOrder, getFoodOrders, getFoodOrderDetail, payOrder, getOrderDetail, getFoodOrder,
  getGoodsList, getGoodsDetail, publishGoods, viewGoods, contactGoods, favoriteGoods, isGoodsFavorite,
  getJobs, getActivities, enrollActivity, publishJob, publishActivity, getMyPublishCount,
  uploadImage, uploadImages, getHomeRecommend
})

// 圈子域已抽到 utils/api/circle.js
const circle = require('./api/circle')
const {
  getCircles, getHotCircles, getCircle, getCirclePosts, joinCircle, leaveCircle, getCircleCategories,
  getCircleMessages, sendCircleMessage, markCircleRead, getCircleUnread, publishCirclePost
} = circle
Object.assign(module.exports, { getCircles, getHotCircles, getCircle, getCirclePosts, joinCircle, leaveCircle, getCircleCategories, publishCirclePost, getCircleMessages, sendCircleMessage, markCircleRead, getCircleUnread, getSchools, getSchoolLocations, aiChat, getAiWelcome })

Object.assign(module.exports, { getChatMessages, sendChatMessage, markChatRead, markAllMessagesRead, clearChatMessages, muteChat, getTimetable, getCalendar, addCourse, removeCourse })

// 学习域已抽到 utils/api/learn.js，宠物域已抽到 utils/api/pet.js
const learn = require('./api/learn')
const pet = require('./api/pet')
Object.assign(module.exports, { getLearnTopics: learn.getLearnTopics, getLearnCounts: learn.getLearnCounts })
Object.assign(module.exports, { getPet: pet.getPet, petFeed: pet.petFeed, petTouch: pet.petTouch, petCheckin: pet.petCheckin })


