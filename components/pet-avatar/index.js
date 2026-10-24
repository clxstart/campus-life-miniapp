/**
 * pet-avatar —— 静态宠物头像
 *
 * 和 pet-canvas 的区别：只画一帧，不起 requestAnimationFrame。
 * 用于聊天头像、列表缩略图等场景，避免同屏多个动画实例拖慢页面。
 */
const { drawPet, LOGICAL } = require('../../utils/pet-engine')

Component({
  options: { addGlobalClass: true },
  properties: {
    appearance: { type: Object, value: {} },
    size: { type: Number, value: 64 },
    /** 头肩特写：裁掉下半身，只留头部与颈部 */
    crop: { type: Boolean, value: true }
  },

  lifetimes: {
    attached() { this._alive = true; this._paint() },
    detached() { this._alive = false },
    ready() { this._paint() }
  },

  observers: {
    'appearance.**, size, crop': function () { this._paint() }
  },

  methods: {
    _paint() {
      if (!this._alive) return
      const query = this.createSelectorQuery()
      query.select('#pet-avatar-canvas').fields({ node: true, size: true })
      query.exec((res) => {
        const item = res && res[0]
        if (!item || !item.node) return
        const canvas = item.node
        const ctx = canvas.getContext('2d')
        const dpr = (wx.getWindowInfo && wx.getWindowInfo().pixelRatio)
          || (wx.getSystemInfoSync && wx.getSystemInfoSync().pixelRatio) || 1
        const box = item.width || this.data.size
        canvas.width = Math.round(box * dpr)
        canvas.height = Math.round(box * dpr)
        ctx.scale(dpr, dpr)
        ctx.clearRect(0, 0, box, box)

        // 只画一帧：t 固定，动画偏移为 0
        ctx.save()
        if (this.data.crop) {
          // 头肩特写：逻辑坐标里头部在 (100,70)，取上半部分放大
          const zoom = box / 110
          ctx.translate(box / 2 - 100 * zoom, box / 2 - 62 * zoom)
          drawPet(ctx, {
            appearance: this.data.appearance || {},
            motion: 'idle',
            t: 0,
            scale: zoom,
            reduceMotion: true
          })
        } else {
          drawPet(ctx, {
            appearance: this.data.appearance || {},
            motion: 'idle',
            t: 0,
            scale: box / LOGICAL,
            reduceMotion: true
          })
        }
        ctx.restore()
      })
    }
  }
})
