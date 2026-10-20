const { getNavigationMetrics } = require('../../utils/navigation')
Page({
  data: { ...getNavigationMetrics() },
  openMarket() { wx.navigateTo({ url: '/pages/market-list/market-list?category=' + encodeURIComponent('数码') }) },
  goBack() { if (getCurrentPages().length > 1) wx.navigateBack(); else wx.reLaunch({ url: '/pages/index/index' }) }
})
