// 积分域：积分/成长值/段位、签到连签、任务明细、兑换商品池、论坛榜单、
// 学生认证、宿舍域、二手成交确认。
//
// 一条贯穿本文件的约定（与 docs/v3.1 及前端总览 3.7 一致）：
//   读接口（me/tasks/logs/rewards/rank/config/dorm/trades）断网时回落「上次缓存 → 演示数据」，
//   页面至少还能看见东西；服务端明确拒绝（401/403/400）仍然原样抛出。
//   写接口（签到/兑换/认证/建圈/建宿舍/加入/退出/成交确认）一律不兜底：
//   这些动作要落库才算数，离线假装成功会让用户以为积分到账、成交生效。

const { request, wait, fallback } = require('./core')

// 离线缓存键：断网时优先给上次真实数据，没有才给演示数据
const CACHE_ME = 'points_me_cache'
const CACHE_DORM = 'dorm_me_cache'

function readCache(key) {
  try { return wx.getStorageSync(key) || null } catch (error) { return null }
}
function writeCache(key, data) {
  try { wx.setStorageSync(key, data) } catch (error) {}
}

// ---- 演示数据：只在「网络不通」时使用，字段与后端契约保持一致 ----
function demoMe() {
  return {
    balance: 63,
    totalEarned: 128,
    valueYuan: 0.63,
    gTotal: 142,
    signedToday: false,
    streakDays: 4,
    nextMilestone: { days: 5, taskKey: 'sign_streak_5', p: 10, g: 10 },
    tier: {
      code: 'silver', name: '白银·圈内熟人', gTotal: 142, gThreshold: 60,
      nextCode: 'gold', nextName: '黄金·活跃分子', nextThreshold: 200,
      percent: 58, maxOpen: false
    },
    privileges: {
      priorityReply: true, postFastlane: false, circleCreate: false,
      effectiveTierCode: 'silver', trialExpiresAt: null
    },
    // 新人礼包是否还没弹过：后端在建号时发一次，前端只负责「弹一次」
    newbieGift: false,
    offline: true
  }
}

// 与后端 GET /points/tasks 同形：直接是数组（不是 {list} 包一层）
function demoTasks() {
  return [
    { taskKey: 'daily_sign', name: '每日签到', module: 'points', p: 1, g: 1, dailyCap: 1, doneToday: 0, available: true, done: false, progress: 0 },
    { taskKey: 'post_create', name: '发论坛帖', module: 'forum', p: 5, g: 5, dailyCap: 3, doneToday: 1, available: true, done: false, progress: 1 },
    { taskKey: 'food_order', name: '外卖点单完成', module: 'commerce', p: 10, g: 20, dailyCap: 2, doneToday: 0, available: true, done: false, progress: 0 }
  ]
}

function demoLogs(page, size) {
  const rows = [
    { id: 3, amount: 5, type: 'post_create', module: 'forum', remark: '发论坛帖', balanceAfter: 63, createdAt: '2026-11-08 21:30' },
    { id: 2, amount: 1, type: 'daily_sign', module: 'points', remark: '每日签到', balanceAfter: 58, createdAt: '2026-11-08 08:10' },
    { id: 1, amount: 30, type: 'newbie_gift', module: 'points', remark: '新人礼包', balanceAfter: 57, createdAt: '2026-11-07 19:02' }
  ]
  return { list: page === 1 ? rows.slice(0, size) : [], page, size, hasMore: false, offline: true }
}

// 与后端 GET /points/rewards 同形：{balance, valueYuan, pointsPerYuan, items}
function demoRewards() {
  return {
    balance: 63,
    valueYuan: '0.63',
    pointsPerYuan: 100,
    items: [
      { itemKey: 'takeout_1', name: '外卖立减 1 元', priceP: 100, cashCents: 0, stock: 198, perUserLimit: 3, myRedeemed: 0, soldOut: false, limitReached: false, enabled: 1, couponTitle: '外卖立减 1 元' },
      { itemKey: 'takeout_0_5', name: '外卖立减 0.5 元', priceP: 50, cashCents: 0, stock: 300, perUserLimit: 6, myRedeemed: 0, soldOut: false, limitReached: false, enabled: 1, couponTitle: '外卖立减 0.5 元' },
      { itemKey: 'takeout_0_25', name: '外卖立减 0.25 元', priceP: 25, cashCents: 0, stock: 400, perUserLimit: 8, myRedeemed: 0, soldOut: false, limitReached: false, enabled: 1, couponTitle: '外卖立减 0.25 元' },
      { itemKey: 'takeout_0_1', name: '外卖立减 0.1 元', priceP: 10, cashCents: 0, stock: 500, perUserLimit: 10, myRedeemed: 0, soldOut: false, limitReached: false, enabled: 1, couponTitle: '外卖立减 0.1 元' }
    ],
    offline: true
  }
}

function demoRank(type) {
  const periodKey = type === 'weekly' ? '2026-W45' : '2026-11-08'
  return {
    type: type === 'weekly' ? 'weekly' : 'daily',
    periodKey,
    settled: false,
    rows: [
      { rankNo: 1, userId: 3, nickname: '陈一川', avatar: '', score: 86, awarded: 10 },
      { rankNo: 2, userId: 1, nickname: '林知夏', avatar: '', score: 71, awarded: 10 },
      { rankNo: 3, userId: 7, nickname: '周舟', avatar: '', score: 64, awarded: 10 },
      { rankNo: 4, userId: 9, nickname: '许南', avatar: '', score: 42, awarded: 10 },
      { rankNo: 27, userId: 2, nickname: '我', avatar: '', score: 18, awarded: 0 }
    ],
    me: { rankNo: 27, score: 18, awarded: 0 },
    offline: true
  }
}

