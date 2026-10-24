/**
 * pet-canvas —— 宠物渲染组件
 *
 * 职责：Canvas 2D 渲染、循环动画、触摸命中。
 * 边界：只负责画；不请求后端，外观数据由父级传入。
 *
 * 关键实现点（对应要求）：
 * - 在**组件内部作用域**查询节点（this.createSelectorQuery()）
 * - 使用 Canvas 2D 接口，不混用旧 CanvasContext
 * - 区分 rpx 布局尺寸 / 显示 px / 画布像素（dpr）
 * - 命中检测把触摸坐标换算到逻辑坐标（在 engine.hitTest 内完成）
 * - 页面隐藏暂停、显示恢复；组件移除时销毁
 */
const { drawPet, hitTest, LOGICAL } = require('../../utils/pet-engine')

Component({
  options: {
    // 允许父页面通过 class 传入外部样式变量
    addGlobalClass: true
  },

  properties: {
    /** 外观部件编号 */
    appearance: { type: Object, value: {} },
    /** 动作：idle / happy / sleep / eat / cheer / walk */
    motion: { type: String, value: 'idle' },
    /** 逻辑尺寸 CSS px（正方形） */
    size: { type: Number, value: 240 },
    /** 减少动画（跟随系统） */
    reduceMotion: { type: Boolean, value: false },
    /** 是否可点击（摸头） */
    interactive: { type: Boolean, value: true }
  },

  data: {},

  lifetimes: {
    attached() {
      this._alive = true
      this._raf = null
      this._canvas = null
      this._ctx = null
      this._boxPx = 0
      this._blinkTimer = null
      this._blinking = false
      this._initCanvas()
    },
    detached() {
      // 组件移除：停止循环、清定时器、释放引用，避免内存泄漏与重复循环
      this._destroy()
    }
  },

  pageLifetimes: {
    show() {
      // 页面重新可见：恢复循环
      if (this._alive && this._ctx && !this._raf) this._startLoop()
    },
    hide() {
      // 页面隐藏：暂停循环，省电
      this._stopLoop()
    }
  },

  methods: {
    /** 组件内部作用域查询 canvas 节点（官方 Canvas 2D 写法） */
    _initCanvas() {
      const query = this.createSelectorQuery()
      query.select('#pet-canvas').fields({ node: true, size: true })
      query.exec((res) => {
        const item = res && res[0]
        if (!item || !item.node) return
        const canvas = item.node
        const ctx = canvas.getContext('2d')

        // dpr 适配：画布像素 = 显示 px * dpr
        const dpr = (wx.getWindowInfo && wx.getWindowInfo().pixelRatio)
          || (wx.getSystemInfoSync && wx.getSystemInfoSync().pixelRatio) || 1
        const boxPx = item.width || this.data.size
        canvas.width = Math.round(boxPx * dpr)
        canvas.height = Math.round(boxPx * dpr)
        // 之后所有绘制按 CSS px 坐标进行
        ctx.scale(dpr, dpr)

        this._canvas = canvas
        this._ctx = ctx
        this._boxPx = boxPx
        this._dpr = dpr

        this._startLoop()
        this._scheduleBlink()
      })
    },

    _startLoop() {
      if (!this._alive || !this._ctx || this._raf) return
      const step = () => {
        if (!this._alive || !this._ctx) return
        this._draw()
        this._raf = this._canvas.requestAnimationFrame(step)
      }
      this._raf = this._canvas.requestAnimationFrame(step)
    },

    _stopLoop() {
      if (this._raf && this._canvas && this._canvas.cancelAnimationFrame) {
        this._canvas.cancelAnimationFrame(this._raf)
      }
      this._raf = null
    },

    _draw() {
      const ctx = this._ctx
      if (!ctx) return
      const box = this._boxPx || this.data.size
      const scale = box / LOGICAL

      ctx.clearRect(0, 0, box, box)
      drawPet(ctx, {
        appearance: this.data.appearance || {},
        motion: this.data.motion,
        t: Date.now(),
        scale,
        blink: this._blinking,
        reduceMotion: this.data.reduceMotion
      })
    },

    /** 眨眼：随机间隔，减少动画时不眨 */
    _scheduleBlink() {
      if (this._blinkTimer) clearTimeout(this._blinkTimer)
      if (this.data.reduceMotion) return
      const delay = 2400 + Math.random() * 3200
      this._blinkTimer = setTimeout(() => {
        if (!this._alive) return
        this._blinking = true
        setTimeout(() => { this._blinking = false }, 130)
        this._scheduleBlink()
      }, delay)
    },

    _destroy() {
      this._alive = false
      this._stopLoop()
      if (this._blinkTimer) { clearTimeout(this._blinkTimer); this._blinkTimer = null }
      this._canvas = null
      this._ctx = null
    },

    /** 触摸 → 命中检测 → 通知父级 */
    onTouchStart(e) {
      if (!this.data.interactive) return
      const touch = (e.touches && e.touches[0]) || (e.changedTouches && e.changedTouches[0])
      if (!touch) return
      const box = this._boxPx || this.data.size
      // touch.x / touch.y 是相对 canvas 的显示像素坐标
      const x = touch.x != null ? touch.x : (touch.clientX - (e.currentTarget.offsetLeft || 0))
      const y = touch.y != null ? touch.y : (touch.clientY - (e.currentTarget.offsetTop || 0))
      if (hitTest(x, y, box)) {
        this.triggerEvent('pettap', { x, y })
      }
    },

    /** 外部可主动重绘（外观切换时） */
    redraw() {
      if (this._ctx) this._draw()
    }
  },

  observers: {
    'appearance.**': function () {
      // 外观变化立即重绘，不必等下一帧
      if (this._ctx) this._draw()
    },
    'reduceMotion': function (v) {
      if (v) {
        this._blinking = false
        if (this._blinkTimer) { clearTimeout(this._blinkTimer); this._blinkTimer = null }
      } else {
        this._scheduleBlink()
      }
    }
  }
})
