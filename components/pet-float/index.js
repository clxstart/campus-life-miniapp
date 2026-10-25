/**
 * pet-float —— 全局悬浮宠物
 *
 * 挂在每个页面右下角，常驻显示，点击冒泡互动（不跳页）。
 *
 * 位置策略：
 *   bottom 默认 200rpx，避开底部导航栏（约 110rpx）与安全区；
 *   页面可通过 bottom 属性调整（例如全屏页面可抬高）。
 *
 * 数据来源：从缓存读宠物名与外观，避免每个页面都请求一次后端。
 *   外观变更（捏脸/换装）后写缓存，各页面把缓存同步过来即可。
 */
const CACHE_KEY = 'pet_float_profile'

const LINES = [
  '要不要去食堂看看？',
  '今天也一起加油呀',
  '记得喝水哦',
  '我在这儿等你',
  '有事可以点我聊天'
]

Component({
  options: { addGlobalClass: true },

  properties: {
    /** 距底部距离（rpx），有底部导航的页面用默认值即可 */
    bottom: { type: Number, value: 200 },
    reduceMotion: { type: Boolean, value: false },
    /** 是否显示红点提醒 */
    hasNews: { type: Boolean, value: false }
  },

  data: {
    appearance: {},
    bubbleText: '',
    _lines: LINES
  },

  lifetimes: {
    attached() {
      this._alive = true
      this._readCache()
      // 首次进入时冒一句
      this._timer = setTimeout(() => {
        if (this._alive) this._say(LINES[Math.floor(Math.random() * LINES.length)])
      }, 1600)
    },
    detached() {
      this._alive = false
      if (this._timer) { clearTimeout(this._timer); this._timer = null }
      if (this._hideTimer) { clearTimeout(this._hideTimer); this._hideTimer = null }
    }
  },

  pageLifetimes: {
    show() { if (this._alive) this._readCache() }
  },

  methods: {
    /** 从缓存读宠物资料 */
    _readCache() {
      try {
        const cached = wx.getStorageSync(CACHE_KEY)
        if (cached && cached.appearance) {
          this.setData({ appearance: cached.appearance })
        }
      } catch (e) {}
    },

    onTap() {
      this._say(LINES[Math.floor(Math.random() * LINES.length)])
    },

    onMove() { /* 预留：拖动时阻止页面滚动穿透 */ },

    _say(text) {
      if (!this._alive || !text) return
      if (this._hideTimer) { clearTimeout(this._hideTimer); this._hideTimer = null }
      this.setData({ bubbleText: text })
      this._hideTimer = setTimeout(() => {
        if (this._alive) this.setData({ bubbleText: '' })
      }, 3200)
    }
  }
})
