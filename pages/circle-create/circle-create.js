/**
 * 创建圈子（V3.1 新页 · 黄金段位特权，设计稿 §2.3）
 *
 * 三段结构：
 *   加载中（skeleton）/ 无权限（只读说明卡 + 去积分页）/ 表单（名称 + 分类 + 简介 + 封面 + 提交审核）
 *
 * 权限口径：GET /api/points/me 返回的 privileges（circleCreate / effectiveTierCode）。
 *   段位不足  → 「创建圈子需要黄金段位（成长值 200）」
 *   开关关闭  → 「圈子创建暂未开放」
 * 名额口径：后端没有「我已创建的圈子」查询接口，前端只能用本机标记做入口灰显；
 *   真正的上限校验仍在 POST /api/circles（403「最多创建 1 个圈子」），前端不假装成功。
 *
 * 写接口一律不兜底：上传封面失败就中止提交，避免把「没封面的圈子」偷偷建出来。
 */
const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
// 分类必须落在后端 CircleService.USER_CATEGORIES 白名单内（兴趣/学习/生活/游戏/运动），
// 这里的常量与后端白名单一致；GET /circles/categories 只返回「已有圈子的分类」，
// 会漏掉暂时没圈子的可建分类，所以不用它做下拉源。
const { CIRCLE_CATEGORIES_FALLBACK } = require('../../utils/constants')
const popup = require('../../utils/ui-popup-helper')

const NAME_MAX = 16
const DESC_MAX = 64
const COVER_MAX_BYTES = 2 * 1024 * 1024
const TIER_RANK = { bronze: 1, silver: 2, gold: 3 }
// 本机「已建 1 个」标记：与 discover 页共用同一 key。
// 只用于入口灰显，不在提交前硬拦——名额以服务端 403 为准，避免缓存过期后把能建的人挡住。
const QUOTA_KEY = 'circle_created_v1'

function markQuotaHit() {
  try { wx.setStorageSync(QUOTA_KEY, true) } catch (error) {}
}

