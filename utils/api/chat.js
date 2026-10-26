// 消息 + 课表域：会话列表、通知、私聊消息（含离线本机存储）、课表、校历。
//
// 离线消息有一条铁律：本机存的演示消息与真实服务端消息分开存，
// 恢复网络后既不冒充「已送达」，也不自动补发 —— 用户看到什么就是他真的做过的。

const mock = require('../mock-data')
const { request, wait, currentUserId } = require('./core')
const { readLocal, saveLocal } = require('./local')

function getConversations(userId = currentUserId()) {
  return request(`/conversations?userId=${userId}`).then(rows => rows.map(row => ({ ...row, time: row.createdAt || '' }))).catch(error => {
    if (error.server) throw error
    const rows = readLocal('chat_conversations_' + userId, userId === currentUserId() ? mock.conversations : [])
    return wait(rows)
  })
}
function getNotifications(userId = currentUserId()) {
  return request(`/notifications?userId=${userId}`).then(rows => rows.map(row => ({ ...row, time: row.createdAt || '' }))).catch(error => {
    if (error.server) throw error
    return wait(readLocal('chat_notifications_' + userId, userId === currentUserId() ? mock.notifications : []))
  })
}

// 消息离线数据与真实服务分开保存，恢复网络后不会冒充已送达或自动补发。
function chatKey(peerId, userId) { return `chat_messages_${userId}_${peerId}` }
function localMessages(peerId, userId) { return readLocal(chatKey(peerId, userId), userId === currentUserId() ? (mock.chatMessages[peerId] || []) : []) }
function rememberConversation(peerId, userId, name, message, remove = false) {
  const key = 'chat_conversations_' + userId
  let rows = readLocal(key, userId === currentUserId() ? mock.conversations : [])
  const previous = rows.find(row => String(row.id) === String(peerId))
  rows = rows.filter(row => String(row.id) !== String(peerId))
  if (!remove) rows.unshift({ ...(previous || {}), id: Number(peerId), name: name || (previous && previous.name) || '校园同学', avatarText: (name || (previous && previous.name) || '同')[0], color: '#7EB6F6', online: false, unread: 0, preview: message.type === 'image' ? '[图片]' : message.content, time: '本机演示', offline: true })
  saveLocal(key, rows)
}
function getChatMessages(peerId, params = {}, userId = currentUserId()) {
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
function sendChatMessage(peerId, content, params = {}, userId = currentUserId()) {
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
function markChatRead(peerId, upToId, userId = currentUserId(), offline = false) {
  const local = () => {
    const key = 'chat_conversations_' + userId
    const rows = readLocal(key, userId === currentUserId() ? mock.conversations : []).map(row => String(row.id) === String(peerId) ? { ...row, unread: 0 } : row)
    saveLocal(key, rows)
    return wait({ offline: true })
  }
  if (offline) return local()
  return request(`/conversations/${peerId}/read`, 'POST', { userId, upToId }).catch(error => { if (error.server) throw error; return local() })
}
function markAllMessagesRead(userId = currentUserId()) {
  return request('/messages/read-all', 'POST', { userId }).catch(error => {
    if (error.server) throw error
    saveLocal('chat_conversations_' + userId, readLocal('chat_conversations_' + userId, userId === currentUserId() ? mock.conversations : []).map(row => ({ ...row, unread: 0 })))
    saveLocal('chat_notifications_' + userId, readLocal('chat_notifications_' + userId, userId === currentUserId() ? mock.notifications : []).map(row => ({ ...row, unread: false })))
    return wait({ offline: true })
  })
}
function clearChatMessages(peerId, userId = currentUserId(), offline = false) {
  const local = () => {
    saveLocal(chatKey(peerId, userId), [])
    rememberConversation(peerId, userId, '', {}, true)
    return wait({ offline: true })
  }
  if (offline) return local()
  return request(`/conversations/${peerId}/clear`, 'POST', { userId }).catch(error => { if (error.server) throw error; return local() })
}
function muteChat(peerId, muted, userId = currentUserId(), offline = false) {
  const local = () => {
    saveLocal(`chat_muted_${userId}_${peerId}`, !!muted)
    const key = 'chat_conversations_' + userId
    saveLocal(key, readLocal(key, userId === currentUserId() ? mock.conversations : []).map(row => String(row.id) === String(peerId) ? { ...row, muted: !!muted } : row))
    return wait({ offline: true })
  }
  if (offline) return local()
  return request(`/conversations/${peerId}/mute?userId=${userId}&muted=${!!muted}`, 'POST').catch(error => { if (error.server) throw error; return local() })
}

// ---- 课表 ----
function getTimetable(params = {}, userId = currentUserId()) {
  const week = params.week ? `&week=${encodeURIComponent(params.week)}` : ''
  return request(`/timetable?userId=${userId}${week}`).catch(error => {
    if (error.server) throw error
    const currentWeek = params.week || require('../term').getTermWeek(new Date(), mock.termInfo.termStart)
    return wait({ termInfo: mock.termInfo, week: currentWeek, courses: currentWeek <= 21 ? mock.timetableCourses : [], offline: true })
  })
}
// 校历（getCalendar）已随「校历」功能下线移除

// 学生自建课程：写入自己名下（挂当前学期），同格同名视为更新
function addCourse(payload) {
  const body = {
    userId: currentUserId(),
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
function removeCourse(id, userId = currentUserId()) {
  return request(`/timetable/courses/${encodeURIComponent(id)}?userId=${userId}`, 'DELETE').catch(error => {
    if (error.server) throw error
    return wait({ offline: true })
  })
}

module.exports = {
  getConversations, getNotifications,
  chatKey, localMessages, rememberConversation,
  getChatMessages, sendChatMessage, markChatRead, markAllMessagesRead, clearChatMessages, muteChat,
  getTimetable, addCourse, removeCourse
}

