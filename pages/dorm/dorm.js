/**
 * 我的宿舍（V3.1 新页）
 *
 * 一个页面两套结构（设计稿 2.1）：
 *   态 A 未加入 —— 说明卡 + 创建宿舍（需已认证）+ 用邀请码加入
 *   态 B 已加入 —— 宿舍卡（邀请码可复制）/ 今日成员头像格子 / 差人提示 / 全员达成条 / 本周榜 / 退出
 *
 * 接口来自 utils/api/points.js 的宿舍封装（GET /dorm/me、POST /dorm、POST /dorm/join、DELETE /dorm/leave）。
 * 读接口断网回落上次缓存（拿不到才显示未加入，并给 offline-banner + 按钮不可点）；
 * 写接口一律不兜底：建宿舍 / 加入 / 退出都要服务端确认，失败如实报错。
 */
const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')

function initialOf(name) {
  const text = String(name || '').trim()
  return text ? text.slice(0, 1) : '舍'
}

// 楼栋 + 房间：任一项缺失都不显示空分隔符
function placeText(building, room) {
  const parts = [String(building || '').trim(), String(room || '').trim()].filter(Boolean)
  return parts.length ? parts.join(' ') : '楼栋房间未填写'
}

Page({
  data: {
    ...getNavigationMetrics(),
    loading: true,
    error: '',
    offline: false,

    // 两态
    joined: false,
    dissolved: false,

    // 态 B 数据
    dorm: {},
    dormName: '',
    placeText: '',
    memberLimit: 8,
    memberText: '0 / 8',
    inviteCode: '',
    members: [],
    today: { signedCount: 0, memberCount: 0, allDone: false, gap: 0 },
    week: null,
    weekText: '',
    weekGapText: '',
    champion: false,

    // 态 A · 创建表单
    createVisible: false,
    createForm: { name: '', building: '', room: '' },
    creating: false,
    // 态 A · 加入失败的行内红字
    joinError: '',
    joinBusy: false,
    leaving: false,

    uiPopup: null
  },

  onLoad() {
    this._alive = true
    this.load()
  },

  // 从积分页签到后返回，本页要能看见刚打上的勾
  onShow() {
    if (this._loaded) this.load()
  },

  onUnload() { this._alive = false },

  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()) },

  async load() {
    if (!this._alive) return
    this.setData({ loading: !this._loaded, error: '' })
    try {
      const result = await api.getDormMe()
      if (!this._alive) return
      this.applyResult(result || {})
    } catch (error) {
      if (!this._alive) return
      this.setData({ loading: false, error: (error && error.message) || '宿舍信息没加载出来' })
    }
  },

  /** 把 /dorm/me（或创建 / 加入的返回）统一铺到 data */
  applyResult(result) {
    const joined = !!result.joined
    const dorm = result.dorm || {}
    const rawMembers = result.members || []
    const today = result.today || { signedCount: 0, memberCount: 0, allDone: false, gap: 0 }
    const week = result.week || null
    const memberLimit = Number(dorm.memberLimit) || 8
    const memberCount = Number(today.memberCount) || rawMembers.length

    this._loaded = true
    this.setData({
      loading: false,
      error: '',
      offline: !!result.offline,
      joined,
      dissolved: !!result.dissolved,
      dorm,
      dormName: dorm.name || '',
      placeText: joined ? placeText(dorm.building, dorm.room) : '',
      memberLimit,
      memberText: `${memberCount} / ${memberLimit}`,
      inviteCode: dorm.inviteCode || '',
      members: rawMembers.map(row => ({
        userId: row.userId,
        nickname: row.nickname || '舍友',
        avatar: row.avatar || '',
        initial: initialOf(row.nickname),
        roleText: row.role === 'leader' ? '舍长' : '成员',
        signedToday: !!row.signedToday
      })),
      today: {
        signedCount: Number(today.signedCount) || 0,
        memberCount,
        allDone: !!today.allDone,
        gap: Number(today.gap) || 0
      },
      week,
      champion: !!(week && week.champion),
      weekText: week
        ? (week.champion ? '本周宿舍冠军' : `本周第 ${Number(week.rank) || 0} 名 · ${Number(week.score) || 0} 分`)
        : '',
      weekGapText: week && !week.champion ? `距第一还差 ${Number(week.gap) || 0} 分` : ''
    })
    // 加入成功后清掉上次的行内错误（失败分支自己 setData，不在这里清）
    if (joined) this.setData({ joinError: '' })
  },

  // ==================== 态 A：创建 ====================

  /**
   * 打开创建表单前先看认证状态（后端也会拦；前端先拦是为了少一次白跑）。
   * 未认证 → 弹「去认证」，不弹失败提示就结束。
   */
  async openCreate() {
    if (this.data.offline) return popup.toast(this, '当前离线，连上网络后再试', { tone: 'info' })
    if (this.data.joinBusy || this.data.creating) return
    let verified = false
    try {
      const me = await api.getVerification()
      verified = !!(me && me.verified)
    } catch (error) {
      return popup.toast(this, (error && error.message) || '认证状态没查出来，请稍后重试', { tone: 'error' })
    }
    if (!verified) {
      return popup.modal(this, {
        title: '需要学生认证',
        message: '创建宿舍需要先完成学生认证，加入宿舍不受限制。',
        confirmText: '去认证',
        confirm: () => wx.navigateTo({ url: '/pages/verify/verify' })
      })
    }
    this.setData({ createVisible: true, joinError: '' })
  },

  inputCreate(event) {
    const field = event.currentTarget.dataset.field
    if (!field) return
    this.setData({ [`createForm.${field}`]: event.detail.value })
  },

  cancelCreate() {
    this.setData({ createVisible: false, createForm: { name: '', building: '', room: '' } })
  },

  async submitCreate() {
    if (this.data.creating) return
    const name = String(this.data.createForm.name || '').trim()
    const building = String(this.data.createForm.building || '').trim()
    const room = String(this.data.createForm.room || '').trim()
    if (!name) return popup.toast(this, '请填写宿舍名，如 6号楼312', { tone: 'warn' })
    if (name.length > 32) return popup.toast(this, '宿舍名最多 32 个字', { tone: 'warn' })
    if (building.length > 32) return popup.toast(this, '楼栋最多 32 个字', { tone: 'warn' })
    if (room.length > 16) return popup.toast(this, '房间号最多 16 个字', { tone: 'warn' })

    this.setData({ creating: true })
    try {
      const result = await api.createDorm({ name, building, room })
      if (!this._alive) return
      this.applyResult(result || {})
      this.setData({ createVisible: false, createForm: { name: '', building: '', room: '' }, creating: false })
      const code = (result && result.dorm && result.dorm.inviteCode) || ''
      popup.modal(this, {
        title: '宿舍已创建',
        message: `邀请码 ${code}，发给舍友就能加入这个宿舍。`,
        confirmText: '复制邀请码',
        cancelText: '稍后',
        confirm: () => this.copyInvite()
      })
    } catch (error) {
      if (!this._alive) return
      this.setData({ creating: false })
      popup.toast(this, (error && error.message) || '创建失败，请稍后重试', { tone: 'error' })
    }
  },

  // ==================== 态 A：邀请码加入 ====================

  openJoin() {
    if (this.data.offline) return popup.toast(this, '当前离线，连上网络后再试', { tone: 'info' })
    if (this.data.joinBusy) return
    this.setData({ joinError: '' })
    popup.modal(this, {
      title: '用邀请码加入',
      message: '输入舍友发给你的 6 位邀请码',
      editable: true,
      placeholder: '如 ABCD12',
      inputValue: '',
      confirmText: '加入',
      confirm: (res) => this.submitJoin((res && res.inputValue) || '')
    })
  },

  async submitJoin(input) {
    const code = String(input || '').trim().toUpperCase()
    if (code.length !== 6) {
      this.setData({ joinError: '邀请码无效，请确认后重试' })
      return
    }
    if (this.data.joinBusy) return
    this.setData({ joinBusy: true, joinError: '' })
    try {
      const result = await api.joinDorm(code)
      if (!this._alive) return
      this.applyResult(result || {})
      this.setData({ joinBusy: false })
      popup.toast(this, '已加入宿舍', { tone: 'success' })
    } catch (error) {
      if (!this._alive) return
      this.setData({ joinBusy: false, joinError: (error && error.message) || '加入失败，请稍后重试' })
    }
  },

  // ==================== 态 B：邀请码 / 退出 ====================

  copyInvite() {
    const code = this.data.inviteCode
    if (!code) return
    wx.setClipboardData({
      data: code,
      success: () => popup.toast(this, '邀请码已复制', { tone: 'success' })
    })
  },

  goPoints() { wx.navigateTo({ url: '/pages/points/points' }) },

  confirmLeave() {
    if (this.data.leaving) return
    popup.modal(this, {
      title: '退出宿舍',
      message: '退出后今天的全员奖励不会重发；如果你是舍长，需要先把宿舍交给其他成员。',
      confirmText: '退出',
      confirm: () => this.doLeave()
    })
  },

  async doLeave() {
    if (this.data.leaving) return
    this.setData({ leaving: true })
    try {
      await api.leaveDorm()
      if (!this._alive) return
      this.setData({ leaving: false })
      popup.toast(this, '已退出宿舍', { tone: 'success' })
      this.load()
    } catch (error) {
      if (!this._alive) return
      this.setData({ leaving: false })
      popup.toast(this, (error && error.message) || '退出失败，请稍后重试', { tone: 'error' })
    }
  },

  retry() { this.load() },

  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})