const api = require('../../utils/api')
const { GOODS_CATEGORIES, GOODS_CONDITIONS } = require('../../utils/constants')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')

Page({
  data: { ...getNavigationMetrics(), images: [], title: '', price: '', conditionLevels: GOODS_CONDITIONS, conditionLevel: '九成新', categories: GOODS_CATEGORIES, category: '其他', description: '', submitting: false, uiPopup: null },
  chooseImage() {
    const remaining = 6 - this.data.images.length
    if (remaining <= 0) return
    wx.chooseMedia({ count: remaining, mediaType: ['image'], sourceType: ['album', 'camera'], success: res => { const added = (res.tempFiles || []).map(file => file.tempFilePath); this.setData({ images: this.data.images.concat(added).slice(0, 6) }) }, fail: error => { if (error.errMsg && !error.errMsg.includes('cancel')) popup.toast(this, '图片选择失败', { tone: 'error' }) } })
  },
  removeMedia(e) { const index = Number(e.currentTarget.dataset.index); this.setData({ images: this.data.images.filter((_, i) => i !== index) }) },
  // 上传图片：失败就中止发布，绝不把本地临时路径当图片地址提交
  async uploadMedia(media) {
    if (!media || !media.length) return []
    return await api.uploadImages(media)
  },
  inputTitle(e) { this.setData({ title: e.detail.value }) },
  inputPrice(e) { this.setData({ price: e.detail.value }) },
  inputDescription(e) { this.setData({ description: e.detail.value }) },
  chooseCondition(e) { this.setData({ conditionLevel: e.currentTarget.dataset.value }) },
  chooseCategory(e) { this.setData({ category: e.currentTarget.dataset.value }) },
  async submit() {
    if (this.data.submitting) return
    const title = this.data.title.trim()
    const price = this.data.price.trim()
    const description = this.data.description.trim()
    if (!this.data.images.length) return popup.toast(this, '请先添加至少一张图片', { tone: 'warn' })
    if (!title || title.length > 28) return popup.toast(this, '标题请填写1至28个字', { tone: 'warn' })
    if (!price || !/^\d+(\.\d{1,2})?$/.test(price) || Number(price) < 0 || Number(price) > 99999) return popup.toast(this, '价格请填写0至99999的数字', { tone: 'warn' })
    if (description.length > 200) return popup.toast(this, '描述不能超过200字', { tone: 'warn' })
    this.setData({ submitting: true })
    try {
      // 必须先上传，拿到服务端地址再提交
      const uploaded = await this.uploadMedia(this.data.images)
      if (!uploaded.length) throw new Error('图片上传失败，请重试')
      const result = await api.publishGoods({ userId: api.CURRENT_USER_ID, title, price, conditionLevel: this.data.conditionLevel, category: this.data.category, images: uploaded, image: uploaded[0], description })
      popup.toast(this, result.offline ? '已保存到本机演示，未发布到后端' : '发布成功', { tone: result.offline ? 'warn' : 'success' })
      setTimeout(() => wx.navigateBack(), 550)
    } catch (error) {
      this.setData({ submitting: false })
      popup.toast(this, error.message || '发布失败', { tone: 'error' })
    }
  },
  goBack() { wx.navigateBack() },
  onPopupConfirm(e) { this._popupConfirm && this._popupConfirm({ detail: e.detail }) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(e) { this._popupAction && this._popupAction({ detail: e.detail }) },
  onPopupInput(e) { this._popupInput && this._popupInput({ detail: e.detail }) },
  onPopupClose() { popup.hide(this) }
})