Page({
  data: {
    ...getNavigationMetrics(),
    loading: true,
    error: '',
    offline: false,

    // 权限闸门
    allowed: false,
    gateTitle: '',
    gateHint: '',
    gateActionText: '去积分页',
    gateToPoints: true,

    // 表单
    categories: CIRCLE_CATEGORIES_FALLBACK.slice(),
    categoryIndex: 0,
    categoryText: '',
    form: { name: '', description: '' },
    nameMax: NAME_MAX,
    descMax: DESC_MAX,
    errors: {},
    coverTemp: '',
    coverUrl: '',
    submitting: false,

    uiPopup: null
  },

  onLoad() {
    this._alive = true
    this.loadStatus()
  },

  onUnload() { this._alive = false },

  onPullDownRefresh() {
    this.loadStatus().finally(() => wx.stopPullDownRefresh())
  },

  /** 拉段位与特权，决定渲染表单还是说明卡 */
  async loadStatus() {
    if (!this._alive) return
    this.setData({ loading: !this._loaded, error: '' })
    try {
      const me = await api.getPointsMe()
      if (!this._alive) return
      const privileges = (me && me.privileges) || {}
      const tier = (me && me.tier) || {}
      const effective = privileges.effectiveTierCode || tier.code || ''
      const rank = TIER_RANK[effective] || 0
      const gold = rank >= TIER_RANK.gold
      const canCreate = !!privileges.circleCreate
      // 显示用的成长值口径：后端 tier.gTotal 是真实值，缺失时退回 0
      const gTotal = Number(tier.gTotal) || 0
      const tierName = tier.name || (gold ? '黄金·活跃分子' : '青铜·萌新')

      this._loaded = true
      if (canCreate) {
        this.setData({ loading: false, offline: !!me.offline, allowed: true, error: '' })
        return
      }
      if (gold) {
        // 黄金但 circleCreate=false：只剩「开关关闭」一种可能（privileges 已把开关算进去）
        this.setData({
          loading: false,
          offline: !!me.offline,
          allowed: false,
          gateTitle: '圈子创建暂未开放',
          gateHint: '平台暂停了用户建圈，开放后会通过站内消息通知你。已创建的圈子不受影响。',
          gateActionText: '返回圈子',
          gateToPoints: false,
          error: ''
        })
        return
      }
      this.setData({
        loading: false,
        offline: !!me.offline,
        allowed: false,
        gateTitle: '创建圈子需要黄金段位',
        gateHint: `当前 ${tierName}（成长值 ${gTotal}），黄金段位（成长值 200）解锁创建圈子。做任务攒成长值就能升级。`,
        gateActionText: '去积分页赚成长值',
        gateToPoints: true,
        error: ''
      })
    } catch (error) {
      if (!this._alive) return
      this.setData({ loading: false, error: (error && error.message) || '段位信息没加载出来' })
    }
  },

  // ==================== 表单 ====================

  inputName(event) {
    this.setData({ 'form.name': event.detail.value, 'errors.name': '' })
  },

  inputDesc(event) {
    this.setData({ 'form.description': event.detail.value, 'errors.description': '' })
  },

  chooseCategory(event) {
    const index = Number(event.detail.value)
    const category = this.data.categories[index]
    if (!category) return
    this.setData({ categoryIndex: index, categoryText: category, 'errors.category': '' })
  },

  chooseCover() {
    if (this.data.submitting) return
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: ({ tempFiles }) => {
        const file = (tempFiles || [])[0]
        if (!file) return
        if (Number(file.size) > COVER_MAX_BYTES) {
          popup.toast(this, '封面图不能超过 2MB', { tone: 'warn' })
          return
        }
        this.setData({ coverTemp: file.tempFilePath, coverUrl: '' })
      },
      fail: (error) => {
        if (error && error.errMsg && !error.errMsg.includes('cancel')) {
          popup.toast(this, '图片选择失败，请重试', { tone: 'error' })
        }
      }
    })
  },

  removeCover() {
    if (this.data.submitting) return
    this.setData({ coverTemp: '', coverUrl: '' })
  },

  /** 与后端 POST /api/circles 的校验口径一致；前端只做即时提示，最终以后端为准 */
  validate() {
    const name = String(this.data.form.name || '').trim()
    const description = String(this.data.form.description || '').trim()
    const errors = {}
    if (!name) errors.name = '请填写圈子名称'
    else if (name.length > NAME_MAX) errors.name = `圈子名称最多 ${NAME_MAX} 个字`
    if (!this.data.categoryText) errors.category = '请选择圈子分类'
    if (description.length > DESC_MAX) errors.description = `简介最多 ${DESC_MAX} 个字`
    this.setData({ errors })
    return { ok: Object.keys(errors).length === 0, name, description, errors }
  },

  async submit() {
    if (this.data.submitting) return
    if (!this.data.allowed) return
    if (this.data.offline) {
      popup.toast(this, '当前离线，连上网络后再提交', { tone: 'info' })
      return
    }
    const checked = this.validate()
    if (!checked.ok) {
      popup.toast(this, Object.values(checked.errors)[0], { tone: 'warn' })
      return
    }

    this.setData({ submitting: true })
    try {
      // 封面可选：选了就必须上传成功，否则中止（不能把本地临时路径或空封面静默落库）
      let cover = this.data.coverUrl
      if (this.data.coverTemp) {
        const uploaded = await api.uploadImages([this.data.coverTemp])
        cover = (uploaded && uploaded[0]) || ''
        if (!cover) throw new Error('封面图上传失败，请重试')
        if (this._alive) this.setData({ coverUrl: cover })
      }
      await api.createCircle({
        name: checked.name,
        category: this.data.categoryText,
        description: checked.description,
        cover
      })
      if (!this._alive) return
      markQuotaHit()
      this.setData({ submitting: false })
      popup.toast(this, '创建成功，待审核通过后展示', { tone: 'success' })
      setTimeout(() => {
        if (!this._alive) return
        const pages = getCurrentPages()
        if (pages.length > 1) wx.navigateBack()
        else wx.redirectTo({ url: '/pages/discover/discover' })
      }, 900)
    } catch (error) {
      if (!this._alive) return
      this.setData({ submitting: false })
      // 失败提示原样展示后端 message（如「最多创建 1 个圈子」「内容包含违规词」）
      const message = (error && error.message) || '创建失败，请稍后重试'
      // 名额用满：记住本机标记，下次入口直接灰显；服务端仍是最终裁判
      if (message.indexOf('最多创建') >= 0) markQuotaHit()
      popup.toast(this, message, { tone: 'error' })
    }
  },

  // ==================== 无权限态 ====================

  openGateAction() {
    if (this.data.gateToPoints) {
      wx.navigateTo({ url: '/pages/points/points' })
      return
    }
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.redirectTo({ url: '/pages/discover/discover' })
  },

  retry() { this.loadStatus() },

  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})