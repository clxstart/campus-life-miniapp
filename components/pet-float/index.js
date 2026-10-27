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

/**
 * 默认距底偏移（rpx）
 *
 * 故意用统一常量、不读各页面传入的 bottom：
 * 各页面传的值不一样（tabBar 页 260、普通页 200、聊天页 220），
 * 切页面时宠物会上下跳 30rpx。既然诉求是「位置固定不变」，就统一成一个值。
 *
 * 取 200rpx 的依据：底部导航是 100rpx + 安全区(≈68rpx)=168rpx，
 * 聊天输入栏同量级，200rpx 能全部让开并留一点间距。
 */
const DEFAULT_BOTTOM_RPX = 200

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
      // 只定尺寸，不落坐标：此刻节点还没布局完，坐标会被夹取
      this._measure()

      // 等一帧让 movable-area 拿到真实高度后再落坐标。
      // 不用 ready 生命周期：它在部分基础库/开发者工具下会和 attached
      // 的执行顺序不稳定，导致组件 attached 抛错。nextTick + 50ms 兜底更稳。
      wx.nextTick(() => {
        if (!this._alive) return
        this._applyPosition()
        this._posTimer = setTimeout(() => {
          if (this._alive) this._applyPosition()
        }, 50)
      })

      this._timer = setTimeout(() => {
        if (this._alive) this._say(LINES[Math.floor(Math.random() * LINES.length)])
      }, 1600)
    },
    detached() {
      this._alive = false
      if (this._timer) { clearTimeout(this._timer); this._timer = null }
      if (this._hideTimer) { clearTimeout(this._hideTimer); this._hideTimer = null }
      if (this._posTimer) { clearTimeout(this._posTimer); this._posTimer = null }
    }
  },

  pageLifetimes: {
    show() {
      if (!this._alive) return
      this._readCache()
      // 拖动中不打断手势
      if (this.data.dragging) return
      // 回到栈里已有的页面时组件不会重建，这里按存储校准一次位置，
      // 保证在别的页面拖过之后切回来位置一致（值相同则内部直接 return）
      this._applyPosition()
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
    /** 读取一份目标位置：默认右下角，或本地存过的「距右 / 距底」偏移 */
    _targetOffset() {
      const winW = this._winW || 375
      // 默认位置：贴右下角，距底统一用 DEFAULT_BOTTOM_RPX。
      // 不读 properties.bottom —— 各页面传值不同，会导致切页面时宠物上下跳。
      let right = 8
      let bottom = Math.round((DEFAULT_BOTTOM_RPX / 750) * winW)

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
      right = Math.min(right, this._maxX || 0)
      bottom = Math.min(bottom, this._maxY || 0)
      return { right, bottom }
    },

    /**
     * 第一阶段：只算尺寸，让 movable-area 先长到正确高度
     *
     * 必须在 attached 里做。movable-area 的高度由 areaHeight 决定，
     * 如果等到设置坐标时高度还是默认 600，movable-view 会把 y 夹到顶部，
     * 表现出来就是「宠物跳到右上角」。
     */
    _measure() {
      let winW = 375, winH = 667
      try {
        const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
        winW = info.windowWidth || winW
        winH = info.windowHeight || winH
      } catch (e) {}

      const ballPx = Math.round((136 / 750) * winW)
      this._winW = winW
      this._winH = winH
      this._ballPx = ballPx
      this._maxX = Math.max(0, winW - ballPx)
      this._maxY = Math.max(0, winH - ballPx)

      // 先把区域高度定下来，坐标留到 ready 再设置
      this.setData({ areaHeight: winH })
    },

    /**
     * 第二阶段：在布局完成之后落坐标
     *
     * 在 ready（或页面 show）里调用。此时 movable-area 已是 winH 高，
     * 目标 y 不会被夹取，宠物直接出现在右下角而不是先跑到顶部。
     */
    _applyPosition() {
      if (this._maxX == null || this._maxY == null) return
      const { right, bottom } = this._targetOffset()
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
