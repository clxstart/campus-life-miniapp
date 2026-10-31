const api = require('../../utils/api')
const school = require('../../utils/school')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')
const fmt = require('../../utils/job-format')

// 两个分区：校内互助（先到先接为主）/ 校外兼职（报名 + 发布者筛选）
const SCOPES = [
  { key: 'campus', label: '校内互助' },
  { key: 'offsite', label: '校外兼职' }
]

Page({
  data: {
    ...getNavigationMetrics(),
    scopes: SCOPES,
    scope: 'campus',
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
    // 错误态：请求失败时页面要如实说「没加载成功」，不拿本地假岗位顶上
    loadError: '',
    // 认证状态（全部来自 GET /verification/me 的真实值，页面不伪造）
    verify: { label: '未认证', verified: false, canTakePaidJob: false, paidJobsOpen: false },
    // 岗位开关（GET /jobs/gates）：校外分区与有偿岗位直接读这里，不再靠 403 文案反推。
    // loaded=false 表示还没读到 —— 这时不猜、不说「未开放」，等接口回来再显示。
    gates: {
      loaded: false,
      error: '',
      offsiteOpen: false,
      offsiteReason: '',
      paidOpen: false,
      paidReason: '',
      takeoverClassOpen: false,
      takeoverClassReason: ''
    },
    uiPopup: null
  },

  onLoad() { this._alive = true },
  onShow() {
    this.loadVerify()
    this.loadGates()
    this.loadJobs(true)
  },
  onUnload() {
    this._alive = false
    if (this._timer) clearTimeout(this._timer)
  },
  onPullDownRefresh() {
    Promise.all([this.loadVerify(), this.loadGates(), this.loadJobs(true)]).finally(() => wx.stopPullDownRefresh())
  },

  // 触底加载下一页
  onReachBottom() {
    if (this.data.loadingMore || this.data.jobs.length >= this.data.total) return
    this.loadJobs(false)
  },

  /**
   * 读认证状态。拿不到就保持「未认证」的保守默认值 ——
   * 宁可让用户多点一次才知道要认证，也不能假装「已认证」让他去撞 403。
   */
  async loadVerify() {
    try {
      const verify = await api.getVerification()
      if (!this._alive || !verify) return
      this.setData({
        verify: {
          label: verify.label || '未认证',
          verified: Boolean(verify.verified),
          canTakePaidJob: Boolean(verify.canTakePaidJob),
          paidJobsOpen: Boolean(verify.paidJobsOpen)
        }
      })
    } catch (error) {
      // 未登录 / 断网：保持默认的未认证状态，不弹错打断浏览
    }
  },

  /**
   * 读岗位开关：校外 / 有偿 / 替课。
   * 读不到就把 loaded 留成 false 并记下原因 —— 页面只描述「状态取不到」，
   * 绝不在不知道的情况下去断言「未开放」。
   */
  async loadGates() {
    try {
      const gates = await api.getJobGates()
      if (!this._alive || !gates) return
      this.setData({
        gates: {
          loaded: true,
          error: '',
          offsiteOpen: Boolean(gates.offsiteOpen),
          offsiteReason: gates.offsiteReason || '',
          paidOpen: Boolean(gates.paidOpen),
          paidReason: gates.paidReason || '',
          takeoverClassOpen: Boolean(gates.takeoverClassOpen),
          takeoverClassReason: gates.takeoverClassReason || ''
        }
      })
    } catch (error) {
      if (!this._alive) return
      this.setData({
        'gates.loaded': false,
        'gates.error': (error && error.message) || '岗位开放状态没取到'
      })
    }
  },

  /**
   * 加载兼职列表。
   * @param {boolean} reset true=回到第一页，false=下一页
   */
  async loadJobs(reset = true) {
    const seq = this._seq = (this._seq || 0) + 1
    const page = reset ? 1 : (this.data.page || 1) + 1
    if (reset) this.setData({ loading: true, loadError: '' })
    else this.setData({ loadingMore: true })
    try {
      const result = await api.getJobsPage({
        scope: this.data.scope,
        settle: this.data.settle,
        keyword: this.data.keyword,
        schoolScope: this.data.schoolScope,
        schoolId: school.getSelectedId(),
        page,
        size: this.data.size || 20
      })
      if (!this._alive || seq !== this._seq) return
      const mapped = (result.list || []).map(job => this.decorate(job))
      // 翻页期间可能有人发新岗位：按 id 去重，避免同一张卡片出现两次
      const jobs = reset ? mapped : this.data.jobs.concat(mapped.filter(next => !this.data.jobs.some(old => old.id === next.id)))
      this.setData({ jobs, page: result.page, total: result.total, loadError: '' })
    } catch (error) {
      if (!this._alive || seq !== this._seq) return
      const message = (error && error.message) || '岗位加载失败，请稍后重试'
      // 翻页失败保留已有列表（用户还能看前面的），首屏失败给完整错误态
      this.setData({ loadError: message, jobs: reset ? [] : this.data.jobs })
      if (!reset) popup.toast(this, message, { tone: 'error' })
    } finally {
      if (this._alive && seq === this._seq) this.setData({ loading: false, loadingMore: false })
    }
  },

  /** 列表项 → 卡片字段（金额/状态文案统一交给 utils/job-format） */
  decorate(job) {
    const view = fmt.jobView(job)
    const canTakeNow = view.scope === 'campus' && view.takeMode === 'first_come'
    return Object.assign(view, {
      // 校内先到先接 → 立即接单；校外/需筛选 → 报名
      actionLabel: canTakeNow ? '立即接单' : '报名',
      actionMode: canTakeNow ? 'take' : 'apply'
    })
  },

  /**
   * 403 兜底：正常路径上页面已经用 /jobs/gates + /verification/me 提前说明，
   * 但开关是服务端状态，可能刚被别人改过。这里只做防御性提示，并顺手刷新开关，
   * 让说明条和后端重新对齐。
   */
  explainGateError(message) {
    const text = String(message || '')
    if (!/校外|有偿|替课/.test(text) || text.indexOf('开放') < 0) return false
    this.loadGates()
    popup.modal(this, { title: '暂时不能操作', message: text, showCancel: false, confirmText: '知道了' })
    return true
  },

  // 输入时防抖查询，避免每敲一个字都打一次接口
  search(e) {
    const keyword = e.detail.value
    this.setData({ keyword })
    if (this._timer) clearTimeout(this._timer)
    this._timer = setTimeout(() => { if (this._alive) this.loadJobs(true) }, 300)
  },

  submitSearch() {
    if (this._timer) clearTimeout(this._timer)
    this.loadJobs(true)
  },

  clearSearch() {
    if (this._timer) clearTimeout(this._timer)
    this.setData({ keyword: '' })
    this.loadJobs(true)
  },

  chooseSettle(e) {
    const settle = e.currentTarget.dataset.settle
    if (settle === this.data.settle) return
    this.setData({ settle, jobs: [], total: 0 })
    this.loadJobs(true)
  },

  // 校内互助 / 校外兼职切换
  switchScope(e) {
    const scope = e.currentTarget.dataset.scope
    if (!scope || scope === this.data.scope) return
    this.setData({ scope, jobs: [], total: 0, page: 1, loadError: '' })
    this.loadJobs(true)
  },

  // 本校/全部切换：兼职按校隔离
  switchSchoolScope(e) {
    const schoolScope = e.currentTarget.dataset.scope
    if (!schoolScope || schoolScope === this.data.schoolScope) return
    this.setData({ schoolScope, jobs: [], total: 0 })
    this.loadJobs(true)
  },

  retry() { this.loadJobs(true) },

  openDetail(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    wx.navigateTo({ url: `/pages/job-detail/job-detail?id=${id}` })
  },

  openMine() { wx.navigateTo({ url: '/pages/job-mine/job-mine' }) },

  /**
   * 卡片上的主操作。
   * mode=take → 校内先到先接；mode=apply → 报名（校外 / 需筛选的岗位）。
   * 认证前置检查放在请求之前：让用户知道「为什么现在点不了」，而不是撞一个 403。
   */
  takeOrApply(e) {
    const id = e.currentTarget.dataset.id
    const job = this.data.jobs.find(item => String(item.id) === String(id))
    if (!job || this._busy) return
    if (job.actionMode === 'apply') {
      // 校外关闭时先说清楚再拦：不让用户把留言写完了才撞 403
      if (job.scope === 'offsite' && this.data.gates.loaded && !this.data.gates.offsiteOpen) {
        return this.gateModal('校外岗位暂未开放', this.data.gates.offsiteReason)
      }
      return this.openApply(job)
    }
    return this.takeJob(job)
  },

  /** 开关关闭时的统一说明（文案优先用后端给的 reason，前端不自己编） */
  gateModal(title, reason) {
    popup.modal(this, {
      title,
      message: reason || '平台还没开放这项能力，资质核验通过后这里会同步更新。',
      showCancel: false,
      confirmText: '知道了'
    })
  },

  /** 接单前的资质说明：开关 → 认证，顺序就是后端校验的顺序 */
  takeJob(job) {
    const verify = this.data.verify
    const gates = this.data.gates
    if (job.paid) {
      // 有偿总闸门关着时，认证过也接不了：先说闸门，别让用户白跑一趟认证页
      if (gates.loaded && !gates.paidOpen) {
        return this.gateModal('有偿岗位暂未开放', gates.paidReason)
      }
      if (!verify.verified) {
        popup.modal(this, {
          title: '接取有偿岗位需要先认证',
          message: `你当前的认证状态是「${verify.label}」。完成实名认证与学生身份认证后，才能接取有偿岗位。`,
          showCancel: false,
          confirmText: '知道了'
        })
        return
      }
      if (!verify.canTakePaidJob) {
        return this.gateModal('有偿岗位暂未开放',
          gates.paidReason || `你当前的认证状态是「${verify.label}」，暂时不能接取有偿岗位。`)
      }
    }
    this.doTake(job)
  },

  async doTake(job) {
    this._busy = true
    try {
      const contract = await api.takeJob(job.id)
      if (!this._alive) return
      const contractId = contract && contract.id
      popup.modal(this, {
        title: '接单成功',
        message: '岗位已经归你，进入履约单后可以开工、提交工时，双方确认工时后才会生成履约账单。',
        confirmText: contractId ? '查看履约单' : '知道了',
        cancelText: '继续看岗位',
        showCancel: Boolean(contractId),
        confirm: () => {
          if (contractId) wx.navigateTo({ url: `/pages/job-mine/job-mine?contractId=${contractId}` })
        }
      })
      this.loadJobs(true)
    } catch (error) {
      if (this._alive) this.handleActionError(error, '接单失败')
    } finally {
      this._busy = false
    }
  },

  openApply(job) {
    popup.modal(this, {
      title: '报名这个岗位',
      message: '留一句想对发布者说的话（选填，200 字以内）',
      editable: true,
      placeholder: '比如：做过后台，周末全天有空',
      confirmText: '提交报名',
      confirm: ({ inputValue }) => this.doApply(job, inputValue)
    })
  },

  async doApply(job, message) {
    try {
      const result = await api.applyJob(job.id, message)
      if (!this._alive) return
      if (result && result.alreadyApplied) {
        popup.toast(this, '你已经报过名了，等发布者筛选', { tone: 'info' })
      } else {
        popup.modal(this, {
          title: '报名已提交',
          message: '发布者会看到你的留言。被录用后可以在「我的兼职」里开工。',
          showCancel: false,
          confirmText: '知道了'
        })
      }
      this.loadJobs(true)
    } catch (error) {
      if (this._alive) this.handleActionError(error, '报名失败')
    }
  },

  /**
   * 统一处理接单/报名的失败：
   *   409 → 岗位已被接走/名额已满：说清楚并刷新（列表已经不是最新的了）
   *   403 闸门 → 说明原因并刷新开关状态（防御服务端与页面判断不一致）
   *   其它 → 原样把后端文案给用户
   */
  handleActionError(error, fallback) {
    const message = (error && error.message) || fallback
    if ((error && error.status === 409) || /已被接取|名额已满|已经处理过/.test(message)) {
      popup.modal(this, {
        title: /名额已满/.test(message) ? '名额已满' : '手慢了，岗位已被接取',
        message: message + '。已经为你刷新列表。',
        showCancel: false,
        confirmText: '刷新看看'
      })
      this.loadJobs(true)
      return
    }
    if (this.explainGateError(message)) return
    popup.modal(this, { title: fallback, message, showCancel: false, confirmText: '知道了' })
  },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.reLaunch({ url: '/pages/index/index' })
  },
  onPopupConfirm(e) { this._popupConfirm && this._popupConfirm({ detail: e.detail }) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(e) { this._popupAction && this._popupAction({ detail: e.detail }) },
  onPopupInput(e) { this._popupInput && this._popupInput({ detail: e.detail }) },
  onPopupClose() { popup.hide(this) }
})
