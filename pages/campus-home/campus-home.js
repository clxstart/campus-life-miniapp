/**
 * 校园首页
 *
 * 关键约束：
 * - 小院与悬浮宠物**共享同一份外观数据**，不各自维护
 * - 两处都用 pet-avatar（静态单帧），不同时跑两个动画实例
 * - 悬浮入口避开底部导航与安全区
 */
const petApi = require('../../utils/pet-api')
const { normalizeAppearance, DEFAULT_APPEARANCE } = require('../../utils/pet-appearance')
const { request } = require('../../utils/api')
const Toast = require('tdesign-miniprogram/toast/index')

const GREETINGS = ['早上好', '上午好', '中午好', '下午好', '晚上好']

Page({
  data: {
    statusBarHeight: 20,
    schoolName: '校园',
    campusName: '本部',
    greeting: '你好',
    appearance: { ...DEFAULT_APPEARANCE },
    petName: '小云',
    petLevel: 1,
    petExp: 0,
    expNext: 100,
    expPercent: 0,
    moodText: '今天心情不错',
    quickEntries: [
      { key: 'market', label: '找二手', icon: '◫' },
      { key: 'activity', label: '看活动', icon: '✦' },
      { key: 'qa', label: '校园问答', icon: '？' },
      { key: 'task', label: '今日任务', icon: '✓' }
    ],
    feed: [],
    floatCollapsed: false,
    floatBottom: 180,
    floatSize: 96,
    bubbleText: '',
    hasBubble: false
  },

  onLoad() {
    const metrics = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    this.setData({
      statusBarHeight: metrics.statusBarHeight || 20,
      greeting: greet()
    })
    this._readLocal()
    this._loadPet()
    this._loadFeed()
    this._greetBubble()
  },

  onShow() {
    // 从捏脸/衣柜返回：外观可能变了，重新同步
    this._loadPet()
  },

  onHide() { this._clearBubble() },
  onUnload() { this._clearBubble() },

  /** 本地信息：学校、校区 */
  _readLocal() {
    try {
      const profile = wx.getStorageSync('profile') || {}
      this.setData({
        schoolName: profile.school || '校园',
        campusName: profile.campus || '本部'
      })
    } catch (e) {}
  },

  /** 宠物资料 + 外观：小院与悬浮共用这一份 */
  async _loadPet() {
    const userId = getUserId()
    this._userId = userId
    try {
      const [pet, ap] = await Promise.all([
        petApi.getPet(userId).catch(() => null),
        petApi.getAppearance(userId).catch(() => null)
      ])
      if (ap) this.setData({ appearance: normalizeAppearance(ap) })
      if (pet) {
        const level = pet.level || 1
        const exp = pet.exp || 0
        const next = level * 100
        this.setData({
          petName: pet.name || '小云',
          petLevel: level,
          petExp: exp,
          expNext: next,
          expPercent: Math.min(100, Math.round((exp / next) * 100)),
          moodText: moodOf(pet.mood)
        })
      }
    } catch (error) { /* 静默：首页不因宠物接口失败而空白 */ }
  },

  /** 校园内容：复用现有帖子接口 */
  async _loadFeed() {
    try {
      const res = await request('/posts?page=1&size=5', 'GET')
      const list = Array.isArray(res) ? res : ((res && res.list) || [])
      this.setData({ feed: list.slice(0, 5).map(normalizePost) })
    } catch (error) {
      this.setData({ feed: [] })
    }
  },

  // ===== 互动 =====
  onFloatTap() {
    // 收起状态点一下展开；展开状态进聊天
    if (this.data.floatCollapsed) {
      this.setData({ floatCollapsed: false })
      return
    }
    this.onGoChat()
  },

  onFloatMove() { /* 预留：拖动时禁止页面滚动穿透 */ },

  onToggleFloat() {
    this.setData({ floatCollapsed: !this.data.floatCollapsed })
  },

  onOpenPet() { this.onGoAppearance() },
  onGoAppearance() { wx.navigateTo({ url: '/pages/appearance/appearance' }) },
  onGoWardrobe() { wx.navigateTo({ url: '/pages/wardrobe/wardrobe' }) },
  onGoDispatch() { wx.navigateTo({ url: '/pages/dispatch/dispatch' }) },
  onGoChat() { wx.navigateTo({ url: '/pages/companion/companion' }) },

  onQuick(e) {
    const key = e.currentTarget.dataset.key
    const routes = {
      market: '/pages/market-list/market-list',
      activity: '/pages/activities/activities',
      qa: '/pages/companion/companion',
      task: '/pages/dispatch/dispatch'
    }
    const url = routes[key]
    if (url) wx.navigateTo({ url })
  },

  onOpenFeed(e) {
    const id = e.currentTarget.dataset.id
    if (id) wx.navigateTo({ url: `/pages/post-detail/post-detail?id=${id}` })
  },

  /** 陪伴气泡：偶尔冒一句，不常驻 */
  _greetBubble() {
    const lines = [
      '今天也一起加油呀',
      '要不要去食堂看看？',
      '记得喝水哦',
      '我在这儿等你'
    ]
    const text = lines[Math.floor(Math.random() * lines.length)]
    this._bubbleTimer = setTimeout(() => {
      this.setData({ bubbleText: text, hasBubble: true })
      this._bubbleHide = setTimeout(() => this._clearBubble(), 4200)
    }, 1200)
  },

  _clearBubble() {
    if (this._bubbleTimer) { clearTimeout(this._bubbleTimer); this._bubbleTimer = null }
    if (this._bubbleHide) { clearTimeout(this._bubbleHide); this._bubbleHide = null }
    if (this.data.bubbleText) this.setData({ bubbleText: '', hasBubble: false })
  }
})

function greet() {
  const h = new Date().getHours()
  if (h < 6) return '夜深了，早点休息'
  if (h < 11) return GREETINGS[0] + '，今天也元气满满'
  if (h < 13) return GREETINGS[2] + '，去吃饭吧'
  if (h < 18) return GREETINGS[3] + '，下午的课还顺利吗'
  return GREETINGS[4] + '，今天辛苦了'
}

function moodOf(mood) {
  const m = Number(mood) || 0
  if (m >= 80) return '心情很好，想黏着你'
  if (m >= 50) return '心情还不错'
  if (m >= 20) return '有点无聊，逗逗它吧'
  return '有点低落，需要你陪陪'
}

function normalizePost(p) {
  if (!p) return null
  const tagMap = { 学习: '学习', 二手: '二手', 兼职: '兼职', 活动: '活动' }
  return {
    id: p.id,
    title: p.title || '无标题',
    summary: (p.content || '').slice(0, 56) + ((p.content || '').length > 56 ? '…' : ''),
    tagName: tagMap[p.tag] || p.learnCategory || '动态',
    author: p.authorNickname || p.nickname || '同学',
    timeText: relativeTime(p.createdAt)
  }
}

function relativeTime(v) {
  if (!v) return ''
  const d = new Date(String(v).replace(/-/g, '/'))
  if (isNaN(d.getTime())) return ''
  const diff = Date.now() - d.getTime()
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr} 小时前`
  return `${Math.floor(hr / 24)} 天前`
}

function getUserId() {
  try {
    const app = getApp()
    if (app && app.globalData && app.globalData.userId) return app.globalData.userId
  } catch (e) {}
  try {
    const profile = wx.getStorageSync('profile') || {}
    if (profile.id) return profile.id
  } catch (e) {}
  return 1
}
