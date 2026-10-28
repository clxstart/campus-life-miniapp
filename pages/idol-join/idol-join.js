const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')
const api = require('../../utils/api')

Page({
  data: {
    ...getNavigationMetrics(),
    gender: 2,
    photo: '',
    name: '',
    college: '',
    slogan: '',
    submitting: false,
    uiPopup: null
  },

  onLoad(query) { this._alive = true;
    const gender = Number(query.gender)
    if (gender === 1 || gender === 2) this.setData({ gender })
  },
  onUnload() { this._alive = false },

  setGender(event) {
    this.setData({ gender: Number(event.currentTarget.dataset.gender) })
  },

  choosePhoto() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: ['album', 'camera'],
      success: ({ tempFiles }) => {
        if (tempFiles && tempFiles[0]) this.setData({ photo: tempFiles[0].tempFilePath })
      }
    })
  },

  inputName(event) { this.setData({ name: event.detail.value }) },
  inputCollege(event) { this.setData({ college: event.detail.value }) },
  inputSlogan(event) { this.setData({ slogan: event.detail.value }) },

  async submit() {
    const { photo, name, college, slogan, gender, submitting } = this.data
    if (submitting) return
    if (!photo) { popup.toast(this, '先上传一张照片', { tone: 'warn' }); return }
    if (!name.trim()) { popup.toast(this, '填写展示昵称', { tone: 'warn' }); return }

    this.setData({ submitting: true })
    try {
      await api.joinIdol({ name: name.trim(), gender, college: college.trim(), photo, slogan: slogan.trim() })
      popup.toast(this, '报名成功，已进入展示队列', { tone: 'success' })
      setTimeout(() => wx.navigateBack(), 650)
    } catch (error) {
      this.setData({ submitting: false })
      popup.toast(this, error.message || '报名失败，稍后再试', { tone: 'error' })
    }
  },

  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupClose() { popup.hide(this) },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) { wx.navigateBack() } else { wx.reLaunch({ url: '/pages/activity-idol/activity-idol' }) }
  }
})
