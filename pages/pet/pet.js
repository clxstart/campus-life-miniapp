const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')
const { SKINS, DEFAULT_SKIN, findSkin } = require('./pet-skins.js')

const SKIN_KEY = 'pet_skin_id'

Page({
  data: {
    ...getNavigationMetrics(),
    pet: null,
    points: 0,
    loading: true,
    offline: false,
    animState: 'idle',
    skinPath: findSkin(DEFAULT_SKIN).path,
    currentSkin: DEFAULT_SKIN,
    skins: SKINS,
    skinPickerVisible: false,
    uiPopup: null
  },

  onLoad() {
    this._alive = true
    // 恢复上次选择的形象
    let saved = DEFAULT_SKIN
    try { saved = wx.getStorageSync(SKIN_KEY) || DEFAULT_SKIN } catch (e) {}
    const skin = findSkin(saved)
    this.setData({ currentSkin: skin.id, skinPath: skin.path })
  },

  onShow() {
    this.loadPet()
  },

  onHide() { this.stopHappyTimer() },

  onUnload() {
    this._alive = false
    this.stopHappyTimer()
  },

  onPullDownRefresh() {
    this.loadPet().finally(() => wx.stopPullDownRefresh())
  },

  // ---------- 数据加载 ----------
  async loadPet() {
    const seq = this._seq = (this._seq || 0) + 1
    try {
      const data = await api.getPet(api.CURRENT_USER_ID)
      if (!this._alive || seq !== this._seq) return
      this.setData({
        pet: data,
        points: data.points,
        loading: false,
        offline: !!data.offline,
        animState: data.state
      })
    } catch (error) {
      if (!this._alive || seq !== this._seq) return
      this.setData({ loading: false, offline: !!error.server })
      if (error.server) popup.toast(this, error.message || '加载失败', { tone: 'error' })
    }
  },

  // ---------- 形象切换 ----------
  openSkinPicker() {
    this.setData({ skinPickerVisible: true })
  },

  closeSkinPicker() {
    this.setData({ skinPickerVisible: false })
  },

  noop() {},

  pickSkin(e) {
    const id = e.currentTarget.dataset.id
    const skin = findSkin(id)
    this.setData({ currentSkin: skin.id, skinPath: skin.path, skinPickerVisible: false })
    try { wx.setStorageSync(SKIN_KEY, skin.id) } catch (err) {}
    popup.toast(this, `已换成${skin.name}`, { tone: 'success' })
  },

  // ---------- 动效切换 ----------
  playAnim(state) {
    if (this.data.animState !== state) this.setData({ animState: state })
  },

  stopHappyTimer() {
    if (this._happyTimer) { clearTimeout(this._happyTimer); this._happyTimer = null }
  },

  // ---------- 互动 ----------
  async feed() {
    const pet = this.data.pet
    if (!pet || this._busy) return
    if (!pet.canFeed) { popup.toast(this, '今天喂太多次啦', { tone: 'warn' }); return }
    if (this.data.points < 10) {
      popup.toast(this, '积分不足 10，去发帖赚积分吧', { tone: 'warn' })
      return
    }
    this._busy = true
    try {
      const data = await api.petFeed(api.CURRENT_USER_ID)
      if (!this._alive) return
      this.applyResult(data, '吃饱啦，经验 +15')
    } catch (error) {
      popup.toast(this, error.message || '喂食失败', { tone: 'error' })
    } finally { this._busy = false }
  },

  async touch() {
    const pet = this.data.pet
    if (!pet || this._busy) return
    if (!pet.canTouch) { popup.toast(this, '它有点累了，歇会儿再摸', { tone: 'warn' }); return }
    this._busy = true
    try {
      const data = await api.petTouch(api.CURRENT_USER_ID)
      if (!this._alive) return
      this.applyResult(data, '开心，心情 +10')
    } catch (error) {
      popup.toast(this, error.message || '操作失败', { tone: 'error' })
    } finally { this._busy = false }
  },

  async checkin() {
    const pet = this.data.pet
    if (!pet || this._busy) return
    if (!pet.canCheckin) { popup.toast(this, '今天已经签到过啦', { tone: 'warn' }); return }
    this._busy = true
    try {
      const data = await api.petCheckin(api.CURRENT_USER_ID)
      if (!this._alive) return
      this.applyResult(data, '签到成功，经验 +20 积分 +5')
    } catch (error) {
      popup.toast(this, error.message || '签到失败', { tone: 'error' })
    } finally { this._busy = false }
  },

  applyResult(data, toastText) {
    const leveled = data.level > (this.data.pet ? this.data.pet.level : 0)
    this.setData({ pet: data, points: data.points })
    if (leveled) {
      popup.toast(this, `升到 Lv.${data.level} 了！`, { tone: 'success' })
      this.playAnim('levelup')
      this.stopHappyTimer()
      this._happyTimer = setTimeout(() => {
        if (this._alive && this.data.pet) this.playAnim(this.data.pet.state || 'idle')
      }, 1800)
    } else {
      popup.toast(this, toastText, { tone: 'success' })
      this.playAnim('happy')
      this.stopHappyTimer()
      this._happyTimer = setTimeout(() => {
        if (this._alive && this.data.pet) this.playAnim(this.data.pet.state || 'idle')
      }, 3000)
    }
  },

  // ---------- 弹窗事件（ui-popup 组件要求页面实现，否则 visible 不会复位）----------
  onPopupClose() { popup.hide(this) },
  onPopupCancel() { popup.hide(this) },
  onPopupConfirm() { popup.hide(this) },
  onPopupAction() { popup.hide(this) },
  onPopupInput() {},

  goBack() { wx.navigateBack() }
})
