/**
 * 领养页
 *
 * 要点：
 * - 候选宠物**可见预览**，不做完全不可知的随机抽取
 * - 选中状态用边框 + 勾 + 文字三重表达
 * - 破壳动画用 Canvas 实现（无可用 Lottie 资源，按「不引入空依赖」处理）
 * - 提供跳过入口
 */
const petApi = require('../../utils/pet-api')
const { randomAppearance, DEFAULT_APPEARANCE } = require('../../utils/pet-appearance')

const PERSONAS = [
  { id: 'gentle', name: '温吞' },
  { id: 'lively', name: '活泼' },
  { id: 'shy', name: '内敛' },
  { id: 'clever', name: '机灵' }
]

/** 三个性格描述各配一句，让选择有依据 */
const PERSONA_HINT = {
  gentle: '它会安静地陪着你',
  lively: '它会第一个冲过来迎接你',
  shy: '熟悉之后才肯靠近你',
  clever: '总能猜到你在想什么'
}

Page({
  data: {
    statusBarHeight: 20,
    schoolName: '校园',
    candidates: [],
    selected: 0,
    current: { name: '', desc: '' },
    currentHint: '点一下看看它们长什么样',
    personas: PERSONAS,
    persona: 'gentle',
    previewAppearance: { ...DEFAULT_APPEARANCE },
    submitting: false,
    hatching: false,
    hatchTitle: '正在破壳…'
  },

  onLoad() {
    const metrics = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    this.setData({ statusBarHeight: metrics.statusBarHeight || 20 })

    // 学校名：从缓存或资料里取，取不到就用通用文案
    let schoolName = '校园'
    try {
      const profile = wx.getStorageSync('profile') || {}
      if (profile.school) schoolName = profile.school
    } catch (e) {}
    this.setData({ schoolName })

    // 生成 3 个候选：用固定 seed，保证本次进入候选稳定
    const seedBase = Date.now() % 100000
    const names = ['小云', '小豆', '阿橘']
    const candidates = names.map((name, i) => {
      const ap = randomAppearance(seedBase + i * 7919)
      return {
        key: 'c' + i,
        name,
        appearance: ap,
        desc: describe(ap)
      }
    })
    this.setData({ candidates }, () => this._syncCurrent(0))
  },

  onUnload() { this._stopHatch() },

  /** 选中候选 */
  onSelect(e) {
    const index = Number(e.currentTarget.dataset.index) || 0
    this.setData({ selected: index }, () => this._syncCurrent(index))
  },

  _syncCurrent(index) {
    const c = this.data.candidates[index]
    if (!c) return
    this.setData({
      current: { name: c.name, desc: c.desc },
      previewAppearance: c.appearance,
      currentHint: PERSONA_HINT[this.data.persona] || '点一下看看它们长什么样'
    })
  },

  onPersona(e) {
    const id = e.currentTarget.dataset.id
    this.setData({ persona: id, currentHint: PERSONA_HINT[id] || '' })
  },

  /** 确认领养 → 写库 → 播放破壳 */
  async onAdopt() {
    if (this.data.submitting) return
    const userId = petApi && getAppUserId()
    if (!userId) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    const candidate = this.data.candidates[this.data.selected]
    if (!candidate) return

    this.setData({ submitting: true })
    try {
      await petApi.saveAppearance(userId, candidate.appearance)
      // 改名
      await petApi.rename(userId, candidate.name)
      this.setData({ submitting: false, hatching: true })
      this._playHatch(candidate.appearance)
    } catch (error) {
      this.setData({ submitting: false })
      wx.showToast({ title: error && error.message ? error.message : '领养失败，请重试', icon: 'none' })
    }
  },

  // ===== 破壳动画（Canvas 2D）=====
  _playHatch(appearance) {
    const query = wx.createSelectorQuery()
    query.select('#hatch-canvas').fields({ node: true, size: true })
    query.exec((res) => {
      const item = res && res[0]
      if (!item || !item.node) return
      const canvas = item.node
      const ctx = canvas.getContext('2d')
      const dpr = (wx.getWindowInfo ? wx.getWindowInfo().pixelRatio : 2) || 2
      const W = item.width || 220
      const H = item.height || 220
      canvas.width = W * dpr
      canvas.height = H * dpr
      ctx.scale(dpr, dpr)

      const { drawPet, LOGICAL } = require('../../utils/pet-engine')
      const start = Date.now()
      const DURATION = 1400
      this._hatchAlive = true

      const frame = () => {
        if (!this._hatchAlive) return
        const t = Math.min(1, (Date.now() - start) / DURATION)
        ctx.clearRect(0, 0, W, H)

        // 蛋壳：上下两半随时间分离
        const gap = t * 46
        ctx.save()
        ctx.translate(W / 2, H / 2 + 10)

        // 蛋壳后片
        ctx.beginPath()
        ctx.ellipse(0, -gap * 0.6, 54, 66, 0, Math.PI, Math.PI * 2)
        ctx.fillStyle = '#F3E7D2'
        ctx.fill()
        ctx.strokeStyle = '#DCC9A6'
        ctx.lineWidth = 2
        ctx.stroke()

        // 蛋壳前片
        ctx.beginPath()
        ctx.ellipse(0, gap, 54, 66, 0, 0, Math.PI)
        ctx.fillStyle = '#EFE0C6'
        ctx.fill()
        ctx.stroke()

        // 蛋壳裂纹
        if (t > 0.25) {
          ctx.beginPath()
          ctx.moveTo(-30, -20 - gap * 0.2)
          ctx.lineTo(-8, -2 - gap * 0.2)
          ctx.lineTo(6, -16 - gap * 0.2)
          ctx.lineTo(28, 4 - gap * 0.2)
          ctx.strokeStyle = '#C9B48C'
          ctx.lineWidth = 2.4
          ctx.stroke()
        }
        ctx.restore()

        // 宠物：随破壳进度淡入放大
        if (t > 0.35) {
          const p = (t - 0.35) / 0.65
          ctx.save()
          ctx.globalAlpha = Math.min(1, p * 1.6)
          const scale = (W / LOGICAL) * (0.72 + 0.28 * p)
          ctx.translate(W / 2 - 100 * scale, H / 2 - 105 * scale)
          drawPet(ctx, {
            appearance,
            motion: 'cheer',
            t: Date.now(),
            scale,
            reduceMotion: false
          })
          ctx.restore()
        }

        if (t < 1) {
          this._hatchRaf = canvas.requestAnimationFrame(frame)
        } else {
          this.setData({ hatchTitle: '你的搭子来啦！' })
          this._hatchTimer = setTimeout(() => this._goAppearance(), 900)
        }
      }

      this._hatchCanvas = canvas
      this._hatchRaf = canvas.requestAnimationFrame(frame)
    })
  },

  _stopHatch() {
    this._hatchAlive = false
    if (this._hatchRaf && this._hatchCanvas && this._hatchCanvas.cancelAnimationFrame) {
      this._hatchCanvas.cancelAnimationFrame(this._hatchRaf)
    }
    this._hatchRaf = null
    if (this._hatchTimer) { clearTimeout(this._hatchTimer); this._hatchTimer = null }
  },

  onSkipHatch() {
    this._stopHatch()
    this._goAppearance()
  },

  onPopupChange(e) {
    if (!e.detail.visible) this._stopHatch()
  },

  _goAppearance() {
    this._stopHatch()
    this.setData({ hatching: false })
    wx.redirectTo({ url: '/pages/appearance/appearance' })
  }
})

function getAppUserId() {
  try {
    const app = getApp()
    if (app && app.globalData && app.globalData.userId) return app.globalData.userId
  } catch (e) {}
  try {
    const profile = wx.getStorageSync('profile') || {}
    if (profile.id) return profile.id
  } catch (e) {}
  // 开发环境兜底：本项目 dev-login 默认用户
  return 1
}

/** 根据外观生成一句描述，让候选项有辨识度 */
function describe(ap) {
  const furName = {
    cream: '奶油白', orange: '橘色', gray: '石墨灰', black: '墨黑', snow: '雪白'
  }[ap.fur] || '奶fufu'
  const traits = {
    round: '圆眼睛总在发光',
    sleepy: '总是一副没睡醒的样子',
    sparkle: '眼睛里有小星星',
    closed: '笑起来眼睛弯成月牙'
  }
  return `${furName}的毛，${traits[ap.eyes] || '看起来很乖巧'}。`
}
