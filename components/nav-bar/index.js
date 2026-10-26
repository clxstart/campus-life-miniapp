/**
 * nav-bar —— 自定义返回导航栏
 *
 * 为什么需要：项目所有页面都设置了 navigationStyle: custom（为了自定义顶部），
 * 但自定义后微信不再提供原生返回按钮，必须自己补一个，否则用户会被困在页面里。
 *
 * 用法：
 *   <nav-bar title="小橘" back="/pages/campus-home/campus-home" />
 *
 * back 指定返回目标：
 *   - 传路径：优先 navigateBack；若无上一页则 redirectTo 该路径
 *   - 不传：等同 navigateBack；仍无上一页则回首页
 */
Component({
  options: { addGlobalClass: true },

  properties: {
    title: { type: String, value: '' },
    /** 无上一页时的兜底路径 */
    back: { type: String, value: '/pages/campus-home/campus-home' },
    /** 是否插入占位块（页面用 flex 布局时可能不需要） */
    placeholder: { type: Boolean, value: true },
    /** 返回图标（默认 ‹） */
    icon: { type: String, value: '‹' }
  },

  data: {
    statusBarHeight: 20,
    navHeight: 44,
    capsuleWidth: 88
  },

  lifetimes: {
    attached() {
      let statusBarHeight = 20
      let navHeight = 44
      let capsuleWidth = 88
      try {
        const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
        statusBarHeight = info.statusBarHeight || 20
        // 导航内容区高度：与微信胶囊大致对齐
        navHeight = 44
        if (wx.getMenuButtonBoundingClientRect) {
          const rect = wx.getMenuButtonBoundingClientRect()
          if (rect && rect.width) {
            capsuleWidth = rect.width + 12
            // 胶囊高度 + 上下间距，得出内容区高度
            navHeight = (rect.top - statusBarHeight) * 2 + rect.height
          }
        }
      } catch (e) {}
      this.setData({ statusBarHeight, navHeight, capsuleWidth })
    }
  },

  methods: {
    onBack() {
      const pages = getCurrentPages()
      if (pages.length > 1) {
        wx.navigateBack({ delta: 1 })
        return
      }
      // 没有上一页（例如直接从分享/编译进入）→ 去兜底页
      const target = this.data.back
      if (target) {
        wx.redirectTo({
          url: target,
          fail: () => wx.reLaunch({ url: target })
        })
      }
    }
  }
})
