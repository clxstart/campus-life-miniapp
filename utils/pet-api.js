/**
 * 校园搭子 API 服务层
 *
 * 职责：所有宠物相关网络请求集中在这里，组件不直接请求后端。
 *
 * 注意：utils/api.js 的 request() 已经做了两件事——
 *   1) 只在 { code: 0 } 时 resolve，否则 reject（带 server: true）
 *   2) resolve 的是已解包后的 data
 * 所以这里不再二次解包，直接把结果透出去。
 */
const { request } = require('./api')

module.exports = {
  // ===== 外观 =====
  getAppearance(userId) {
    return request(`/pet/appearance?userId=${encodeURIComponent(userId)}`, 'GET')
  },
  saveAppearance(userId, appearance) {
    const body = Object.assign({ userId }, appearance || {})
    return request('/pet/appearance', 'PUT', body)
  },
  resetAppearance(userId) {
    return request('/pet/appearance/reset', 'POST', { userId })
  },

  // ===== 衣柜 =====
  getWardrobe(userId) {
    return request(`/pet/wardrobe?userId=${encodeURIComponent(userId)}`, 'GET')
  },
  purchaseItem(userId, code) {
    return request('/pet/wardrobe/purchase', 'POST', { userId, code })
  },
  equipItem(userId, code) {
    return request('/pet/wardrobe/equip', 'POST', { userId, code })
  },
  unequipItem(userId) {
    return request('/pet/wardrobe/unequip', 'POST', { userId })
  },

  // ===== 派遣 =====
  getDispatch(userId) {
    return request(`/pet/dispatch?userId=${encodeURIComponent(userId)}`, 'GET')
  },
  startDispatch(userId, code) {
    return request('/pet/dispatch/start', 'POST', { userId, code })
  },
  claimDispatch(userId, dispatchId) {
    return request('/pet/dispatch/claim', 'POST', { userId, dispatchId })
  },

  // ===== 既有宠物接口 =====
  getPet(userId) {
    return request(`/pet?userId=${encodeURIComponent(userId)}`, 'GET')
  },
  rename(userId, name) {
    return request('/pet/name', 'PUT', { userId, name })
  }
}
