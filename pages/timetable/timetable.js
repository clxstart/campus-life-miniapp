const { getNavigationMetrics } = require('../../utils/navigation')
const { getTermWeek, startOfWeek } = require('../../utils/term')
const api = require('../../utils/api')
const school = require('../../utils/school')
const popup = require('../../utils/ui-popup-helper')

// 作息三级优先级：学生本地覆盖 > 学校配置 > 这里的内置默认
const PERIODS = [
  { label: '一', time: '08:00' },
  { label: '二', time: '10:00' },
  { label: '三', time: '14:00' },
  { label: '四', time: '16:00' },
  { label: '五', time: '19:00' }
]
const DAYS = ['一', '二', '三', '四', '五', '六', '日']
const PERIOD_KEY = 'campus_period_times'

// 读回学生自定义作息；损坏或缺失时返回 null（由调用方继续往下回落）
function readLocalPeriodTimes() {
  try {
    const saved = wx.getStorageSync(PERIOD_KEY)
    if (!saved) return null
    const times = typeof saved === 'string' ? JSON.parse(saved) : saved
    if (Array.isArray(times) && times.length === PERIODS.length && times.every(t => /^\d{2}:\d{2}$/.test(String(t)))) {
      return times.map(String)
    }
  } catch (error) {}
  return null
}

// 用一组时间生成 periods（保留一~五的标签）
function periodsFromTimes(times) {
  return PERIODS.map((p, i) => ({ ...p, time: times[i] || p.time }))
}

// 同步读取：只认学生本地覆盖或内置默认（学校配置要异步拉，见 syncSchoolPeriods）
function loadPeriods() {
  const local = readLocalPeriodTimes()
  return periodsFromTimes(local || PERIODS.map(p => p.time))
}

