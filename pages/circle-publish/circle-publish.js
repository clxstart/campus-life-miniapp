const api = require('../../utils/api')
const school = require('../../utils/school')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')

Page({
  data: {
    ...getNavigationMetrics(),
    circleId: 0,
    circle: null,
    title: '',
    content: '',
    media: [],
    location: '',
    visibleToSchool: true,
    submitting: false,
    error: '',
    uiPopup: null
  },

  onLoad(options) {
    this._alive = true
    const id = Number(options.id)
    if (!Number.isSafeInteger(id) || id < 1) {
      this.setData({ error: '圈子不存在' })
      return
    }
    this.setData({ circleId: id })
    this.loadCircle()
  },

  onUnload() { this._alive = false },

  async loadCircle() {
    try {
      const circle = await api.getCircle(this.data.circleId)
      if (!this._alive) return
      this.setData({ circle })
    } catch (error) {
      if (this._alive) this.setData({ error: error.message || '圈子加载失败' })
    }
  },

  inputTitle(event) { this.setData({ title: event.detail.value }) },
  inputContent(event) { this.setData({ content: event.detail.value }) },

  chooseMedia() {
    const remaining = 6 - this.data.media.length
    if (remaining <= 0) return
    wx.chooseMedia({
      count: remaining,
      mediaType: ['image'],
      sourceType: ['album', 'camera'],
      success: ({ tempFiles }) => {
        this.setData({ media: this.data.media.concat(tempFiles.map((file) => file.tempFilePath)) })
      },
      fail: (error) => {
        if (error.errMsg && !error.errMsg.includes('cancel')) popup.toast(this, '图片选择失败', { tone: 'error' })
      }
    })
  },

  removeMedia(event) {
    const index = Number(event.currentTarget.dataset.index)
    this.setData({ media: this.data.media.filter((_, i) => i !== index) })
  },

  // 位置选项与发布页保持一致，避免两个发帖入口体验割裂
  // 地点按当前选中学校拉取；拉不到时 school.js 会回落到通用地点
  async chooseLocation() {
    const locations = await school.getSelectedLocations()
    if (!this._alive) return
    if (!locations.length) { popup.toast(this, '暂时没有可选地点', { tone: 'warn' }); return }
    popup.sheet(this, {
      title: '选择地点',
      actions: locations.map((label) => ({ label })),
      action: ({ index }) => this.setData({ location: locations[index] })
    })
  },

  toggleVisible(event) { this.setData({ visibleToSchool: event.detail.value }) },

  async submit() {
    if (this.data.submitting) return
    const { title, content, media, circleId } = this.data
    if (!circleId || !this.data.circle) { popup.toast(this, '圈子信息缺失，请返回重进', { tone: 'error' }); return }
    if (!title.trim()) { popup.toast(this, '请填写标题', { tone: 'warn' }); return }
    if (!content.trim()) { popup.toast(this, '写一点内容再发布吧', { tone: 'warn' }); return }

    this.setData({ submitting: true })
    try {
      // 先把本地临时图片上传成持久 URL；上传失败则不带图发布，不阻断流程
      let uploaded = []
      if (media.length) {
        try {
          uploaded = await api.uploadImages(media)
        } catch (uploadError) {
          uploaded = []
          popup.toast(this, uploadError.message || '图片上传失败，将不带图发布', { tone: 'warn' })
        }
      }
      await api.publishCirclePost({
        title: title.trim(),
        content: content.trim(),
        media: uploaded.length ? uploaded : media,
        circleId,
        location: this.data.location,
        typeLabel: this.data.circle.category || '校园日常'
      })
      popup.toast(this, '发布成功', { tone: 'success' })
      // 回到圈子详情，让其重新拉取帖子与计数
      setTimeout(() => {
        const pages = getCurrentPages()
        if (pages.length > 1) wx.navigateBack()
        else wx.redirectTo({ url: `/pages/circle-detail/circle-detail?id=${circleId}` })
      }, 700)
    } catch (error) {
      popup.toast(this, error.message || '发布失败，请重试', { tone: 'error' })
    } finally {
      if (this._alive) this.setData({ submitting: false })
    }
  },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) { wx.navigateBack() } else { wx.reLaunch({ url: '/pages/discover/discover' }) }
  },

  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})
