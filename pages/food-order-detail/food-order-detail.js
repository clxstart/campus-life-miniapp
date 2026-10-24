const api = require('../../utils/api')

const STATUS = {
  created: ['待支付', '订单已创建，等待支付确认'],
  paid: ['待商家接单', '支付已确认，等待店铺接单'],
  accepted: ['等待骑手', '商家已接单，等待骑手接单'],
  delivering: ['骑手已接单', '骑手已接单，正在赶来取餐'],
  picked: ['正在配送', '骑手已取餐，正在配送'],
  completed: ['已送达', '订单已完成，祝你用餐愉快'],
  cancelled: ['已取消', '订单已取消'],
  refunded: ['已退款', '订单款项已退回']
}

Page({
  data: { orderId: '', order: null, statusTitle: '', statusHint: '', itemSummary: '', loading: true, error: '', offline: false },
  onLoad(options) {
    this.orderId = decodeURIComponent(options.id || '')
    this._alive = true
    this.loadOrder()
  },
  onShow() {
    if (this._alive && this.data.order && !this.terminal(this.data.order.status)) this.schedulePoll()
  },
  onHide() { this.clearPoll() },
  onUnload() { this._alive = false; this.clearPoll() },
  terminal(status) { return ['completed', 'cancelled', 'refunded'].includes(status) },
  clearPoll() { if (this._pollTimer) clearTimeout(this._pollTimer); this._pollTimer = null },
  schedulePoll() {
    this.clearPoll()
    if (!this._alive || !this.data.order || this.terminal(this.data.order.status)) return
    this._pollTimer = setTimeout(() => this.loadOrder(true), 3000)
  },
  async loadOrder(silent = false) {
    if (!this.orderId) { this.setData({ loading: false, error: '订单编号无效' }); return }
    try {
      const order = await api.getFoodOrder(this.orderId)
      if (!this._alive) return
      const [statusTitle, statusHint] = STATUS[order.status] || ['订单处理中', '订单状态正在更新']
      let items = []
      try { items = JSON.parse(order.items || '[]') } catch (error) {}
      this.setData({ order, statusTitle, statusHint, itemSummary: items.map(item => `${item.name}×${item.count}`).join('、'), loading: false, error: '', offline: !!order.offline })
      this.schedulePoll()
    } catch (error) {
      if (!this._alive) return
      this.setData({ loading: false, error: error.message || '订单信息加载失败' })
      if (silent) this.schedulePoll()
    }
  },
  refresh() { this.loadOrder() },
  goBack() { wx.navigateBack({ delta: 1 }) }
})
