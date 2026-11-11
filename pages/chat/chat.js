const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { formatTime } = require('../../utils/format-time')
const popup = require('../../utils/ui-popup-helper')

// URL 参数解码：navigateTo 传的中文是百分号编码，微信不会自动解码。
// 解码失败退回原串，不能让页面崩。
function safeDecode(value, fallback) {
  const raw = String(value == null ? '' : value)
  if (!raw) return fallback
  try {
    return decodeURIComponent(raw)
  } catch (error) {
    return raw
  }
}

Page({
  data: { ...getNavigationMetrics(), name: '同学', avatar: '同', color: '#7EB6F6', inputValue: '', inputCursor: 0, emojiVisible: false, scrollTo: 'message-end', messages: [], loading: false, sending: false, hasMore: false, muted: false, offline: false, error: '', uiPopup: null },
  onLoad(options) {
    this._alive = true
    this.peerId = Number(options.id)
    this.setData({
      name: safeDecode(options.name, '同学'),
      avatar: safeDecode(options.avatar, '同'),
      color: safeDecode(options.color, '#7EB6F6')
    })
    if (!Number.isSafeInteger(this.peerId) || this.peerId < 1 || this.peerId === api.currentUserId()) this.setData({ error: this.peerId === api.currentUserId() ? '这是你自己的账号，无需发起私聊' : '聊天对象无效' })
  },
  onShow() {
    if (!Number.isSafeInteger(this.peerId) || this.peerId < 1 || this.peerId === api.currentUserId()) return
    clearInterval(this._poll)
    this.loadMessages()
    this._poll = setInterval(() => this.loadMessages(false, true), 5000)
  },
  onHide() { clearInterval(this._poll); this._poll = null },
  onUnload() { this._alive = false; this.onHide(); this._seq = (this._seq || 0) + 1 },
  async loadMessages(older = false, quiet = false) {
    if (this._busy || this._mutating || this.data.sending || (older && !this.data.hasMore)) return
    const beforeId = older && this.data.messages.length ? this.data.messages[0].id : undefined
    const seq = this._seq = (this._seq || 0) + 1
    this._busy = true
    if (!quiet) this.setData({ loading: true, error: '' })
    try {
      const result = await api.getChatMessages(this.peerId, { beforeId })
      if (!this._alive || seq !== this._seq) return
      const sourceChanged = this.data.messages.length && this.data.offline !== !!result.offline
      if (sourceChanged && older) { this._busy = false; this.loadMessages(); return }
      const previous = sourceChanged ? [] : this.data.messages
      const map = new Map(previous.map(message => [String(message.id), message]))
      ;(result.list || []).forEach(message => map.set(String(message.id), { ...message, time: formatTime(message.createdAt) }))
      const messages = Array.from(map.values()).sort((a, b) => Number(a.id) - Number(b.id))
      const changed = messages.length !== previous.length
      this.setData({ messages, offline: !!result.offline, muted: !!result.muted, error: '', hasMore: older || !previous.length ? !!result.hasMore : this.data.hasMore, ...(changed && !older ? { scrollTo: 'message-' + messages[messages.length - 1].id } : {}) })
      const last = messages[messages.length - 1]
      if (last) await api.markChatRead(this.peerId, last.id, api.currentUserId(), !!result.offline)
    } catch (error) {
      if (this._alive && seq === this._seq) this.setData({ error: error.message || '消息加载失败，请重试' })
    } finally {
      if (seq === this._seq) { this._busy = false; if (this._alive) this.setData({ loading: false }) }
    }
  },
  loadOlder() { return this.loadMessages(true) },
  retry() { return this.loadMessages() },
  // 输入框与光标：value 走 bindinput，光标位置存内存 + inputCursor（selection-start/end）。
  // 只在插入 emoji 时写 inputCursor，避免中文输入法组词时 setData 抢光标。
  inputMessage(event) {
    const detail = event.detail || {}
    const value = typeof detail.value === 'string' ? detail.value : ''
    const cursor = Number.isSafeInteger(detail.cursor) && detail.cursor >= 0 ? detail.cursor : value.length
    this._cursor = cursor
    this.setData({ inputValue: value })
  },
  // 失焦（点 ☺ 或点面板）时尽量同步一次光标：部分基础库的 blur 事件会带 cursor，
  // 没带就保留 bindinput 记录的位置，不要把用户刚点的位置覆盖成结尾。
  onInputBlur(event) {
    const detail = event.detail || {}
    if (Number.isSafeInteger(detail.cursor) && detail.cursor >= 0) this._cursor = detail.cursor
  },
  toggleEmojiPanel() {
    if (this.data.sending) return
    const emojiVisible = !this.data.emojiVisible
    this.setData({ emojiVisible })
    // 打开面板时收起键盘，两者不要同时占着屏幕下半部分
    if (emojiVisible && typeof wx.hideKeyboard === 'function') wx.hideKeyboard({ fail() {} })
  },
  closeEmojiPanel() {
    if (this.data.emojiVisible) this.setData({ emojiVisible: false })
  },
  onEmojiPick(event) {
    const emoji = event && event.detail ? event.detail.emoji : ''
    if (!emoji || this.data.sending) return
    const value = String(this.data.inputValue || '')
    const tracked = Number(this._cursor)
    const cursor = Number.isSafeInteger(tracked) ? Math.min(Math.max(tracked, 0), value.length) : value.length
    // input 的 maxlength=1000：放不下就整条不插，避免把 emoji 的代理对截成半个字符
    if (value.length + emoji.length > 1000) return
    const next = value.slice(0, cursor) + emoji + value.slice(cursor)
    this._cursor = cursor + emoji.length
    this.setData({ inputValue: next, inputCursor: this._cursor })
  },
  async sendMessage() {
    const content = this.data.inputValue.trim()
    if (!content || this.data.sending || this._mutating) return
    if (!Number.isSafeInteger(this.peerId) || this.peerId < 1 || this.peerId === api.currentUserId()) { popup.toast(this, '聊天对象无效'); return }
    this._seq = (this._seq || 0) + 1
    this._busy = false
    // 弱网重试：同一条内容失败后再发复用 clientId，避免服务端已落库但响应丢失时重复发送。
    const pending = this._pendingChat
    const clientId = pending && pending.content === content
      ? pending.clientId
      : `chat-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
    this.setData({ sending: true, loading: false })
    try {
      const message = await api.sendChatMessage(this.peerId, content, { name: this.data.name, clientId })
      this._pendingChat = null
      if (!this._alive) return
      const sourceChanged = this.data.offline !== !!message.offline
      const messages = (sourceChanged ? [] : this.data.messages).filter(old => String(old.id) !== String(message.id)).concat({ ...message, time: formatTime(message.createdAt) })
      this._cursor = 0
      this.setData({ messages, inputValue: '', inputCursor: 0, emojiVisible: false, offline: !!message.offline, error: '', scrollTo: 'message-' + message.id })
      if (message.offline) popup.toast(this, '已保存到本机演示记录，未发送给对方', { tone: 'warn' })
    } catch (error) {
      this._pendingChat = { content, clientId }
      if (this._alive) popup.toast(this, error.message || '发送失败，请重试', { tone: 'error' })
    } finally {
      if (this._alive) { this.setData({ sending: false }); this.loadMessages(false, true) }
    }
  },
  more() {
    popup.sheet(this, {
      title: this.data.name,
      actions: [{ label: '查看资料' }, { label: this.data.muted ? '关闭免打扰' : '消息免打扰' }, { label: '清空聊天记录' }],
      action: ({ index }) => {
        if (index === 0) { wx.navigateTo({ url: `/pages/user-home/user-home?id=${this.peerId}` }); return }
        if (index === 1) return this.toggleMute()
        popup.modal(this, { title: '清空聊天记录', message: '只清空你看到的记录，不影响对方，确定清空吗？', tone: 'error', confirmText: '清空', confirm: () => this.clearMessages() })
      }
    })
  },
  async toggleMute() {
    const muted = !this.data.muted
    await api.muteChat(this.peerId, muted, api.currentUserId(), this.data.offline)
    if (this._alive) this.setData({ muted })
  },
  async clearMessages() {
    if (this._mutating || this.data.sending) return
    this._mutating = true
    this._seq = (this._seq || 0) + 1
    try {
      await api.clearChatMessages(this.peerId, api.currentUserId(), this.data.offline)
      if (this._alive) this.setData({ messages: [], hasMore: false, error: '', loading: false })
    } finally { this._mutating = false; this._busy = false }
  },
  goBack() { if (getCurrentPages().length > 1) wx.navigateBack(); else wx.reLaunch({ url: '/pages/messages/messages' }) },
  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event) },
  onPopupInput(event) { this._popupInput && this._popupInput(event) },
  onPopupClose() { popup.hide(this) }
})
