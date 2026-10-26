// 交易域：外卖（店铺/菜单/订单/支付）、二手（商品/收藏/浏览上报）、兼职、活动、发布、图片上传、首页推荐。
//
// 一条贯穿全文件的约定：
//   读接口断网 → 本地演示数据；写接口（下单/支付/发布）→ 只在「网络不通」时演示，
//   服务端明确拒绝（超时/售罄/校验失败）必须原样抛给页面，不能让用户以为下单成功了。

const mock = require('../mock-data')
const { BASE_URL, request, wait, getToken, currentUserId, fallback } = require('./core')
const { keywordMatcher, normalizeGoods } = require('./local')

// ==================== 外卖 ====================
function localFoodAddresses(userId) { try { const rows = wx.getStorageSync('food_addresses_' + userId); return Array.isArray(rows) ? rows : (userId === currentUserId() ? mock.foodAddresses : []) } catch (error) { return userId === currentUserId() ? mock.foodAddresses : [] } }
function cacheFoodAddresses(userId, rows) { try { wx.setStorageSync('food_addresses_' + userId, rows) } catch (error) {} }

function getFoodShops(category = '', schoolScope = 'local', schoolId = null) {
  const school = schoolId ? `&schoolId=${encodeURIComponent(schoolId)}` : ''
  return request(`/food/shops?category=${encodeURIComponent(category)}&schoolScope=${encodeURIComponent(schoolScope)}${school}&userId=${currentUserId()}`, 'GET')
    .then(rows => (rows || []).map(s => ({ ...s, isOpen: Boolean(s.isOpen) })))
    .catch(error => { if (error.server) throw error; return wait(mock.foodShops.filter(s => !category || s.category === category).map(s => ({ ...s, isOpen: true }))) })
}
function getFoodMenu(id) { return request(`/food/shops/${id}/menu`, 'GET').catch(error => { if (error.server) throw error; return wait(mock.foodMenus[id] || []) }) }
function createFoodOrder(payload) { return request('/orders', 'POST', { ...payload, userId: currentUserId() }).catch(error => { if (error.server) throw error; return wait(mock.makeFoodOrder(payload)) }) }
function getFoodOrders(page = 1, size = 20) { return request(`/orders?page=${page}&size=${size}`, 'GET').catch(error => { if (error.server) throw error; return wait({ list: [], total: 0, page, size, offline: true }) }) }
function getFoodOrderDetail(id) { return request(`/orders/${encodeURIComponent(id)}`, 'GET') }
// 支付是写操作，服务端失败时必须把错误直接交给页面处理，避免用户误以为已经付款。
function payOrder(orderId) { return request(`/orders/${encodeURIComponent(orderId)}/pay`, 'POST', {}) }
// 订单详情是读操作，断网时允许进入未知状态，由页面继续提示用户稍后查看。
function getOrderDetail(orderId) {
  return request(`/orders/${encodeURIComponent(orderId)}`, 'GET').catch(error => {
    if (error.server) throw error
    return wait({ id: orderId, orderId, payStatus: 'unknown', status: 'unknown', offline: true })
  })
}
// 骑手端：订单详情（含本地演示订单兜底）
function getFoodOrder(orderId) {
  if (String(orderId).startsWith('local-')) {
    return wait({ id: orderId, status: 'paid', shopName: '演示订单', address: '', items: '[]', deliveryFee: 0, offline: true })
  }
  return request(`/orders/${encodeURIComponent(orderId)}?userId=${currentUserId()}`, 'GET')
}

// ==================== 二手 ====================
function getGoodsList(mode = 1, category = '', schoolScope = 'local', schoolId = null) {
  // schoolScope：二手按校隔离（异地无法面交）。local=只看本校（默认），all=全部学校
  // schoolId：小程序里选中的学校，优先级高于账号所属学校
  const school = schoolId ? `&schoolId=${encodeURIComponent(schoolId)}` : ''
  const query = `/goods?mode=${Number(mode) === 2 ? 2 : 1}&category=${encodeURIComponent(category || '')}`
    + `&schoolScope=${encodeURIComponent(schoolScope)}&userId=${currentUserId()}${school}`
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
  return request('/goods', 'POST', { userId: currentUserId(), ...payload }).catch(error => {
    if (error.server) throw error
    return wait({ id: mock.makeGoods(payload).id, offline: true })
  })
}
// 商品行为上报：浏览/收藏/私聊，供真实热度统计
function viewGoods(id) { return request(`/goods/${id}/view`, 'POST').catch(() => wait({})) }
function contactGoods(id) { return request(`/goods/${id}/contact`, 'POST').catch(() => wait({})) }
function favoriteGoods(id, userId = currentUserId()) {
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

// ==================== 兼职 / 活动 ====================
// 兼职列表：settle 结算方式筛选 + keyword 关键词（标题/地点/描述）
function getJobs(settle = '', keyword = '', schoolScope = 'local', schoolId = null) {
  const kw = String(keyword || '').trim()
  const school = schoolId ? `&schoolId=${encodeURIComponent(schoolId)}` : ''
  const qs = `?settle=${encodeURIComponent(settle || '')}&keyword=${encodeURIComponent(kw)}&schoolScope=${encodeURIComponent(schoolScope)}${school}&userId=${currentUserId()}`
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
function getActivities(userId = currentUserId(), schoolScope = 'local', schoolId = null) {
  const school = schoolId ? `&schoolId=${encodeURIComponent(schoolId)}` : ''
  return request(`/activities?userId=${userId}&schoolScope=${encodeURIComponent(schoolScope)}${school}`, 'GET').catch(error => {
    if (error.server) throw error
    return wait(mock.activities)
  })
}
function enrollActivity(id, userId = currentUserId()) {
  return request(`/activities/${id}/enroll`, 'POST', { userId }).catch(error => {
    if (error.server) throw error
    return wait(mock.enrollActivity(id))
  })
}

// ---- 兼职 / 活动发布（对应「发布」页的后两个选项）----
function publishJob(payload) {
  const body = {
    userId: currentUserId(),
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
    userId: currentUserId(),
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
function getMyPublishCount(userId = currentUserId()) {
  return request(`/users/${userId}/publish-count`).catch(error => {
    if (error.server) throw error
    return wait({ post: 0, goods: 0, job: 0, activity: 0, offline: true })
  })
}

// ==================== 图片上传 ====================
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

// ==================== 首页推荐 ====================
// 首页今日推荐：外卖最火 + 二手最火（真实热度）。batch 用于「换一批」轮换。
function getHomeRecommend(params = {}) {
  const batch = Math.max(0, Math.floor(Number(params.batch) || 0))
  const perType = Math.min(10, Math.max(1, Math.floor(Number(params.perType) || 1)))
  const userId = params.userId || currentUserId()
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

module.exports = {
  localFoodAddresses, cacheFoodAddresses,
  getFoodShops, getFoodMenu, createFoodOrder, getFoodOrders, getFoodOrderDetail, payOrder, getOrderDetail, getFoodOrder,
  getGoodsList, getGoodsDetail, publishGoods, viewGoods, contactGoods, favoriteGoods, isGoodsFavorite, markGoodsFavorite,
  getJobs, getActivities, enrollActivity, publishJob, publishActivity, getMyPublishCount,
  uploadImage, uploadImages,
  getHomeRecommend, localRecommend
}
