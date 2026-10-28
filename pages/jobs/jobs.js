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
    page: 1,
    size: 20,
    loadingMore: false,
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

  // 触底加载下一页
  onReachBottom() {
    if (this.data.loadingMore || this.data.jobs.length >= this.data.total) return
    this.loadJobs(false)
  },

  /**
   * 加载兼职列表。
   * @param {boolean} reset true=回到第一页，false=下一页
   */
  async loadJobs(reset = true) {
    const seq = this._seq = (this._seq || 0) + 1
    const page = reset ? 1 : (this.data.page || 1) + 1
    if (reset) this.setData({ loading: true })
    else this.setData({ loadingMore: true })
    try {
      const result = await api.getJobsPage({
        settle: this.data.settle,
        keyword: this.data.keyword,
        schoolScope: this.data.schoolScope,
        schoolId: school.getSelectedId(),
        page,
        size: this.data.size || 20
      })
      if (!this._alive || seq !== this._seq) return
      const mapped = (result.list || []).map(item => ({ ...item, salaryText: `¥${item.daySalary}/天` }))
      const jobs = reset ? mapped : this.data.jobs.concat(mapped.filter(next => !this.data.jobs.some(old => old.id === next.id)))
      this.setData({ jobs, page: result.page, total: result.total })
    } catch (error) {
      if (this._alive && seq === this._seq) popup.toast(this, error.message || '兼职加载失败', { tone: 'error' })
    } finally {
      if (this._alive && seq === this._seq) this.setData({ loading: false, loadingMore: false })
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

