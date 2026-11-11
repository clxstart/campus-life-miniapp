const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')
const fmt = require('../../utils/job-format')

Page({
  data: {
    ...getNavigationMetrics(),
    id: '',
    loading: true,
    loadError: '',
    // 详情原样保留一份，页面只读派生字段，避免二次拼装时算错金额
    view: null,
    feeRuleText: '',
    isOwner: false,
    // 认证状态（真实值）
    verify: { label: '未认证', verified: false, canTakePaidJob: false, paidJobsOpen: false },
    // 岗位开关（GET /jobs/gates）：校外 / 有偿，关闭时显示后端给的原因
    gates: {
      loaded: false,
      error: '',
      offsiteOpen: false,
      offsiteReason: '',
      paidOpen: false,
      paidReason: ''
    },
    // 发布者视角：报名列表
    applications: [],
    applicationsLoading: false,
    applicationsError: '',
    headcountText: '',
    // 承接方视角：我的报名 / 我的履约单
    myApplicationText: '',
    myContract: null,
    myContractStatusText: '',
    myContractHoursText: '',
    // F10 交易埋点：结算完成且确实发过分时，履约卡顶部的一行提示（空串 = 不显示）
    myContractAwardedText: '',
    acting: false,
    uiPopup: null
  },

  onLoad(options) {
    this._alive = true
    const id = options && options.id ? String(options.id) : ''
    this.setData({ id })
    if (!id) {
      this.setData({ loading: false, loadError: '缺少岗位参数' })
      return
    }
  },
  onShow() {
    if (!this.data.id) return
    this.loadVerify()
    this.loadGates()
    this.loadDetail()
  },
  onUnload() { this._alive = false },

  /** 岗位开关：校外 / 有偿。读不到不说「已关闭」，只说状态没取到 */
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
          paidReason: gates.paidReason || ''
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
      // 未登录 / 断网：保持未认证的保守默认值
    }
  },

  async loadDetail() {
    this.setData({ loading: true, loadError: '' })
    try {
      const job = await api.getJobDetail(this.data.id)
      if (!this._alive) return
      if (!job) throw new Error('岗位不存在或已下架')
      const view = fmt.jobView(job)
      const me = api.currentUserId()
      const isOwner = String(job.userId) === String(me)
      const myContract = job.myContract || null
      this.setData({
        view,
        isOwner,
        feeRuleText: fmt.feeRuleText(job.feeMode, job.feeValue),
        headcountText: fmt.headcountText(job.headcount, job.acceptedCount),
        myApplicationText: job.myApplication ? fmt.applicationText(job.myApplication.status) : '',
        myContract,
        myContractStatusText: myContract ? fmt.contractText(myContract.status) : '',
        myContractHoursText: myContract ? fmt.quantityText(myContract.agreedHours || myContract.workerHours, view.payUnit) : '',
        myContractAwardedText: this.awardedLine(myContract),
        loading: false
      })
      if (isOwner) this.loadApplications()
    } catch (error) {
      if (!this._alive) return
      this.setData({ loading: false, loadError: (error && error.message) || '岗位详情加载失败' })
    }
  },

  /**
   * F10 交易埋点：结算完成的积分提示（设计稿 §2.2）
   *
   * 只在「合约 settled」且后端 pointsAwarded > 0 时给文案。后者读的是 point_log，
   * 所以争议中、整单退回、日上限命中都不会谎报「+5 已到账」。
   * 积分发给承接方，本页的 myContract 正是「我承接的那张单」，所以文案对本人成立。
   */
  awardedLine(contract) {
    const points = Number(contract && contract.pointsAwarded) || 0
    if (!contract || contract.status !== 'settled' || points <= 0) return ''
    return `结算完成，+${points} 积分已到账`
  },

  /** 发布者：报名列表（只有发布者能看，其他人会 403） */
  async loadApplications() {
    this.setData({ applicationsLoading: true, applicationsError: '' })
    try {
      const result = await api.getJobApplications(this.data.id)
      if (!this._alive) return
      const list = (result && result.list) || []
      this.setData({
        applications: list.map(item => ({
          id: item.id,
          applicantId: item.applicantId,
          status: item.status,
          statusText: fmt.applicationText(item.status),
          message: item.message || '',
          appliedAtText: fmt.dateText(item.appliedAt),
          pending: item.status === 'pending'
        })),
        headcountText: fmt.headcountText(result && result.headcount, result && result.acceptedCount)
      })
    } catch (error) {
      if (!this._alive) return
      this.setData({ applicationsError: (error && error.message) || '报名列表加载失败' })
    } finally {
      if (this._alive) this.setData({ applicationsLoading: false })
    }
  },

  /** 开关关闭时的统一说明（文案优先用后端 reason，前端不自己编） */
  gateModal(title, reason) {
    popup.modal(this, {
      title,
      message: reason || '平台还没开放这项能力，资质核验通过后这里会同步更新。',
      showCancel: false,
      confirmText: '知道了'
    })
  },

  /**
   * 403 兜底：正常路径已经用 /jobs/gates + /verification/me 提前说明，
   * 但开关是服务端状态（可能刚被别人改过）。这里给提示并重新拉一次开关。
   */
  explainGateError(message) {
    const text = String(message || '')
    if (!/校外|有偿|替课/.test(text) || text.indexOf('开放') < 0) return false
    this.loadGates()
    popup.modal(this, { title: '暂时不能操作', message: text, showCancel: false, confirmText: '知道了' })
    return true
  },

  /** 主操作：校内先到先接 → 立即接单；否则 → 报名 */
  takeOrApply() {
    const view = this.data.view
    if (!view || this.data.acting) return
    if (!view.open) {
      popup.toast(this, '该岗位当前状态是「' + view.statusText + '」，暂时不能接取或报名', { tone: 'warn' })
      return
    }
    const canTakeNow = view.scope === 'campus' && view.takeMode === 'first_come'
    if (canTakeNow) return this.takeJob(view)
    // 校外关闭时先说清楚再拦，别让用户写完留言才撞 403
    if (view.scope === 'offsite' && this.data.gates.loaded && !this.data.gates.offsiteOpen) {
      return this.gateModal('校外岗位暂未开放', this.data.gates.offsiteReason)
    }
    this.openApply(view)
  },

  /** 接单前的资质说明：顺序与后端一致（有偿闸门 → 认证） */
  takeJob(view) {
    const verify = this.data.verify
    const gates = this.data.gates
    if (view.paid) {
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
    this.doTake(view.id)
  },

  async doTake(id) {
    this.setData({ acting: true })
    try {
      const contract = await api.takeJob(id)
      if (!this._alive) return
      const contractId = contract && contract.id
      popup.modal(this, {
        title: '接单成功',
        message: '岗位已经归你。进入履约单后可以开工、提交工时，双方确认工时后才会生成履约账单。',
        confirmText: contractId ? '查看履约单' : '知道了',
        cancelText: '留在这里',
        showCancel: Boolean(contractId),
        confirm: () => {
          if (contractId) wx.redirectTo({ url: `/pages/job-mine/job-mine?contractId=${contractId}` })
        }
      })
      this.loadDetail()
    } catch (error) {
      if (this._alive) this.handleActionError(error, '接单失败')
    } finally {
      if (this._alive) this.setData({ acting: false })
    }
  },

  openApply(view) {
    popup.modal(this, {
      title: '报名这个岗位',
      message: '留一句想对发布者说的话（选填，200 字以内）',
      editable: true,
      placeholder: '比如：做过后台，周末全天有空',
      confirmText: '提交报名',
      confirm: ({ inputValue }) => this.doApply(view.id, inputValue)
    })
  },

  async doApply(id, message) {
    this.setData({ acting: true })
    try {
      const result = await api.applyJob(id, message)
      if (!this._alive) return
      if (result && result.alreadyApplied) {
        popup.toast(this, '你已经报过名了，等发布者筛选', { tone: 'info' })
      } else {
        popup.modal(this, {
          title: '报名已提交',
          message: '发布者会看到你的留言。被录用后会出现履约单，可以在「我的兼职」里开工。',
          showCancel: false,
          confirmText: '知道了'
        })
      }
      this.loadDetail()
    } catch (error) {
      if (this._alive) this.handleActionError(error, '报名失败')
    } finally {
      if (this._alive) this.setData({ acting: false })
    }
  },

  /** 发布者：录用 / 拒绝一条报名 */
  decide(e) {
    const applicationId = e.currentTarget.dataset.id
    const approve = e.currentTarget.dataset.approve === true || e.currentTarget.dataset.approve === 'true'
    if (!applicationId || this.data.acting) return
    if (!approve) {
      popup.modal(this, {
        title: '拒绝这条报名',
        message: '可以写一句原因（选填，200 字以内），对方能看到',
        editable: true,
        placeholder: '比如：这次排班冲突了',
        tone: 'warn',
        confirmText: '确认拒绝',
        confirm: ({ inputValue }) => this.doDecide(applicationId, false, inputValue)
      })
      return
    }
    // 录用会占一个名额，先确认再动手
    popup.modal(this, {
      title: '录用这位同学',
      message: '录用后该同学会拿到履约单，并占用一个名额。确认真要录用吗？',
      confirmText: '确认录用',
      confirm: () => this.doDecide(applicationId, true, '')
    })
  },

  async doDecide(applicationId, approve, reason) {
    this.setData({ acting: true })
    try {
      const result = await api.decideJobApplication(this.data.id, applicationId, approve, reason)
      if (!this._alive) return
      const contractId = result && result.contractId
      if (approve && contractId) {
        // 发布方的履约单入口统一在「我的兼职 → 我参与履约的」，不再在本地记 id
        popup.modal(this, {
          title: '已录用',
          message: '履约单已生成。开工和确认工时都在「我的兼职 → 我参与履约的」里，双方工时一致后才会结算。',
          confirmText: '去我的兼职',
          cancelText: '留在这里',
          showCancel: true,
          confirm: () => wx.navigateTo({ url: '/pages/job-mine/job-mine?tab=contracts' })
        })
      } else {
        popup.toast(this, approve ? '已录用' : '已拒绝', { tone: 'success' })
      }
      this.loadApplications()
      this.loadDetail()
    } catch (error) {
      if (this._alive) this.handleActionError(error, approve ? '录用失败' : '拒绝失败')
    } finally {
      if (this._alive) this.setData({ acting: false })
    }
  },

  handleActionError(error, fallback) {
    const message = (error && error.message) || fallback
    if ((error && error.status === 409) || /已被接取|名额已满|已经处理过/.test(message)) {
      popup.modal(this, {
        title: /名额已满/.test(message) ? '名额已满' : '状态已经变了',
        message: message + '。已为你刷新最新状态。',
        showCancel: false,
        confirmText: '刷新看看'
      })
      this.loadDetail()
      if (this.data.isOwner) this.loadApplications()
      return
    }
    if (this.explainGateError(message)) return
    popup.modal(this, { title: fallback, message, showCancel: false, confirmText: '知道了' })
  },

  /** 承接方：看自己的履约单（金额与账单都在履约页里，那里才有工时） */
  openContract() {
    const contract = this.data.myContract
    if (!contract || !contract.id) return
    wx.navigateTo({ url: `/pages/job-mine/job-mine?contractId=${contract.id}` })
  },

  openMine() { wx.navigateTo({ url: '/pages/job-mine/job-mine' }) },
  retry() { this.loadDetail() },

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
