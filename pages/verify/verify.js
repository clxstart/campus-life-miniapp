/**
 * 学生认证（V3.1 新页）
 *
 * 一页四态：未提交（表单）/ 审核中 / 已认证 / 已驳回。
 * 首屏先拉 GET /api/verification/me 再决定渲染哪一态，避免「先露表单再切」的闪烁。
 * 表单字段按 GET /api/verification/config 的 require 动态校验，不写死四项必填。
 *
 * 隐私口径（与设计稿一致）：只收学校/学院/姓名/学号/手机号，不采集证件照片。
 */
const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { formatTime } = require('../../utils/format-time')
const popup = require('../../utils/ui-popup-helper')

const LABEL = {
  none: '未认证',
  pending: '审核中',
  verified: '已认证',
  rejected: '认证未通过'
}

// 默认配置：接口没回来时也先把表单渲染出来（校验仍以后端为准）
const DEFAULT_CONFIG = {
  enabled: true,
  require: { realName: true, studentNo: true, college: false, phone: false },
  studentNoPattern: '',
  reviewMode: 'manual',
  dailyLimit: 3
}

function isStatus(value) {
  return ['none', 'pending', 'verified', 'rejected'].includes(value)
}

// 必填项齐全才让提交按钮可点（与 validate 的必填口径一致）
function requiredFilled(form, require) {
  const need = (value) => String(value == null ? '' : value).trim().length > 0
  const must = require || {}
  if (!need(form.schoolId)) return false
  if (must.realName && !need(form.realName)) return false
  if (must.studentNo && !need(form.studentNo)) return false
  if (must.college && !need(form.college)) return false
  if (must.phone && !need(form.phone)) return false
  return true
}

