const api = require('../../utils/api')
const school = require('../../utils/school')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')

Page({
  data: {
    ...getNavigationMetrics(),
    settles: [{ value: '', label: '全部' }, { value: '日结', label: '日结' }, { value: '周结', label: '周结' }],
    settle: '',
    keyword: '',
    // 兼职按校隔离：校内岗位，默认只看本校
    schoolScope: 'local',
    jobs: [],
    total: 0,
    loading: true,
    uiPopup: null
  },

  onLoad() { this._alive = true },
  onShow() { this.loadJobs() },
  onUnload() {
    this._alive = false
    if (this._timer) clearTimeout(this._timer)
  },
  onPullDownRefresh() { this.loadJobs().finally(() => wx.stopPullDownRefresh()) },

  async loadJobs() {
    const seq = this._seq = (this._seq || 0) + 1
    this.setData({ loading: true })
    try {
      const rows = await api.getJobs(this.data.settle, this.data.keyword, this.data.schoolScope, school.getSelectedId())
      if (!this._alive || seq !== this._seq) return
      const jobs = (rows || []).map(item => ({ ...item, salaryText: `¥${item.daySalary}/天` }))
      this.setData({ jobs, total: jobs.length })
    } catch (error) {
      if (this._alive && seq === this._seq) popup.toast(this, error.message || '兼职加载失败', { tone: 'error' })
    } finally {
      if (this._alive && seq === this._seq) this.setData({ loading: false })
    }
  },

  // 输入时防抖查询，避免每敲一个字都打一次接口
  search(e) {
    const keyword = e.detail.value
    this.setData({ keyword })
    if (this._timer) clearTimeout(this._timer)
    this._timer = setTimeout(() => { if (this._alive) this.loadJobs() }, 300)
  },

  // 回车立即查询，不等防抖
  submitSearch() {
    if (this._timer) clearTimeout(this._timer)
    this.loadJobs()
  },

  clearSearch() {
    if (this._timer) clearTimeout(this._timer)
    this.setData({ keyword: '' })
    this.loadJobs()
  },

  chooseSettle(e) {
    const settle = e.currentTarget.dataset.settle
    if (settle === this.data.settle) return
    this.setData({ settle })
    this.loadJobs()
  },

  // 本校/全部切换：兼职按校隔离
  switchSchoolScope(e) {
    const schoolScope = e.currentTarget.dataset.scope
    if (!schoolScope || schoolScope === this.data.schoolScope) return
    this.setData({ schoolScope, jobs: [] })
    this.loadJobs()
  },

  goBack() { wx.navigateBack() },
  onPopupConfirm(e) { this._popupConfirm && this._popupConfirm({ detail: e.detail }) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(e) { this._popupAction && this._popupAction({ detail: e.detail }) },
  onPopupInput(e) { this._popupInput && this._popupInput({ detail: e.detail }) },
  onPopupClose() { popup.hide(this) }
})
