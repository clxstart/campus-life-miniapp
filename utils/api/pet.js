// 宠物域：查宠物（读，可离线兜底）与喂食/摸摸/签到（写，不兜底）。
//
// 写接口刻意不兜底：宠物状态是服务端权威数据，
// 离线时假装「喂过了」会让经验值/心情在两次登录之间对不上。

const { request, wait, fallback } = require('./core')

// 查宠物：读接口，断网时回落本地演示数据；401/403 仍然抛出
function getPet(userId) {
  return fallback(request(`/pet?userId=${userId}`, 'GET'), () => wait({
    id: 1, name: '小星', level: 3, exp: 50, expNeed: 350, expPercent: 14,
    hunger: 60, mood: 70, state: 'idle',
    canFeed: true, canTouch: true, canCheckin: true,
    points: 320, offline: true
  }, 80))
}

// 喂食：写接口，不兜底
function petFeed(userId) {
  return request('/pet/feed', 'POST', { userId })
}

// 摸摸：写接口，不兜底
function petTouch(userId) {
  return request('/pet/touch', 'POST', { userId })
}

// 签到：写接口，不兜底
function petCheckin(userId) {
  return request('/pet/checkin', 'POST', { userId })
}

module.exports = { getPet, petFeed, petTouch, petCheckin }