// ---- 积分 / 成长值 / 段位 ----
// 我的积分：余额、累计、成长值、段位、今日签到态、特权一次性返回，首屏只发一个请求
function getPointsMe() {
  return fallback(
    request('/points/me', 'GET').then(me => { writeCache(CACHE_ME, me); return me }),
    () => wait({ ...demoMe(), ...(readCache(CACHE_ME) || {}), offline: true }, 80)
  )
}

// 今日任务目录 + 进度
function getPointsTasks() {
  return fallback(request('/points/tasks', 'GET'), () => wait(demoTasks(), 80))
}

// 积分明细（倒序分页）
function getPointsLogs(params = {}) {
  const page = Math.max(1, Math.floor(Number(params.page) || 1))
  const size = Math.min(50, Math.max(1, Math.floor(Number(params.size) || 20)))
  return fallback(
    request(`/points/logs?page=${page}&size=${size}`, 'GET'),
    () => wait(demoLogs(page, size), 80)
  )
}

// 每日签到：写接口，不兜底。返回签名见 docs/v3.1/签到与连签 第五节
function signPoints() {
  return request('/points/sign', 'POST', {})
}

// ---- 兑换 ----
function getPointsRewards() {
  return fallback(request('/points/rewards', 'GET'), () => wait(demoRewards(), 80))
}

// 兑换：写接口，不兜底（扣积分 + 发券 + 扣库存，必须服务端确认）
function redeemPoints(itemKey) {
  if (!itemKey) return Promise.reject(new Error('请选择要兑换的商品'))
  return request('/points/redeem', 'POST', { itemKey })
}

// ---- 论坛日榜 / 周榜 ----
function getForumRank(type = 'daily') {
  const board = type === 'weekly' ? 'weekly' : 'daily'
  return fallback(
    request(`/forum/rank?type=${board}`, 'GET'),
    () => wait(demoRank(board), 80)
  )
}

// ---- 学生认证 ----
// 提交认证：写接口，不兜底（要落库、要限频、要进人工审核队列）
function applyVerification(payload = {}) {
  return request('/verification/apply', 'POST', {
    schoolId: payload.schoolId || null,
    college: payload.college || '',
    realName: payload.realName || '',
    studentNo: payload.studentNo || '',
    phone: payload.phone || ''
  })
}

// 认证配置：公开接口，断网回落「姓名 + 学号必填」的默认口径
function getVerificationConfig() {
  return fallback(request('/verification/config', 'GET'), () => wait({
    enabled: false,
    require: { realName: true, studentNo: true, college: false, phone: false },
    studentNoPattern: '',
    reviewMode: 'manual',
    dailyLimit: 3,
    offline: true
  }, 80))
}

// ---- 宿舍域 ----
function getDormMe() {
  return fallback(
    request('/dorm/me', 'GET').then(dorm => { writeCache(CACHE_DORM, dorm); return dorm }),
    () => wait(readCache(CACHE_DORM) || { joined: false, offline: true }, 80)
  )
}

// 创建宿舍：写接口，不兜底（要校验认证、生成唯一邀请码）
function createDorm(payload = {}) {
  return request('/dorm', 'POST', {
    name: payload.name || '',
    building: payload.building || '',
    room: payload.room || ''
  })
}

// 用邀请码加入：写接口，不兜底
function joinDorm(inviteCode) {
  const code = String(inviteCode || '').trim().toUpperCase()
  if (!code) return Promise.reject(new Error('请输入邀请码'))
  return request('/dorm/join', 'POST', { inviteCode: code })
}

// 退出宿舍：写接口，不兜底
function leaveDorm() {
  return request('/dorm/leave', 'DELETE')
}

// ---- 圈子创建（黄金特权） ----
// 写接口，不兜底：建圈要过段位校验与内容审核，前端不能假装成功
function createCircle(payload = {}) {
  return request('/circles', 'POST', {
    name: payload.name || '',
    category: payload.category || '',
    description: payload.description || '',
    cover: payload.cover || ''
  })
}

// ---- 二手成交确认 ----
// 买家发起：写接口，不兜底
function startMarketTrade(goodsId) {
  if (!goodsId) return Promise.reject(new Error('缺少商品编号'))
  return request(`/market/${encodeURIComponent(goodsId)}/trade`, 'POST', {})
}

// 卖家确认成交：写接口，不兜底（确认后双方各发 5 积分）
function confirmMarketTrade(tradeId) {
  if (!tradeId) return Promise.reject(new Error('缺少成交编号'))
  return request(`/market/trade/${encodeURIComponent(tradeId)}/confirm`, 'POST', {})
}

// 卖家/买家取消待确认成交：写接口，不兜底
function cancelMarketTrade(tradeId) {
  if (!tradeId) return Promise.reject(new Error('缺少成交编号'))
  return request(`/market/trade/${encodeURIComponent(tradeId)}/cancel`, 'POST', {})
}

// 我的发布 + 成交记录（卖家视角）：读接口，断网回落空列表
function getMyMarketTrades() {
  return fallback(request('/market/trades/mine', 'GET'), () => wait({ list: [], offline: true }, 80))
}

module.exports = {
  getPointsMe, getPointsTasks, getPointsLogs, signPoints,
  getPointsRewards, redeemPoints,
  getForumRank,
  applyVerification, getVerificationConfig,
  getDormMe, createDorm, joinDorm, leaveDorm,
  createCircle,
  startMarketTrade, confirmMarketTrade, cancelMarketTrade, getMyMarketTrades
}