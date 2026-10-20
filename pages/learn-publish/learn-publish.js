const api = require('../../utils/api')
const school = require('../../utils/school')
const popup = require('../../utils/ui-popup-helper')
const { getNavigationMetrics } = require('../../utils/navigation')
const { categories, findCategory } = require('../../utils/learn')

Page({
  data: {
    ...getNavigationMetrics(), categories, category: 'daike', current: findCategory('daike'),
    schoolName: '', schoolId: null, title: '', content: '', media: [], location: '',
    submitting: false, uiPopup: null
  },
  onLoad(query = {}) {
    this._alive = true
    const current = findCategory(query.category || 'daike')
    this.setData({ ...getNavigationMetrics(), category: current.key, current,
      schoolId: school.getSelectedId(), schoolName: school.getSelectedName() })
  },
  onUnload() { this._alive = false; if (this._redirectTimer) clearTimeout(this._redirectTimer) },
  chooseCategory(event) {
    if (this.data.submitting) return
    const key = event.currentTarget.dataset.key
    if (categories.some(item => item.key === key)) this.setData({ category: key, current: findCategory(key) })
  },
  inputTitle(event) { this.setData({ title: event.detail.value }) },
  inputContent(event) { this.setData({ content: event.detail.value }) },
  chooseMedia() {
    if (this.data.submitting || this.data.media.length) return
    wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'],
      success: ({ tempFiles }) => {
        if (this._alive && tempFiles && tempFiles.length) this.setData({ media: [tempFiles[0].tempFilePath] })
      },
      fail: error => { if (this._alive && !String(error.errMsg).includes('cancel')) popup.toast(this, '图片选择失败，请重试', { tone: 'error' }) }
    })
  },
  removeMedia() { if (!this.data.submitting) this.setData({ media: [] }) },
  async chooseLocation() {
    if (this.data.submitting) return
    const locations = await school.getSelectedLocations()
    if (!this._alive) return
    popup.sheet(this, { title: '所在位置', actions: [{ label: '不显示位置' }].concat(locations.map(label => ({ label }))),
      action: ({ index }) => this.setData({ location: index === 0 ? '' : locations[index - 1] }) })
  },
  async submit() {
    if (this._submitting) return
    const title = this.data.title.trim(), content = this.data.content.trim()
    if (!title) { popup.toast(this, '先给分享起个标题吧', { tone: 'warn' }); return }
    if (title.length > 64) { popup.toast(this, '标题不能超过64字', { tone: 'warn' }); return }
    if (!content) { popup.toast(this, '写一点具体内容，更容易遇见同路人', { tone: 'warn' }); return }
    if (content.length > 3000) { popup.toast(this, '正文不能超过3000字', { tone: 'warn' }); return }
    // 发布前再读取选中学校，避免停留在编辑页期间选校状态已经发生变化。
    const schoolId = school.getSelectedId()
    if (!schoolId) { popup.toast(this, '请先回首页选择学校，再来分享', { tone: 'warn' }); return }
    const category = this.data.category
    this._submitting = true
    this.setData({ submitting: true, schoolId, schoolName: school.getSelectedName() })
    let published = false
    try {
      const uploaded = await api.uploadImages(this.data.media)
      if (!this._alive) return
      await api.publishPost({ title, content, learnCategory: category, schoolId,
        crossSchool: false, media: uploaded, location: this.data.location }, api.currentUserId())
      published = true
      if (!this._alive) return
      popup.toast(this, '分享成功，去看看你的新帖子', { tone: 'success' })
      this._redirectTimer = setTimeout(() => {
        if (!this._alive) return
        const pages = getCurrentPages(), previous = pages[pages.length - 2]
        if (previous && previous.route === 'pages/learn/learn') {
          previous.setData({ category, current: findCategory(category) })
          previous._needReload = true
          wx.navigateBack()
        } else wx.redirectTo({ url: `/pages/learn/learn?category=${category}` })
      }, 600)
    } catch (error) {
      if (this._alive) popup.toast(this, error.message || '发布失败，内容已保留，请重试', { tone: 'error' })
    } finally {
      // 成功后直到离页都锁住提交，避免弹层显示期间重复发布。
      if (!published) { this._submitting = false; if (this._alive) this.setData({ submitting: false }) }
    }
  },
  goBack() {
    if (this.data.submitting) return
    const leave = () => getCurrentPages().length > 1 ? wx.navigateBack() : wx.redirectTo({ url: `/pages/learn/learn?category=${this.data.category}` })
    if (this.data.title || this.data.content || this.data.media.length) {
      popup.modal(this, { title: '这份分享还没发布', message: '离开后，本次编辑的内容不会保存。', confirmText: '离开', cancelText: '继续编辑', confirm: leave })
    } else leave()
  },
  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})
