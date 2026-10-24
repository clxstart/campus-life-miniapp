const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { money } = require('../../utils/food')
const popup = require('../../utils/ui-popup-helper')

Page({
  data: { ...getNavigationMetrics(), orders: [], loading: true, error: '', uiPopup: null },

  onLoad() {
    this._alive = true
    this._authReady = false
    api.ensureLogin().then(() => {
      if (!this._alive) return
      this._authReady = true
      this.load()
    })
  },
  onShow() { if (this._alive && this._authReady) this.load() },
  onUnload() { this._alive = false },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()) },

  async load() {
    this.setData({ loading: true, error: '' })
    try {
      const data = await api.getFoodOrders(1, 50)
      if (!this._alive) return
      const orders = (data.list || []).map(order => {
        const items = Array.isArray(order.items) ? order.items : []
        return {
          ...order,
          totalText: money(order.total),
          itemSummary: items.map(item => `${item.name} × ${item.count}`).join('、') || '商品信息暂不可用',
          statusText: this.statusText(order.status),
          statusTone: this.statusTone(order.status),
          createdText: this.formatDate(order.createdAt)
        }
      })
      this.setData({ orders, loading: false })
    } catch (error) {
      if (this._alive) this.setData({ loading: false, error: error.message || '订单加载失败' })
    }
  },

  openOrder(event) {
    const id = Number(event.currentTarget.dataset.id)
    if (id > 0) wx.navigateTo({ url: `/pages/food-order-detail/food-order-detail?id=${id}` })
  },

  goFood() { wx.navigateTo({ url: '/pages/food-list/food-list' }) },

  statusText(status) {
    return ({ unpaid: '待支付', pending_payment: '待支付', created: '待支付', paid: '待接单', accepted: '已接单', delivering: '配送中', completed: '已完成', cancelled: '已取消', refunded: '已退款' })[status] || '状态更新中'
  },
  statusTone(status) {
    if (status === 'completed') return 'done'
    if (status === 'cancelled' || status === 'refunded') return 'closed'
    return 'active'
  },
  formatDate(value) {
    if (!value) return ''
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { hour12: false })
  },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.reLaunch({ url: '/pages/profile/profile' })
  },
  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm({ detail: event.detail }) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction({ detail: event.detail }) },
  onPopupInput(event) { this.setData({ 'uiPopup.inputValue': event.detail.value }); this._popupInput && this._popupInput({ detail: event.detail }) },
  onPopupClose() { popup.hide(this) }
})
