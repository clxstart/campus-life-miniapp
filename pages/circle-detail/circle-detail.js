const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { formatTime } = require('../../utils/format-time')
const popup = require('../../utils/ui-popup-helper')

function postCard(post) {
  return {
    id: post.id,
    authorId: post.authorId,
    author: post.authorNickname || '校园同学',
    avatar: post.authorAvatar || '',
    avatarText: (post.authorNickname || '同')[0],
    time: formatTime(post.createdAt),
    title: post.title,
    content: post.content,
    image: post.image || '',
    location: post.location || '',
    tag: post.tag || '',
    likes: post.likeCount || 0,
    comments: post.commentCount || 0,
    liked: !!post.liked,
    followed: !!post.followed
  }
}

// 火花状态（与 discover 列表同一套语义）：
//   已点亮 + 今天活跃 → 🔥 5天；已点亮 + 今天没活跃 → 灰态 + 今日待续上；
//   未满 3 天 → 连续 2/3；未满且今天没活跃 → 灰态 + 今日待续上；断了 → 不显示。
// 后端字段有扁平版（streakDays/sparkOn/activeToday）和 streak 对象两种，优先扁平版。
function sparkView(circle) {
  const streak = circle && circle.streak ? circle.streak : {}
  const read = (flat, nested) => (circle && circle[flat] != null ? circle[flat] : streak[nested])
  const activeToday = !!read('activeToday', 'activeToday')
  const sparkOn = !!read('sparkOn', 'sparkOn')
  const days = Math.max(0, Number(read('streakDays', 'streakDays')) || 0)
  if (!days) return { show: false, state: 'off', days: 0, label: '', hint: '' }
  if (sparkOn) {
    return { show: true, state: activeToday ? 'on' : 'wait', days, label: `🔥 已连续 ${days} 天`, hint: activeToday ? '' : '今日待续上' }
  }
  return { show: true, state: activeToday ? 'progress' : 'progress-wait', days, label: `连续 ${days}/3`, hint: activeToday ? `再 ${3 - days} 天点亮火花` : '今日待续上' }
}

Page({
  data: {
    ...getNavigationMetrics(),
    circle: null,
    posts: [],
    page: 1,
    size: 10,
    total: 0,
    hasMore: false,
    loading: true,
    loadingMore: false,
    error: '',
    offline: false,
    joinPending: false,
    uiPopup: null
  },

  onLoad(options) {
    this._alive = true
    this.circleId = Number(options.id)
    if (!Number.isSafeInteger(this.circleId) || this.circleId < 1) {
      this.setData({ loading: false, error: '圈子不存在' })
      return
    }
    this.loadCircle()
    this.loadPosts()
  },

  onUnload() { this._alive = false },

  onShow() {
    if (this.data.circle) this.loadCircle()
    if (this.data.posts.length) this.loadPosts()
  },

  onPullDownRefresh() {
    Promise.all([this.loadCircle(), this.loadPosts()]).finally(() => wx.stopPullDownRefresh())
  },

  async loadCircle() {
    try {
      const circle = await api.getCircle(this.circleId)
      if (!this._alive) return
      this.setData({ circle: { ...circle, spark: sparkView(circle) }, offline: !!circle.offline })
    } catch (error) {
      if (this._alive) this.setData({ loading: false, error: error.message || '圈子加载失败' })
    }
  },

  async loadPosts() {
    const seq = this._seq = (this._seq || 0) + 1
    this.setData({ loading: true, page: 1 })
    try {
      const res = await api.getCirclePosts(this.circleId, { page: 1, size: this.data.size })
      if (!this._alive || seq !== this._seq) return
      const posts = (res.list || []).map(postCard)
      this.setData({
        posts,
        total: res.total || posts.length,
        hasMore: posts.length < (res.total || posts.length),
        loading: false,
        offline: !!res.offline
      })
    } catch (error) {
      if (this._alive && seq === this._seq) this.setData({ loading: false, error: error.message || '帖子加载失败' })
    }
  },

  async loadMore() {
    if (!this.data.hasMore || this.data.loadingMore || this.data.loading) return
    const next = this.data.page + 1
    this.setData({ loadingMore: true })
    try {
      const res = await api.getCirclePosts(this.circleId, { page: next, size: this.data.size })
      if (!this._alive) return
      const posts = this.data.posts.concat((res.list || []).map(postCard))
      this.setData({
        posts,
        page: next,
        hasMore: posts.length < (res.total || posts.length),
        loadingMore: false
      })
    } catch (error) {
      if (this._alive) {
        this.setData({ loadingMore: false })
        popup.toast(this, error.message || '加载失败', { tone: 'error' })
      }
    }
  },

  async toggleJoin() {
    const circle = this.data.circle
    if (!circle || this.data.joinPending) return
    this.setData({ joinPending: true })
    try {
      const res = circle.joined ? await api.leaveCircle(circle.id) : await api.joinCircle(circle.id)
      if (!this._alive) return
      this.setData({
        'circle.joined': !!res.joined,
        'circle.memberCount': res.memberCount != null ? res.memberCount : circle.memberCount
      })
      popup.toast(this, res.joined ? `已加入「${circle.name}」` : `已退出「${circle.name}」`, { tone: res.joined ? 'success' : 'info' })
    } catch (error) {
      if (this._alive) popup.toast(this, error.message || '操作失败', { tone: 'error' })
    } finally {
      if (this._alive) this.setData({ joinPending: false })
    }
  },

  // 圈内发帖：标题/正文走弹层输入；成功后回到列表顶部
  openCompose() {
    const circle = this.data.circle
    if (!circle) return
    if (!circle.joined) {
      popup.modal(this, {
        title: '还没加入圈子',
        message: `加入「${circle.name}」后就能在里面发帖了，要现在加入吗？`,
        confirmText: '加入',
        showCancel: true,
        confirm: () => this.toggleJoin()
      })
      return
    }
    // 跳独立发帖页：与「发布」页同一套表单，不再是连续弹窗
    wx.navigateTo({ url: `/pages/circle-publish/circle-publish?id=${this.circleId}` })
  },

  openChat() {
    const circle = this.data.circle
    if (!circle) return
    const go = () => wx.navigateTo({ url: `/pages/circle-chat/circle-chat?id=${this.circleId}&name=${encodeURIComponent(circle.name || '圈子群聊')}` })
    if (circle.joined) return go()
    popup.modal(this, {
      title: '还没加入圈子',
      message: `加入「${circle.name}」后才能进入群聊，要现在加入吗？`,
      confirmText: '加入并进入',
      showCancel: true,
      confirm: async () => { await this.toggleJoin(); if (this.data.circle && this.data.circle.joined) go() }
    })
  },

  openPost(event) {
    wx.navigateTo({ url: `/pages/post-detail/post-detail?id=${encodeURIComponent(event.currentTarget.dataset.id)}` })
  },

  async toggleLike(event) {
    const id = event.currentTarget.dataset.id
    const post = this.data.posts.find(item => String(item.id) === String(id))
    if (!post || this._likePending) return
    this._likePending = true
    try {
      await api.likePost(id)
      const posts = this.data.posts.map(item => String(item.id) === String(id)
        ? { ...item, liked: !item.liked, likes: Math.max(0, item.likes + (item.liked ? -1 : 1)) }
        : item)
      this.setData({ posts })
    } catch (error) {
      popup.toast(this, error.message || '点赞失败', { tone: 'error' })
    } finally { this._likePending = false }
  },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) { wx.navigateBack() } else { wx.reLaunch({ url: '/pages/discover/discover' }) }
  },

  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})
