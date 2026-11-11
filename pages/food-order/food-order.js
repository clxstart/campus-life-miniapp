const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { cents, bill, orderItems } = require('../../utils/food')
const popup = require('../../utils/ui-popup-helper')

// V3.1 券：页面只做「选哪张 + 展示抵扣」，抵扣额最终以服务端订单返回为准。
// 可用口径与 CouponService.usable() 保持一致：status=1、scope 为 takeout、未过期、达到门槛。
function couponUsable(coupon, subtotalCents) {
  if (!coupon || Number(coupon.status) !== 1) return false
  if (coupon.scope && coupon.scope !== 'takeout') return false
  return (Number(coupon.thresholdCents) || 0) <= subtotalCents
}

function couponReason(coupon, subtotalCents) {
  if (coupon && coupon.scope && coupon.scope !== 'takeout') return '该券不适用于外卖订单'
  const threshold = coupon ? Number(coupon.thresholdCents) || 0 : 0
  return threshold > subtotalCents ? `满 ${(threshold / 100).toFixed(2)} 元可用` : '该券当前不可用'
}

// 抵扣额展示值：券面额，但不超过「商品合计 − 店铺满减」（与服务端 min 口径一致）
function couponDiscountCents(coupon, fees) {
  if (!coupon) return 0
  const cap = Math.max(0, cents(fees.subtotal) - cents(fees.discount))
  return Math.min(Number(coupon.valueCents) || 0, cap)
}

function yuanText(centsValue) { return (Math.max(0, centsValue) / 100).toFixed(2) }

