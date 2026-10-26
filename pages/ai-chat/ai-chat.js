const api = require('../../utils/api')
const school = require('../../utils/school')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')

// 本地对话历史（存 storage，进页面恢复；最多保留 60 条）
const HISTORY_KEY = 'campus_ai_history'
const MAX_HISTORY = 60
const MAX_INPUT = 200

// 常驻关键词：后端配置读不到时用它，保证底部永远有可点的词
const DEFAULT_CHIPS = ['今天吃什么', '有什么活动', '有兼职吗', '我该学习了吗', '好困想睡', '陪我聊聊']
const WELCOME_FALLBACK = '你好呀，我是你的校园小助手。可以问我今天吃什么、有什么活动、有兼职吗，也可以随便聊聊。'

function loadHistory() {
  try {
    const raw = wx.getStorageSync(HISTORY_KEY)
    if (!raw) return []
    const list = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (!Array.isArray(list)) return []
    return list.filter(item => item && item.role && item.text).slice(-MAX_HISTORY)
  } catch (error) {
    return []
  }
}

function saveHistory(list) {
  try { wx.setStorageSync(HISTORY_KEY, JSON.stringify(list.slice(-MAX_HISTORY))) } catch (error) {}
}

let seq = 0
function makeMessage(role, text, quickReplies) {
  seq += 1
  return {
    id: `${Date.now()}-${seq}`,
    role,
    text: String(text || ''),
    quickReplies: Array.isArray(quickReplies) ? quickReplies : []
  }
}

Page({
  data: {
    ...getNavigationMetrics(),
    messages: [],
    quickReplies: [],
    // 常驻关键词：快捷追问为空时用它兜底，保证底部永远有可点的词
    starterChips: DEFAULT_CHIPS,
    inputValue: '',
    sending: false,
    offline: false,
    scrollTarget: '',
    uiPopup: null
  },

  onLoad() {
    this._alive = true
    this.loadWelcome()

    const history = loadHistory()
    if (history.length) {
      // 关键：从历史里恢复「最后一条带快捷追问的 AI 消息」，
      // 否则第二次进入页面底部就没有关键词可点了
      const lastWithChips = [...history].reverse()
        .find(item => item.role === 'ai' && item.quickReplies && item.quickReplies.length)
      this.setData({
        messages: history,
        quickReplies: lastWithChips ? lastWithChips.quickReplies : []
      })
      this.scrollToBottom()
    } else {
      const welcome = makeMessage('ai', WELCOME_FALLBACK, DEFAULT_CHIPS)
      this.setData({ messages: [welcome], quickReplies: welcome.quickReplies })
      saveHistory([welcome])
    }
  },

  // 欢迎语和常驻关键词都从后端配置读（管理端可改），失败用本地兜底
  async loadWelcome() {
    try {
      const data = await api.getAiWelcome()
      if (!this._alive || !data) return
      const chips = Array.isArray(data.quickReplies) && data.quickReplies.length
        ? data.quickReplies : DEFAULT_CHIPS

      // 「只有一条欢迎语」= 还没真正聊过 → 用后端配置的欢迎语替换
      const messages = this.data.messages || []
      const fresh = messages.length <= 1 && (!messages.length || messages[0].role === 'ai')
      if (fresh) {
        const welcome = makeMessage('ai', data.reply || WELCOME_FALLBACK, chips)
        this.setData({ messages: [welcome], quickReplies: chips, starterChips: chips })
        saveHistory([welcome])
        return
      }

      // 已经有真实对话 → 只更新常驻关键词，不动历史
      const patch = { starterChips: chips }
      if (!this.data.quickReplies.length) patch.quickReplies = chips
      this.setData(patch)
    } catch (error) {
      // 读不到就用本地兜底，不打扰用户
    }
  },

  onUnload() { this._alive = false },

  // 底部留白跟着键盘高度走，避免输入框被遮
  onKeyboard(e) {
    const height = e && e.detail ? Number(e.detail.height) || 0 : 0
    this.setData({ keyboardHeight: height })
    if (height > 0) this.scrollToBottom()
  },

  inputMessage(e) {
    this.setData({ inputValue: e.detail.value })
  },

  // 点快捷追问 = 直接发这句话
  tapQuick(e) {
    const text = e.currentTarget.dataset.text
    if (!text || this.data.sending) return
    this.send(text)
  },

  sendCurrent() {
    this.send(this.data.inputValue)
  },

  async send(rawText) {
    if (this.data.sending) return
    const text = String(rawText || '').trim()
    if (!text) return
    if (text.length > MAX_INPUT) {
      popup.toast(this, `消息不能超过 ${MAX_INPUT} 字`, { tone: 'warn' })
      return
    }

    const userMessage = makeMessage('user', text)
    const messages = this.data.messages.concat(userMessage)
    this.setData({
      messages,
      inputValue: '',
      sending: true,
      quickReplies: []
    })
    saveHistory(messages)
    this.scrollToBottom()

    try {
      const res = await api.aiChat(text, { schoolId: school.getSelectedId() })
      if (!this._alive) return
      const reply = makeMessage('ai', res.reply, res.quickReplies)
      const next = this.data.messages.concat(reply)
      this.setData({
        messages: next,
        quickReplies: reply.quickReplies,
        offline: !!res.offline
      })
      saveHistory(next)
    } catch (error) {
      if (!this._alive) return
      const fail = makeMessage('ai', error.message || '我这边出了点问题，再说一次好吗？', [])
      const next = this.data.messages.concat(fail)
      this.setData({ messages: next, quickReplies: [] })
      saveHistory(next)
    } finally {
      if (this._alive) {
        this.setData({ sending: false })
        this.scrollToBottom()
      }
    }
  },

  scrollToBottom() {
    const list = this.data.messages || []
    if (!list.length) return
    this.setData({ scrollTarget: `msg-${list[list.length - 1].id}` })
  },

  // 清空对话
  clearChat() {
    popup.confirm(this, {
      title: '清空对话',
      message: '清空后之前的聊天记录就找不回来了',
      confirmText: '清空',
      onConfirm: () => {
        const welcome = makeMessage('ai',
          '我们重新开始吧。想聊点什么？',
          ['今天吃什么', '有什么活动', '我该学习了吗'])
        this.setData({ messages: [welcome], quickReplies: welcome.quickReplies })
        saveHistory([welcome])
      }
    })
  },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) { wx.navigateBack() } else { wx.reLaunch({ url: '/pages/index/index' }) }
  },

  onPopupConfirm(e) { this._popupConfirm && this._popupConfirm({ detail: e.detail }) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(e) { this._popupAction && this._popupAction({ detail: e.detail }) },
  onPopupInput(e) { this._popupInput && this._popupInput({ detail: e.detail }) },
  onPopupClose() { popup.hide(this) }
})
