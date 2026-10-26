// 圈子域：圈子列表/详情/帖子、加入退出、分类、圈子群聊消息。
//
// 离线兜底的思路：以 mock.circles 为准，用户「加入过哪些圈子」记在本机，
// 这样无后端时也能演示加入/退出，而且重进页面状态不会丢。

const mock = require('../mock-data')
const { request, wait, currentUserId } = require('./core')
const { readLocal, saveLocal } = require('./local')

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
function publishCirclePost(payload, userId = currentUserId()) {
  return require('./forum').publishPost({ ...payload, circleId: payload.circleId }, userId)
}

module.exports = {
  localCircles, markJoined, decorateCircles,
  getCircles, getHotCircles, getCircle, getCirclePosts, joinCircle, leaveCircle, getCircleCategories,
  getCircleMessages, sendCircleMessage, markCircleRead, getCircleUnread, publishCirclePost
}
