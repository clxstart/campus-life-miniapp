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
  data: {
    orderId: '',
    order: null,
    statusTitle: '',
    statusHint: '',
    itemSummary: '',
    loading: true,
    error: '',
    offline: false,
    // F10 交易埋点：订单完成且实际发过分时顶部提示条文案（空串 = 不显示）
    pointsNotice: ''
  },
  onLoad(options) {
    this.orderId = decodeURIComponent(options.id || '')
    this._alive = true
    this.loadOrder()
  },
  onShow() {
    if (this._alive && this.data.order && !this.terminal(this.data.order.status)) this.schedulePoll()
  },
  onHide() { this.clearPoll() },
  onUnload() { this._alive = false; this.clearPoll(); this.clearNoticeTimer() },
  terminal(status) { return ['completed', 'cancelled', 'refunded'].includes(status) },
  clearPoll() { if (this._pollTimer) clearTimeout(this._pollTimer); this._pollTimer = null },
  clearNoticeTimer() { if (this._noticeTimer) clearTimeout(this._noticeTimer); this._noticeTimer = null },
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
      this.syncPointsNotice(order)
      this.schedulePoll()
    } catch (error) {
      if (!this._alive) return
      this.setData({ loading: false, error: error.message || '订单信息加载失败' })
      if (silent) this.schedulePoll()
    }
  },

  /**
   * 完成提示条（F10 设计稿 §2.1）
   *
   * 唯一判据是后端返回的 pointsAwarded：它来自 point_log，模拟支付 / 退款 / 日上限命中
   * 都不会有流水，因此一律显示 0 —— 前端绝不靠「状态是 completed」自己推断 +10，
   * 否则会出现「页面说 +10、积分明细里查不到」这种对不上账的投诉。
   * 4 秒自动收起，点 × 立即关闭；手动关掉或刷新后不再重复弹（_noticeDismissed 记在页面实例上）。
   */
  syncPointsNotice(order) {
    const points = Number(order && order.pointsAwarded) || 0
    if (!order || order.status !== 'completed' || points <= 0 || this._noticeDismissed) return
    this.clearNoticeTimer()
    this.setData({ pointsNotice: `订单完成，+${points} 积分已到账` })
    this._noticeTimer = setTimeout(() => {
      if (this._alive) this.setData({ pointsNotice: '' })
    }, 4000)
  },
  closePointsNotice() {
    this._noticeDismissed = true
    this.clearNoticeTimer()
    this.setData({ pointsNotice: '' })
  },

  refresh() { this.loadOrder() },
  goBack() { wx.navigateBack({ delta: 1 }) }
})
