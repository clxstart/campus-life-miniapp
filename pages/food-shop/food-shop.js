const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { money, bill, orderItems } = require('../../utils/food')
const popup = require('../../utils/ui-popup-helper')

Page({
  data: {
    ...getNavigationMetrics(),
    shop: null,
    menu: [],
    activeCategory: 0,
    visibleGoods: [],
    counts: {},
    cartCount: 0,
    subtotal: '0.00',
    missing: '0.00',
    loading: true,
    error: '',
    uiPopup: null
  },

  async onLoad(options) {
    this._alive = true
    const id = Number(options.id)
    try {
      if (!Number.isSafeInteger(id) || id < 1) throw new Error('店铺不存在')
      const [shops, menu] = await Promise.all([api.getFoodShops(), api.getFoodMenu(id)])
      if (!this._alive) return
      const shop = shops.find(row => Number(row.id) === id)
      if (!shop) throw new Error('店铺不存在')
      const decorated = (menu || [])
        .map(group => ({
          ...group,
          goods: (group.goods || [])
            .filter(goods => Number(goods.status) === 1)
            .map(goods => ({ ...goods, priceText: money(goods.price) }))
        }))
        .filter(group => group.goods.length > 0)
      this.setData({
        shop: { ...shop, isOpen: Boolean(shop.isOpen), minPriceText: money(shop.minPrice), deliveryFeeText: money(shop.deliveryFee) },
        menu: decorated,
        visibleGoods: decorated[0] ? decorated[0].goods : [],
        loading: false
      })
      this.updateCart()
    } catch (error) {
      if (this._alive) this.setData({ loading: false, error: error.message || '加载失败' })
    }
  },

  onUnload() { this._alive = false },

  chooseCategory(event) {
    const index = Number(event.currentTarget.dataset.index)
    const group = this.data.menu[index]
    if (group) this.setData({ activeCategory: index, visibleGoods: group.goods })
  },

  changeCount(event) {
    if (!this.data.shop?.isOpen) { popup.toast(this, '本店休息中，暂时不能下单'); return }
    const id = Number(event.currentTarget.dataset.id)
    const delta = Number(event.currentTarget.dataset.delta)
    if (!this.data.menu.some(group => group.goods.some(goods => Number(goods.id) === id))) return
    const count = (this.data.counts[id] || 0) + delta
    if (count > 99) { popup.toast(this, '单件商品最多购买 99 件'); return }
    const counts = { ...this.data.counts }
    if (count <= 0) delete counts[id]
    else counts[id] = count
    this.setData({ counts })
    this.updateCart()
  },

  cartItems() {
    const wanted = Object.keys(this.data.counts).map(id => ({ goodsId: Number(id), count: this.data.counts[id] }))
    return wanted.length ? orderItems(this.data.menu, wanted) : []
  },

  updateCart() {
    if (!this.data.shop) return
    const items = this.cartItems()
    const fees = bill(this.data.shop, items)
    this.setData({ cartCount: items.reduce((sum, item) => sum + item.count, 0), subtotal: fees.subtotal, missing: fees.missing })
  },

  checkout() {
    try {
      if (!this.data.shop?.isOpen) { popup.toast(this, '本店休息中，可浏览但不能下单'); return }
      const items = this.cartItems()
      if (!items.length) { popup.toast(this, '先选一份喜欢的菜'); return }
      if (Number(this.data.missing) > 0) { popup.toast(this, '还差 ¥' + this.data.missing + ' 起送'); return }
      wx.setStorageSync('pending_order', {
        shopId: this.data.shop.id,
        shopName: this.data.shop.name,
        items: items.map(({ goodsId, name, price, count }) => ({ goodsId, name, price, count }))
      })
      wx.navigateTo({ url: '/pages/food-order/food-order' })
    } catch (error) {
      popup.toast(this, error.message || '无法保存订单，请重试', { tone: 'error' })
    }
  },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.reLaunch({ url: '/pages/index/index' })
  },
  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm({ detail: event.detail }) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction({ detail: event.detail }) },
  onPopupInput(event) { this.setData({ 'uiPopup.inputValue': event.detail.value }); this._popupInput && this._popupInput({ detail: event.detail }) },
  onPopupClose() { popup.hide(this) }
})
