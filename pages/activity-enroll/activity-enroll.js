const api = require('../../utils/api')
const popup = require('../../utils/ui-popup-helper')

Page({
  data: {
    activityId: 0,
    activity: null,
    loading: true,
    error: '',
    submitting: false,
    submitted: false,
    // 表单
    realName: '',
    studentNo: '',
    phone: '',
    remark: '',
    materials: [],      // 已上传成功的 url 列表
    maxFiles: 3,
    uploading: false
  },

  onLoad(query) {
    this._alive = true
    const id = Number(query && query.id)
    if (!Number.isSafeInteger(id) || id < 1) {
      this.setData({ loading: false, error: '活动参数无效' })
      return
    }
    this.setData({ activityId: id })
    this.loadActivity(id)
  },

  onUnload() { this._alive = false },

  async loadActivity(id) {
    try {
      // 从列表里取该活动的配置（复用已有接口，不额外增加接口）
      const list = await api.getActivities(api.currentUserId(), 'all')
      if (!this._alive) return
      const activity = (list || []).find(a => Number(a.id) === id)
      if (!activity) { this.setData({ loading: false, error: '活动不存在或已结束' }); return }
      this.setData({
        activity,
        maxFiles: Number(activity.enrollMaxFiles) || 3,
        loading: false
      })
    } catch (error) {
      if (this._alive) this.setData({ loading: false, error: error.message || '活动加载失败' })
    }
  },

  onInput(event) {
    const field = event.currentTarget.dataset.field
    if (!field) return
    this.setData({ [field]: event.detail.value })
  },

  // 选材料：目前只支持图片和视频。
  // 注意 wx.chooseMedia 不支持选音频文件，所以 mp3 伴奏传不了；
  // 需要支持 mp3/zip 等任意文件时要改用 wx.chooseMessageFile（从聊天记录选文件）。
  async chooseMaterial() {
    if (this.data.uploading) return
    const left = this.data.maxFiles - this.data.materials.length
    if (left <= 0) { popup.toast(this, `最多上传 ${this.data.maxFiles} 份材料`, { tone: 'warn' }); return }
    try {
      const res = await new Promise((resolve, reject) => {
        wx.chooseMedia({
          count: left,
          mediaType: ['image', 'video'],
          sourceType: ['album', 'camera'],
          success: resolve,
          fail: reject
        })
      })
      const files = (res.tempFiles || []).map(f => f.tempFilePath).filter(Boolean)
      if (!files.length) return
      this.setData({ uploading: true })
      // 逐个上传，任一失败就整体失败（避免出现半截材料）
      const urls = []
      for (const path of files) {
        const uploaded = await api.uploadImage(path)
        if (!uploaded || !uploaded.url) throw new Error('材料上传失败，请重试')
        urls.push(uploaded.url)
      }
      if (!this._alive) return
      this.setData({ materials: this.data.materials.concat(urls).slice(0, this.data.maxFiles) })
    } catch (error) {
      // 用户主动取消选择不算错误
      if (error && /cancel/i.test(String(error.errMsg || error.message || ''))) return
      if (this._alive) popup.toast(this, error.message || '材料上传失败', { tone: 'error' })
    } finally {
      if (this._alive) this.setData({ uploading: false })
    }
  },

  removeMaterial(event) {
    const index = Number(event.currentTarget.dataset.index)
    const materials = this.data.materials.slice()
    materials.splice(index, 1)
    this.setData({ materials })
  },

  previewMaterial(event) {
    const url = event.currentTarget.dataset.url
    if (url) wx.previewImage({ urls: [url], current: url })
  },

  async submit() {
    if (this.data.submitting || this.data.submitted) return
    const realName = String(this.data.realName || '').trim()
    const phone = String(this.data.phone || '').trim()
    if (!realName) { popup.toast(this, '请填写真实姓名', { tone: 'warn' }); return }
    if (!phone) { popup.toast(this, '请填写联系电话', { tone: 'warn' }); return }
    if (!this.data.materials.length) { popup.toast(this, '请至少上传 1 份材料', { tone: 'warn' }); return }

    this.setData({ submitting: true })
    try {
      await api.enrollActivityWithForm(this.data.activityId, {
        realName,
        studentNo: String(this.data.studentNo || '').trim(),
        phone,
        remark: String(this.data.remark || '').trim(),
        materials: this.data.materials
      })
      if (!this._alive) return
      this.setData({ submitted: true, submitting: false })
      popup.toast(this, '已提交，等待管理员审核', { tone: 'success' })
    } catch (error) {
      if (this._alive) {
        this.setData({ submitting: false })
        popup.toast(this, error.message || '提交失败', { tone: 'error' })
      }
    }
  },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.reLaunch({ url: '/pages/activities/activities' })
  }
})
