const lottie = require('../../libs/lottie.min.js')
const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')

// 状态 → 动画文件映射
const ANIMS = {
  idle: () => require('./pet-idle.json'),
  happy: () => require('./pet-happy.json'),
  hungry: () => require('./pet-hungry.json'),
  sleep: () => require('./pet-sleep.json'),
  levelup: () => require('./pet-levelup.json')
}

Page({
  data: {
    ...getNavigationMetrics(),
    pet: null,
    points: 0,
    loading: true,
    offline: false,
    uiPopup: null
  },

  onLoad() {
    this._alive = true
    this._currentAnim = null
  },

  onReady() {
    this.initLottie()
  },

  onShow() {
    this.loadPet()
  },

  onHide() {
    if (this.ani) this.ani.pause()
    this.stopHappyTimer()
  },

  onUnload() {
    this._alive = false
    this.stopHappyTimer()
    if (this.ani) {
      try { this.ani.destroy() } catch (e) {}
      this.ani = null
    }
  },

  onPullDownRefresh() {
    this.loadPet().finally(() => wx.stopPullDownRefresh())
  },

  // ---------- Lottie 初始化 ----------
  initLottie() {
    const query = wx.createSelectorQuery().in(this)
    query.select('#pet-canvas')
      .fields({ node: true, size: true })
      .exec((res) => {
        if (!this._alive) return
        if (!res || !res[0] || !res[0].node) {
          console.error('[pet] canvas 节点获取失败')
          return
        }
        const canvas = res[0].node
        const ctx = canvas.getContext('2d')

        // 处理设备像素比，否则高分屏动画模糊
        const dpr = (wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()).pixelRatio || 2
        canvas.width = res[0].width * dpr
        canvas.height = res[0].height * dpr
        ctx.scale(dpr, dpr)

        this._canvas = canvas
        this._ctx = ctx
        lottie.setup(canvas)
        this._lottieReady = true

        // 数据已到就按状态播；数据没到先播 idle
        if (this.data.pet) this.playAnim(this.data.pet.state)
        else this.playAnim('idle')
      })
  },

  // ---------- 动画切换 ----------
  playAnim(state) {
    if (!this._lottieReady) return
    if (this._currentAnim === state && this.ani) return
    const loader = ANIMS[state] || ANIMS.idle
    let data
    try { data = loader() } catch (e) { data = ANIMS.idle() }
    if (this.ani) { try { this.ani.destroy() } catch (e) {} }
    lottie.setup(this._canvas)
    this.ani = lottie.loadAnimation({
      loop: state !== 'levelup',
      autoplay: true,
      animationData: data,
      rendererSettings: { context: this._ctx }
    })
    this._currentAnim = state

    // levelup 只播一次，播完回 idle
    if (state === 'levelup') {
      this.stopHappyTimer()
      this._happyTimer = setTimeout(() => {
        if (this._alive && this.data.pet) this.playAnim(this.data.pet.state || 'idle')
      }, 1800)
    }
  },

  stopHappyTimer() {
    if (this._happyTimer) { clearTimeout(this._happyTimer); this._happyTimer = null }
  },

  // ---------- 数据加载 ----------
  async loadPet() {
    const seq = this._seq = (this._seq || 0) + 1
    try {
      const data = await api.getPet(api.CURRENT_USER_ID)
      if (!this._alive || seq !== this._seq) return
      this.setData({ pet: data, points: data.points, loading: false, offline: !!data.offline })
      this.playAnim(data.state)
    } catch (error) {
      if (!this._alive || seq !== this._seq) return
      this.setData({ loading: false, offline: !!error.server })
      if (error.server) popup.toast(this, error.message || '加载失败', { tone: 'error' })
    }
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

  // 应用互动结果：更新数据 + 播 happy/levelup
  applyResult(data, toastText) {
    const leveled = data.level > (this.data.pet ? this.data.pet.level : 0)
    this.setData({ pet: data, points: data.points })
    if (leveled) {
      popup.toast(this, `升到 Lv.${data.level} 了！`, { tone: 'success' })
      this.playAnim('levelup')
    } else {
      popup.toast(this, toastText, { tone: 'success' })
      this.playAnim('happy')
      this.stopHappyTimer()
      this._happyTimer = setTimeout(() => {
        if (this._alive && this.data.pet) this.playAnim(this.data.pet.state || 'idle')
      }, 3000)
    }
  },

  // ---------- 导航 ----------
  goBack() { wx.navigateBack() }
})
