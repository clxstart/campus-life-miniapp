const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')
const { SKINS, DEFAULT_SKIN, findSkin, pathForState } = require('./pet-skins.js')

const SKIN_KEY = 'pet_skin_id'

// 点击触发的 4 个表情动效（第 1 只 idle 是常驻，不在此列）
const EFFECTS = [
  { key: 'happy',  cls: 'fx-pop',   dur: 1300 },
  { key: 'hungry', cls: 'fx-tremble', dur: 1200 },
  { key: 'sleep',  cls: 'fx-doze',  dur: 1600 },
  { key: 'levelup',cls: 'fx-burst', dur: 1500 }
]

Page({
  data: {
    ...getNavigationMetrics(),
    pet: null,
    points: 0,
    loading: true,
    offline: false,
    animState: 'idle',
    basePath: '',
    effectPath: '',
    effectClass: '',
    blink: false,
    skinPath: findSkin(DEFAULT_SKIN).path,
    currentSkin: DEFAULT_SKIN,
    skins: SKINS,
    skinPickerVisible: false,
    uiPopup: null
  },

  onLoad() {
    this._alive = true
    let saved = DEFAULT_SKIN
    try { saved = wx.getStorageSync(SKIN_KEY) || DEFAULT_SKIN } catch (e) {}
    this.applySkin(findSkin(saved))
  },

  onShow() {
    this.loadPet()
    this.startBlinkLoop()
  },

  onHide() {
    this.stopHappyTimer()
    this.stopBlinkLoop()
  },

  onUnload() {
    this._alive = false
    this.stopHappyTimer()
    this.stopBlinkLoop()
  },

  onPullDownRefresh() {
    this.loadPet().finally(() => wx.stopPullDownRefresh())
  },

  // ---------- 形象 ----------
  applySkin(skin) {
    const base = pathForState(skin, 'idle')
    this.setData({
      currentSkin: skin.id,
      basePath: base,
      skinPath: base
    })
  },

  // ---------- 常驻眨眼（模拟眼睛动）----------
  startBlinkLoop() {
    this.stopBlinkLoop()
    const loop = () => {
      if (!this._alive) return
      // 2.5~5.5 秒随机眨眼
      this._blinkTimer = setTimeout(() => {
        if (!this._alive) return
        this.setData({ blink: true })
        setTimeout(() => {
          if (this._alive) this.setData({ blink: false })
        }, 160)
        loop()
      }, 2500 + Math.random() * 3000)
    }
    loop()
  },

  stopBlinkLoop() {
    if (this._blinkTimer) { clearTimeout(this._blinkTimer); this._blinkTimer = null }
    if (this._blinkOffTimer) { clearTimeout(this._blinkOffTimer); this._blinkOffTimer = null }
  },

  // ---------- 点击触发表情动效 ----------
  tapPet() {
    if (this._fxPlaying) return
    const skin = findSkin(this.data.currentSkin)
    if (!skin.states) {
      // 没有多状态图的形象，只做一次轻弹
      this.setData({ effectClass: 'fx-pop' })
      this._fxPlaying = true
      this._fxTimer = setTimeout(() => {
        this._fxPlaying = false
        if (this._alive) this.setData({ effectClass: '' })
      }, 900)
      return
    }

    const pick = EFFECTS[Math.floor(Math.random() * EFFECTS.length)]
    const path = skin.states[pick.key]
    if (!path) return

    this._fxPlaying = true
    this.setData({ effectPath: path, effectClass: pick.cls })
    if (this._fxTimer) clearTimeout(this._fxTimer)
    this._fxTimer = setTimeout(() => {
      this._fxPlaying = false
      if (this._alive) this.setData({ effectPath: '', effectClass: '' })
    }, pick.dur)
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
  openSkinPicker() { this.setData({ skinPickerVisible: true }) },
  closeSkinPicker() { this.setData({ skinPickerVisible: false }) },
  noop() {},

  pickSkin(e) {
    const id = e.currentTarget.dataset.id
    const skin = findSkin(id)
    this.applySkin(skin)
    this.setData({ skinPickerVisible: false })
    try { wx.setStorageSync(SKIN_KEY, skin.id) } catch (err) {}
    popup.toast(this, `已换成${skin.name}`, { tone: 'success' })
  },

  // ---------- 互动 ----------
  stopHappyTimer() {
    if (this._happyTimer) { clearTimeout(this._happyTimer); this._happyTimer = null }
  },

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
      this.playEffect('levelup', 'fx-burst', 1500)
    } else {
      popup.toast(this, toastText, { tone: 'success' })
      this.playEffect('happy', 'fx-pop', 1300)
    }
  },

  // 播一次指定表情动效
  playEffect(key, cls, dur) {
    const skin = findSkin(this.data.currentSkin)
    if (!skin.states || !skin.states[key]) return
    this.setData({ effectPath: skin.states[key], effectClass: cls })
    if (this._fxTimer) clearTimeout(this._fxTimer)
    this._fxTimer = setTimeout(() => {
      if (this._alive) this.setData({ effectPath: '', effectClass: '' })
    }, dur)
  },

  // ---------- 弹窗事件 ----------
  onPopupClose() { popup.hide(this) },
  onPopupCancel() { popup.hide(this) },
  onPopupConfirm() { popup.hide(this) },
  onPopupAction() { popup.hide(this) },
  onPopupInput() {},

  goBack() { wx.navigateBack() }
})
