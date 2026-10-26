const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { formatTime } = require('../../utils/format-time')
const popup = require('../../utils/ui-popup-helper')

// URL 参数解码：navigateTo 传的中文是百分号编码，需要解回来。
// 解码失败（如参数里带孤立的 %）时退回原串，不能让页面崩。
function safeDecode(value) {
  const raw = String(value == null ? '' : value)
  if (!raw) return ''
  try {
    return decodeURIComponent(raw).trim()
  } catch (error) {
    return raw.trim()
  }
}

// 结果页分类：key 对应后端 type；all 为混排
const TABS = [
  { key: 'all', label: '全部' },
  { key: 'post', label: '帖子' },
  { key: 'circle', label: '圈子' },
  { key: 'goods', label: '闲置' },
  { key: 'activity', label: '活动' },
  { key: 'job', label: '兼职' },
  { key: 'food', label: '外卖' }
]

const GOODS_MODE_TEXT = { 1: '出售', 2: '求购' }

function money(value) {
  const num = Number(value)
  return Number.isFinite(num) ? num.toFixed(2) : '0.00'
}

// 后端返回的异构结果 → 统一卡片；_type 决定展示与跳转目标
function toCard(row, fallbackType) {
  const type = row._type || fallbackType || 'post'
  if (type === 'circle') {
    return {
      key: `circle-${row.id}`, type, id: row.id, title: row.name, typeLabel: '圈子',
      desc: row.description || '', image: row.cover || '',
      meta: `${row.memberCount || 0} 成员 · ${row.postCount || 0} 帖子`, url: `/pages/circle-detail/circle-detail?id=${row.id}`
    }
  }
  if (type === 'goods') {
    return {
      key: `goods-${row.id}`, type, id: row.id, title: row.title, typeLabel: '闲置',
      desc: row.description || '', image: row.image || '',
      meta: `¥${money(row.price)} · ${GOODS_MODE_TEXT[row.mode] || '出售'}${row.conditionLevel ? ' · ' + row.conditionLevel : ''}`,
      url: `/pages/market-detail/market-detail?id=${row.id}`
    }
  }
  if (type === 'activity') {
    return {
      key: `activity-${row.id}`, type, id: row.id, title: row.title, typeLabel: '活动',
      desc: row.location || '', image: row.poster || '',
      meta: `${row.timeDesc || ''} · ${row.enrolled || 0} 人已报名`, url: '/pages/activities/activities'
    }
  }
  if (type === 'job') {
    return {
      key: `job-${row.id}`, type, id: row.id, title: row.title, typeLabel: '兼职',
      desc: row.location || '', image: '',
      meta: `${row.daySalary ? row.daySalary + '元/天' : ''}${row.settle ? ' · ' + row.settle : ''}`, url: '/pages/jobs/jobs'
    }
  }
  if (type === 'shop') {
    return {
      key: `shop-${row.id}`, type, id: row.id, title: row.name, typeLabel: '外卖店铺',
      desc: row.notice || '', image: row.cover || '',
      meta: `★${row.rating || '-'} · 月售${row.monthlySales || 0} · 起送¥${money(row.minPrice)}`,
      url: `/pages/food-shop/food-shop?id=${row.id}`
    }
  }
  if (type === 'shopGoods') {
    return {
      key: `dish-${row.id}`, type, id: row.id, title: row.name, typeLabel: '外卖菜品',
      desc: row.shopName ? `来自「${row.shopName}」` : '', image: row.image || '',
      meta: `¥${money(row.price)} · 月售${row.monthlySales || 0}`,
      url: `/pages/food-shop/food-shop?id=${row.shopId}`
    }
  }
  // 帖子
  return {
    key: `post-${row.id}`, type: 'post', id: row.id, title: row.title, typeLabel: '帖子',
    desc: row.content || '', image: row.image || '',
    avatar: row.authorAvatar || '', avatarText: (row.authorNickname || '同')[0], author: row.authorNickname || '校园同学',
    meta: `${formatTime(row.createdAt)}${row.tag ? ' · ' + row.tag : ''} · ♥ ${row.likeCount || 0} · ◌ ${row.commentCount || 0}`,
    url: `/pages/post-detail/post-detail?id=${row.id}`
  }
}