Page({
  data: {
    ...getNavigationMetrics(),
    loading: true,
    error: '',
    config: DEFAULT_CONFIG,
    state: 'none',
    label: '未认证',
    me: null,
    schools: [],
    schoolNames: [],
    schoolIndex: 0,
    form: { schoolId: '', college: '', realName: '', studentNo: '', phone: '' },
    errors: {},
    submitting: false,
    // 开关关闭（verification.student.enabled=0）时表单整页换成说明卡
    closed: false,
    schoolText: '',
    submittedAtText: '',
    verifiedAtText: '',
    rejectReason: '',
    uiPopup: null
  },

  onLoad() {
    this._alive = true
    this.loadAll()
  },

  onUnload() { this._alive = false },

  onPullDownRefresh() { this.loadAll().finally(() => wx.stopPullDownRefresh()) },

  async loadAll() {
    this.setData({ loading: true, error: '' })
    const configRes = await api.getVerificationConfig().then(data => ({ data }), error => ({ error }))
    const meRes = await api.getVerification().then(data => ({ data }), error => ({ error }))

    if (!this._alive) return
    // 两个都拿不到才算页面失败；只挂一个时用默认配置 / 未提交态继续
    if (configRes.error && meRes.error) {
      this.setData({
        loading: false,
        offline: !(meRes.error && meRes.error.server),
        error: (meRes.error && meRes.error.message) || '认证状态没加载出来'
      })
      return
    }

    const config = { ...DEFAULT_CONFIG, ...(configRes.data || {}) }
    config.require = { ...DEFAULT_CONFIG.require, ...((configRes.data && configRes.data.require) || {}) }
    const me = meRes.data || null
    const state = this.deriveState(me)
    // 后端 label 是权威文案（未认证 / 认证审核中 / 已认证 / 认证未通过）；
    // 拿不到 /me 时才回落到本地四态文案
    const backendLabel = me && me.label ? me.label : ''
    const formData = {
      schoolId: (me && me.schoolId) || '',
      college: (me && me.college) || '',
      realName: (me && me.realName) || '',
      studentNo: (me && me.studentNo) || '',
      // 手机号不回填：/me 只下发脱敏值（138****1234），回填后再提交会被后端判成格式错误；
      // 重提时让用户重新输入明文，审核中/已认证态的脱敏值用 me.phone 只读展示。
      phone: ''
    }

    this.setData({
      config,
      me,
      state,
      label: backendLabel || LABEL[state] || LABEL.none,
      // 开关关闭：表单不渲染（已认证的人仍可看自己的认证信息）
      closed: config.enabled === false && state !== 'verified',
      loading: false,
      offline: false,
      error: '',
      form: formData,
      canSubmit: requiredFilled(formData, config.require),
      submittedAtText: formatTime(me && me.submittedAt),
      verifiedAtText: formatTime(me && me.verifiedAt),
      rejectReason: (me && me.rejectReason) || '',
      schoolText: (me && me.schoolName) || ''
    })
    // 学校列表学校名回显要用（/me 只给 schoolId，不给 schoolName），所以四种态都拉
    if (state === 'none' || state === 'rejected' || (me && me.schoolId)) this.loadSchools(me && me.schoolId)
  },

  // 四态推导：实名与学生都通过才算「已认证」（沿用后端 VerificationService 口径）
  deriveState(me) {
    if (!me) return 'none'
    const real = me.realNameStatus
    const student = me.studentStatus
    if (real === 'verified' && student === 'verified') return 'verified'
    if (real === 'pending' || student === 'pending') return 'pending'
    if (real === 'rejected' || student === 'rejected') return 'rejected'
    if (isStatus(me.status)) return me.status
    return 'none'
  },

  async loadSchools(schoolId) {
    try {
      const rows = await api.getSchools()
      if (!this._alive) return
      const list = Array.isArray(rows) ? rows : ((rows && rows.list) || [])
      const schools = list.map(row => ({ id: row.id, name: row.name }))
      const index = schools.findIndex(row => String(row.id) === String(schoolId || ''))
      const matched = index >= 0 ? schools[index] : null
      const patch = {
        schools,
        schoolNames: schools.map(row => row.name),
        schoolIndex: index >= 0 ? index : 0
      }
      if (matched) patch.schoolText = matched.name
      // 只有表单态才回填 form.schoolId；审核中/已认证是只读回显，不能被列表默认值改掉
      if (this.data.state === 'none' || this.data.state === 'rejected') {
        patch['form.schoolId'] = matched ? matched.id : (schools[0] ? schools[0].id : '')
        if (!matched && schools[0]) patch.schoolText = schools[0].name
      }
      this.setData(patch)
      this.refreshCanSubmit()
    } catch (error) {
      // 学校列表拉不到时保留已选学校，用户仍可提交；选择器给空说明
      if (this._alive) this.setData({ schools: [], schoolNames: [] })
    }
  },

  chooseSchool(event) {
    const index = Number(event.detail.value)
    const school = this.data.schools[index]
    if (!school) return
    this.setData({ schoolIndex: index, 'form.schoolId': school.id, schoolText: school.name, 'errors.school': '' })
    this.refreshCanSubmit()
  },

  inputField(event) {
    const field = event.currentTarget.dataset.field
    if (!field) return
    this.setData({ [`form.${field}`]: event.detail.value, [`errors.${field}`]: '' })
    this.refreshCanSubmit()
  },

  /** 按钮可用态跟着必填项实时变，不用等用户点了提交才知道缺项 */
  refreshCanSubmit() {
    this.setData({ canSubmit: requiredFilled(this.data.form, this.data.config.require) })
  },

  /** 校验口径与后端 POST /api/verification/apply 对齐（前端只做即时提示，最终以后端为准） */
  validate() {
    const { form, config } = this.data
    const require = config.require || {}
    const errors = {}
    const realName = String(form.realName || '').trim()
    const studentNo = String(form.studentNo || '').trim()
    const college = String(form.college || '').trim()
    const phone = String(form.phone || '').trim()

    if (!form.schoolId) errors.school = '请选择学校'
    if (require.realName && !/^[\u4e00-\u9fa5A-Za-z·]{2,20}$/.test(realName)) errors.realName = '请填写真实姓名'
    if (!require.realName && realName && !/^[\u4e00-\u9fa5A-Za-z·]{2,20}$/.test(realName)) errors.realName = '请填写真实姓名'
    if (require.studentNo && !/^[0-9A-Za-z]{4,20}$/.test(studentNo)) errors.studentNo = '学号格式不正确'
    if (!require.studentNo && studentNo && !/^[0-9A-Za-z]{4,20}$/.test(studentNo)) errors.studentNo = '学号格式不正确'
    if ((require.college || college) && (college.length < 2 || college.length > 32)) errors.college = '请填写学院'
    // 与后端一致：必填时空 →「请填写手机号」；填了但格式不对 →「手机号格式不正确」
    if (require.phone && !phone) errors.phone = '请填写手机号'
    else if (phone && !/^1[0-9]{10}$/.test(phone)) errors.phone = '手机号格式不正确'

    this.setData({ errors })
    return Object.keys(errors).length === 0
  },

  async submit() {
    if (this.data.submitting) return
    if (this.data.closed) return
    if (!this.validate()) return
    this.setData({ submitting: true })
    try {
      const { form } = this.data
      const me = await api.applyVerification({
        schoolId: form.schoolId,
        college: String(form.college || '').trim(),
        realName: String(form.realName || '').trim(),
        studentNo: String(form.studentNo || '').trim(),
        phone: String(form.phone || '').trim()
      })
      if (!this._alive) return
      // 成功就地切到「审核中」，不整页重载
      const nextMe = { ...(this.data.me || {}), ...(me || {}), realNameStatus: 'pending', studentStatus: 'pending' }
      this.setData({
        state: 'pending',
        label: (me && me.label) || LABEL.pending,
        me: nextMe,
        submitting: false,
        submittedAtText: formatTime(me && me.submittedAt)
      })
      popup.toast(this, '已提交，等待审核', { tone: 'success' })
    } catch (error) {
      if (!this._alive) return
      this.setData({ submitting: false })
      popup.toast(this, (error && error.message) || '提交失败，请稍后重试', { tone: 'error' })
    }
  },

  // 驳回后重新填写：回到表单态并带出上次填写值
  reapply() {
    this.setData({ state: 'none', label: LABEL.none, errors: {}, rejectReason: '' })
    this.refreshCanSubmit()
    if (!this.data.schoolNames.length) this.loadSchools(this.data.form.schoolId)
  },

  retry() { this.loadAll() },

  openPoints() { wx.navigateTo({ url: '/pages/points/points' }) },

  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})