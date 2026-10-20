const api = require('../../utils/api')
const { GOODS_CATEGORY_FILTERS } = require('../../utils/constants')
const school = require('../../utils/school')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')

const AVATAR_COLORS = ['#7EB6F6', '#F5A7C3', '#F3B66B', '#62C894', '#9A8CF2']

Page({
  data: {
    ...getNavigationMetrics(),
    modes: [{ value: 1, label: '出售' }, { value: 2, label: '求购' }],
    mode: 1,
    // 二手按校隔离：默认只看本校（异地无法面交）
    schoolScope: 'local',
    categories: GOODS_CATEGORY_FILTERS,
    category: '全部',
    goods: [],
    loading: true,
    uiPopup: null
  },
  onLoad(options) {
    this._alive = true
    // 学习好物是首页展示名，沿用数据库已有的图书分类。
    let category = options.category || '全部'
    try { category = decodeURIComponent(category) } catch (error) {}
    if (category === '学习好物') category = '图书'
    if (this.data.categories.includes(category)) this.setData({ category })
  },
  onShow() { this.loadGoods() },
  onUnload() { this._alive = false },
  onPullDownRefresh() { this.loadGoods().finally(() => wx.stopPullDownRefresh()) },
  async loadGoods() {
    const seq = this._seq = (this._seq || 0) + 1
    this.setData({ loading: true })
    try {
      const rows = await api.getGoodsList(this.data.mode, this.data.category === '全部' ? '' : this.data.category, this.data.schoolScope, school.getSelectedId())
      if (!this._alive || seq !== this._seq) return
      const goods = (rows || []).map((item, index) => {
        const seller = item.seller || {}
        const price = Number(item.price || 0)
        return {
          ...item,
          priceText: Number.isInteger(price) ? String(price) : price.toFixed(2),
          conditionText: item.conditionLevel || '九成新',
          sellerName: seller.nickname || '校园同学',
          sellerInitial: (seller.nickname || '同').slice(0, 1),
          sellerColor: AVATAR_COLORS[index % AVATAR_COLORS.length]
        }
      })
      this.setData({ goods })
    } catch (error) {
      if (this._alive && seq === this._seq) popup.toast(this, error.message || '市场加载失败', { tone: 'error' })
    } finally {
      if (this._alive && seq === this._seq) this.setData({ loading: false })
    }
  },
  chooseMode(e) {
    const mode = Number(e.currentTarget.dataset.mode)
    if (mode === this.data.mode) return
    this.setData({ mode })
    this.loadGoods()
  },
  chooseCategory(e) {
    const category = e.currentTarget.dataset.category
    if (category === this.data.category) return
    this.setData({ category })
    this.loadGoods()
  },
  // 本校/全部切换：异地无法面交，默认只看本校
  switchSchoolScope(e) {
    const schoolScope = e.currentTarget.dataset.scope
    if (!schoolScope || schoolScope === this.data.schoolScope) return
    this.setData({ schoolScope, goods: [] })
    this.loadGoods()
  },
  openDetail(e) { wx.navigateTo({ url: `/pages/market-detail/market-detail?id=${e.currentTarget.dataset.id}` }) },
  openPublish() { wx.navigateTo({ url: '/pages/market-publish/market-publish' }) },
  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.reLaunch({ url: '/pages/index/index' })
  },
  onPopupConfirm(e) { this._popupConfirm && this._popupConfirm({ detail: e.detail }) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(e) { this._popupAction && this._popupAction({ detail: e.detail }) },
  onPopupInput(e) { this._popupInput && this._popupInput({ detail: e.detail }) },
  onPopupClose() { popup.hide(this) }
})
