const api = require('../../utils/api')
const school = require('../../utils/school')
const popup = require('../../utils/ui-popup-helper')
const { getNavigationMetrics } = require('../../utils/navigation')
const { formatTime } = require('../../utils/format-time')
const { categories, findCategory } = require('../../utils/learn')

Page({
  data: {
    ...getNavigationMetrics(), categories, category: 'daike', current: findCategory('daike'),
    schoolName: '', schoolId: null, counts: {}, posts: [], page: 0, cursor: '', size: 10,
    total: 0, loading: false, hasMore: false, offline: false, error: '', uiPopup: null
  },
  onLoad(query = {}) {
    this._alive = true
    this._generation = 0
    const current = findCategory(query.category || 'daike')
    this.setData({ ...getNavigationMetrics(), category: current.key, current,
      schoolId: school.getSelectedId(), schoolName: school.getSelectedName() })
    this.refresh()
  },
  onShow() {
    if (!this._alive) return
    const schoolId = school.getSelectedId()
    if (schoolId !== this.data.schoolId || this._needReload) {
      this._needReload = false
      this.setData({ schoolId, schoolName: school.getSelectedName() })
      this.refresh()
    }
  },
  onUnload() { this._alive = false; this._generation += 1 },
  refresh() { return Promise.all([this.loadPosts(true), this.loadCounts()]) },
  async loadCounts() {
    const schoolId = this.data.schoolId
    try {
      const counts = await api.getLearnCounts(schoolId)
      if (this._alive && this.data.schoolId === schoolId) this.setData({ counts })
    } catch (error) {
      // 数量是辅助信息；计数失败不清空仍可阅读的列表。
      if (this._alive && this.data.schoolId === schoolId) this.setData({ counts: {} })
    }
  },
  async loadPosts(reset = false) {
    if (!reset && (this.data.loading || !this.data.hasMore)) return
    const generation = reset ? ++this._generation : this._generation
    const page = reset ? 1 : this.data.page + 1
    const category = this.data.category
    this.setData({ loading: true, error: '', ...(reset ? { posts: [], page: 0, cursor: '', total: 0, hasMore: false } : {}) })
    try {
      const result = await api.getLearnTopics({ category, schoolId: this.data.schoolId, beforeId: reset ? '' : this.data.cursor, size: this.data.size })
      // 快速切分类、切学校、下拉刷新时，旧响应不能覆盖新列表。
      if (!this._alive || generation !== this._generation) return
      if (result.offline && !reset) {
        this.setData({ offline: true, error: '网络暂不可用，已保留当前内容' })
        return
      }
      const incoming = (result.list || []).map(item => ({ ...item,
        authorName: item.authorName || item.authorNickname || '校园同学',
        avatarText: (item.authorName || item.authorNickname || '同')[0],
        image: api.assetUrl(item.image), authorAvatar: api.assetUrl(item.authorAvatar),
        timeText: formatTime(item.createdAt)
      }))
      const posts = reset ? [] : this.data.posts.slice()
      const seen = new Set(posts.map(item => String(item.id)))
      incoming.forEach(item => { if (!seen.has(String(item.id))) { posts.push(item); seen.add(String(item.id)) } })
      const total = Number(result.total) || 0
      this.setData({ posts, total, page, offline: !!result.offline, cursor: result.nextCursor || '',
        hasMore: !result.offline && incoming.length > 0 && !!result.hasMore && !!result.nextCursor })
    } catch (error) {
      if (this._alive && generation === this._generation) this.setData({ error: error.message || '加载失败，点击重试', offline: false })
    } finally {
      if (this._alive && generation === this._generation) this.setData({ loading: false })
    }
  },
  onTabTap(event) {
    const category = event.currentTarget.dataset.key
    if (category === this.data.category || !categories.some(item => item.key === category)) return
    this.setData({ category, current: findCategory(category) })
    this.loadPosts(true)
  },
  async onPullDownRefresh() { try { await this.refresh() } finally { wx.stopPullDownRefresh() } },
  onReachBottom() { return this.loadPosts(false) },
  retry() { return this.data.posts.length ? this.loadPosts(false) : this.refresh() },
  onEmptyAction() { if (this.data.offline) this.openGuide(); else this.openPublish() },
  openPublish() {
    this._needReload = true
    wx.navigateTo({ url: `/pages/learn-publish/learn-publish?category=${this.data.category}` })
  },
  openDetail(event) {
    this._needReload = true
    wx.navigateTo({ url: `/pages/post-detail/post-detail?id=${encodeURIComponent(event.currentTarget.dataset.id)}` })
  },
  openAuthor(event) { wx.navigateTo({ url: `/pages/user-home/user-home?id=${encodeURIComponent(event.currentTarget.dataset.id)}` }) },
  openAiChat() { wx.navigateTo({ url: '/pages/ai-chat/ai-chat' }) },
  openGuide() {
    const current = this.data.current
    popup.modal(this, { title: current.guide, message: current.tips.join('\n\n'), showCancel: false, confirmText: '开始学习' })
  },
  openPlan() {
    popup.modal(this, { title: '留一小段时间给自己', message: '选一件今天最想完成的小事。\n\n给它留一段不被打扰的时间。\n\n完成后记下收获，也让自己好好休息。', showCancel: false, confirmText: '就从今天开始' })
  },
  async onLike(event) {
    const id = event.currentTarget.dataset.id
    if (this._liking) return
    this._liking = true
    try {
      const result = await api.likePost(id)
      if (!this._alive) return
      if (result.offline) throw new Error('离线时暂时无法点赞')
      this.setData({ posts: this.data.posts.map(item => {
        if (String(item.id) !== String(id)) return item
        const liked = typeof result.liked === 'boolean' ? result.liked : !item.liked
        return { ...item, liked, likeCount: Math.max(0, (Number(item.likeCount) || 0) + (liked === !!item.liked ? 0 : liked ? 1 : -1)) }
      }) })
    } catch (error) { if (this._alive) popup.toast(this, error.message || '点赞失败，请重试', { tone: 'error' }) }
    finally { this._liking = false }
  },
  onPostImageError(event) {
    const id = event.currentTarget.dataset.id
    this.setData({ posts: this.data.posts.map(item => String(item.id) === String(id) ? { ...item, image: '' } : item) })
  },
  onAvatarError(event) {
    const id = event.currentTarget.dataset.id
    this.setData({ posts: this.data.posts.map(item => String(item.id) === String(id) ? { ...item, authorAvatar: '' } : item) })
  },
  goBack() { if (getCurrentPages().length > 1) wx.navigateBack(); else wx.reLaunch({ url: '/pages/index/index' }) },
  onShareAppMessage() { return { title: `学习专区 · ${this.data.current.label}`, path: `/pages/learn/learn?category=${this.data.category}` } },
  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})
