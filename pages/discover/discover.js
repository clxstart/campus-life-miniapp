const api = require('../../utils/api')
const school = require('../../utils/school')
const { getNavigationMetrics } = require('../../utils/navigation')
const { CIRCLE_CATEGORIES_FALLBACK } = require('../../utils/constants')
const popup = require('../../utils/ui-popup-helper')

// 分类从后端 /circles/categories 拉取（只返回有圈子的分类）；
// 拉不到时回落到本地兜底，保证离线也能筛。
const CATEGORIES_FALLBACK = ['全部'].concat(CIRCLE_CATEGORIES_FALLBACK)

function formatCount(value) {
  const count = Number(value) || 0
  return count >= 10000 ? `${(count / 10000).toFixed(1)}w` : count >= 1000 ? `${(count / 1000).toFixed(1)}k` : String(count)
}

// 后端圈子行 → 轮播卡片（沿用原 banner 的视觉字段，样式不变）
function heroCard(circle) {
  return {
    id: circle.id,
    theme: circle.theme || 'blue',
    eyebrow: circle.category || 'CIRCLE',
    title: circle.name,
    subtitle: circle.description || '',
    badge: `${formatCount(circle.memberCount)}人`
  }
}

// 后端圈子行 → 圈子卡片
function circleCard(circle) {
  return {
    id: circle.id,
    name: circle.name,
    category: circle.category || '兴趣',
    description: circle.description || '',
    cover: circle.cover || '/assets/social/spring-picnic.webp',
    memberText: formatCount(circle.memberCount),
    postText: formatCount(circle.postCount),
    joined: !!circle.joined,
    unreadCount: Number(circle.unreadCount) || 0,
    lastMessage: circle.lastMessage || ''
  }
}

Page({
  data: {
    ...getNavigationMetrics(),
    heroCircles: [],
    categories: CATEGORIES_FALLBACK,
    activeCategory: 0,
    circles: [],
    loading: true,
    offline: false,
    joiningId: 0,
    // 圈子按校隔离：默认只看本校
    schoolScope: 'local',
    uiPopup: null
  },

  onLoad() {
    this._alive = true
    this.loadCategories()
    this.loadHot()
    this.loadCircles()
  },

  onUnload() { this._alive = false },

  onShow() {
    if (this.data.circles.length) this.loadCircles()
  },

  onPullDownRefresh() {
    Promise.all([this.loadCategories(), this.loadHot(), this.loadCircles()]).finally(() => wx.stopPullDownRefresh())
  },

  // 分类：从后端拉「有圈子的分类」，避免管理端新建分类后小程序筛不到
  async loadCategories() {
    try {
      const rows = await api.getCircleCategories()
      if (!this._alive) return
      if (!Array.isArray(rows) || !rows.length) return
      const names = rows.map(row => row.category).filter(Boolean)
      if (!names.length) return
      const categories = ['全部'].concat(names)
      // 当前选中的分类还在新列表里就保持，否则回到「全部」
      const current = (this.data.categories || [])[this.data.activeCategory]
      const index = categories.indexOf(current)
      this.setData({ categories, activeCategory: index >= 0 ? index : 0 })
    } catch (error) {
      // 拉不到就用兜底分类，不打扰用户
    }
  },

  // 顶部轮播：热门圈子（替代原来的活动 banner）
  async loadHot() {
    try {
      const list = await api.getHotCircles(5, undefined, this.data.schoolScope, school.getSelectedId())
      if (!this._alive) return
      if (Array.isArray(list) && list.length) this.setData({ heroCircles: list.map(heroCard) })
    } catch (error) {
      if (this._alive) this.setData({ offline: true })
    }
  },

  async loadCircles() {
    const seq = this._seq = (this._seq || 0) + 1
    this.setData({ loading: true })
    try {
      const category = (this.data.categories || [])[this.data.activeCategory]
      const res = await api.getCircles({ category: category === '全部' ? '' : category, page: 1, size: 50, schoolScope: this.data.schoolScope, schoolId: school.getSelectedId() })
      if (!this._alive || seq !== this._seq) return
      this.setData({
        circles: (res.list || []).map(circleCard),
        loading: false,
        offline: !!res.offline
      })
    } catch (error) {
      if (this._alive && seq === this._seq) this.setData({ loading: false, offline: true })
    }
  },

  selectCategory(event) {
    const index = Number(event.currentTarget.dataset.index)
    if (index === this.data.activeCategory) return
    this.setData({ activeCategory: index })
    this.loadCircles()
  },

  // 本校/全部切换：圈子按校隔离，轮播与列表都要跟着切
  switchSchoolScope(event) {
    const schoolScope = event.currentTarget.dataset.scope
    if (!schoolScope || schoolScope === this.data.schoolScope) return
    this.setData({ schoolScope, circles: [] })
    this.loadHot()
    this.loadCircles()
  },

  // 点轮播 → 直接进圈子详情
  openHero(event) {
    const circle = this.data.heroCircles[Number(event.currentTarget.dataset.index)]
    if (!circle) return
    this.openCircle({ currentTarget: { dataset: { id: circle.id } } })
  },

  openCircle(event) {
    wx.navigateTo({ url: `/pages/circle-detail/circle-detail?id=${encodeURIComponent(event.currentTarget.dataset.id)}` })
  },

  // 列表里的快速加入/退出，不改动 data.circles 的引用避免重排
  async toggleJoin(event) {
    const id = event.currentTarget.dataset.id
    const circle = this.data.circles.find(item => String(item.id) === String(id))
    if (!circle || this.data.joiningId) return
    this.setData({ joiningId: id })
    try {
      if (circle.joined) {
        await api.leaveCircle(id)
        popup.toast(this, `已退出「${circle.name}」`, { tone: 'info' })
      } else {
        await api.joinCircle(id)
        popup.toast(this, `已加入「${circle.name}」`, { tone: 'success' })
      }
      const circles = this.data.circles.map(item => String(item.id) === String(id)
        ? { ...item, joined: !item.joined, memberText: formatCount(Math.max(0, Number(item.memberText) + (item.joined ? -1 : 1))) }
        : item)
      this.setData({ circles })
      this.loadHot()
    } catch (error) {
      popup.toast(this, error.message || '操作失败', { tone: 'error' })
    } finally {
      this.setData({ joiningId: 0 })
    }
  },

  openSearch() {
    popup.modal(this, {
      title: '搜索',
      editable: true,
      placeholder: '搜圈子、帖子、闲置、活动…',
      confirmText: '搜索',
      inputValue: '',
      confirm: (res) => {
        const keyword = (res.inputValue || '').trim()
        if (!keyword) return
        if (keyword.length > 64) { popup.toast(this, '关键词不能超过64字'); return }
        // 从圈子页发起时默认落在「圈子」分类，用户仍可在结果页切到全部
        wx.navigateTo({ url: `/pages/search/search?keyword=${encodeURIComponent(keyword)}&type=circle` })
      }
    })
  },

  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})
