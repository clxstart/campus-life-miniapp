const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { formatTime } = require('../../utils/format-time')
const popup = require('../../utils/ui-popup-helper')
const { asset } = require('../../utils/remote-assets')

Page({
  data: {
    ...getNavigationMetrics(),
    // 无图时的兜底插画（和列表页同一张）
    defaultCover: asset('/assets/home/secondhand-box.webp'),
    item: null,
    heatText: '',
    favored: false,
    isMine: false,
    // F10 交易埋点：商品在售且我不是卖家时才有「我已买到」
    canTrade: false,
    // 已发起、等卖家确认：按钮变灰且不可再点（进入页面时从服务端恢复，见 restoreTradeState）
    tradePending: false,
    tradeBusy: false,
    loading: true,
    error: '',
    uiPopup: null
  },
  onLoad(options) {
    this._alive = true
    this.id = String(options.id || '')
    this.loadDetail()
    // 浏览上报：真实热度来源之一；失败不影响页面
    if (/^[1-9]\d*$/.test(this.id)) api.viewGoods(this.id).catch(() => {})
  },
  onUnload() { this._alive = false },
  // F10：每次进入/切回本页只查一次「我在这件商品上的成交」，刷新后仍能保持「等待卖家确认」
  onShow() { this.restoreTradeState() },
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
      // 「我已买到」只在商品在售、且我不是卖家时出现。
      // 详情接口（selectGood）本身只返回 status=1 的商品，这里仍显式判一次：
      // 将来接口把 status 带出来时不用再改页面。
      const goodsStatus = item.status === undefined || item.status === null ? 1 : Number(item.status)
      const canTrade = !isMine && goodsStatus === 1
      this.setData({
        item: { ...item, createdAt: formatTime(item.createdAt), priceText: Number.isInteger(price) ? String(price) : price.toFixed(2), sellerInitial: (seller.nickname || '同').slice(0, 1), sellerName: seller.nickname || '校园同学', sellerColor: '#7EB6F6' },
        heatText: this.buildHeatText(item),
        favored: api.isGoodsFavorite(this.id),
        isMine,
        canTrade,
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

  /**
   * F10：恢复「等待卖家确认」状态（刷新 / 重进 / 从其它页返回时）
   *
   * 只查「我自己在这件商品上」的那笔成交，所以不会看到别的买家或卖家侧的其它成交。
   * 只有 pending 且 buyerId 是我才置等待态；cancelled / confirmed / 无记录（接口返回 {}）
   * 都回到正常售卖态。未登录不请求：该接口要登录，401 只会白白清一次本地登录态。
   * 失败静默忽略：详情与按钮保持现状，用户重新点「我已买到」时后端状态机仍会兜底。
   */
  async restoreTradeState() {
    if (!this._alive || !api.isLoggedIn()) return
    if (!/^[1-9]\d*$/.test(this.id) || this._tradeRestoring) return
    this._tradeRestoring = true
    try {
      const mine = await api.request(`/market/${encodeURIComponent(this.id)}/trade`, 'GET')
      if (!this._alive) return
      const pending = !!mine && mine.status === 'pending'
        && String(mine.buyerId) === String(api.currentUserId())
      this.setData({ tradePending: pending })
    } catch (error) {
      // 恢复失败不影响页面：保持当前渲染，发起成交仍由后端状态机验证
    } finally {
      this._tradeRestoring = false
    }
  },

  /**
   * 「我已买到」（F10 设计稿 §2.3 买家侧）
   *
   * 两步确认的第一步：这里只产生一笔 pending 成交，不发分，要等卖家在自己的「我的发布」里确认。
   * 所以文案必须说清楚「需要卖家确认才算成交」，不能让买家以为自己已经买成了。
   *
   * 写接口不兜底：失败就把后端原话给用户（该商品已有成交记录 / 已有待确认的成交 / 商品已售出）。
   * 刷新/重进时由 restoreTradeState 调 GET /api/market/{goodsId}/trade 恢复「等待卖家确认」。
   */
  startTrade() {
    const item = this.data.item
    if (!item || this.data.isMine || this.data.tradePending || this.data.tradeBusy) return
    popup.modal(this, {
      title: '确认已买到？',
      message: '确认后会通知卖家，需要卖家确认才算成交；成交后双方各得 5 积分',
      confirmText: '确认',
      cancelText: '取消',
      confirm: () => this.doStartTrade()
    })
  },

  async doStartTrade() {
    if (this.data.tradeBusy) return
    this.setData({ tradeBusy: true })
    try {
      const result = await api.startMarketTrade(this.id)
      if (!this._alive) return
      // 卖家还没确认，按钮进入等待态：不发分，也不再允许重复发起
      // （alreadyPending=true 说明我之前已经发起过，用户看到的结论一样：等卖家确认）
      this.setData({ tradePending: true })
      popup.toast(this, result && result.alreadyPending ? '已发起过，等待卖家确认' : '已通知卖家，等待卖家确认', { tone: 'success' })
    } catch (error) {
      if (!this._alive) return
      // 幂等分支之外的失败都是真实拒绝（已有成交记录 / 已有别人在等 / 已售出），原样转述后端原因
      popup.modal(this, {
        title: '暂时不能发起成交',
        message: (error && error.message) || '发起失败，请稍后重试',
        showCancel: false,
        confirmText: '知道了'
      })
    } finally {
      if (this._alive) this.setData({ tradeBusy: false })
    }
  },

  goBack() { wx.navigateBack() },
  onPopupConfirm(e) { this._popupConfirm && this._popupConfirm({ detail: e.detail }) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(e) { this._popupAction && this._popupAction({ detail: e.detail }) },
  onPopupInput(e) { this._popupInput && this._popupInput({ detail: e.detail }) },
  onPopupClose() { popup.hide(this) }
})
