/**
 * pet-float —— 全局悬浮宠物（可拖动）
 *
 * 交互：
 *   - 全屏任意拖动（movable-area 铺满视口，movable-view 自由移动）
 *   - 松手后记住位置，切页面保留（存本地缓存）
 *   - 轻点冒泡，拖动时不触发冒泡（用位移阈值区分）
 *
 * 数据来源：从缓存读宠物外观，避免每个页面都请求后端。
 */
const CACHE_KEY = 'pet_float_profile'
const POS_KEY = 'pet_float_pos'

const LINES = [
  '要不要去食堂看看？',
  '今天也一起加油呀',
  '记得喝水哦',
  '我在这儿等你',
  '有事可以点我聊天'
]

/** 拖动判定阈值（px）：位移超过此值算拖动，不算点击 */
const DRAG_THRESHOLD = 6

Component({
  options: { addGlobalClass: true },

  properties: {
    /** 保留兼容：不再使用固定 bottom，改为可拖动 */
    bottom: { type: Number, value: 200 },
    reduceMotion: { type: Boolean, value: false },
    hasNews: { type: Boolean, value: false }
  },

  data: {
    appearance: {},
    bubbleText: '',
    bubbleLeft: 0,
    bubbleTop: 0,
    // 视口尺寸与可移动范围
    areaHeight: 600,
    x: 0,
    y: 0,
    /** 拖动中：用于暂停宠物动画，减少渲染压力 */
    dragging: false
  },

  lifetimes: {
    attached() {
      this._alive = true
      this._moved = false
      this._readCache()
      this._initPosition()

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
    _readCache() {
      try {
        const cached = wx.getStorageSync(CACHE_KEY)
        if (cached && cached.appearance) {
          this.setData({ appearance: cached.appearance })
        }
      } catch (e) {}
    },

    /** 计算可移动区域，并恢复上次位置 */
    _initPosition() {
      let winW = 375, winH = 667
      try {
        const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
        winW = info.windowWidth || winW
        winH = info.windowHeight || winH
      } catch (e) {}

      // 宠物球实际占位（rpx → px：750rpx = 屏宽）
      const ballPx = Math.round((136 / 750) * winW)
      const maxX = Math.max(0, winW - ballPx)
      const maxY = Math.max(0, winH - ballPx)

      let x = maxX
      let y = Math.max(0, maxY - Math.round((200 / 750) * winW))

      // 恢复上次位置
      try {
        const saved = wx.getStorageSync(POS_KEY)
        if (saved && typeof saved.x === 'number' && typeof saved.y === 'number') {
          x = Math.min(Math.max(0, saved.x), maxX)
          y = Math.min(Math.max(0, saved.y), maxY)
        }
      } catch (e) {}

      this._maxX = maxX
      this._maxY = maxY
      this._ballPx = ballPx
      this.setData({ areaHeight: winH, x, y })
    },

    /**
     * 拖动中
     *
     * 性能要点（这是修复「跳帧」的关键）：
     * movable-view 自己维护位置，拖动过程中**不需要**把 x/y 回写 data。
     * 之前每帧一次 setData({x,y}) 会触发跨线程通信 + 视图层重排版，
     * 是跳帧的主因。现在只在实例变量里记坐标，松手才落盘。
     */
    onDragChange(e) {
      if (!e.detail || e.detail.source !== 'touch') return
      const { x, y } = e.detail

      // 首次移动才进入 dragging 态（暂停 Canvas 动画），此后不再 setData
      if (!this.data.dragging) this.setData({ dragging: true })

      if (!this._moved &&
          (Math.abs(x - this.data.x) > DRAG_THRESHOLD || Math.abs(y - this.data.y) > DRAG_THRESHOLD)) {
        this._moved = true
      }
      // 只记在实例上，不 setData
      this._curX = x
      this._curY = y
    },

    /** 松手：一次性回写坐标 + 恢复动画 + 保存位置 */
    onDragEnd() {
      const finalX = this._curX != null ? this._curX : this.data.x
      const finalY = this._curY != null ? this._curY : this.data.y

      // 只在这里同步一次 data，保证下次进入时位置正确
      this.setData({ x: finalX, y: finalY, dragging: false })

      try {
        wx.setStorageSync(POS_KEY, { x: finalX, y: finalY })
      } catch (e) {}

      // 延迟复位，避免同一次手势里 bindtap 又触发
      setTimeout(() => { this._moved = false }, 60)
    },

    onTap() {
      if (this._moved) return   // 拖动结束误触，忽略
      this._say(LINES[Math.floor(Math.random() * LINES.length)])
    },

    _say(text) {
      if (!this._alive || !text) return
      if (this._hideTimer) { clearTimeout(this._hideTimer); this._hideTimer = null }

      // 气泡放在宠物左上方，跟随当前位置
      const ballPx = this._ballPx || 60
      const left = Math.max(8, this.data.x - 150)
      const top = Math.max(8, this.data.y - 56)

      this.setData({ bubbleText: text, bubbleLeft: left, bubbleTop: top })
      this._hideTimer = setTimeout(() => {
        if (this._alive) this.setData({ bubbleText: '' })
      }, 3200)
    }
  }
})
