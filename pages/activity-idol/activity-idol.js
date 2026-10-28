const { getNavigationMetrics } = require('../../utils/navigation')
const { formatTime } = require('../../utils/format-time')
const popup = require('../../utils/ui-popup-helper')
const api = require('../../utils/api')

const DEFAULT_VOTE_END = '2026-10-31T23:59:59'

function countdownText(voteEndAt) {
  const end = new Date((voteEndAt || DEFAULT_VOTE_END).replace(' ', 'T'))
  const diff = end - new Date()
  if (!(diff > 0)) return ''
  const days = Math.floor(diff / 86400000)
  const hours = Math.floor((diff % 86400000) / 3600000)
  return days > 0 ? `距结束 ${days} 天 ${hours} 小时` : `距结束 ${hours} 小时`
}

Page({
  data: {
    ...getNavigationMetrics(),
    gender: 2,
    contestants: [],
    stats: { contestants: 0, votes: 0 },
    countdown: '',
    loading: true,
    uiPopup: null
  },

  onLoad() { this._alive = true;
    this.loadActivity()
    this.loadContestants()
    this._timer = setInterval(() => {
      this.setData({ countdown: countdownText(this.data.voteEndAt) })
    }, 60000)
  },

  onUnload() { this._alive = false;
    if (this._timer) clearInterval(this._timer)
  },

  onShareAppMessage() {
    return {
      title: '校草校花评选进行中，快来为 TA 打 call！',
      path: '/pages/activity-idol/activity-idol'
    }
  },

  async loadActivity() {
    const activity = await api.getIdolActivity()
    const voteEndAt = (activity && activity.voteEndAt) || DEFAULT_VOTE_END
    this.setData({
      voteEndAt,
      countdown: countdownText(voteEndAt),
      stats: {
        contestants: (activity && activity.contestants) || 0,
        votes: (activity && activity.votes) || 0
      }
    })
  },

  async loadContestants() {
    this.setData({ loading: true })
    const list = await api.getIdolContestants(this.data.gender)
    const contestants = (list || []).map((item, index) => ({
      ...item,
      rank: index + 1,
      voteCount: item.voteCount || 0,
      voted: !!item.voted
    }))
    this.setData({ contestants, loading: false })
  },

  switchGender(event) {
    const gender = Number(event.currentTarget.dataset.gender)
    if (gender === this.data.gender) return
    this.setData({ gender })
    this.loadContestants()
  },

  async onVote(event) {
    const index = Number(event.currentTarget.dataset.index)
    const target = this.data.contestants[index]
    if (!target) return
    if (target.voted) {
      popup.toast(this, '已经给 TA 投过票啦', { tone: 'warn' })
      return
    }
    // 先本地生效再上报，失败时回滚并提示
    this.setData({
      [`contestants[${index}].voted`]: true,
      [`contestants[${index}].voteCount`]: target.voteCount + 1,
      [`stats.votes`]: this.data.stats.votes + 1
    })
    try {
      await api.voteIdol(target.id)
      popup.toast(this, `投票成功，感谢支持 ${target.name}`, { tone: 'success' })
    } catch (error) {
      this.setData({
        [`contestants[${index}].voted`]: false,
        [`contestants[${index}].voteCount`]: target.voteCount,
        [`stats.votes`]: this.data.stats.votes - 1
      })
      popup.toast(this, error.message || '投票失败，稍后再试', { tone: 'error' })
    }
  },

  goJoin() {
    wx.navigateTo({ url: `/pages/idol-join/idol-join?gender=${this.data.gender}` })
  },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) { wx.navigateBack() } else { wx.reLaunch({ url: '/pages/discover/discover' }) }
  }
})