Page({
  data: { ...getNavigationMetrics(), shop: null, items: [], addresses: [], address: null, remark: '', fees: {}, coupons: [], coupon: null, couponLabel: '', couponHint: '', couponDiscount: '0.00', payTotal: '', loading: true, submitting: false, submitted: false, error: '', uiPopup: null },
  async onLoad() {
    this._alive = true
    try {
      await api.ensureLogin()
      const pending = wx.getStorageSync('pending_order')
      if (!pending || !Number.isSafeInteger(Number(pending.shopId)) || Number(pending.shopId) < 1) throw new Error('暂时没有待结算订单')
      // 用 schoolScope='all' 查店铺：shopId 已经确定，这里只要它的展示信息。
      // 按默认本校范围查的话，用户从「跨校店铺」进结算会找不到店
      // （和 food-shop 详情页同一类问题）。
      const [shops, menu, addresses] = await Promise.all([
        api.getFoodShops('', 'all', null),
        api.getFoodMenu(pending.shopId),
        api.getAddresses(api.currentUserId())
      ])
      if (!this._alive) return
      const shop = shops.find(s => Number(s.id) === Number(pending.shopId))
      if (!shop) throw new Error('店铺不存在，请重新选购')
      if (!shop.isOpen) throw new Error('本店休息中，暂时不能下单')
      const items = orderItems(menu, pending.items)
      const fees = bill(shop, items)
      this.setData({ shop, items, addresses, address: addresses[0] || null, fees, payTotal: fees.total, loading: false })
      // 券列表不阻塞首屏：拉失败就是「暂无可用」，只影响这一行展示
      this.loadCoupons()
    } catch (error) {
      if (this._alive) this.setData({ loading: false, error: error.message || '订单加载失败' })
    }
  },
  /**
   * 拉当前用户的可用券并按订单金额标注可选/不可选。
   * selectBest=true 时自动选「抵扣最多」的一张；用户手动选过（含选「不使用」）就不再自动改。
   */
  async loadCoupons() {
    try {
      const list = await api.getCoupons(api.currentUserId())
      if (!this._alive) return
      const rows = (Array.isArray(list) ? list : []).filter(row => row && Number(row.valueCents) > 0)
      const subtotal = cents(this.data.fees.subtotal || '0')
      const coupons = rows.map(row => ({ ...row, usableNow: couponUsable(row, subtotal), reason: couponReason(row, subtotal) }))
      const usable = coupons.filter(row => row.usableNow)
      const keepId = this.data.coupon && this.data.coupon.id
      // 刷新后优先沿用已选券；它已不可用（被别人锁走/过期）则回落到最优券
      const kept = keepId ? usable.find(row => Number(row.id) === Number(keepId)) : null
      const best = usable.slice().sort((a, b) => (Number(b.valueCents) || 0) - (Number(a.valueCents) || 0))[0] || null
      const next = this._couponTouched ? kept || null : kept || best
      this.setData({ coupons })
      this.applyCoupon(next)
    } catch (error) {
      if (!this._alive) return
      // 券接口失败不阻断下单：按「暂无可用」处理，提交时服务端仍会校验
      this.setData({ coupons: [] })
      this.applyCoupon(null)
    }
  },
  /** 选中/清空券后的展示：券抵扣行、实付金额、行内文案（金额只做展示，最终以订单返回为准） */
  applyCoupon(coupon) {
    const fees = this.data.fees || {}
    const subtotal = cents(fees.subtotal || '0')
    const discount = couponDiscountCents(coupon, { subtotal: fees.subtotal || '0', discount: fees.discount || '0' })
    const payable = Math.max(0, cents(fees.total || '0') - discount)
    const usable = (this.data.coupons || []).filter(row => couponUsable(row, subtotal))
    const firstBlocked = usable.length ? null : (this.data.coupons || [])[0]
    this.setData({
      coupon: coupon || null,
      couponDiscount: yuanText(discount),
      payTotal: yuanText(payable),
      couponLabel: coupon ? `${coupon.title} −¥${yuanText(discount)}` : (usable.length ? '选择优惠券' : '暂无可用'),
      couponHint: coupon || usable.length ? '' : (firstBlocked ? couponReason(firstBlocked, subtotal) : '')
    })
  },
  /** 选券弹层：可选券在前、不可选券带原因，最后一项是不使用 */
  openCoupons() {
    if (this.data.loading || this.data.error || this.data.submitting || this.data.submitted) return
    const subtotal = cents(this.data.fees.subtotal || '0')
    const rows = this.data.coupons || []
    const current = this.data.coupon
    const usable = rows.filter(row => couponUsable(row, subtotal))
    const blocked = rows.filter(row => !couponUsable(row, subtotal))
    if (!rows.length) { popup.toast(this, '暂无优惠券，去「我的积分」兑换一张吧', { tone: 'info' }); return }
    const actions = usable.map(row => ({
      label: `${current && Number(current.id) === Number(row.id) ? '✓ ' : ''}${row.title} · 抵 ¥${yuanText(Number(row.valueCents) || 0)}${row.expireOn ? ' · ' + row.expireOn + ' 前有效' : ''}`,
      couponId: row.id,
      disabled: false
    })).concat(blocked.map(row => ({
      label: `${row.title} · 不可选（${couponReason(row, subtotal)}）`,
      couponId: 0,
      disabled: true,
      reason: couponReason(row, subtotal)
    }))).concat([{ label: '不使用优惠券', couponId: 0, disabled: false }])
    popup.sheet(this, {
      title: '选择优惠券',
      actions,
      action: ({ item }) => {
        if (!item) return
        if (item.disabled) { popup.toast(this, item.reason || '该券当前不可用', { tone: 'warn' }); return }
        this._couponTouched = true
        const picked = item.couponId ? (this.data.coupons || []).find(row => Number(row.id) === Number(item.couponId)) : null
        this.applyCoupon(picked || null)
      }
    })
  },
  /** 提交后用订单返回的金额校准展示：客户端算的只是预估，服务端才是准的 */
  calibrateAmount(order) {
    if (!order) return
    const paid = order.total !== undefined && order.total !== null
      ? order.total
      : (order.payAmountCents !== undefined && order.payAmountCents !== null ? yuanText(Number(order.payAmountCents)) : null)
    if (paid === null) return
    const discount = order.couponDiscount !== undefined && order.couponDiscount !== null
      ? Number(order.couponDiscount)
      : Math.max(0, cents(this.data.fees.total || '0') - cents(paid)) / 100
    this.setData({ payTotal: yuanText(cents(paid)), couponDiscount: (Math.max(0, discount) / 1).toFixed(2) })
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
      // finding-05：同一次结算使用同一个幂等键，并记住已创建的订单号。
      // 支付取消/失败/网络超时/小程序重启后再点提交，复用同一订单发起支付，不再生成新订单。
      const attempt = this.checkoutAttempt()
      let orderId = attempt.orderId
      if (!orderId) {
        const order = await api.createFoodOrder({ shopId: this.data.shop.id, items: this.data.items.map(item => ({ goodsId: item.goodsId, count: item.count })), address: this.data.address.detail, contactName: this.data.address.contact || '', contactPhone: this.data.address.phone || '', remark: this.data.remark.trim(), idempotencyKey: attempt.key, couponId: this.data.coupon ? Number(this.data.coupon.id) : undefined })
        if (!this._alive) return
        // 金额以服务端返回为准：客户端算的券抵扣只是预估
        this.calibrateAmount(order)
        if (order.offline) return this.finish(order)
        orderId = order.orderId || order.id
        this.saveAttempt({ ...attempt, orderId })
      }
      let payParams
      try { payParams = await api.payOrder(orderId) }
      catch (error) {
        // 旧订单已关闭（超时/取消）：丢弃本次结算记录，下次提交换新键重新下单；
        // 关单会把锁定的券释放回可用，这里同步刷新一次券列表
        if (error.server && /不可支付|已关闭|关闭/.test(String(error.message || ''))) { this.resetAttempt(); this.loadCoupons() }
        throw error
      }
      if (!this._alive) return
      if (payParams.simulated || payParams.payStatus === 'paid') return this.pollOrderStatus(orderId, true)
      wx.requestPayment({ ...payParams, success: () => this.pollOrderStatus(orderId, false), fail: err => { if (!this._alive) return; this.setData({ submitting: false }); const cancelled = String(err.errMsg || '').includes('cancel'); popup.toast(this, cancelled ? '已取消支付' : '支付失败，请重试', { tone: cancelled ? 'info' : 'error' }) } })
    } catch (error) {
      // 服务端提示上一笔已关闭：清掉旧幂等键，用户再点一次即按新结算下单
      if (error && error.server && /已关闭/.test(String(error.message || ''))) { this.resetAttempt(); this.loadCoupons() }
      // 券被别的订单锁走 / 已过期 / 门槛不足：原样提示中文原因，并刷新券列表让用户重选
      const message = String((error && error.message) || '')
      if (error && error.server && /券/.test(message)) { this._couponTouched = false; this.loadCoupons() }
      if (this._alive) { this.setData({ submitting: false }); popup.toast(this, message || '下单失败，请重试', { tone: 'error' }) }
    }
  },
  // 结算尝试：{ key, orderId, sig }。sig 绑定店铺+商品+地址+券：
  // 换一张券等于换一次结算，必须换新幂等键，否则会复用旧订单导致券与订单对不上。
  attemptSignature() {
    const items = this.data.items.map(item => item.goodsId + 'x' + item.count).sort().join(',')
    const couponId = this.data.coupon ? Number(this.data.coupon.id) : 0
    return [this.data.shop && this.data.shop.id, items, this.data.address && this.data.address.detail, couponId].join('|')
  },
  checkoutAttempt() {
    const sig = this.attemptSignature()
    let saved = null
    try { saved = wx.getStorageSync('pending_order_attempt') } catch (error) {}
    if (saved && saved.key && saved.sig === sig) return saved
    const attempt = { key: 'co' + Date.now().toString(36) + Math.random().toString(36).slice(2, 12), orderId: null, sig }
    this.saveAttempt(attempt)
    return attempt
  },
  saveAttempt(attempt) { try { wx.setStorageSync('pending_order_attempt', attempt) } catch (error) {} },
  resetAttempt() { try { wx.removeStorageSync('pending_order_attempt') } catch (error) {} },
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
    this.resetAttempt()
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
