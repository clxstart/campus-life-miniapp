const { getNavigationMetrics } = require('../../utils/navigation')
const { assetMap } = require('../../utils/remote-assets')
Page({
  // 广告位插画：wxml 调不了函数，先在 data 里解析（utils/remote-assets.js）
  data: { ...getNavigationMetrics(), art: assetMap({ laptop: '/assets/home/laptop-silver.webp' }) },
  openMarket() { wx.navigateTo({ url: '/pages/market-list/market-list?category=' + encodeURIComponent('数码') }) },
  goBack() { if (getCurrentPages().length > 1) wx.navigateBack(); else wx.reLaunch({ url: '/pages/index/index' }) }
})
