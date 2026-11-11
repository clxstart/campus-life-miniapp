/**
 * 校园首页
 *
 * 学校问候 → 快捷入口 → 校园新鲜事。
 * 宠物模块已下线，此页不再依赖任何 pet-* 资源。
 */
const { request } = require('../../utils/api')

const GREETINGS = ['早上好', '上午好', '中午好', '下午好', '晚上好']

Page({
  data: {
    statusBarHeight: 20,
    schoolName: '校园',
    campusName: '本部',
    greeting: '你好',
    quickEntries: [
      { key: 'market', label: '找二手', icon: '◫' },
      { key: 'activity', label: '看活动', icon: '✦' },
      { key: 'qa', label: 'AI 助手', icon: '？' },
      { key: 'task', label: '我的积分', icon: '✓' }
    ],
    feed: []
  },

  onLoad() {
    this._alive = true
    const metrics = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    this.setData({
      statusBarHeight: metrics.statusBarHeight || 20,
      greeting: greet()
    })
    this._readLocal()
    this._loadFeed()
  },

  onShow() { this._loadFeed() },

  onUnload() { this._alive = false },

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

  /** 校园内容：复用现有帖子接口 */
  async _loadFeed() {
    try {
      const res = await request('/posts?page=1&size=5', 'GET')
      const list = Array.isArray(res) ? res : ((res && res.list) || [])
      if (!this._alive) return
      this.setData({ feed: list.slice(0, 5).map(normalizePost) })
    } catch (error) {
      if (this._alive) this.setData({ feed: [] })
    }
  },

  onQuick(e) {
    const key = e.currentTarget.dataset.key
    const routes = {
      market: '/pages/market-list/market-list',
      activity: '/pages/activities/activities',
      qa: '/pages/ai-chat/ai-chat',
      task: '/pages/points/points'
    }
    const url = routes[key]
    if (url) wx.navigateTo({ url })
  },

  onOpenFeed(e) {
    const id = e.currentTarget.dataset.id
    if (id) wx.navigateTo({ url: `/pages/post-detail/post-detail?id=${id}` })
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