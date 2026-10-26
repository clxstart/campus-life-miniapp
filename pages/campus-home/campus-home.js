/**
 * 校园首页
 *
 * 关键约束：
 * - 小院与悬浮宠物**共享同一份外观数据**，不各自维护
 * - 悬浮入口与展开态不同时跑动画（展开时收起悬浮球）
 * - 悬浮入口避开底部导航与安全区
 *
 * 交互设计：点宠物卡片**原地展开**大图互动，不跳转页面
 * （之前是 navigateTo 到互动页，用户会觉得“跳一下”）
 */
const petApi = require('../../utils/pet-api')
const { normalizeAppearance, DEFAULT_APPEARANCE } = require('../../utils/pet-appearance')
const { request } = require('../../utils/api')
const Toast = require('tdesign-miniprogram/toast/index')

const GREETINGS = ['早上好', '上午好', '中午好', '下午好', '晚上好']

/** 与引擎动作对应（已按需求去掉 sleepy 打瞌睡与 celebrate 跳起来） */
const ACTIONS = [
  { key: 'idle',  name: '待机', icon: '◡' },
  { key: 'happy', name: '开心', icon: '◠' },
  { key: 'sad',   name: '委屈', icon: '⌒' }
]

const TIPS = {
  idle: '它在发呆，偶尔眨眨眼',
  happy: '尾巴摇起来了',
  sad: '它有点小情绪'
}

/** 悬浮宠物被点时说的一句话 */
const FLOAT_LINES = [
  '要不要去食堂看看？',
  '今天也一起加油呀',
  '记得喝水哦',
  '我在这儿等你',
  '点我聊天，我有很多话想说'
]

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
    petMotion: 'idle',
    currentAction: 'idle',
    tip: TIPS.idle,
    actions: ACTIONS,
    quickEntries: [
      { key: 'market', label: '找二手', icon: '◫' },
      { key: 'activity', label: '看活动', icon: '✦' },
      { key: 'qa', label: '校园问答', icon: '？' },
      { key: 'task', label: '今日任务', icon: '✓' }
    ],
    feed: [],
    floatBottom: 200,
    /* 悬浮球外径 132rpx，内部宠物略小，留出白边呼吸感 */
    floatSize: 92,
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
  onUnload() {
    this._clearBubble()
    if (this._actionTimer) { clearTimeout(this._actionTimer); this._actionTimer = null }
  },

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

  // ===== 宠物互动（常驻大图，与首页内容同屏） =====
  /** 点宠物本体：播开心 */
  onPetTap() {
    this.setData({ petMotion: 'happy', currentAction: 'happy', tip: '被你摸到了，很开心' })
    this._restoreAction()
  },

  /** 选动作 */
  onAction(e) {
    const key = e.currentTarget.dataset.key
    if (!ACTIONS.some(a => a.key === key)) return
    this.setData({ petMotion: key, currentAction: key, tip: TIPS[key] || '' })
    if (key !== 'idle') this._restoreAction()
  },

  /** 动作播完回到待机 */
  _restoreAction() {
    if (this._actionTimer) clearTimeout(this._actionTimer)
    this._actionTimer = setTimeout(() => {
      this.setData({ petMotion: 'idle', currentAction: 'idle', tip: TIPS.idle })
    }, 4200)
  },

  // ===== 悬浮宠物（常驻右下角） =====
  /** 点悬浮宠物：只冒泡 + 让它开心一下，不跳页 */
  onFloatTap() {
    this._say(FLOAT_LINES[Math.floor(Math.random() * FLOAT_LINES.length)])
  },

  onFloatMove() { /* 预留：拖动时禁止页面滚动穿透 */ },

  // ===== 跳转 =====
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

  /** 进场时自动冒一句（延迟一点，避免刚进来就弹） */
  _greetBubble() {
    this._bubbleTimer = setTimeout(() => {
      this._say(FLOAT_LINES[0])
    }, 1500)
  },

  /** 让悬浮宠物说一句话，几秒后自动收起 */
  _say(text) {
    if (!text) return
    if (this._bubbleHide) { clearTimeout(this._bubbleHide); this._bubbleHide = null }
    this.setData({ bubbleText: text, hasBubble: true })
    this._bubbleHide = setTimeout(() => this._clearBubble(), 3800)
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
