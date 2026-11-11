/**
 * 积分兑换（V3.1 新页）
 *
 * 商品池来自 GET /api/points/rewards，余额来自 GET /api/points/me。
 * 兑换是写操作：弹窗二次确认 → 按钮置灰防连点 → POST /api/points/redeem，
 * 失败原样报错（积分不足 / 已兑完 / 达上限都不在前端假装成功）。
 */
const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')

function yuanText(priceP) {
  return `≈¥${((Number(priceP) || 0) / 100).toFixed(2)}`
}

Page({
  data: {
    ...getNavigationMetrics(),
    loading: true,
    error: '',
    offline: false,
    balance: 0,
    balanceText: '0 积分',
    valueYuan: '≈¥0.00',
    items: [],
    busyKey: '',
    // 兑换成功后出现的「查看我的优惠券 ›」引导条
    guideVisible: false,
    uiPopup: null
  },

  onLoad() {
    this._alive = true
    this.loadAll()
  },

  onShow() {
    // 从券列表返回时余额可能已变
    if (this._loaded) this.loadBalance()
  },

  onUnload() { this._alive = false },

  onPullDownRefresh() { this.loadAll().finally(() => wx.stopPullDownRefresh()) },

  async loadAll() {
    this.setData({ loading: !this.data.items.length, error: '' })
    const results = await Promise.all([
      this.loadBalance().catch(error => error),
      this.loadRewards().catch(error => error)
    ])
    if (!this._alive) return
    // 商品池是页面主体，它失败才算整页失败；余额失败只影响顶部数字
    const rewardsError = results[1]
    this.setData({
      loading: false,
      error: rewardsError ? ((rewardsError && rewardsError.message) || '商品池没加载出来') : ''
    })
  },

  async loadBalance() {
    const me = await api.getPointsMe()
    if (!this._alive || !me) return
    const balance = Number(me.balance) || 0
    this._loaded = true
    this.setData({
      balance,
      balanceText: `${balance} 积分`,
      valueYuan: yuanText(balance),
      offline: !!me.offline
    })
  },

  async loadRewards() {
    const result = await api.getPointsRewards()
    if (!this._alive) return
    // 后端 GET /points/rewards 返回 {balance, valueYuan, pointsPerYuan, items}
    const rows = Array.isArray(result) ? result : ((result && (result.items || result.list || result.rows)) || [])
    const items = rows
      // 下架商品不展示（后端通常已过滤，这里再兜一层）
      .filter(row => row.enabled !== false && row.enabled !== 0)
      .map(row => {
        const stock = Number(row.stock) || 0
        const limit = Number(row.perUserLimit) || 0
        // 后端字段是 myRedeemed（我兑过几件），旧的演示字段叫 redeemedCount
        const redeemed = Number(row.myRedeemed !== undefined ? row.myRedeemed : row.redeemedCount) || 0
        // 售罄 / 达上限以后端判定为准，没有标记再本地兜底
        const soldOut = row.soldOut !== undefined ? !!row.soldOut : stock <= 0
        const limitReached = row.limitReached !== undefined
          ? !!row.limitReached
          : (limit > 0 && redeemed >= limit)
        return {
          ...row,
          priceP: Number(row.priceP) || 0,
          priceText: `${Number(row.priceP) || 0} 积分`,
          yuanText: yuanText(row.priceP),
          stockText: soldOut ? '已兑完' : `还剩 ${stock}`,
          limitText: limit > 0 ? `每人限 ${limit} 件` : '不限购',
          soldOut,
          limitReached,
          disabled: soldOut || limitReached
        }
      })
    this.setData({
      items,
      offline: this.data.offline || !!(result && result.offline)
    })
  },

  /**
   * 点商品：先判状态再弹确认。
   * 售罄 / 达上限直接 toast（不弹窗，减少一次点击）；
   * 积分不足给「去赚积分」的去处，而不是只说失败。
   */
  tapItem(event) {
    const key = event.currentTarget.dataset.key
    const item = this.data.items.find(row => String(row.itemKey) === String(key))
    if (!item || this.data.busyKey) return
    if (item.soldOut) return popup.toast(this, '手慢了，这件已经兑完了', { tone: 'info' })
    if (item.limitReached) return popup.toast(this, '已达上限', { tone: 'info' })
    if (this.data.balance < item.priceP) {
      const gap = item.priceP - this.data.balance
      return popup.modal(this, {
        title: '积分不足',
        message: `积分不足，还差 ${gap} 分。签到、发帖、点外卖都能攒积分。`,
        confirmText: '去赚积分',
        cancelText: '再看看',
        confirm: () => wx.navigateTo({ url: '/pages/points/points' })
      })
    }
    popup.modal(this, {
      title: '确认兑换',
      message: `将扣除 ${item.priceP} 积分，兑换后不可退`,
      confirmText: '兑换',
      confirm: () => this.doRedeem(item)
    })
  },

  async doRedeem(item) {
    if (this.data.busyKey) return
    this.setData({ busyKey: item.itemKey })
    try {
      await api.redeemPoints(item.itemKey)
      if (!this._alive) return
      popup.toast(this, '兑换成功，已放入「我的优惠券」', { tone: 'success' })
      // 局部刷新余额与库存，不整页重载
      await Promise.all([this.loadBalance(), this.loadRewards()])
      if (this._alive) this.setData({ guideVisible: true, busyKey: '' })
    } catch (error) {
      if (!this._alive) return
      this.setData({ busyKey: '' })
      popup.toast(this, (error && error.message) || '兑换失败，请稍后重试', { tone: 'error' })
      // 后端 400（并发售罄/积分不足）后库存与余额都可能已变，重新拉一次
      this.loadAll()
    }
  },

  openCoupons() {
    // 「我的优惠券」在个人页「优惠与权益」里，直接回个人页
    wx.navigateTo({ url: '/pages/profile/profile' })
  },

  openPoints() { wx.navigateTo({ url: '/pages/points/points' }) },

  retry() { this.loadAll() },

  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})