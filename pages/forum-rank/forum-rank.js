/**
 * 论坛日榜 / 周榜（V3.1 新页）
 *
 * 算分口径（发帖 3 / 评论 2 / 被赞 1）与结算都在后端，前端只负责展示快照。
 * 榜单是「已结算快照」，所以页面不会随点赞实时跳动；
 * 当天还没结算时展示最近一期，并在顶部说清楚。
 */
const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')

// periodKey：日榜 2026-11-08 → 11-08；周榜 2026-W45 → 第 45 周
function periodText(type, periodKey) {
  const key = String(periodKey || '')
  if (!key) return ''
  if (type === 'weekly') {
    const match = key.match(/W(\d+)/i)
    return match ? `第 ${Number(match[1])} 周` : key
  }
  const parts = key.split('-')
  return parts.length >= 3 ? `${parts[1]}-${parts[2]}` : key
}

function rewardText(row, settled) {
  const awarded = Number(row.awarded) || 0
  if (awarded > 0) return `+${awarded} 积分`
  return settled ? '已发放' : '待结算'
}

Page({
  data: {
    ...getNavigationMetrics(),
    types: [{ key: 'daily', label: '日榜' }, { key: 'weekly', label: '周榜' }],
    type: 'daily',
    periodText: '',
    settled: false,
    settledText: '',
    loading: true,
    error: '',
    offline: false,
    podium: [],
    rest: [],
    me: null,
    meText: '',
    uiPopup: null
  },

  onLoad(options) {
    this._alive = true
    const type = options && options.type === 'weekly' ? 'weekly' : 'daily'
    this.setData({ type })
    this.loadRank()
  },

  onUnload() { this._alive = false },

  onPullDownRefresh() { this.loadRank().finally(() => wx.stopPullDownRefresh()) },

  async loadRank() {
    if (!this._alive) return
    this.setData({ loading: !this.data.podium.length, error: '' })
    try {
      const result = await api.getForumRank(this.data.type)
      if (!this._alive) return
      const rows = ((result && result.rows) || []).map(row => ({
        ...row,
        rewardText: rewardText(row, !!result.settled),
        initial: (row.nickname || '同').slice(0, 1)
      }))
      // 前三名按 2-1-3 视觉排序：冠军放中间且最高
      const podium = [rows[1], rows[0], rows[2]].filter(Boolean).map(row => ({
        ...row,
        placeClass: row.rankNo === 1 ? 'place-1' : row.rankNo === 2 ? 'place-2' : 'place-3'
      }))
      const me = (result && result.me) || null
      this.setData({
        podium,
        rest: rows.slice(3),
        me,
        meText: me && me.rankNo
          ? `我的排名：第 ${me.rankNo} 名 · 得分 ${Number(me.score) || 0}`
          : '你还没上榜，发个帖子试试',
        periodText: periodText(this.data.type, result && result.periodKey),
        settled: !!(result && result.settled),
        settledText: result && result.settled
          ? '本榜已于 00:10 结算'
          : '今日尚未结算，展示昨日榜',
        offline: !!(result && result.offline),
        loading: false
      })
    } catch (error) {
      if (!this._alive) return
      this.setData({ loading: false, error: (error && error.message) || '榜单没加载出来' })
    }
  },

  switchType(event) {
    const type = event.currentTarget.dataset.type
    if (!type || type === this.data.type) return
    // 切榜清空列表再拉，避免日榜数据短暂出现在周榜下面
    this.setData({ type, podium: [], rest: [], me: null })
    this.loadRank()
  },

  openUser(event) {
    const userId = event.currentTarget.dataset.userId
    if (!userId) return
    wx.navigateTo({ url: `/pages/user-home/user-home?id=${userId}` })
  },

  goPublish() { wx.navigateTo({ url: '/pages/publish/publish' }) },

  retry() { this.loadRank() },

  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})