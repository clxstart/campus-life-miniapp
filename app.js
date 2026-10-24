const api = require('./utils/api')

App({
  globalData: {
    schoolName: '内蒙古大学',
    userId: 1,
    loginReady: false,
    socket: null,
    socketCircleId: null,
    socketListeners: [],
    socketState: 'closed'
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
  },

  // 圈子群聊统一连接：页面只订阅事件，不各自创建 WebSocket。
  connectCircleChat(circleId) {
    circleId = Number(circleId)
    if (!Number.isSafeInteger(circleId) || circleId < 1) return Promise.reject(new Error('圈子参数无效'))
    const current = this.globalData.socket
    if (current && this.globalData.socketCircleId === circleId && ['open', 'connecting'].includes(this.globalData.socketState)) return this._socketOpenPromise || Promise.resolve()
    this.closeCircleChat()
    this._desiredCircleId = circleId
    this._manualSocketClose = false
    this._socketRetryCount = 0
    return this._openCircleSocket(circleId)
  },

  _openCircleSocket(circleId) {
    if (this.globalData.socket && this.globalData.socketCircleId === circleId && ['open', 'connecting'].includes(this.globalData.socketState)) {
      return this._socketOpenPromise || Promise.resolve()
    }
    const token = api.getToken()
    const base = (api.BASE_URL || 'http://127.0.0.1:8080/api').replace(/\/api\/?$/, '')
    const scheme = base.indexOf('https://') === 0 ? 'wss://' : 'ws://'
    const host = base.replace(/^https?:\/\//, '')
    const url = `${scheme}${host}/ws/circle?token=${encodeURIComponent(token)}&circleId=${encodeURIComponent(circleId)}`
    const socket = wx.connectSocket({ url })
    this.globalData.socket = socket
    this.globalData.socketCircleId = circleId
    this.globalData.socketState = 'connecting'
    let settled = false
    this._socketOpenPromise = new Promise((resolve, reject) => {
      socket.onOpen(() => {
        if (this.globalData.socket !== socket) return
        settled = true
        this.globalData.socketState = 'open'
        clearTimeout(this._socketStableTimer)
        this._socketStableTimer = setTimeout(() => { this._socketRetryCount = 0 }, 60000)
        this.startHeartbeat()
        this._notifyCircle({ type: 'socket', data: { event: 'open', circleId } })
        resolve()
      })
      socket.onMessage((res) => {
        if (this.globalData.socket !== socket) return
        let message
        try { message = typeof res.data === 'string' ? JSON.parse(res.data) : res.data } catch (error) { return }
        if (message && message.type === 'ping') {
          try { socket.send({ data: JSON.stringify({ type: 'pong' }) }) } catch (error) {}
          return
        }
        this._notifyCircle(message)
      })
      socket.onError((error) => {
        if (this.globalData.socket !== socket) return
        if (!settled) { settled = true; reject(error || new Error('群聊连接失败')) }
        this.stopHeartbeat()
      })
      socket.onClose(() => {
        if (this.globalData.socket !== socket) return
        this.stopHeartbeat()
        this.globalData.socket = null
        this.globalData.socketCircleId = null
        this.globalData.socketState = 'closed'
        this._notifyCircle({ type: 'socket', data: { event: 'close', circleId } })
        if (!settled) { settled = true; reject(new Error('群聊连接已关闭')) }
        if (!this._manualSocketClose && this._desiredCircleId === circleId) {
          this._socketRetryCount = (this._socketRetryCount || 0) + 1
          if (this._socketRetryCount <= 5) setTimeout(() => {
            if (!this._manualSocketClose && this._desiredCircleId === circleId) this._openCircleSocket(circleId).catch(() => {})
          }, 3000)
        }
      })
    })
    return this._socketOpenPromise
  },

  closeCircleChat() {
    this._manualSocketClose = true
    this._desiredCircleId = null
    clearTimeout(this._socketStableTimer)
    this.stopHeartbeat()
    const socket = this.globalData.socket
    this.globalData.socket = null
    this.globalData.socketCircleId = null
    this.globalData.socketState = 'closed'
    if (socket) { try { socket.close() } catch (error) {} }
  },

  startHeartbeat() {
    this.stopHeartbeat()
    this._heartbeat = setInterval(() => {
      const socket = this.globalData.socket
      if (socket && this.globalData.socketState === 'open') {
        try { socket.send({ data: JSON.stringify({ type: 'pong' }) }) } catch (error) {}
      }
    }, 25000)
  },

  stopHeartbeat() {
    if (this._heartbeat) { clearInterval(this._heartbeat); this._heartbeat = null }
  },

  _notifyCircle(message) {
    const listeners = this.globalData.socketListeners.slice()
    listeners.forEach(listener => { try { listener(message) } catch (error) {} })
  },

  onCircleMessage(listener) {
    if (typeof listener !== 'function') return () => {}
    this.globalData.socketListeners.push(listener)
    return () => {
      const index = this.globalData.socketListeners.indexOf(listener)
      if (index >= 0) this.globalData.socketListeners.splice(index, 1)
    }
  }
})
