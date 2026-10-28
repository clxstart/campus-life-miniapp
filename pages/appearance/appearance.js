/**
 * 宠物互动页
 *
 * 形象固定为小橘猫（成套动画贴图），原「捏脸」已不适用，
 * 本页改为动作互动：展示动作 + 摸头反馈。
 *
 * 动作来源 utils/orange-pet-engine.js，按需求保留三个：
 *   idle / happy / sad，各 4 秒
 * （已去掉 sleepy 打瞌睡与 celebrate 跳起来）
 */
const ACTIONS = [
  { key: 'idle',  name: '待机', icon: '◡', tip: '它在发呆，偶尔眨眨眼' },
  { key: 'happy', name: '开心', icon: '◠', tip: '尾巴摇起来了' },
  { key: 'sad',   name: '委屈', icon: '⌒', tip: '它有点小情绪' }
]

Page({
  data: {
    statusBarHeight: 20,
    appearance: {},
    previewMotion: 'idle',
    currentAction: 'idle',
    actions: ACTIONS,
    tip: '它在发呆，偶尔眨眨眼',
    reduceMotion: false
  },

  onLoad() { this._alive = true;
    const metrics = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    let reduceMotion = false
    try {
      const app = getApp()
      reduceMotion = !!(app && app.globalData && app.globalData.reduceMotion)
      if (metrics.reducedMotion != null) reduceMotion = !!metrics.reducedMotion
    } catch (e) {}
    this.setData({
      statusBarHeight: metrics.statusBarHeight || 20,
      reduceMotion
    })
  },

  /** 点宠物：播开心 */
  onPetTap() {
    this.setData({
      previewMotion: 'happy',
      currentAction: 'happy',
      tip: '被你摸到了，很开心'
    })
    this._restore('idle')
  },

  /** 选动作 */
  onAction(e) {
    const key = e.currentTarget.dataset.key
    const item = ACTIONS.find(a => a.key === key)
    if (!item) return
    this.setData({
      previewMotion: key,
      currentAction: key,
      tip: item.tip
    })
    if (key !== 'idle') this._restore('idle')
  },

  /** 动作播完回落到待机 */
  _restore(key) {
    if (this._timer) clearTimeout(this._timer)
    this._timer = setTimeout(() => {
      this.setData({ previewMotion: key, currentAction: key })
    }, 4200)
  },

  onUnload() { this._alive = false;
    if (this._timer) { clearTimeout(this._timer); this._timer = null }
  }
})