Page({
  data: {
    ...getNavigationMetrics(),
    termLabel: '',
    loading: true, error: '', offline: false,
    periods: loadPeriods(),
    days: DAYS,
    grid: [],
    todayIdx: 0,
    weekDates: [],
    header: '',
    week: null,
    canEdit: true,
    // 添加课程表单
    composing: null,
    composeName: '',
    composeRoom: '',
    composeError: '',
    submitting: false
  },

  onLoad() {
    this._alive = true
    // 作息是【页面实例级】状态：storage 可能在上个实例被改过，进页时重读
    this.setData({ periods: loadPeriods() })
    this.syncSchoolPeriods()
    this.loadTimetable()
  },
  onUnload() { this._alive = false; if (this._debounce) clearTimeout(this._debounce) },

  // 学校作息：学生没自己改过时，用管理端配置的作息替换内置默认
  async syncSchoolPeriods() {
    if (readLocalPeriodTimes()) return  // 学生已自定义，尊重他的选择
    try {
      const times = await school.getSelectedPeriodTimes()
      if (!this._alive || !times) return
      this.setData({ periods: periodsFromTimes(times) })
    } catch (error) {
      // 拉不到就用内置默认，不影响课表显示
    }
  },
  onPullDownRefresh() { this.loadTimetable().finally(() => wx.stopPullDownRefresh()) },

  async loadTimetable() {
    this.setData({ loading: true, error: '' })
    try {
      const week = this.data.week
      const result = await api.getTimetable(week != null ? { week } : {}, api.currentUserId())
      if (!this._alive) return
      const { termInfo, courses } = result
      const today = new Date()
      today.setHours(0, 0, 0, 0)
      const monday = startOfWeek(today)
      const todayIdx = (today.getDay() + 6) % 7
      const weekDates = DAYS.map((_, index) => {
        const day = new Date(monday)
        day.setDate(monday.getDate() + index)
        return `${('0' + (day.getMonth() + 1)).slice(-2)}/${('0' + day.getDate()).slice(-2)}`
      })
      const weekNo = week != null
        ? week  // 显式请求的周优先（切周查看）
        : (result.week != null ? Number(result.week) : getTermWeek(today, termInfo.termStart))
      // 周次过滤：后端已按周过滤，这里再过滤一层，保证调休课只在对应周出现
      const visible = (courses || []).filter(c => {
        const ws = Number(c.weekStart)
        const we = Number(c.weekEnd)
        if (!Number.isFinite(ws) || !Number.isFinite(we)) return true
        return weekNo >= ws && weekNo <= we
      })
      // grid[节次][星期] → 课程
      const grid = this.data.periods.map((_, pi) =>
        DAYS.map((_, di) => visible.find((c) => c.day === di && c.period === pi) || null)
      )
      this.setData({
        grid, termLabel: termInfo.termLabel, offline: !!result.offline, loading: false,
        week: weekNo,
        todayIdx,
        weekDates,
        header: `${today.getMonth() + 1}月${today.getDate()}日 周${DAYS[todayIdx]} · 第${weekNo}教学周`
      })
    } catch (error) { if (this._alive) this.setData({ loading: false, error: error.message || '课表加载失败' }) }
  },

  // 切换周次：查看第 N 周（调休课只在对应周出现）
  switchWeek(e) {
    const week = Number(e.currentTarget.dataset.week)
    if (!week || week === this.data.week) return
    this.setData({ week })
    this.loadTimetable()
  },

  // ---- 学生自建课程 ----
  // 点空格子打开表单，记录位置
  openCompose(e) {
    const day = Number(e.detail.day)
    const period = Number(e.detail.period)
    if (Number.isNaN(day) || Number.isNaN(period)) return
    this.setData({ composing: { day, period }, composeName: '', composeRoom: '', composeError: '' })
  },

  inputComposeName(e) { this.setData({ composeName: e.detail.value, composeError: '' }) },
  inputComposeRoom(e) { this.setData({ composeRoom: e.detail.value }) },

  cancelCompose() { this.setData({ composing: null, composeName: '', composeRoom: '', composeError: '' }) },

  submitCompose() {
    if (this.data.submitting || !this.data.composing) return
    const name = this.data.composeName.trim()
    if (!name) { this.setData({ composeError: '请填写课程名称' }); return }
    if (name.length > 64) { this.setData({ composeError: '课程名称不能超过64字' }); return }
    this.setData({ submitting: true })
    const period = this.data.composing.period
    const day = this.data.composing.day
    api.addCourse({
      day, period,
      name,
      room: this.data.composeRoom.trim(),
      theme: 'green'
    }).then(course => {
      if (!this._alive) return
      // 乐观插入网格，不用整页重拉
      const grid = this.data.grid.map(row => row.slice())
      grid[period][day] = course && course.id ? course : { ...course, name, room: this.data.composeRoom.trim() }
      this.setData({ grid, composing: null, composeName: '', composeRoom: '', submitting: false })
    }).catch(error => {
      if (this._alive) this.setData({ composeError: error.message || '添加失败', submitting: false })
    })
  },

  // 点已有课程 → 确认后删除（只能删自己的）
  removeCourse(e) {
    const day = Number(e.currentTarget.dataset.day)
    const period = Number(e.currentTarget.dataset.period)
    const course = this.data.grid[period] && this.data.grid[period][day]
    if (!course || !course.id || this.data.submitting) return
    popup.modal(this, {
      title: '删除课程',
      message: '删除后无法恢复，确定删除这门课吗？',
      tone: 'error',
      confirmText: '删除',
      confirm: () => {
        api.removeCourse(course.id).then(() => {
          if (!this._alive) return
          const grid = this.data.grid.map(row => row.slice())
          grid[period][day] = null
          this.setData({ grid })
        }).catch(error => {
          if (this._alive) popup.toast(this, error.message || '删除失败', { tone: 'error' })
        })
      }
    })
  },

  // ---- 作息时间：学校配置为默认，学生可改，改动存本地（优先级最高） ----
  setPeriodTime(e) {
    const index = Number(e.detail.index)
    const value = String(e.detail.value || '').trim()
    if (Number.isNaN(index) || !this.data.periods[index]) return
    if (!/^\d{1,2}:\d{2}$/.test(value)) return
    const time = value.length === 4 ? `0${value}` : value
    const periods = this.data.periods.map((p, i) => i === index ? { ...p, time } : p)
    this.setData({ periods })
    try { wx.setStorageSync(PERIOD_KEY, JSON.stringify(periods.map(p => p.time))) } catch (error) {}
  },

  // 恢复学校作息：清掉本地覆盖，重新按学校配置（或内置默认）来
  async resetPeriods() {
    try { wx.removeStorageSync(PERIOD_KEY) } catch (error) {}
    this.setData({ periods: periodsFromTimes(PERIODS.map(p => p.time)) })
    await this.syncSchoolPeriods()
    popup.toast(this, '已恢复默认作息', { tone: 'success' })
  },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) { wx.navigateBack() } else { wx.reLaunch({ url: '/pages/index/index' }) }
  },

  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})
