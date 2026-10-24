const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { money } = require('../../utils/food')
const popup = require('../../utils/ui-popup-helper')

Page({
  data: { ...getNavigationMetrics(), order: null, loading: true, error: '', uiPopup: null },

  async onLoad(options) {
    this._alive = true
    this._authReady = false
    this._orderId = Number(options.id)
    if (!Number.isSafeInteger(this._orderId) || this._orderId < 1) {
      this.setData({ loading: false, error: '订单不存在' })
      return
    }
    await api.ensureLogin()
    if (!this._alive) return
    this._authReady = true
    await this.loadOrder(false)
    this.startPolling()
  },

  onShow() { if (this._alive && this._authReady && this._orderId) this.startPolling() },
  onHide() { this.stopPolling() },
  onUnload() { this._alive = false; this.stopPolling() },

  async loadOrder(notify = true) {
    try {
      const raw = await api.getFoodOrderDetail(this._orderId)
      if (!this._alive) return
      const previous = this.data.order?.status
      const items = (raw.items || []).map(item => ({ ...item, priceText: money(item.price), lineTotal: money(Number(item.price) * Number(item.count)) }))
      const order = { ...raw, items, totalText: money(raw.total), statusText: this.statusText(raw.status), createdText: this.formatDate(raw.createdAt) }
      this.setData({ order, loading: false, error: '' })
      if (notify && previous && previous !== raw.status) popup.toast(this, order.statusText, { tone: 'success' })
    } catch (error) {
      if (this._alive && !this.data.order) this.setData({ loading: false, error: error.message || '订单加载失败' })
    }
  },

  startPolling() {
    if (this._timer || !this._alive) return
    this._timer = setInterval(() => { if (this._alive) this.loadOrder(true) }, 3000)
  },
  stopPolling() { if (this._timer) { clearInterval(this._timer); this._timer = null } },

  statusText(status) {
    return ({ unpaid: '待支付', pending_payment: '待支付', created: '待支付', paid: '待接单', accepted: '商家已接单', delivering: '配送中', completed: '已完成', cancelled: '已取消', refunded: '已退款' })[status] || '状态更新中'
  },
  formatDate(value) {
    if (!value) return ''
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { hour12: false })
  },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.redirectTo({ url: '/pages/food-orders/food-orders' })
  },
  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm({ detail: event.detail }) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction({ detail: event.detail }) },
  onPopupInput(event) { this.setData({ 'uiPopup.inputValue': event.detail.value }); this._popupInput && this._popupInput({ detail: event.detail }) },
  onPopupClose() { popup.hide(this) }
})
