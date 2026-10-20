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
  data: { ...getNavigationMetrics(), name: '同学', avatar: '同', color: '#7EB6F6', inputValue: '', scrollTo: 'message-end', messages: [], loading: false, sending: false, hasMore: false, muted: false, offline: false, error: '', uiPopup: null },
  onLoad(options) {
    this._alive = true
    this.peerId = Number(options.id)
    this.setData({
      name: safeDecode(options.name, '同学'),
      avatar: safeDecode(options.avatar, '同'),
      color: safeDecode(options.color, '#7EB6F6')
    })
    if (!Number.isSafeInteger(this.peerId) || this.peerId < 1 || this.peerId === api.CURRENT_USER_ID) this.setData({ error: this.peerId === api.CURRENT_USER_ID ? '这是你自己的账号，无需发起私聊' : '聊天对象无效' })
  },
  onShow() {
    if (!Number.isSafeInteger(this.peerId) || this.peerId < 1 || this.peerId === api.CURRENT_USER_ID) return
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
      if (last) await api.markChatRead(this.peerId, last.id, api.CURRENT_USER_ID, !!result.offline)
    } catch (error) {
      if (this._alive && seq === this._seq) this.setData({ error: error.message || '消息加载失败，请重试' })
    } finally {
      if (seq === this._seq) { this._busy = false; if (this._alive) this.setData({ loading: false }) }
    }
  },
  loadOlder() { return this.loadMessages(true) },
  retry() { return this.loadMessages() },
  inputMessage(event) { this.setData({ inputValue: event.detail.value }) },
  async sendMessage() {
    const content = this.data.inputValue.trim()
    if (!content || this.data.sending || this._mutating) return
    if (!Number.isSafeInteger(this.peerId) || this.peerId < 1 || this.peerId === api.CURRENT_USER_ID) { popup.toast(this, '聊天对象无效'); return }
    this._seq = (this._seq || 0) + 1
    this._busy = false
    this.setData({ sending: true, loading: false })
    try {
      const message = await api.sendChatMessage(this.peerId, content, { name: this.data.name })
      if (!this._alive) return
      const sourceChanged = this.data.offline !== !!message.offline
      const messages = (sourceChanged ? [] : this.data.messages).filter(old => String(old.id) !== String(message.id)).concat({ ...message, time: formatTime(message.createdAt) })
      this.setData({ messages, inputValue: '', offline: !!message.offline, error: '', scrollTo: 'message-' + message.id })
      if (message.offline) popup.toast(this, '已保存到本机演示记录，未发送给对方', { tone: 'warn' })
    } catch (error) {
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
    await api.muteChat(this.peerId, muted, api.CURRENT_USER_ID, this.data.offline)
    if (this._alive) this.setData({ muted })
  },
  async clearMessages() {
    if (this._mutating || this.data.sending) return
    this._mutating = true
    this._seq = (this._seq || 0) + 1
    try {
      await api.clearChatMessages(this.peerId, api.CURRENT_USER_ID, this.data.offline)
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
