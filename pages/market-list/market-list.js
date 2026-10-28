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
    loadingMore: false,
    page: 1,
    size: 20,
    total: 0,
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
  onShow() { this.loadGoods(true) },
  onUnload() { this._alive = false },
  onPullDownRefresh() { this.loadGoods(true).finally(() => wx.stopPullDownRefresh()) },
  // 触底加载下一页：只有还有数据且当前不在加载中才继续
  onReachBottom() {
    if (this.data.loadingMore || this.data.goods.length >= this.data.total) return
    this.loadGoods(false)
  },
  /**
   * 加载二手列表。
   * @param {boolean} reset true=回到第一页（切换筛选/下拉刷新），false=加载下一页
   */
  async loadGoods(reset = true) {
    const seq = this._seq = (this._seq || 0) + 1
    const page = reset ? 1 : (this.data.page || 1) + 1
    if (reset) this.setData({ loading: true })
    else this.setData({ loadingMore: true })
    try {
      const result = await api.getGoodsPage({
        mode: this.data.mode,
        category: this.data.category === '全部' ? '' : this.data.category,
        schoolScope: this.data.schoolScope,
        schoolId: school.getSelectedId(),
        page,
        size: this.data.size || 20
      })
      if (!this._alive || seq !== this._seq) return
      const mapped = (result.list || []).map((item, index) => {
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
      // 分页追加时按 id 去重，避免翻页期间有人发布导致重复卡片
      const goods = reset ? mapped : this.data.goods.concat(mapped.filter(next => !this.data.goods.some(old => old.id === next.id)))
      this.setData({ goods, page: result.page, total: result.total })
    } catch (error) {
      if (this._alive && seq === this._seq) popup.toast(this, error.message || '市场加载失败', { tone: 'error' })
    } finally {
      if (this._alive && seq === this._seq) this.setData({ loading: false, loadingMore: false })
    }
  },
  chooseMode(e) {
    const mode = Number(e.currentTarget.dataset.mode)
    if (mode === this.data.mode) return
    this.setData({ mode, goods: [], page: 1, total: 0 })
    this.loadGoods(true)
  },
  chooseCategory(e) {
    const category = e.currentTarget.dataset.category
    if (category === this.data.category) return
    this.setData({ category, goods: [], page: 1, total: 0 })
    this.loadGoods(true)
  },
  // 本校/全部切换：异地无法面交，默认只看本校
  switchSchoolScope(e) {
    const schoolScope = e.currentTarget.dataset.scope
    if (!schoolScope || schoolScope === this.data.schoolScope) return
    this.setData({ schoolScope, goods: [], page: 1, total: 0 })
    this.loadGoods(true)
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

