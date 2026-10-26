// 请求内核：地址、登录态、鉴权门、统一请求入口。
//
// 从 utils/api.js 抽出来的第一块（原来 900 多行的上帝模块）：
// 只负责「怎么发一个请求」和「我是谁」，不关心帖子、外卖、二手这些业务。
// 页面仍然只 import utils/api.js，这里属于内部实现，不直接对外。

const dataMode = require('../data-mode')
const config = require('../config')

const BASE_URL = config.BASE_URL
// 未登录时的演示账号：本地兜底数据都挂在它名下，保持与后端种子数据一致。
const DEMO_USER_ID = 1
const TOKEN_KEY = 'campus_auth_token'
const USER_KEY = 'campus_auth_user'

// ---- 登录态：token 存本地，请求自动携带；未登录时回落到 DEMO_USER_ID 的演示行为 ----
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
  return DEMO_USER_ID
}
function isLoggedIn() { return !!getToken() }

function clone(data) { return JSON.parse(JSON.stringify(data)) }
function wait(data, delay = 160) {
  dataMode.setDemo(true)
  return new Promise(resolve => setTimeout(() => resolve(clone(data)), delay))
}

// 真正发请求
function send(url, method, data, options) {
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
          // 401 = token 失效/缺失，清掉本地登录态，下次请求会重新登录；
          // 403 = 已登录但无权访问（例如操作别人的订单），此时清 token 只会把用户莫名踢下线，所以只报错不清。
          if (res.statusCode === 401) clearAuth()
          reject(Object.assign(new Error(body.msg || `请求失败（${res.statusCode}）`), { server: true, status: res.statusCode }))
        }
      },
      fail(error) { dataMode.connection(url, false); reject(error) }
    })
  })
}

// ---- 登录 ----
function authStatus() {
  return request('/auth/status', 'GET', {}, { timeout: 3000 }).catch(() => ({ devLogin: false, offline: true }))
}
function loginWithWxCode(code) {
  return request('/auth/login', 'POST', { code }).then(res => { setAuth(res.token, res); return res })
}
// 开发登录：未配置微信 AppID 时使用，保证 touristappid 下也能拿到 token
function devLogin(userId = DEMO_USER_ID) {
  return request('/auth/dev-login', 'POST', { userId }).then(res => { setAuth(res.token, res); return res })
}

// 登录进行中的 Promise：request() 会等它，避免冷启动时抢在 token 之前发请求
let authPending = null

// 登录包装：优先微信登录，失败时在开启开发登录的环境下自动降级
function ensureLogin() {
  if (isLoggedIn()) return Promise.resolve({ userId: currentUserId(), cached: true })
  if (authPending) return authPending
  authPending = new Promise((resolve) => {
    if (typeof wx.login !== 'function') return resolve(devLogin().catch(() => null))
    wx.login({
      success: (res) => {
        if (!res || !res.code) return resolve(devLogin().catch(() => null))
        loginWithWxCode(res.code).catch(() => devLogin().catch(() => null)).then(resolve)
      },
      fail: () => { devLogin().catch(() => null).then(resolve) }
    })
  }).then(res => { authPending = null; return res })
  return authPending
}

// 统一入口：需要身份的请求在没有 token 时先等登录（没有登录就主动发起一次），
// 公开只读接口不等待，避免无网络时首屏被登录拖慢。
const AUTH_PATHS = ['/auth/status', '/auth/login', '/auth/dev-login']
// 与后端 AuthInterceptor 的「我的数据」前缀保持一致
const PRIVATE_READ_PREFIXES = ['/conversations', '/notifications', '/timetable', '/calendar', '/pet', '/users/', '/circle-messages']
const PUBLIC_WRITE_PATHS = ['/ai/', '/upload']

function isAuthPath(url) { return AUTH_PATHS.some(prefix => url.indexOf(prefix) === 0) }
function needsIdentity(url, method) {
  const verb = String(method || 'GET').toUpperCase()
  if (verb === 'GET') return PRIVATE_READ_PREFIXES.some(prefix => url.indexOf(prefix) === 0)
  return !PUBLIC_WRITE_PATHS.some(prefix => url.indexOf(prefix) === 0)
}

function request(url, method = 'GET', data = {}, options = {}) {
  if (isAuthPath(url) || !needsIdentity(url, method) || getToken()) return send(url, method, data, options)
  const pending = authPending || ensureLogin().catch(() => null)
  return Promise.race([
    Promise.resolve(pending).then(() => null),
    new Promise(resolve => setTimeout(() => resolve(null), 2500))
  ]).then(() => send(url, method, data, options))
}

// 离线兜底的唯一规则：
//   网络不通（后端没起来 / 断网）→ 用本地演示数据，页面还能看；
//   服务端有回应但报错（401 未登录、403 越权、409 冲突、500）→ 原样抛出，绝不能用假数据盖住。
//
// 以前每个接口各写一遍 .catch()，其中十来个把服务端错误也一起吞了：
// 未登录时「我的收藏」显示成空列表、越权被拒却像成功了一样，用户完全看不出发生了什么。
function fallback(apiCall, mockFactory) {
  return apiCall.catch(error => {
    if (error && error.server) throw error
    return mockFactory()
  })
}

module.exports = {
  BASE_URL, DEMO_USER_ID,
  getToken, setAuth, clearAuth, currentUserId, isLoggedIn,
  request, send, wait, clone, fallback,
  authStatus, loginWithWxCode, devLogin, ensureLogin
}
