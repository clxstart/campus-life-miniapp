/**
 * 聊天页
 *
 * 状态要求：发送中 / 失败可重试 / 无结果 / Mock 演示 都要有清楚表现
 */
const petApi = require('../../utils/pet-api')
const petChat = require('../../utils/pet-chat')
const { normalizeAppearance, DEFAULT_APPEARANCE } = require('../../utils/pet-appearance')
const Toast = require('tdesign-miniprogram/toast/index')

const QUICK_ASK = ['二食堂今天有什么', '哪里适合自习', '有二手教材吗']

Page({
  data: {
    statusBarHeight: 20,
    appearance: { ...DEFAULT_APPEARANCE },
    petName: '小云',
    messages: [],
    input: '',
    canSend: false,
    quickAsk: QUICK_ASK,
    scrollTo: ''
  },

  onLoad() {
    const metrics = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    this.setData({ statusBarHeight: metrics.statusBarHeight || 20 })
    this._userId = getUserId()
    this._seq = 0
    this._load()
    this._greet()
  },

  async _load() {
    try {
      const [ap, pet] = await Promise.all([
        petApi.getAppearance(this._userId),
        petApi.getPet(this._userId).catch(() => null)
      ])
      if (ap) this.setData({ appearance: normalizeAppearance(ap) })
      if (pet && pet.name) this.setData({ petName: pet.name })
    } catch (error) { /* 静默：聊天页外观失败不影响使用 */ }
  },

  _greet() {
    this._push({
      role: 'pet',
      content: `我是${this.data.petName}～有什么想知道的校园的事，都可以问我。`,
      status: 'done',
      mock: false
    })
  },

  onInput(e) {
    const v = e.detail.value || ''
    this.setData({ input: v, canSend: v.trim().length > 0 })
  },

  onQuickAsk(e) {
    const text = e.currentTarget.dataset.text
    if (!text) return
    this.setData({ input: text, canSend: true }, () => this.onSend())
  },

  async onSend() {
    const text = (this.data.input || '').trim()
    if (!text) return

    // 用户消息
    this._push({ role: 'user', content: text, status: 'done', mock: false })
    // 宠物占位（发送中）
    const petMsg = this._push({
      role: 'pet', content: '', status: 'sending', mock: false
    })
    this.setData({ input: '', canSend: false })
    this._scrollToBottom()

    await this._request(text, petMsg.id)
  },

  /** 实际请求；失败时把消息标成 failed 并保留原文以便重试 */
  async _request(text, msgId) {
    try {
      const res = await petChat.chat({ userId: this._userId, text })
      this._update(msgId, {
        content: (res && res.content) || '我没太明白，能再说一次吗？',
        status: 'done',
        mock: !!(res && res.mock),
        result: (res && res.result) || null,
        _source: text
      })
    } catch (error) {
      this._update(msgId, {
        status: 'failed',
        failReason: (error && error.message) || '网络不太好',
        content: '',
        _source: text
      })
    }
    this._scrollToBottom()
  },

  onRetry(e) {
    const id = Number(e.currentTarget.dataset.id)
    const msg = this.data.messages.find(m => m.id === id)
    if (!msg || !msg._source) return
    this._update(id, { status: 'sending', failReason: '' })
    this._request(msg._source, id)
  },

  onOpenResult(e) {
    const { type, targetId } = e.detail || {}
    if (!targetId) {
      Toast({ context: this, selector: '#t-toast', message: '演示卡片，暂无可跳转详情', theme: 'default' })
      return
    }
    const routes = {
      goods: `/pages/market-detail/market-detail?id=${targetId}`,
      activity: `/pages/activities/activities?id=${targetId}`,
      job: `/pages/jobs/jobs?id=${targetId}`,
      post: `/pages/post-detail/post-detail?id=${targetId}`,
      circle: `/pages/circle-detail/circle-detail?id=${targetId}`
    }
    const url = routes[type]
    if (url) wx.navigateTo({ url })
  },

  // ===== 消息工具 =====
  _push(msg) {
    const id = ++this._seq
    const list = this.data.messages.concat([{ id, ...msg }])
    this.setData({ messages: list })
    return { id }
  },

  _update(id, patch) {
    const idx = this.data.messages.findIndex(m => m.id === id)
    if (idx < 0) return
    const data = {}
    Object.keys(patch).forEach(k => { data[`messages[${idx}].${k}`] = patch[k] })
    this.setData(data)
  },

  _scrollToBottom() {
    const list = this.data.messages
    if (!list.length) return
    const last = list[list.length - 1]
    this.setData({ scrollTo: `msg-${last.id}` })
  }
})

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
