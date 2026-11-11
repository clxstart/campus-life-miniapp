const api = require('../../utils/api')
const school = require('../../utils/school')
const { getNavigationMetrics } = require('../../utils/navigation')
const { CIRCLE_CATEGORIES_FALLBACK } = require('../../utils/constants')
const popup = require('../../utils/ui-popup-helper')
const { asset } = require('../../utils/remote-assets')

// 分类从后端 /circles/categories 拉取（只返回有圈子的分类）；
// 拉不到时回落到本地兜底，保证离线也能筛。
const CATEGORIES_FALLBACK = ['全部'].concat(CIRCLE_CATEGORIES_FALLBACK)

// 「创建圈子」入口（黄金段位特权，设计稿 §2.3）：
// 权限只认 GET /api/points/me 的 privileges.circleCreate（含开关），不写死；
// 名额后端没有查询接口，用本机标记灰显，服务端 POST /api/circles 仍是最终裁判。
const TIER_RANK = { bronze: 1, silver: 2, gold: 3 }
const QUOTA_KEY = 'circle_created_v1'

function quotaHit() {
  try { return !!wx.getStorageSync(QUOTA_KEY) } catch (error) { return false }
}

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

// 火花状态：后端已经算好 streakDays / sparkOn / activeToday（也包在 streak 对象里），
// 前端只翻成界面文案。四种状态互斥：
//   已点亮 + 今天活跃      → 🔥 5天
//   已点亮 + 今天还没活跃  → 灰态 🔥 5天 · 今日待续上
//   未满 3 天 + 今天活跃   → 连续 2/3
//   未满 3 天 + 今天没活跃 → 灰态 连续 2/3 · 今日待续上
//   streakDays=0（断了）   → 不显示
function sparkView(circle) {
  const streak = circle && circle.streak ? circle.streak : {}
  const read = (flat, nested) => (circle && circle[flat] != null ? circle[flat] : streak[nested])
  const activeToday = !!read('activeToday', 'activeToday')
  const sparkOn = !!read('sparkOn', 'sparkOn')
  const days = Math.max(0, Number(read('streakDays', 'streakDays')) || 0)
  if (!days) return { show: false, state: 'off', days: 0, label: '', hint: '' }
  if (sparkOn) {
    return { show: true, state: activeToday ? 'on' : 'wait', days, label: `🔥 ${days}天`, hint: activeToday ? '' : '今日待续上' }
  }
  return { show: true, state: activeToday ? 'progress' : 'progress-wait', days, label: `连续 ${days}/3`, hint: activeToday ? '' : '今日待续上' }
}

// 后端圈子行 → 圈子卡片
function circleCard(circle) {
  return {
    id: circle.id,
    name: circle.name,
    category: circle.category || '兴趣',
    description: circle.description || '',
    cover: circle.cover || asset('/assets/social/spring-picnic.webp'),
    memberText: formatCount(circle.memberCount),
    postText: formatCount(circle.postCount),
    joined: !!circle.joined,
    unreadCount: Number(circle.unreadCount) || 0,
    lastMessage: circle.lastMessage || '',
    spark: sparkView(circle)
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
    // 创建圈子入口：只有「名额是否用满」需要驱动样式，权限在点击时校验
    circleEntryDisabled: false,
    uiPopup: null
  },

  onLoad() {
    this._alive = true
    this.loadCategories()
    this.loadHot()
    this.loadCircles()
    this.loadCirclePrivilege()
  },

  onUnload() { this._alive = false },

  onShow() {
    if (!this._loaded) return
    this.loadCircles()
    // 从建圈页返回后名额标记可能变了，入口要跟着刷新
    this.loadCirclePrivilege()
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
      this._loaded = true
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

  /**
   * 建圈权限：只由 /api/points/me 的 privileges 决定（circleCreate 已把段位与开关算在一起）。
   * 拉不到时保持入口可点——点击时会再拉一次并给出明确提示，不静默失败。
   */
  async loadCirclePrivilege() {
    try {
      const me = await api.getPointsMe()
      if (!this._alive) return
      const privileges = (me && me.privileges) || {}
      const tier = (me && me.tier) || {}
      const effective = privileges.effectiveTierCode || tier.code || ''
      const gold = (TIER_RANK[effective] || 0) >= TIER_RANK.gold
      this._circleEntry = {
        gold,
        allowed: gold && !!privileges.circleCreate,
        // 黄金但 circleCreate=false = 开关关闭
        switchOff: gold && !privileges.circleCreate,
        quota: gold && quotaHit()
      }
      const quota = this._circleEntry.quota
      this.setData({ circleEntryDisabled: quota })
    } catch (error) {
      // 段位拿不到就保持默认入口，点击时再校验并提示
      if (this._alive) this.setData({ circleEntryDisabled: false })
    }
  },

  /** 创建圈子入口：段位不足给原因 + 去积分页；名额用满只提示不跳转 */
  async openCircleCreate() {
    if (this._creating) return
    this._creating = true
    let entry = this._circleEntry
    if (!entry) {
      await this.loadCirclePrivilege()
      entry = this._circleEntry
    }
    this._creating = false
    if (!entry) {
      popup.toast(this, '段位信息没拿到，请稍后重试', { tone: 'error' })
      return
    }
    if (entry.quota) {
      popup.toast(this, '你已创建 1 个圈子（上限 1）', { tone: 'info' })
      return
    }
    if (entry.allowed) {
      wx.navigateTo({ url: '/pages/circle-create/circle-create' })
      return
    }
    if (entry.switchOff) {
      popup.toast(this, '圈子创建暂未开放', { tone: 'info' })
      return
    }
    popup.modal(this, {
      title: '需要黄金段位',
      message: '创建圈子需要黄金段位（成长值 200）。做任务攒成长值就能升级。',
      confirmText: '去积分页',
      cancelText: '知道了',
      confirm: () => wx.navigateTo({ url: '/pages/points/points' })
    })
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
