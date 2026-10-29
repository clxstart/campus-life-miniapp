const api = require('../../utils/api')

/**
 * 未读数缓存（模块级，所有 bottom-nav 实例共享）
 *
 * 5 个 tab 页各挂一个 bottom-nav，每次切页 show 都会触发 refreshUnread()，
 * 各请求 conversations + notifications 两个接口。
 * 来回切 tab 会产生大量重复请求。
 *
 * 这里做两件事：
 *   1) TTL 内直接复用上次结果
 *   2) 同一时刻的并发调用共享同一个 Promise（_pending）
 * 需要立即刷新时（如刚读完消息）可传 force=true。
 */
const TTL = 10000
let _cache = null
let _cacheAt = 0
let _pending = null

Component({
  // 允许 app.wxss 的全局类（如 hover-class 用的 .tap）作用于本组件
  options: { addGlobalClass: true },
  properties: {
    active: {
      type: String,
      value: 'home'
    },
    unread: {
      type: Number,
      value: 0
    }
  },

  lifetimes: { attached() { this.refreshUnread() } },
  pageLifetimes: { show() { this.refreshUnread() } },
  methods: {
    /**
     * 刷新未读数
     * @param {boolean} force 忽略缓存强制刷新（刚读完消息等场景）
     */
    async refreshUnread(force) {
      const now = Date.now()
      // 缓存命中：直接用上次结果，不再请求
      if (!force && _cache != null && now - _cacheAt < TTL) {
        if (this.data.unread !== _cache) this.setData({ unread: _cache })
        return
      }
      // 已有请求在路上：等多个实例共享同一次请求
      if (_pending) {
        const shared = await _pending
        if (shared != null && this.data.unread !== shared) this.setData({ unread: shared })
        return
      }

      _pending = (async () => {
        try {
          const [conversations, notifications] = await Promise.all([
            api.getConversations(),
            api.getNotifications()
          ])
          const unread = conversations.reduce((sum, row) => sum + (row.muted ? 0 : Number(row.unread) || 0), 0) +
            notifications.filter(row => row.unread).length
          _cache = unread
          _cacheAt = Date.now()
          return unread
        } catch (error) {
          console.warn('[未读消息]', error.message || '读取失败')
          return null
        } finally {
          _pending = null
        }
      })()

      const unread = await _pending
      if (unread != null && this.data.unread !== unread) this.setData({ unread })
    },
    navigate(event) {
      const key = event.currentTarget.dataset.key
      if (!key || key === this.data.active) return

      const routes = {
        home: '/pages/index/index',
        discover: '/pages/discover/discover',
        publish: '/pages/publish/publish',
        messages: '/pages/messages/messages',
        profile: '/pages/profile/profile'
      }

      wx.redirectTo({ url: routes[key] })
    }
  }
})
