const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { formatTime } = require('../../utils/format-time')
const popup = require('../../utils/ui-popup-helper')

Page({
  data: { ...getNavigationMetrics(), item: null, heatText: '', favored: false, isMine: false, loading: true, error: '', uiPopup: null },
  onLoad(options) {
    this._alive = true
    this.id = String(options.id || '')
    this.loadDetail()
    // 浏览上报：真实热度来源之一；失败不影响页面
    if (/^[1-9]\d*$/.test(this.id)) api.viewGoods(this.id).catch(() => {})
  },
  onUnload() { this._alive = false },
  async loadDetail() {
    if (!(/^[1-9]\d*$/.test(this.id) || /^local-\d+$/.test(this.id))) { this.setData({ loading: false, error: '物品不存在' }); return }
    try {
      const item = await api.getGoodsDetail(this.id)
      if (!this._alive) return
      const seller = item.seller || {}
      const price = Number(item.price || 0)
      // 是不是自己发的：决定底部显示「私聊卖家」还是「删除」。
      // 注意详情接口不返回顶层 userId，卖家身份在 seller.id 里
      // （seller.id 就是 goods.user_id）。
      const isMine = String(seller.id || '') === String(api.currentUserId())
      this.setData({
        item: { ...item, createdAt: formatTime(item.createdAt), priceText: Number.isInteger(price) ? String(price) : price.toFixed(2), sellerInitial: (seller.nickname || '同').slice(0, 1), sellerName: seller.nickname || '校园同学', sellerColor: '#7EB6F6' },
        heatText: this.buildHeatText(item),
        favored: api.isGoodsFavorite(this.id),
        isMine,
        loading: false
      })
    } catch (error) {
      if (this._alive) this.setData({ loading: false, error: error.message || '详情加载失败' })
    }
  },
  // 与后端 goodsHeatText 保持一致：优先展示更能说明热度的行为
  buildHeatText(item) {
    const contacts = Number(item.contactCount) || 0
    const favorites = Number(item.favoriteCount) || 0
    const views = Number(item.viewCount) || 0
    if (contacts > 0) return `${contacts} 人咨询`
    if (favorites > 0) return `${favorites} 人收藏`
    if (views > 0) return `${views} 次浏览`
    return ''
  },
  async toggleFavorite() {
    if (!this.data.item || this._favPending) return
    this._favPending = true
    try {
      const res = await api.favoriteGoods(this.id)
      if (!this._alive) return
      this.setData({ favored: !!(res && res.favored) })
      popup.toast(this, res && res.favored ? '已收藏' : '已取消收藏', { tone: 'success' })
    } catch (error) {
      if (this._alive) popup.toast(this, error.message || '操作失败', { tone: 'error' })
    } finally { this._favPending = false }
  },
  openSeller() {
    const seller = this.data.item && this.data.item.seller
    if (seller && seller.id) wx.navigateTo({ url: `/pages/user-home/user-home?id=${seller.id}` })
  },

  /**
   * 删除自己发布的商品
   *
   * 后端 DELETE /api/goods/{id} 一直存在（身份按 token 认），
   * 但页面此前没有入口，用户发布后删不掉。
   * 只对「自己发的」显示按钮（isMine）。
   */
  deleteItem() {
    const item = this.data.item
    if (!item || !this.data.isMine) return
    if (this._deleting) return
    popup.modal(this, {
      title: '删除物品',
      message: `确定删除「${item.title}」吗？删除后无法恢复。`,
      confirmText: '删除',
      confirm: async () => {
        this._deleting = true
        try {
          await api.deleteGoods(this.id)
          popup.toast(this, '已删除', { tone: 'success' })
          // 回列表页，避免停留在已删除的详情上
          setTimeout(() => {
            const pages = getCurrentPages()
            if (pages.length > 1) wx.navigateBack()
            else wx.redirectTo({ url: '/pages/market-list/market-list' })
          }, 600)
        } catch (error) {
          popup.toast(this, error.message || '删除失败', { tone: 'error' })
        } finally {
          this._deleting = false
        }
      }
    })
  },
  chatSeller() {
    const item = this.data.item
    if (!item || !item.seller) return
    const seller = item.seller
    // 私聊上报：咨询数 +1，是热度权重最高的行为
    if (/^[1-9]\d*$/.test(this.id)) api.contactGoods(this.id).catch(() => {})
    wx.navigateTo({ url: `/pages/chat/chat?id=${seller.id}&name=${encodeURIComponent(seller.nickname || '校园同学')}&avatar=${encodeURIComponent((seller.nickname || '同').slice(0, 1))}&color=%237EB6F6` })
  },
  goBack() { wx.navigateBack() },
  onPopupConfirm(e) { this._popupConfirm && this._popupConfirm({ detail: e.detail }) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(e) { this._popupAction && this._popupAction({ detail: e.detail }) },
  onPopupInput(e) { this._popupInput && this._popupInput({ detail: e.detail }) },
  onPopupClose() { popup.hide(this) }
})
