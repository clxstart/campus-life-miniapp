const api = require('../../utils/api')

Page({
  onLoad() { this._alive = true },
  onUnload() { this._alive = false },
  data: { retrying: false },

  async retry() {
    if (this.data.retrying) return
    this.setData({ retrying: true })
    try {
      // 探一个最轻的公开接口：能通说明后端回来了
      await api.getSchools()
      wx.reLaunch({ url: '/pages/index/index' })
    } catch (error) {
      wx.showToast({ title: '还没恢复，请稍等一会儿', icon: 'none' })
    } finally {
      this.setData({ retrying: false })
    }
  }
})
