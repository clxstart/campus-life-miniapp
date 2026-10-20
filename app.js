const api = require('./utils/api')

App({
  globalData: {
    schoolName: '内蒙古大学',
    userId: 1,
    loginReady: false
  },

  onLaunch() {
    this.globalData.userId = api.currentUserId()
    // 静默登录：失败也不阻断，页面继续用本地演示数据
    api.ensureLogin().then(res => {
      if (res && res.userId) {
        this.globalData.userId = res.userId
        this.globalData.loginReady = true
        if (typeof this.loginCallback === 'function') this.loginCallback(res)
      }
    }).catch(() => {})
  },

  // 页面可注册回调，在登录完成后刷新自身的用户相关数据
  onLoginReady(callback) {
    if (this.globalData.loginReady) return callback({ userId: this.globalData.userId })
    this.loginCallback = callback
  }
})
