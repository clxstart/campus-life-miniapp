/**
 * 领养页
 *
 * 要点：
 * - 候选宠物**可见预览**，不做完全不可知的随机抽取
 * - 选中状态用边框 + 勾 + 文字三重表达
 * - 破壳演出用图片 + CSS 动画（无可用 Lottie 资源，按「不引入空依赖」处理）
 * - 提供跳过入口
 */
const petApi = require('../../utils/pet-api')
const {
  randomAppearance, DEFAULT_APPEARANCE, findAccessory
} = require('../../utils/pet-appearance')

const PERSONAS = [
  { id: 'gentle', name: '温吞' },
  { id: 'lively', name: '活泼' },
  { id: 'shy', name: '内敛' },
  { id: 'clever', name: '机灵' }
]

/** 性格各配一句，让选择有依据 */
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
    hatchTitle: '正在破壳…',
    /** 破壳演出的阶段：cracking → revealed */
    hatchStage: 'cracking',
    hatchImage: '/assets/pets/cat.png',
    hatchFilter: 'none'
  },

  onLoad() {
    const metrics = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    this.setData({ statusBarHeight: metrics.statusBarHeight || 20 })

    let schoolName = '校园'
    try {
      const profile = wx.getStorageSync('profile') || {}
      if (profile.school) schoolName = profile.school
    } catch (e) {}
    this.setData({ schoolName })

    // 三个候选：固定 seed 保证本次进入候选稳定；每个候选是不同物种
    const seedBase = Date.now() % 100000
    const names = ['小云', '小豆', '阿橘']
    const candidates = names.map((name, i) => {
      const ap = randomAppearance(seedBase + i * 7919)
      return { key: 'c' + i, name, appearance: ap, desc: describe(ap) }
    })
    this.setData({ candidates }, () => this._syncCurrent(0))
  },

  onUnload() { this._stopHatch() },

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
    const userId = getAppUserId()
    if (!userId) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    const candidate = this.data.candidates[this.data.selected]
    if (!candidate) return

    this.setData({ submitting: true })
    try {
      await petApi.saveAppearance(userId, candidate.appearance)
      await petApi.rename(userId, candidate.name)
      this.setData({ submitting: false })
      this._playHatch(candidate.appearance)
    } catch (error) {
      this.setData({ submitting: false })
      wx.showToast({ title: (error && error.message) || '领养失败，请重试', icon: 'none' })
    }
  },

  // ===== 破壳演出（图片 + CSS 动画）=====
  _playHatch(appearance) {
    this.setData({
      hatching: true,
      hatchStage: 'cracking',
      hatchTitle: '正在破壳…',
      // 小橘猫为固定形象，用引擎的待机贴图
      hatchImage: '/assets/pet-orange/idle.png',
      hatchFilter: 'none'
    })

    // 蛋壳裂开
    this._hatchTimer = setTimeout(() => {
      this.setData({ hatchStage: 'open', hatchTitle: '蛋壳裂开了！' })
    }, 700)

    // 宠物出现
    this._hatchTimer2 = setTimeout(() => {
      this.setData({ hatchStage: 'revealed', hatchTitle: '你的搭子来啦！' })
    }, 1400)

    // 自动进入捏脸（用户也可点跳过）
    this._hatchTimer3 = setTimeout(() => this._goAppearance(), 2600)
  },

  _stopHatch() {
    if (this._hatchTimer) { clearTimeout(this._hatchTimer); this._hatchTimer = null }
    if (this._hatchTimer2) { clearTimeout(this._hatchTimer2); this._hatchTimer2 = null }
    if (this._hatchTimer3) { clearTimeout(this._hatchTimer3); this._hatchTimer3 = null }
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
  return 1
}

/**
 * 候选描述
 *
 * 小橘猫是固定形象（美术为成套动画贴图），所以三个候选不是「不同物种」，
 * 而是同一只小橘的不同性格预设——描述按性格给出，避免误导成三只不同的猫。
 */
function describe(ap) {
  const acc = findAccessory(ap.accessory)
  const accText = acc.id === 'none' ? '什么都没戴' : `戴着${acc.name}`
  return `小橘猫，${accText}。`
}

