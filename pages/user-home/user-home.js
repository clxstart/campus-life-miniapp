const popup = require('../../utils/ui-popup-helper')
const api = require('../../utils/api')
const { formatTime } = require('../../utils/format-time')
const { getNavigationMetrics } = require('../../utils/navigation')

Page({
  data: {
    ...getNavigationMetrics(),
    user: null,
    posts: [],
    page: 1,
    size: 10,
    total: 0,
    hasMore: false,
    loading: true,
    loadingMore: false,
    error: '',
    uiPopup: null
  },

  onLoad(q) {
    this._alive = true
    this.userId = Number(q.id)
    if (!Number.isSafeInteger(this.userId) || this.userId < 1) {
      this.setData({ loading: false, error: '用户不存在' })
      return
    }
    this.fetch()
  },

  onUnload() { this._alive = false },

  onPullDownRefresh() { this.fetch().finally(() => wx.stopPullDownRefresh()) },

  // 资料与统计来自后端真实数据（发帖/关注/粉丝），不再写死
  async fetch() {
    this.setData({ loading: true, error: '' })
    try {
      const [stats, res] = await Promise.all([
        api.getUserStats(this.userId),
        api.getUserPosts(this.userId, { page: 1, size: this.data.size })
      ])
      if (!this._alive) return
      const list = res.list || []
      this.setData({
        user: {
          id: this.userId,
          nickname: stats.nickname || '校园同学',
          avatar: stats.avatar || '',
          initial: (stats.nickname || '同')[0],
          school: stats.school || '',
          posts: stats.posts || 0,
          following: stats.following || 0,
          followers: stats.followers || 0,
          likedTotal: stats.likedTotal || 0,
          followed: list.length ? !!list[0].followed : false
        },
        posts: list.map(row => ({ ...row, createdAt: formatTime(row.createdAt) })),
        page: 1,
        total: res.total || list.length,
        hasMore: list.length < (res.total || list.length),
        loading: false
      })
    } catch (error) {
      if (this._alive) this.setData({ loading: false, error: error.message || '加载失败' })
    }
  },

  async loadMore() {
    if (!this.data.hasMore || this.data.loadingMore || this.data.loading) return
    const next = this.data.page + 1
    this.setData({ loadingMore: true })
    try {
      const res = await api.getUserPosts(this.userId, { page: next, size: this.data.size })
      if (!this._alive) return
      const posts = this.data.posts.concat((res.list || []).map(row => ({ ...row, createdAt: formatTime(row.createdAt) })))
      this.setData({ posts, page: next, hasMore: posts.length < (res.total || posts.length), loadingMore: false })
    } catch (error) {
      if (this._alive) {
        this.setData({ loadingMore: false })
        popup.toast(this, error.message || '加载失败', { tone: 'error' })
      }
    }
  },

  openDetail(e) { wx.navigateTo({ url: `/pages/post-detail/post-detail?id=${e.currentTarget.dataset.id}` }) },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) { wx.navigateBack() } else { wx.reLaunch({ url: '/pages/index/index' }) }
  },

  // 关注后粉丝数按真实增减，并回读一次统计避免与后端漂移
  async onFollow() {
    const u = this.data.user
    if (!u || this._following) return
    this._following = true
    try {
      await api.followUser(u.id)
      if (!this._alive) return
      const followed = !u.followed
      this.setData({
        'user.followed': followed,
        'user.followers': Math.max(0, (u.followers || 0) + (followed ? 1 : -1))
      })
      const stats = await api.getUserStats(this.userId)
      if (this._alive) this.setData({ 'user.followers': stats.followers || 0, 'user.following': stats.following || 0 })
    } catch (error) {
      if (this._alive) popup.toast(this, error.message || '关注失败', { tone: 'error' })
    } finally { this._following = false }
  },

  chat() {
    const u = this.data.user
    if (!u) return
    wx.navigateTo({ url: `/pages/chat/chat?id=${u.id}&name=${encodeURIComponent(u.nickname)}&avatar=${encodeURIComponent(u.initial || '同')}&color=${encodeURIComponent('#7EB6F6')}` })
  },

  onPopupConfirm(e) { this._popupConfirm && this._popupConfirm(e) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(e) { this._popupAction && this._popupAction(e) },
  onPopupInput(e) { this._popupInput && this._popupInput(e) },
  onPopupClose() { popup.hide(this) }
})
