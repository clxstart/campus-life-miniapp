/**
 * 捏脸页
 *
 * - 选项变化立即更新预览（不点保存也能看到）
 * - 保存写库；重新进入能从数据库恢复
 * - 保存是主要操作，随机/重置为次要
 */
const petApi = require('../../utils/pet-api')
const {
  DEFAULT_APPEARANCE, randomAppearance, normalizeAppearance
} = require('../../utils/pet-appearance')
const Toast = require('tdesign-miniprogram/toast/index')

Page({
  data: {
    statusBarHeight: 20,
    appearance: { ...DEFAULT_APPEARANCE },
    previewMotion: 'idle',
    reduceMotion: false,
    saving: false,
    dirty: false,
    resetVisible: false
  },

  onLoad() {
    const metrics = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    // 尊重系统「减少动态效果」
    let reduceMotion = false
    try {
      const app = getApp()
      reduceMotion = !!(app && app.globalData && app.globalData.reduceMotion)
      if (metrics.reducedMotion != null) reduceMotion = !!metrics.reducedMotion
    } catch (e) {}
    this.setData({ statusBarHeight: metrics.statusBarHeight || 20, reduceMotion })
    this._userId = getUserId()
    this._load()
  },

  onShow() {
    // 从衣柜返回时外观可能已变，重新拉一次
    if (this._loaded) this._load()
  },

  async _load() {
    try {
      const ap = await petApi.getAppearance(this._userId)
      if (ap) {
        this.setData({ appearance: normalizeAppearance(ap), dirty: false })
      }
      this._loaded = true
    } catch (error) {
      // 没领养过或网络失败：用默认外观，页面仍可用
      this.setData({ appearance: { ...DEFAULT_APPEARANCE } })
      this._loaded = true
    }
  },

  /** 选中部件：立即更新预览 */
  onPick(e) {
    const { category, id } = e.detail || {}
    if (!category || !id) return
    this.setData({
      [`appearance.${category}`]: id,
      dirty: true
    })
  },

  onPetTap() {
    this.setData({ previewMotion: 'happy' })
    if (this._happyTimer) clearTimeout(this._happyTimer)
    this._happyTimer = setTimeout(() => this.setData({ previewMotion: 'idle' }), 1200)
  },

  onRandom() {
    const ap = randomAppearance(Date.now())
    this.setData({ appearance: ap, dirty: true })
  },

  onReset() {
    this.setData({ resetVisible: true })
  },

  onResetCancel() {
    this.setData({ resetVisible: false })
  },

  async onResetConfirm() {
    this.setData({ resetVisible: false })
    try {
      const ap = await petApi.resetAppearance(this._userId)
      this.setData({ appearance: normalizeAppearance(ap || DEFAULT_APPEARANCE), dirty: false })
      Toast({ context: this, selector: '#t-toast', message: '已恢复默认外观', theme: 'success' })
    } catch (error) {
      // 网络失败也允许本地重置，至少预览能变
      this.setData({ appearance: { ...DEFAULT_APPEARANCE }, dirty: true })
      Toast({ context: this, selector: '#t-toast', message: error.message || '重置失败', theme: 'fail' })
    }
  },

  async onSave() {
    if (this.data.saving) return
    this.setData({ saving: true })
    try {
      const saved = await petApi.saveAppearance(this._userId, this.data.appearance)
      this.setData({
        appearance: normalizeAppearance(saved || this.data.appearance),
        saving: false,
        dirty: false
      })
      Toast({ context: this, selector: '#t-toast', message: '外观已保存', theme: 'success' })
    } catch (error) {
      this.setData({ saving: false })
      Toast({ context: this, selector: '#t-toast', message: error.message || '保存失败，请重试', theme: 'fail' })
    }
  },

  onUnload() {
    if (this._happyTimer) clearTimeout(this._happyTimer)
  }
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
