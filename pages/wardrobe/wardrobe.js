/**
 * 收藏页（原衣柜）
 *
 * 变更说明：原「衣柜」依赖宠物配饰图层，但小橘猫引擎不含配饰，
 * 装备后在宠物身上看不到效果。经确认取消穿戴，改为纯收藏玩法：
 *   - 保留积分消耗与兑换记录（后端逻辑不变）
 *   - 去掉 equip / unequip，不再显示「已装备」态
 *
 * 保留兑换的确认弹窗，让积分消耗有明确提示。
 */
const petApi = require('../../utils/pet-api')
const { DEFAULT_APPEARANCE, findAccessory } = require('../../utils/pet-appearance')
const popup = require('../../utils/ui-popup-helper')

Page({
  data: {
    statusBarHeight: 20,
    appearance: { ...DEFAULT_APPEARANCE },
    balance: 0,
    items: [],
    previewMotion: 'idle',
    buyVisible: false,
    buying: { name: '', price: 0, code: '' }
  },

  onLoad() {
    const metrics = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    this.setData({ statusBarHeight: metrics.statusBarHeight || 20 })
    this._userId = getUserId()
  },

  onShow() { this._load() },

  async _load() {
    try {
      const wd = await petApi.getWardrobe(this._userId)
      const list = (wd && wd.list) || []
      // 给每件物品配一个符号，纯展示用
      const items = list.map(it => {
        const acc = findAccessory(it.partId || it.code)
        return {
          ...it,
          symbol: SYMBOL[it.partId] || '◆',
          description: it.description || ''
        }
      })
      this.setData({
        items,
        balance: (wd && wd.balance) || 0
      })
    } catch (error) {
      popup.toast(this, error.message || '加载失败', { tone: 'error' })
      this.setData({ items: [] })
    }
  },

  onPetTap() {
    this.setData({ previewMotion: 'happy' })
    if (this._t) clearTimeout(this._t)
    this._t = setTimeout(() => this.setData({ previewMotion: 'idle' }), 1400)
  },

  // ===== 兑换 =====
  onBuy(e) {
    const ds = e.currentTarget.dataset
    this.setData({
      buyVisible: true,
      buying: {
        code: ds.code,
        name: ds.name,
        price: Number(ds.price) || 0
      }
    })
  },

  // 遮罩层阻止穿透滚动用（catchtouchmove 需要一个空处理函数）
  noop() {},
  onBuyCancel() { this.setData({ buyVisible: false }) },

  async onBuyConfirm() {
    const item = this.data.buying
    this.setData({ buyVisible: false })
    if (!item || !item.code) return
    try {
      await petApi.purchaseItem(this._userId, item.code)
      popup.toast(this, '兑换成功，已收进收藏', { tone: 'success' })
      await this._load()
    } catch (error) {
      popup.toast(this, error.message || '兑换失败', { tone: 'error' })
    }
  },

  onUnload() { if (this._t) clearTimeout(this._t) }
})

const SYMBOL = {
  scarf: '🧣',
  bell: '🔔',
  glasses: '👓',
  leaf: '🍃'
}

function getUserId() {
  try {
    const app = getApp()
    if (app && app.globalData && app.globalData.userId) return app.globalData.userId
  } catch (e) {}
  try {
    const profile = wx.getStorageSync('profile') || {}
    if (profile.id) return profile.id
  } catch (e) {}
  return 1
}


