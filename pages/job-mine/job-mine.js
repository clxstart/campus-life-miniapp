const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')
const fmt = require('../../utils/job-format')

const TABS = [
  { key: 'published', label: '我发布的' },
  // 履约这条线同时包含「我承接的」与「我作为发布方的」，两张都要能进来确认工时
  { key: 'contracts', label: '我参与履约的' }
]

Page({
  data: {
    ...getNavigationMetrics(),
    tabs: TABS,
    tab: 'published',
    loading: true,
    loadError: '',
    published: [],
    // 履约单：我承接的（role=worker）+ 我发布的（role=employer）合并成一条线
    contracts: [],
    // 当前打开的履约单（从列表点进来，或用 ?contractId= 直接进来）
    contractId: '',
    contract: null,
    contractLoading: false,
    contractError: '',
    acting: false,
    uiPopup: null
  },

  onLoad(options) {
    this._alive = true
    const contractId = options && options.contractId ? String(options.contractId) : ''
    // 从岗位详情「去我的兼职」过来时带 tab=contracts，直接落在履约列表
    const tab = options && options.tab === 'contracts' ? 'contracts' : ''
    if (contractId) this.setData({ contractId, tab: 'contracts' })
    else if (tab) this.setData({ tab })
  },
  onShow() {
    this.loadMine()
    if (this.data.contractId) this.loadContract(this.data.contractId)
  },
  onUnload() { this._alive = false },

  onPullDownRefresh() {
    const jobs = [this.loadMine()]
    if (this.data.contractId) jobs.push(this.loadContract(this.data.contractId))
    Promise.all(jobs).finally(() => wx.stopPullDownRefresh())
  },

  /** 我发布的 + 我参与的履约单（两个 tab 一次拉齐，切 tab 不再请求） */
  async loadMine() {
    this.setData({ loading: true, loadError: '' })
    try {
      const result = await api.getMyJobs()
      if (!this._alive) return
      const published = (result.published || []).map(item => {
        const view = fmt.jobView(item)
        // 我发布的还要看审核状态：待审核 / 已下架 都是真实状态，不能一律写「招募中」
        view.auditText = item.auditStatus === 'passed' ? '' : (item.auditStatus === 'pending' ? '审核中' : '已下架')
        view.auditReason = item.auditReason || ''
        return view
      })
      // working = 我是承接方，employer = 我是发布方，同一张卡片模板，只差一个角色标记
      const asWorker = (result.working || []).map(item => this.decorateContractRow(item, 'worker'))
      const asEmployer = (result.employer || []).map(item => this.decorateContractRow(item, 'employer'))
      const contracts = asWorker.concat(asEmployer)
        .sort((a, b) => Number(b.id || 0) - Number(a.id || 0))
      this.setData({ published, contracts, loading: false })
    } catch (error) {
      if (!this._alive) return
      this.setData({ loading: false, loadError: (error && error.message) || '我的兼职加载失败' })
    }
  },

  /** 履约单列表项：worker / employer 两个视角统一成一个形状 */
  decorateContractRow(item, role) {
    const isWorker = role === 'worker'
    return {
      id: item.contractId,
      jobId: item.jobId,
      title: item.title || '未命名岗位',
      location: item.location || '',
      scopeText: fmt.scopeText(item.scope),
      role,
      roleText: isWorker ? '我承接' : '我发布',
      roleClass: isWorker ? 'role-worker' : 'role-employer',
      payText: fmt.payText(item.payCentsSnapshot, item.payUnitSnapshot),
      contractStatus: item.contractStatus,
      contractStatusText: fmt.contractText(item.contractStatus),
      contractStatusClass: fmt.contractClass(item.contractStatus),
      // 我这一侧提交的工时 / 对方那一侧，方便一眼看出还差谁确认
      myHoursText: fmt.hoursText(isWorker ? item.workerHours : item.employerHours),
      otherHoursText: fmt.hoursText(isWorker ? item.employerHours : item.workerHours),
      // 发布方视角要显示承接人是谁
      otherPartyText: isWorker ? '' : (item.workerName || ''),
      createdAtText: fmt.dateText(item.contractCreatedAt)
    }
  },

  switchTab(e) {
    const tab = e.currentTarget.dataset.tab
    if (!tab || tab === this.data.tab) return
    this.setData({ tab })
  },

  /** 履约单详情：金额三块 + 工时 + 账单 + 可做的动作，全靠后端返回，前端只格式化 */
  async loadContract(id) {
    this.setData({ contractLoading: true, contractError: '', contractId: String(id) })
    try {
      const contract = await api.getJobContract(id)
      if (!this._alive) return
      this.setData({ contract: this.decorateContract(contract), contractLoading: false })
    } catch (error) {
      if (!this._alive) return
      this.setData({ contract: null, contractLoading: false, contractError: (error && error.message) || '履约单加载失败' })
    }
  },

  decorateContract(raw) {
    const contract = raw || {}
    const status = contract.status
    const me = api.currentUserId()
    const isWorker = String(contract.workerId) === String(me)
    const settlement = fmt.settlementView(contract.settlement)
    const entries = (contract.ledger || []).map((row, index) => ({
      key: row.bizNo || ('row-' + index),
      label: fmt.ledgerText(row.entryType),
      amountText: fmt.moneyText(row.amountCents),
      statusText: fmt.ledgerStatusText(row.status),
      note: row.note || ''
    }))
    return {
      id: contract.id,
      jobId: contract.jobId,
      jobTitle: contract.jobTitle || '未命名岗位',
      status,
      statusText: fmt.contractText(status),
      statusClass: fmt.contractClass(status),
      isWorker,
      roleText: isWorker ? '我是接单方' : '我是发布方',
      workerName: contract.workerName || '',
      employerName: contract.employerName || '',
      payText: fmt.payText(contract.payCentsSnapshot, contract.payUnitSnapshot),
      workerHoursText: fmt.hoursText(contract.workerHours),
      employerHoursText: fmt.hoursText(contract.employerHours),
      agreedHoursText: fmt.hoursText(contract.agreedHours),
      startedText: fmt.dateText(contract.startedAt),
      settledText: fmt.dateText(contract.settledAt),
      cancelReason: contract.cancelReason || '',
      disputeReason: (contract.dispute && contract.dispute.reason) || '',
      settlement,
      entries,
      // 动作白名单：与后端条件 UPDATE 的状态条件保持一致，不给出注定失败的按钮
      canStart: status === 'created',
      canSubmitHours: status === 'working' || status === 'pending_confirm',
      canCancel: status === 'created' || status === 'working' || status === 'pending_confirm',
      canDispute: status === 'working' || status === 'pending_confirm',
      frozen: status === 'disputed',
      finished: status === 'settled' || status === 'cancelled' || status === 'refunded'
    }
  },

  openContract(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    this.loadContract(id)
  },

  closeContract() { this.setData({ contractId: '', contract: null, contractError: '' }) },

  openJob(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    wx.navigateTo({ url: `/pages/job-detail/job-detail?id=${id}` })
  },

  openPublish() { wx.navigateTo({ url: '/pages/publish/publish' }) },
  retry() { this.loadMine() },
  retryContract() { if (this.data.contractId) this.loadContract(this.data.contractId) },

  /** 开工 */
  startContract() {
    const contract = this.data.contract
    if (!contract || this.data.acting) return
    popup.modal(this, {
      title: '确认开工',
      message: '开工后双方就可以各自提交工时，双方确认一致后才会结算。',
      confirmText: '开始干活',
      confirm: async () => {
        this.setData({ acting: true })
        try {
          const next = await api.startJobContract(contract.id)
          if (!this._alive) return
          popup.toast(this, '已开工', { tone: 'success' })
          this.applyContractResult(next)
        } catch (error) {
          if (this._alive) this.handleActionError(error, '开工失败')
        } finally {
          if (this._alive) this.setData({ acting: false })
        }
      }
    })
  },

  /** 提交 / 确认工时：两个接口写的是同一对列，差别只在语义 */
  inputHours() {
    const contract = this.data.contract
    if (!contract || this.data.acting) return
    const isWorker = contract.isWorker
    popup.modal(this, {
      title: isWorker ? '提交我的工时' : '确认工时',
      message: '按小时填写，例如 2 或 2.5。双方填的工时不一致会自动进入争议，金额先冻结。',
      editable: true,
      placeholder: '这次干了多少小时？',
      confirmText: '提交',
      confirm: ({ inputValue }) => this.doHours(contract, inputValue)
    })
  },

  async doHours(contract, raw) {
    const hours = Number(String(raw || '').trim())
    if (!(hours > 0)) {
      popup.toast(this, '请填写大于 0 的工时', { tone: 'warn' })
      return
    }
    if (hours > 1000) {
      popup.toast(this, '单次工时不能超过 1000 小时', { tone: 'warn' })
      return
    }
    this.setData({ acting: true })
    try {
      const next = contract.isWorker
        ? await api.submitJobHours(contract.id, hours)
        : await api.confirmJobHours(contract.id, hours)
      if (!this._alive) return
      this.applyContractResult(next)
      const status = next && next.status
      if (status === 'disputed') {
        popup.modal(this, {
          title: '已进入争议，金额冻结',
          message: '双方提交的工时不一致，这笔账已冻结并等待平台处理。在裁定之前不会生成收入。',
          showCancel: false,
          confirmText: '知道了',
          tone: 'warn'
        })
      } else if (status === 'settled') {
        popup.modal(this, {
          title: '工时一致，已结算',
          message: '履约账单已生成。注意：账单不是钱包余额，也不是已付款项，不能提现。',
          showCancel: false,
          confirmText: '知道了'
        })
      } else {
        popup.toast(this, '工时已提交，等双方确认', { tone: 'success' })
      }
    } catch (error) {
      if (this._alive) this.handleActionError(error, '工时提交失败')
    } finally {
      if (this._alive) this.setData({ acting: false })
    }
  },

  /** 取消履约：原因选填 */
  cancelContract() {
    const contract = this.data.contract
    if (!contract || this.data.acting) return
    popup.modal(this, {
      title: '取消这个履约单',
      message: '开工前取消整单按未履约处理；开工后只结算双方都确认过的工时。可以写一句原因（选填）。',
      editable: true,
      placeholder: '取消原因（选填）',
      tone: 'warn',
      confirmText: '确认取消',
      confirm: ({ inputValue }) => this.doCancel(contract, inputValue)
    })
  },

  async doCancel(contract, reason) {
    this.setData({ acting: true })
    try {
      const next = await api.cancelJobContract(contract.id, reason)
      if (!this._alive) return
      popup.toast(this, '履约单已取消', { tone: 'info' })
      this.applyContractResult(next)
      this.loadMine()
    } catch (error) {
      if (this._alive) this.handleActionError(error, '取消失败')
    } finally {
      if (this._alive) this.setData({ acting: false })
    }
  },

  /** 发起争议：原因必填（后端也会校验） */
  disputeContract() {
    const contract = this.data.contract
    if (!contract || this.data.acting) return
    popup.modal(this, {
      title: '发起争议',
      message: '请写清楚争议原因（必填），提交后金额立即冻结，等平台处理。',
      editable: true,
      placeholder: '例如：实际干了 3 小时，对方只确认 1 小时',
      tone: 'warn',
      confirmText: '提交争议',
      confirm: ({ inputValue }) => this.doDispute(contract, inputValue)
    })
  },

  async doDispute(contract, reason) {
    if (!String(reason || '').trim()) {
      popup.toast(this, '请填写争议原因', { tone: 'warn' })
      return
    }
    this.setData({ acting: true })
    try {
      const next = await api.disputeJobContract(contract.id, reason)
      if (!this._alive) return
      this.applyContractResult(next)
      popup.modal(this, {
        title: '已进入争议，金额冻结',
        message: '平台会核对双方的工时记录后再裁定。在这之前这笔钱既不算收入，也不可结算。',
        showCancel: false,
        confirmText: '知道了',
        tone: 'warn'
      })
    } catch (error) {
      if (this._alive) this.handleActionError(error, '发起争议失败')
    } finally {
      if (this._alive) this.setData({ acting: false })
    }
  },

  /** 接口直接返回最新履约单，拿它刷新界面，不自己推演状态 */
  applyContractResult(next) {
    if (next && next.id) {
      this.setData({ contract: this.decorateContract(next) })
    }
    this.loadMine()
  },

  handleActionError(error, fallback) {
    const message = (error && error.message) || fallback
    popup.modal(this, { title: fallback, message, showCancel: false, confirmText: '知道了' })
    // 409 / 400 多半是别人已经推过状态了：重新拉一遍最新数据
    if (error && (error.status === 409 || error.status === 400)) {
      if (this.data.contractId) this.loadContract(this.data.contractId)
      this.loadMine()
    }
  },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.reLaunch({ url: '/pages/jobs/jobs' })
  },
  onPopupConfirm(e) { this._popupConfirm && this._popupConfirm({ detail: e.detail }) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(e) { this._popupAction && this._popupAction({ detail: e.detail }) },
  onPopupInput(e) { this._popupInput && this._popupInput({ detail: e.detail }) },
  onPopupClose() { popup.hide(this) }
})
