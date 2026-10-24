/**
 * 派遣页
 *
 * 进度以服务端为准：
 * - 每次进入/列表到期都重新拉服务端状态
 * - 客户端倒计时只负责显示，不能凭本地时间发奖
 * - 关闭小程序不丢进度（进度存在 pet_dispatch 表）
 */
const petApi = require('../../utils/pet-api')
const { normalizeAppearance, DEFAULT_APPEARANCE } = require('../../utils/pet-appearance')
const Toast = require('tdesign-miniprogram/toast/index')

const STATUS_TEXT = {
  running: '进行中',
  claimable: '可领取',
  claimed: '已完成'
}

Page({
  data: {
    statusBarHeight: 20,
    appearance: { ...DEFAULT_APPEARANCE },
    petMotion: 'idle',
    taskList: [],
    current: null,
    statusText: '',
    remainText: '',
    claimedCount: 0
  },

  onLoad() {
    const metrics = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    this.setData({ statusBarHeight: metrics.statusBarHeight || 20 })
    this._userId = getUserId()
  },

  onShow() {
    this._load()
    this._startTick()
  },

  onHide() { this._stopTick() },
  onUnload() { this._stopTick() },

  async _load() {
    try {
      const [ap, st] = await Promise.all([
        petApi.getAppearance(this._userId),
        petApi.getDispatch(this._userId)
      ])
      if (ap) this.setData({ appearance: normalizeAppearance(ap) })

      const current = st && st.current ? st.current : null
      const tasks = ((st && st.taskList) || []).map(t => {
        // 每个任务展示自己的状态：当前派遣对应的任务显示真实状态，其余可派遣
        const isCurrent = current && current.taskCode === t.code
        return {
          ...t,
          _status: isCurrent ? current.status : 'idle',
          _remain: isCurrent ? (current.remainSeconds || 0) : 0
        }
      })

      this.setData({
        taskList: tasks,
        current,
        statusText: current ? (STATUS_TEXT[current.status] || '') : '',
        remainText: current ? formatRemain(current.remainSeconds) : '',
        claimedCount: (st && st.claimedCount) || 0,
        petMotion: current && current.status === 'running' ? 'walk'
          : (current && current.status === 'claimable' ? 'happy' : 'idle')
      })
    } catch (error) {
      Toast({ context: this, selector: '#t-toast', message: error.message || '加载失败', theme: 'fail' })
    }
  },

  /** 本地每秒刷新剩余时间（仅显示） */
  _startTick() {
    this._stopTick()
    this._tick = setInterval(() => {
      const cur = this.data.current
      if (!cur || cur.status !== 'running') return
      const next = Math.max(0, (cur.remainSeconds || 0) - 1)
      this.setData({
        'current.remainSeconds': next,
        remainText: formatRemain(next)
      })
      if (next <= 0) {
        // 到期：向服务端确认，而不是本地发奖
        this._stopTick()
        this._load()
      }
    }, 1000)
  },

  _stopTick() {
    if (this._tick) { clearInterval(this._tick); this._tick = null }
  },

  async onStart(e) {
    const task = e.detail && e.detail.task
    if (!task || !task.code) return
    try {
      await petApi.startDispatch(this._userId, task.code)
      Toast({ context: this, selector: '#t-toast', message: '小云出发啦', theme: 'success' })
      await this._load()
      this._startTick()
    } catch (error) {
      Toast({ context: this, selector: '#t-toast', message: error.message || '派遣失败', theme: 'fail' })
    }
  },

  async onClaim() {
    const cur = this.data.current
    if (!cur || !cur.id) return
    try {
      const res = await petApi.claimDispatch(this._userId, cur.id)
      const reward = res && res.reward != null ? res.reward : 0
      Toast({ context: this, selector: '#t-toast', message: `领取到 ${reward} 积分`, theme: 'success' })
      await this._load()
    } catch (error) {
      Toast({ context: this, selector: '#t-toast', message: error.message || '领取失败', theme: 'fail' })
      // 失败也刷新一次，保证状态与服务端一致
      this._load()
    }
  },

  /** 任务卡倒计时归零 → 问服务端 */
  onDue() { this._load() }
})

function formatRemain(sec) {
  const s = Math.max(0, Number(sec) || 0)
  if (s <= 0) return '即将返回'
  const m = Math.floor(s / 60)
  const r = s % 60
  return m > 0 ? `${m} 分 ${r} 秒` : `${r} 秒`
}

function getUserId() {
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
