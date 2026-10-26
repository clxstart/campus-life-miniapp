const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { bill, orderItems } = require('../../utils/food')
const popup = require('../../utils/ui-popup-helper')

Page({
  data: { ...getNavigationMetrics(), shop: null, items: [], addresses: [], address: null, remark: '', fees: {}, loading: true, submitting: false, submitted: false, error: '', uiPopup: null },
  async onLoad() {
    this._alive = true
    try {
      await api.ensureLogin()
      const pending = wx.getStorageSync('pending_order')
      if (!pending || !Number.isSafeInteger(Number(pending.shopId)) || Number(pending.shopId) < 1) throw new Error('暂时没有待结算订单')
      const [shops, menu, addresses] = await Promise.all([api.getFoodShops(), api.getFoodMenu(pending.shopId), api.getAddresses(api.currentUserId())])
      if (!this._alive) return
      const shop = shops.find(s => Number(s.id) === Number(pending.shopId))
      if (!shop) throw new Error('店铺不存在，请重新选购')
      if (!shop.isOpen) throw new Error('本店休息中，暂时不能下单')
      const items = orderItems(menu, pending.items)
      this.setData({ shop, items, addresses, address: addresses[0] || null, fees: bill(shop, items), loading: false })
    } catch (error) {
      if (this._alive) this.setData({ loading: false, error: error.message || '订单加载失败' })
    }
  },
  onUnload() { this._alive = false; if (this._backTimer) clearTimeout(this._backTimer); if (this._pollTimer) clearTimeout(this._pollTimer) },
  chooseAddress() {
    if (this.data.submitting || this.data.submitted) return
    const addresses = this.data.addresses
    popup.sheet(this, { title: '送到哪里？', tone: 'success', actions: addresses.map(a => ({ label: a.detail + (a.contact ? ' · ' + a.contact : '') })).concat([{ label: '＋ 新增收货地址' }]), action: ({ index }) => { if (index === addresses.length) this.addAddress(); else if (addresses[index]) this.setData({ address: addresses[index] }) } })
  },
  addAddress() {
    popup.modal(this, { title: '新增收货地址', message: '请填写宿舍楼、房间号和收件信息', tone: 'success', editable: true, placeholder: '例如：学生公寓 6 号楼312 · 林同学', confirm: async res => { const detail = (res.inputValue || '').trim(); if (!detail || detail.length > 128) { popup.toast(this, '地址请填写 1 至 128 个字', { tone: 'warn' }); return } try { const address = await api.addAddress(api.currentUserId(), detail); if (!this._alive) return; this.setData({ addresses: [address].concat(this.data.addresses), address }); popup.toast(this, '地址已添加', { tone: 'success' }) } catch (error) { if (this._alive) popup.toast(this, error.message || '地址添加失败', { tone: 'error' }) } } })
  },
  editRemark(e) { this.setData({ remark: e.detail.value }) },
  async submit() {
    if (this.data.submitting || this.data.submitted || this.data.loading || this.data.error) return
    if (!this.data.address) { popup.toast(this, '请选择收货地址', { tone: 'warn' }); return }
    if (Number(this.data.fees.missing) > 0) { popup.toast(this, '未达到起送金额，请返回加购', { tone: 'warn' }); return }
    this.setData({ submitting: true })
    try {
      const order = await api.createFoodOrder({ shopId: this.data.shop.id, items: this.data.items.map(item => ({ goodsId: item.goodsId, count: item.count })), address: this.data.address.detail, contactName: this.data.address.contact || '', contactPhone: this.data.address.phone || '', remark: this.data.remark.trim() })
      if (!this._alive) return
      if (order.offline) return this.finish(order)
      const orderId = order.orderId || order.id
      const payParams = await api.payOrder(orderId)
      if (!this._alive) return
      if (payParams.simulated || payParams.payStatus === 'paid') return this.pollOrderStatus(orderId, true)
      wx.requestPayment({ ...payParams, success: () => this.pollOrderStatus(orderId, false), fail: err => { if (!this._alive) return; this.setData({ submitting: false }); const cancelled = String(err.errMsg || '').includes('cancel'); popup.toast(this, cancelled ? '已取消支付' : '支付失败，请重试', { tone: cancelled ? 'info' : 'error' }) } })
    } catch (error) {
      if (this._alive) { this.setData({ submitting: false }); popup.toast(this, error.message || '下单失败，请重试', { tone: 'error' }) }
    }
  },
  pollOrderStatus(orderId, simulated) {
    let attempts = 0
    const poll = async () => {
      try {
        const detail = await api.getOrderDetail(orderId)
        if (detail.payStatus === 'paid' || detail.status === 'paid' || simulated) return this.finish(detail)
      } catch (error) {
        if (error.server && attempts >= 9) { this.setData({ submitting: false }); popup.toast(this, error.message || '支付状态查询失败', { tone: 'error' }); return }
      }
      attempts += 1
      if (attempts >= 10) { this.setData({ submitting: false }); popup.toast(this, '支付结果还在确认中，请稍后在订单中查看', { tone: 'info' }); return }
      this._pollTimer = setTimeout(poll, 2000)
    }
    poll()
  },
  finish(result) {
    if (!this._alive) return
    this.setData({ submitted: true, submitting: false })
    try { wx.removeStorageSync('pending_order') } catch (error) {}
    popup.toast(this, result && result.offline ? '下单成功（离线演示）' : '支付成功，订单已提交', { tone: 'success' })
    // 支付成功后进入订单详情，用户能看到「待商家接单」和后续骑手进度
    const orderId = result && (result.orderId || result.id)
    this._backTimer = setTimeout(() => {
      if (!this._alive) return
      if (orderId) return wx.redirectTo({ url: '/pages/food-order-detail/food-order-detail?id=' + encodeURIComponent(orderId) })
      const pages = getCurrentPages(); const index = pages.findIndex(p => p.route === 'pages/food-list/food-list')
      if (index >= 0 && index < pages.length - 1) wx.navigateBack({ delta: pages.length - 1 - index }); else wx.redirectTo({ url: '/pages/food-list/food-list' })
    }, 1600)
  },
  goBack() { const pages = getCurrentPages(); if (pages.length > 1) wx.navigateBack(); else wx.reLaunch({ url: '/pages/index/index' }) },
  onPopupConfirm(e) { this._popupConfirm && this._popupConfirm({ detail: e.detail }) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(e) { this._popupAction && this._popupAction({ detail: e.detail }) },
  onPopupInput(e) { this.setData({ 'uiPopup.inputValue': e.detail.value }); this._popupInput && this._popupInput({ detail: e.detail }) },
  onPopupClose() { popup.hide(this) }
})
