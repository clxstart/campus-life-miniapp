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
    /** 拖动中：用于暂停宠物动画、隐藏阴影 */
    dragging: false,
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
    show() {
      if (!this._alive) return
      this._readCache()
      // 回到栈里已有的页面时组件不会重建，这里重新按存储校准一次位置，
      // 保证在别的页面拖动过之后，切回来位置也是同一个
      this._syncSavedPosition()
    }
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

    /**
     * 计算可移动区域，并恢复上次位置
     *
     * 位置按「距右边 / 距底边」的偏移量存储，而不是绝对坐标。
     * 原因：不同页面 windowHeight 不一样（有 tabBar 的页面少约 100rpx），
     * 存绝对 y 时会被 maxY 压缩、压缩后的值又被存回去，
     * 结果每切换一次页面猫就往上挪一点。
     * 存右下角偏移后，各页面视觉位置完全一致。
     */
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

      // 默认位置：贴右下角。
      // 底部偏移用页面传入的 bottom 属性（rpx）换算成 px —— 各页面已经按
      // 自己的底部导航 / 输入框高度调好了这个值（tabBar 页 260、普通页 200），
      // 直接复用就不会压住底部导航。
      const defaultBottomPx = Math.round(((this.properties.bottom || 200) / 750) * winW)
      let right = 8
      let bottom = defaultBottomPx

      // 恢复上次位置
      // 只认 {right,bottom} 格式。旧版存的 {x,y} 是绝对坐标，
      // 在不同 windowHeight 的页面之间会漂移（每切一次页面就往上挪），
      // 所以直接忽略，让它回到干净的默认右下角。
      try {
        const saved = wx.getStorageSync(POS_KEY)
        if (saved && typeof saved.right === 'number' && typeof saved.bottom === 'number') {
          right = Math.max(0, saved.right)
          bottom = Math.max(0, saved.bottom)
        }
      } catch (e) {}

      // 偏移量不能超过可移动范围，否则球会跑出屏幕
      right = Math.min(right, maxX)
      bottom = Math.min(bottom, maxY)

      const x = maxX - right
      const y = maxY - bottom

      this._maxX = maxX
      this._maxY = maxY
      this._ballPx = ballPx
      this.setData({ areaHeight: winH, x, y })
    },

    /**
     * 按本地存储重新校准位置（页面 show 时调用）
     *
     * 场景：在 A 页拖过猫，切到 B 页，再返回 A 页（A 没被销毁）——
     * A 页的组件实例还停留在旧位置，这里重新读存储把它拉到同一处。
     */
    _syncSavedPosition() {
      if (this._maxX == null || this._maxY == null) return
      let saved = null
      try { saved = wx.getStorageSync(POS_KEY) } catch (e) { return }
      if (!saved) return
      // 只认新格式，旧 {x,y} 会被忽略，回到默认右下角
      if (typeof saved.right !== 'number' || typeof saved.bottom !== 'number') return

      const right = Math.min(Math.max(0, saved.right), this._maxX)
      const bottom = Math.min(Math.max(0, saved.bottom), this._maxY)

      const x = this._maxX - right
      const y = this._maxY - bottom
      if (x === this.data.x && y === this.data.y) return
      this.setData({ x, y })
    },


    /**
     * 拖动中
     *
     * 性能要点（修复「跳帧」的关键）：
     * movable-view 自己维护位置，拖动过程中**不需要**把 x/y 回写 data。
     * 每帧一次 setData 会触发跨线程通信 + 视图层重排版。
     *
     * 阴影处理：拖动一开始就隐藏阴影。
     * 阴影若跟着猫走，会像拖着一坨灰；留原地又会闪。
     * 干脆拖动期间不显示，松手后再在新位置出现。
     */
    onDragChange(e) {
      if (!e.detail || e.detail.source !== 'touch') return
      const { x, y } = e.detail

      // 首次移动：进入 dragging 态（暂停动画 + 隐藏阴影），此后不再 setData
      if (!this.data.dragging) {
        this.setData({ dragging: true })
      }

      if (!this._moved &&
          (Math.abs(x - this.data.x) > DRAG_THRESHOLD || Math.abs(y - this.data.y) > DRAG_THRESHOLD)) {
        this._moved = true
      }
      // 只记在实例上，不 setData
      this._curX = x
      this._curY = y
    },

    /** 松手：回写坐标 + 恢复动画 + 在新位置显示阴影 + 落盘 */
    onDragEnd() {
      const finalX = this._curX != null ? this._curX : this.data.x
      const finalY = this._curY != null ? this._curY : this.data.y

      // 只在这里同步一次 data
      this.setData({ x: finalX, y: finalY, dragging: false })

      try {
        // 存「距右边 / 距底边」的偏移，切页面时视觉位置不变
        const maxX = this._maxX || 0
        const maxY = this._maxY || 0
        wx.setStorageSync(POS_KEY, {
          right: Math.max(0, maxX - finalX),
          bottom: Math.max(0, maxY - finalY)
        })
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
