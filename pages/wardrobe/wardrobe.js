/**
 * 衣柜页
 *
 * - 三态明确：未解锁（可兑换）/ 已拥有 / 已装备
 * - 兑换前弹确认，显示积分与消耗
 * - 装备后预览立即变化，并写库同步到首页
 */
const petApi = require('../../utils/pet-api')
const { normalizeAppearance, DEFAULT_APPEARANCE } = require('../../utils/pet-appearance')
const Toast = require('tdesign-miniprogram/toast/index')

Page({
  data: {
    statusBarHeight: 20,
    appearance: { ...DEFAULT_APPEARANCE },
    balance: 0,
    tab: 'accessory',
    categories: [],
    list: [],
    previewMotion: 'idle',
    buyVisible: false,
    buying: {}
  },

  onLoad() {
    const metrics = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    this.setData({ statusBarHeight: metrics.statusBarHeight || 20 })
    this._userId = getUserId()
    this._all = []
  },

  onShow() { this._load() },

  async _load() {
    try {
      const [ap, wd] = await Promise.all([
        petApi.getAppearance(this._userId),
        petApi.getWardrobe(this._userId)
      ])
      if (ap) this.setData({ appearance: normalizeAppearance(ap) })
      const list = (wd && wd.list) || []
      this._all = list
      this.setData({ balance: (wd && wd.balance) || 0 })
      this._buildCategories()
    } catch (error) {
      Toast({ context: this, selector: '#t-toast', message: error.message || '加载失败', theme: 'fail' })
      this.setData({ list: [], categories: [{ key: 'accessory', name: '配饰', count: 0 }] })
    }
  },

  /** 按类别分组：第一版只有配饰一类 */
  _buildCategories() {
    const groups = {}
    for (const item of this._all) {
      const cat = item.category || 'accessory'
      groups[cat] = groups[cat] || []
      groups[cat].push(item)
    }
    const categories = Object.keys(groups).map(key => ({
      key,
      name: key === 'accessory' ? '配饰' : key,
      count: groups[key].length
    }))
    if (categories.length === 0) categories.push({ key: 'accessory', name: '配饰', count: 0 })
    const tab = categories.some(c => c.key === this.data.tab) ? this.data.tab : categories[0].key
    this.setData({ categories, tab })
    this._applyTab(tab)
  },

  _applyTab(key) {
    this.setData({ list: this._all.filter(i => (i.category || 'accessory') === key) })
  },

  onTab(e) {
    const key = e.currentTarget.dataset.key
    this.setData({ tab: key })
    this._applyTab(key)
  },

  onPetTap() {
    this.setData({ previewMotion: 'happy' })
    if (this._t) clearTimeout(this._t)
    this._t = setTimeout(() => this.setData({ previewMotion: 'idle' }), 1200)
  },

  // ===== 兑换 =====
  onBuy(e) {
    const item = e.detail && e.detail.item
    if (!item) return
    this.setData({ buyVisible: true, buying: item })
  },

  onBuyCancel() { this.setData({ buyVisible: false }) },

  async onBuyConfirm() {
    const item = this.data.buying
    this.setData({ buyVisible: false })
    if (!item || !item.code) return
    try {
      await petApi.purchaseItem(this._userId, item.code)
      Toast({ context: this, selector: '#t-toast', message: '兑换成功', theme: 'success' })
      await this._load()
    } catch (error) {
      Toast({ context: this, selector: '#t-toast', message: error.message || '兑换失败', theme: 'fail' })
    }
  },

  // ===== 装备 / 卸下 =====
  async onEquip(e) {
    const item = e.detail && e.detail.item
    if (!item || !item.code) return
    try {
      const res = await petApi.equipItem(this._userId, item.code)
      if (res && res.appearance) {
        this.setData({ appearance: normalizeAppearance(res.appearance) })
      }
      Toast({ context: this, selector: '#t-toast', message: '已装备', theme: 'success' })
      await this._load()
    } catch (error) {
      Toast({ context: this, selector: '#t-toast', message: error.message || '装备失败', theme: 'fail' })
    }
  },

  async onUnequip() {
    try {
      const res = await petApi.unequipItem(this._userId)
      if (res && res.appearance) {
        this.setData({ appearance: normalizeAppearance(res.appearance) })
      }
      Toast({ context: this, selector: '#t-toast', message: '已卸下', theme: 'success' })
      await this._load()
    } catch (error) {
      Toast({ context: this, selector: '#t-toast', message: error.message || '操作失败', theme: 'fail' })
    }
  },

  onUnload() { if (this._t) clearTimeout(this._t) }
})

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
