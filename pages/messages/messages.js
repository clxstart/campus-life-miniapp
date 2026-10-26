const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { formatTime } = require('../../utils/format-time')
const popup = require('../../utils/ui-popup-helper')

Page({
  data: { ...getNavigationMetrics(), activeTab: 'chat', conversations: [], notifications: [], visibleNotifications: [], noticeFilter: '', unreadTotal: 0, likeUnread: 0, commentUnread: 0, loading: false, error: '', uiPopup: null },
  onLoad(options) { this._alive = true; if (options.tab === 'notice') this.setData({ activeTab: 'notice' }) },
  onShow() { this.loadMessages() },
  onUnload() { this._alive = false; this._seq = (this._seq || 0) + 1 },
  onPullDownRefresh() { this.loadMessages().finally(() => wx.stopPullDownRefresh()) },
  async loadMessages() {
    const seq = this._seq = (this._seq || 0) + 1
    this.setData({ loading: true, error: '' })
    try {
      const [conversations, notifications] = await Promise.all([api.getConversations(), api.getNotifications()])
      if (!this._alive || seq !== this._seq) return
      this.setData({ conversations: conversations.map(row => ({ ...row, time: row.createdAt ? formatTime(row.createdAt) : row.time })), notifications: notifications.map(row => ({ ...row, time: row.createdAt ? formatTime(row.createdAt) : row.time })) })
      this.applyNotices()
    } catch (error) {
      if (this._alive && seq === this._seq) this.setData({ error: error.message || '消息加载失败，请重试' })
    } finally { if (this._alive && seq === this._seq) this.setData({ loading: false }) }
  },
  applyNotices() {
    const notifications = this.data.notifications
    const filter = this.data.noticeFilter
    this.setData({
      visibleNotifications: notifications.filter(row => !filter || (filter === 'like' ? ['like', 'favorite', 'follow'].includes(row.category) : row.category === filter)),
      unreadTotal: this.data.conversations.reduce((sum, row) => sum + (row.muted ? 0 : Number(row.unread) || 0), 0) + notifications.filter(row => row.unread).length,
      likeUnread: notifications.filter(row => row.unread && ['like', 'favorite', 'follow'].includes(row.category)).length,
      commentUnread: notifications.filter(row => row.unread && row.category === 'comment').length
    })
  },
  switchMessageTab(event) {
    this.setData({ activeTab: event.currentTarget.dataset.tab, noticeFilter: '' })
    this.applyNotices()
  },
  async markAllRead() {
    if (this._marking) return
    this._marking = true
    try {
      const result = await api.markAllMessagesRead()
      if (!this._alive) return
      this.setData({ conversations: this.data.conversations.map(row => ({ ...row, unread: 0 })), notifications: this.data.notifications.map(row => ({ ...row, unread: false })) })
      this.applyNotices()
      popup.toast(this, result && result.offline ? '本机演示记录已标记为已读' : '已全部标记为已读')
    } catch (error) {
      if (this._alive) popup.toast(this, error.message || '标记失败，请重试', { tone: 'error' })
    } finally { this._marking = false }
  },
  openChat(event) {
    const { id, name, color, avatar } = event.currentTarget.dataset
    wx.navigateTo({ url: `/pages/chat/chat?id=${encodeURIComponent(id)}&name=${encodeURIComponent(name || '同学')}&color=${encodeURIComponent(color || '#7EB6F6')}&avatar=${encodeURIComponent(avatar || (name || '同')[0])}` })
  },
  openQuick(event) {
    const filters = { '赞和收藏': 'like', '评论和回复': 'comment', '系统通知': 'system' }
    this.setData({ activeTab: 'notice', noticeFilter: filters[event.currentTarget.dataset.label] || '' })
    this.applyNotices()
  },
  openNotice(event) {
    const id = event.currentTarget.dataset.postId
    if (id) wx.navigateTo({ url: `/pages/post-detail/post-detail?id=${encodeURIComponent(id)}` })
  },
  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event) },
  onPopupInput(event) { this._popupInput && this._popupInput(event) },
  onPopupClose() { popup.hide(this) }
})
