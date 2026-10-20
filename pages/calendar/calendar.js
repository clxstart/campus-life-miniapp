const { getNavigationMetrics } = require('../../utils/navigation')
const { parseDate, getTermWeek, daysUntil } = require('../../utils/term')
const api = require('../../utils/api')

function shortDate(date) {
  return `${date.getMonth() + 1}.${date.getDate()}`
}

Page({
  data: {
    ...getNavigationMetrics(),
    termLabel: '',
    loading: true, error: '', offline: false,
    termStartLabel: '',
    termEndLabel: '',
    weekNo: 1,
    headline: '',
    progress: 0,
    events: []
  },

  onLoad() { this._alive = true; this.loadCalendar() },
  onUnload() { this._alive = false },
  onPullDownRefresh() { this.loadCalendar().finally(() => wx.stopPullDownRefresh()) },
  async loadCalendar() {
    this.setData({ loading: true, error: '' })
    try {
      const result = await api.getCalendar()
      if (!this._alive) return
      const { termInfo } = result
      const today = new Date()
      today.setHours(0, 0, 0, 0)
      const termStart = parseDate(termInfo.termStart)
      const termEnd = parseDate(termInfo.termEnd)
      const weekNo = getTermWeek(today, termInfo.termStart)

      // 事件状态：按当天是否落在起止区间内判断
      const events = result.events.map((event) => {
        const start = parseDate(event.start)
        const end = parseDate(event.end || event.start)
        const status = today > end ? 'past' : today >= start ? 'ongoing' : 'upcoming'
        return {
          ...event,
          dateLabel: event.start === event.end ? shortDate(start) : `${shortDate(start)} - ${shortDate(end)}`,
          status,
          statusLabel: status === 'past' ? '已结束' : status === 'ongoing' ? '进行中' : '未开始'
        }
      })

      const totalDays = Math.max(1, (termEnd - termStart) / 86400000)
      const passed = Math.min(totalDays, Math.max(0, (today - termStart) / 86400000))
      const daysToMidterm = daysUntil(termInfo.midtermStart, today)
      const daysToFinal = daysUntil(termInfo.finalStart, today)
      const headline = today > termEnd
        ? '本学期已结束，好好享受假期'
        : daysToMidterm > 0
          ? `距期中考试还有 ${daysToMidterm} 天`
          : daysToFinal > 0 ? `距期末考试还有 ${daysToFinal} 天` : '考试期间，具体安排以教务通知为准'

      this.setData({
        termLabel: termInfo.termLabel, loading: false, offline: !!result.offline,
        termStartLabel: `${termStart.getMonth() + 1}月${termStart.getDate()}日`,
        termEndLabel: `${termEnd.getMonth() + 1}月${termEnd.getDate()}日`,
        weekNo,
        headline,
        progress: Math.round((passed / totalDays) * 100),
        events
      })
    } catch (error) { if (this._alive) this.setData({ loading: false, error: error.message || '校历加载失败' }) }
  },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) { wx.navigateBack() } else { wx.reLaunch({ url: '/pages/index/index' }) }
  }
})