Page({
  data: {
    ...getNavigationMetrics(),
    tabs: TABS,
    activeTab: 0,
    keyword: '',
    draft: '',
    counts: {},
    cards: [],
    page: 0,
    size: 10,
    total: 0,
    hasMore: false,
    loading: false,
    searched: false,
    offline: false,
    error: '',
    uiPopup: null
  },

  onLoad(options) {
    this._alive = true
    // 页面参数来自 navigateTo 的 url，微信不会自动解码，需要自己解一次。
    // 只解码 URL 参数；页面内输入框的关键词不走这里，避免把用户输入的 %xx 改掉。
    const keyword = safeDecode(options.keyword)
    // 支持从别处带类型进来（如圈子里点搜索默认查圈子）
    const tabIndex = TABS.findIndex(tab => tab.key === options.type)
    this.setData({ keyword, draft: keyword, activeTab: tabIndex >= 0 ? tabIndex : 0 })
    if (keyword) this.loadResults(true)
  },

  onUnload() { this._alive = false; this._seq = (this._seq || 0) + 1 },

  inputKeyword(event) { this.setData({ draft: event.detail.value }) },

  submitSearch() {
    const keyword = this.data.draft.trim()
    if (!keyword || keyword.length > 64) { popup.toast(this, !keyword ? '请输入搜索关键词' : '关键词不能超过64字'); return }
    this.setData({ keyword })
    this.loadResults(true)
  },

  switchTab(event) {
    const index = Number(event.currentTarget.dataset.index)
    if (index === this.data.activeTab || this.data.loading) return
    this.setData({ activeTab: index })
    this.loadResults(true)
  },

  async loadResults(reset = false) {
    if (!reset && (this.data.loading || this.data.cards.length >= this.data.total)) return
    const seq = this._seq = (this._seq || 0) + 1
    const page = reset ? 1 : this.data.page + 1
    const { keyword, tabs, activeTab } = this.data
    const type = tabs[activeTab].key
    this.setData({ loading: true, error: '', searched: true, ...(reset ? { cards: [], page: 0, total: 0, offline: false } : {}) })
    try {
      const result = await api.searchAll({ keyword, type, page, size: this.data.size })
      if (!this._alive || seq !== this._seq) return
      const cards = (result.list || []).map(row => toCard(row, type))
      // 后端上线/掉线时重新读第一页，避免真实数据与演示数据混为一次分页
      if (!reset && this.data.offline !== !!result.offline) { this.loadResults(true); return }
      const merged = reset
        ? cards
        : this.data.cards.concat(cards.filter(card => !this.data.cards.some(old => old.key === card.key)))
      this.setData({
        cards: merged,
        counts: reset ? (result.counts || {}) : this.data.counts,
        page,
        total: Number(result.total) || 0,
        // 混排模式一次给全，不再翻页；单类模式按总数判断是否还有下一页
        hasMore: type === 'all' ? false : merged.length < (Number(result.total) || 0),
        offline: !!result.offline
      })
    } catch (error) {
      if (this._alive && seq === this._seq) this.setData({ error: error.message || '搜索失败，请重试' })
    } finally {
      if (this._alive && seq === this._seq) this.setData({ loading: false })
    }
  },

  loadMore() { return this.loadResults(false) },
  retry() { return this.loadResults(this.data.page === 0) },
  onReachBottom() { if (!this.data.error) this.loadMore() },
  onPullDownRefresh() { this.loadResults(true).finally(() => wx.stopPullDownRefresh()) },

  // 各类结果按类型跳转；活动/兼职没有详情页，直接回列表页
  openCard(event) {
    const card = this.data.cards.find(item => item.key === event.currentTarget.dataset.key)
    if (!card || !card.url) return
    wx.navigateTo({ url: card.url })
  },

  goBack() { if (getCurrentPages().length > 1) wx.navigateBack(); else wx.reLaunch({ url: '/pages/index/index' }) },
  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event) },
  onPopupInput(event) { this._popupInput && this._popupInput(event) },
  onPopupClose() { popup.hide(this) }
})
