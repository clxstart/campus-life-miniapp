const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { formatTime } = require('../../utils/format-time')
const popup = require('../../utils/ui-popup-helper')

function messageCard(message) {
  const senderId = Number(message.senderId)
  const mine = senderId === Number(api.currentUserId())
  const name = mine ? '我' : (message.senderName || '校园同学')
  return {
    ...message,
    id: message.id,
    mine,
    senderName: name,
    avatar: message.senderAvatar || '',
    avatarText: name.slice(0, 1),
    messageType: message.messageType || message.type || 'text',
    time: formatTime(message.createdAt),
    pending: !!message.pending
  }
}

Page({
  data: {
    ...getNavigationMetrics(),
    circleName: '圈子群聊',
    circleId: 0,
    messages: [],
    inputValue: '',
    onlineCount: 0,
    onlineNames: '',
    loading: true,
    loadingMore: false,
    hasMore: false,
    sending: false,
    uploading: false,
    connected: false,
    error: '',
    scrollIntoView: 'msg-bottom',
    offline: false,
    uiPopup: null
  },

  onLoad(options) {
    this._alive = true
    this.circleId = Number(options.id)
    this.setData({
      circleId: this.circleId,
      circleName: options.name ? this.safeDecode(options.name) : '圈子群聊'
    })
    if (!Number.isSafeInteger(this.circleId) || this.circleId < 1) {
      this.setData({ loading: false, error: '圈子不存在' })
      return
    }
    this._unsubscribe = getApp().onCircleMessage(message => this.onSocketMessage(message))
    api.ensureLogin()
      .then(() => this.loadMessages())
      .then(() => this.connectSocket())
      .then(() => this.loadMessages({ afterId: this.lastMessageId() || 0 }))
      .catch(error => {
        if (this._alive) this.setData({ loading: false, error: error.message || '群聊加载失败' })
      })
      .finally(() => { this._loaded = true })
  },

  onShow() {
    // 从别的页面返回、或全局连接被其他群聊替换后，重新建立本圈连接。
    if (!this._loaded || !this._alive) return
    const app = getApp()
    if (!app.globalData.socket || app.globalData.socketCircleId !== this.circleId) this.connectSocket()
  },

  onUnload() {
    this._alive = false
    if (this._unsubscribe) this._unsubscribe()
    const last = this.lastMessageId()
    if (last) api.markCircleRead(this.circleId, last).catch(() => {})
    const app = getApp()
    // 仅关闭本页持有的连接，避免离开旧群聊时误关新群聊的连接。
    if (app.globalData.socketCircleId === this.circleId) app.closeCircleChat()
  },

  safeDecode(value) {
    try { return decodeURIComponent(String(value)) } catch (error) { return String(value) }
  },

  async connectSocket() {
    try {
      await getApp().connectCircleChat(this.circleId)
      if (this._alive) this.setData({ connected: true, error: '' })
    } catch (error) {
      if (this._alive) this.setData({ connected: false, error: '实时连接失败，正在尝试重连' })
    }
  },

  async loadMessages(options = {}) {
    if (!this._alive) return
    if (this._loadingMessages) {
      if (options.afterId != null) {
        const cursor = Number(options.afterId)
        this._pendingAfterId = this._pendingAfterId == null ? cursor : Math.min(this._pendingAfterId, cursor)
      }
      return
    }
    const older = !!options.older
    const afterId = options.afterId
    if (older && (!this.data.hasMore || this.data.loadingMore)) return
    this._loadingMessages = true
    let beforeId
    if (older) {
      for (const message of this.data.messages) {
        if (Number.isSafeInteger(Number(message.id)) && Number(message.id) > 0) { beforeId = message.id; break }
      }
    }
    this.setData(older ? { loadingMore: true } : { loading: true, error: '' })
    try {
      let result = await api.getCircleMessages(this.circleId, { beforeId, afterId, size: 30 })
      if (!this._alive) return
      let rows = (result.list || []).slice()
      let cursor = rows.length ? Number(rows[rows.length - 1].id) : Number(afterId)
      while (afterId != null && result.hasMore && rows.length < 300 && cursor > 0) {
        result = await api.getCircleMessages(this.circleId, { afterId: cursor, size: 30 })
        rows = rows.concat(result.list || [])
        if (result.list && result.list.length) cursor = Number(result.list[result.list.length - 1].id)
        else break
      }
      const cards = rows.map(messageCard)
      this.mergeMessages(cards, !older)
      this.setData({
        hasMore: older ? !!result.hasMore : (afterId != null ? this.data.hasMore : !!result.hasMore),
        offline: !!result.offline,
        loading: false,
        loadingMore: false,
        error: ''
      })
      const last = this.lastMessageId()
      if (last && !older) api.markCircleRead(this.circleId, last).catch(() => {})
    } catch (error) {
      if (this._alive) this.setData({ loading: false, loadingMore: false, error: error.message || '消息加载失败' })
    } finally {
      this._loadingMessages = false
      if (this._alive && this._pendingAfterId != null) {
        const pendingAfterId = this._pendingAfterId
        this._pendingAfterId = null
        this.loadMessages({ afterId: pendingAfterId })
      }
    }
  },

  loadOlder() { return this.loadMessages({ older: true }) },

  onSocketMessage(message) {
    if (!this._alive || !message) return
    // 全局只有一个连接，页面仍在栈里时也可能收到别的群聊事件，先按圈子过滤。
    if (message.type === 'socket') {
      if (!message.data || Number(message.data.circleId) !== this.circleId) return
      if (message.data.event === 'close') {
        this.setData({ connected: false })
        return
      }
      const reconnect = this._socketOpened
      this._socketOpened = true
      this.setData({ connected: true, error: '' })
      if (reconnect) this.loadMessages({ afterId: this.lastMessageId() || 0 })
      return
    }
    if (message.type === 'message') {
      if (!message.data || Number(message.data.circleId) !== this.circleId) return
      this.mergeMessages([messageCard(message.data)], true)
      const id = Number(message.data.id)
      if (id > 0) api.markCircleRead(this.circleId, id).catch(() => {})
      this.setData({ connected: true })
      return
    }
    if (Number(getApp().globalData.socketCircleId) !== this.circleId) return
    if (message.type === 'online') {
      const users = message.data && Array.isArray(message.data.users) ? message.data.users : []
      const names = users.slice(0, 3).map(user => user.userName).filter(Boolean)
      const remaining = Math.max(0, users.length - names.length)
      const onlineNames = names.join('、') + (remaining ? ` 等${users.length}人` : '')
      this.setData({ onlineCount: Number(message.data && message.data.count) || 0, onlineNames })
      return
    }
    if (message.type === 'error') {
      this.setData({ connected: false, error: (message.data && message.data.msg) || '群聊连接被拒绝' })
    }
  },

  mergeMessages(incoming, scroll) {
    let rows = this.data.messages.slice()
    incoming.forEach(next => {
      rows = rows.filter(old => !(next.clientId && old.clientId === next.clientId) && String(old.id) !== String(next.id))
      rows.push(next)
    })
    rows.sort((a, b) => {
      const ai = Number(a.id); const bi = Number(b.id)
      if (Number.isFinite(ai) && Number.isFinite(bi)) return ai - bi
      return String(a.createdAt || '').localeCompare(String(b.createdAt || ''))
    })
    this.setData({ messages: rows })
    if (scroll) this.scrollToBottom()
  },

  lastMessageId() {
    for (let i = this.data.messages.length - 1; i >= 0; i--) {
      const id = Number(this.data.messages[i].id)
      if (Number.isSafeInteger(id) && id > 0) return id
    }
    return 0
  },

  inputMessage(event) { this.setData({ inputValue: event.detail.value }) },

  async sendMessage() {
    const content = String(this.data.inputValue || '').trim()
    if (!content || this.data.sending || this.data.uploading) return
    await this.sendPayload({ type: 'text', content })
  },

  chooseImage() {
    if (this.data.sending || this.data.uploading) return
    wx.chooseImage({ count: 1, sizeType: ['compressed'], sourceType: ['album', 'camera'], success: async result => {
      const filePath = result.tempFilePaths && result.tempFilePaths[0]
      if (!filePath) return
      this.setData({ uploading: true })
      try {
        const uploaded = await api.uploadImage(filePath)
        await this.sendPayload({ type: 'image', image: uploaded && uploaded.url })
      } catch (error) {
        if (this._alive) popup.toast(this, error.message || '图片发送失败', { tone: 'error' })
      } finally { if (this._alive) this.setData({ uploading: false }) }
    } })
  },

  async sendPayload(payload) {
    if (this.data.sending) return
    const clientId = `circle-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
    const optimistic = messageCard({
      id: `local-${clientId}`,
      circleId: this.circleId,
      senderId: api.currentUserId(),
      senderName: '我',
      messageType: payload.type,
      content: payload.content || '',
      image: payload.image || '',
      clientId,
      createdAt: new Date().toISOString(),
      pending: true
    })
    this.setData({ sending: true, inputValue: '' })
    this.mergeMessages([optimistic], true)
    try {
      const message = await api.sendCircleMessage(this.circleId, { ...payload, clientId })
      if (!this._alive) return
      this.mergeMessages([messageCard(message)], true)
    } catch (error) {
      if (this._alive) {
        const confirmed = this.data.messages.some(item => item.clientId === clientId && !item.pending)
        if (confirmed) return
        this.setData({ messages: this.data.messages.filter(item => item.clientId !== clientId) })
        popup.toast(this, error.message || '发送失败，请重试', { tone: 'error' })
      }
    } finally { if (this._alive) this.setData({ sending: false }) }
  },

  scrollToBottom() {
    const rows = this.data.messages
    const last = rows.length ? rows[rows.length - 1] : null
    this.setData({ scrollIntoView: last ? `msg-${last.id}` : 'msg-bottom' })
  },
  goBack() { if (getCurrentPages().length > 1) wx.navigateBack(); else wx.reLaunch({ url: '/pages/discover/discover' }) },
  retry() { this.loadMessages().then(() => this.connectSocket()) },
  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event) },
  onPopupInput(event) { this._popupInput && this._popupInput(event) },
  onPopupClose() { popup.hide(this) }
})
