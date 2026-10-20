function getNavigationMetrics() {
  try {
    const windowInfo = wx.getWindowInfo
      ? wx.getWindowInfo()
      : wx.getSystemInfoSync()
    const menuRect = wx.getMenuButtonBoundingClientRect
      ? wx.getMenuButtonBoundingClientRect()
      : null
    const statusBarHeight = windowInfo.statusBarHeight || 20
    let navBarHeight = 44
    let capsuleReservedRight = 16

    if (menuRect && menuRect.height && menuRect.top) {
      navBarHeight = (menuRect.top - statusBarHeight) * 2 + menuRect.height
      capsuleReservedRight = Math.max(16, windowInfo.windowWidth - menuRect.left + 8)
    }

    return { statusBarHeight, navBarHeight, capsuleReservedRight }
  } catch (error) {
    return { statusBarHeight: 20, navBarHeight: 44, capsuleReservedRight: 16 }
  }
}

module.exports = { getNavigationMetrics }
