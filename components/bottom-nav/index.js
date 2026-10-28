const api = require('../../utils/api')

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
    async refreshUnread() {
      try {
        const [conversations, notifications] = await Promise.all([api.getConversations(), api.getNotifications()])
        const unread = conversations.reduce((sum, row) => sum + (row.muted ? 0 : Number(row.unread) || 0), 0) + notifications.filter(row => row.unread).length
        this.setData({ unread })
      } catch (error) { console.warn('[未读消息]', error.message || '读取失败') }
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
