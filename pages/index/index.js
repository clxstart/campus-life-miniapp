const app = getApp()
const popup = require('../../utils/ui-popup-helper')
const api = require('../../utils/api')
const school = require('../../utils/school')
const { assetMap } = require('../../utils/remote-assets')

// 新人礼包（功能 6 §2.1）：只弹一次的本地标记。
// 为什么用 storage 而不是后端字段：建号只发生一次，登录响应里的 newUser 就是权威信号；
// storage 只负责「同一台设备只弹一次」，换设备/清缓存后再弹一次也不会重复发奖（发奖靠 biz_key 幂等）。
const NEWBIE_GIFT_SHOWN = 'newbieGiftShown'

// key 与首页各入口的 data-label 精确一致。
// 今日推荐卡不走这里，它由 /recommend/home 返回的 url 直接跳转。
const FEATURE_ROUTES = {
  '校园论坛': { url: '/pages/community/community' },
  '外卖': { url: '/pages/food-list/food-list' },
  '二手市场': { url: '/pages/market-list/market-list' },
  '兼职': { url: '/pages/jobs/jobs' },
  '季节活动': { url: '/pages/activities/activities' },
  '作业答疑': { url: '/pages/learn/learn?category=daike' },
  '论文指导': { url: '/pages/learn/learn?category=paper' },
  '开学优惠': { url: '/pages/activities/activities' },
  '学习好物': { url: '/pages/market-list/market-list?category=' + encodeURIComponent('学习好物') },
  '联想小新校园优惠': { url: '/pages/campus-offer/campus-offer' }
}

Page({
  data: {
    schoolName: '内蒙古大学',
    schoolLogo: '',
    statusBarHeight: 20,
    navBarHeight: 44,
    capsuleReservedRight: 16,
    activeTab: 'home',
    // 首页插画：wxml 调不了函数，只能先在 data 里解析好（见 utils/remote-assets.js）
    art: assetMap({
      food: '/assets/home/food-bowl.webp',
      market: '/assets/home/secondhand-box.webp',
      parttime: '/assets/home/parttime-worker.webp',
      event: '/assets/home/spring-event.webp',
      laptop: '/assets/home/laptop-silver.webp'
    }),
    // 今日推荐：外卖最火 + 二手最火，数据来自 /recommend/home（真实热度）
    recommendations: [],
    recommendLoading: true,
    recommendBatch: 0,
    recommendHasMore: false,
    uiPopup: null
  },

  onLoad() {
    this._alive = true
    this.initNavigation()
    this.setData({
      schoolName: school.getSelectedName() || app.globalData.schoolName || '内蒙古大学'
    })
    this.syncSchoolLogo()
    // 首次进入必须先选学校，否则各列表不知道该看哪所学校的数据
    this.ensureSchool()
    // 上面三件事互不依赖，都是并行发起。
    // 学校列表在 utils/school.js 里做了「同一时刻单请求 + 5 分钟缓存」，
    // 所以 syncSchoolLogo 和 ensureSchool 不会再各打一次 /api/schools。
    this.loadRecommendations(0)
    // 登录是异步的，通常在 onLoad 之后才回来；登录完成后再判断要不要弹新人礼包
    app.onLoginReady(() => this.maybeShowNewbieGift())
  },

  onShow() { this.maybeShowNewbieGift() },

  /**
   * 新人礼包一次性提示（设计稿 §2.1）。
   *
   * 三个条件同时满足才弹：登录已完成、这次登录是首次建号（newUser）、本地没弹过。
   * 用 popup.modal（不是 toast，要给用户时间读完），文案与设计稿一致；
   * 不叠加学生认证引导（设计稿要求礼包优先）。
   */
  maybeShowNewbieGift() {
    if (!this._alive) return
    if (this._newbieGiftChecked) return
    if (!app.globalData.loginReady || !app.globalData.newUser) return
    let shown = false
    try { shown = !!wx.getStorageSync(NEWBIE_GIFT_SHOWN) } catch (error) {}
    if (shown) return

    this._newbieGiftChecked = true
    // 先落标记再弹：弹窗打开期间 onShow 可能再进一次，避免弹两遍
    try { wx.setStorageSync(NEWBIE_GIFT_SHOWN, 1) } catch (error) {}
    popup.modal(this, {
      title: '新人礼包已到账',
      message: '30 积分\n白银体验 7 天（快速应答）',
      tone: 'success',
      confirmText: '去看看',
      cancelText: '知道了',
      confirm: () => {
        wx.navigateTo({
          url: '/pages/points/points',
          fail: () => popup.toast(this, '积分页打开失败，请稍后再试', { tone: 'error' })
        })
      }
    })
  },

  // 学校 logo：管理端填了才显示（当前两所都为空，界面上不占位）
  async syncSchoolLogo() {
    const selected = school.getSelected()
    if (!selected) return
    try {
      const list = await school.fetchSchools()
      if (!this._alive) return
      const hit = list.find(row => Number(row.id) === Number(selected.id))
      const logo = hit && hit.logo ? api.assetUrl(hit.logo) : ''
      this.setData({ schoolLogo: logo })
    } catch (error) {}
  },

  // 没选过学校 → 强制弹选择器；选完再加载数据
  async ensureSchool() {
    if (school.hasSelected()) return
    const list = await school.fetchSchools()
    if (!this._alive) return
    if (!list.length) return
    // 只有一所学校时直接选中，不用打扰用户
    if (list.length === 1) {
      school.setSelected(list[0])
      this.setData({ schoolName: list[0].name })
      this.loadRecommendations(0)
      return
    }
    this.openSchoolPicker(list, true)
  },

  // 学校选择器：force=true 时不允许取消
  openSchoolPicker(list, force) {
    popup.sheet(this, {
      title: '选择你的学校',
      subtitle: force ? '选择后可在首页随时切换' : '',
      actions: list.map(row => ({ label: row.name })),
      action: ({ index }) => {
        const picked = list[index]
        if (!picked) return
        school.setSelected(picked)
        this.setData({ schoolName: picked.name })
        // 换学校后数据要重拉，否则看到的还是上一所学校的推荐
        this.loadRecommendations(0)
        if (typeof this.onSchoolChanged === 'function') this.onSchoolChanged(picked)
      }
    })
  },

  onUnload() { this._alive = false },

  initNavigation() {
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
        capsuleReservedRight = Math.max(
          16,
          windowInfo.windowWidth - menuRect.left + 8
        )
      }

      this.setData({
        statusBarHeight,
        navBarHeight,
        capsuleReservedRight
      })
    } catch (error) {
      this.setData({
        statusBarHeight: 20,
        navBarHeight: 44,
        capsuleReservedRight: 16
      })
    }
  },

  // 选学校：列表从后端动态拉取，不再硬编码
  async chooseSchool() {
    const list = await school.fetchSchools()
    if (!this._alive) return
    if (!list.length) { popup.toast(this, '暂时没有可选学校', { tone: 'error' }); return }
    this.openSchoolPicker(list, false)
  },

  submitSearch(event) {
    const keyword = String(event.detail.value || '').trim()
    if (!keyword) { popup.toast(this, '请输入搜索关键词'); return }
    if (keyword.length > 64) { popup.toast(this, '关键词不能超过64字'); return }
    wx.navigateTo({ url: `/pages/search/search?keyword=${encodeURIComponent(keyword)}` })
  },

  // 整卡进入独立学习页；内部 catchtap 入口继续由 openFeature 分发。
  openLearnZone() {
    wx.navigateTo({ url: '/pages/learn/learn?category=daike' })
  },

  // 校园首页（学校问候 / 快捷入口 / 校园新鲜事）
  openCampusHome() {
    wx.navigateTo({ url: '/pages/campus-home/campus-home' })
  },

  // AI 校园助手（关键词匹配陪聊）
  openAiChat() { wx.navigateTo({ url: '/pages/ai-chat/ai-chat' }) },

  openNotifications() {
    wx.navigateTo({ url: '/pages/messages/messages?tab=notice' })
  },

  openFeature(event) {
    const label = event.currentTarget.dataset.label || ''
    const route = Object.prototype.hasOwnProperty.call(FEATURE_ROUTES, label) && FEATURE_ROUTES[label]
    if (!route) {
      console.warn(`[首页路由] 该入口未配置路由：${label || '(空标签)'}`)
      popup.toast(this, '入口暂不可用，请稍后再试', { tone: 'warn' })
      return
    }
    if (route.action) return this[route.action]()
    wx.navigateTo({ url: route.url })
  },

  // 今日推荐：真实热度榜（外卖 + 二手），batch 递增实现「换一批」轮换
  async loadRecommendations(batch) {
    if (this._recommendLoading) return
    this._recommendLoading = true
    this.setData({ recommendLoading: true })
    try {
      const res = await api.getHomeRecommend({ batch, perType: 1 })
      if (this._alive === false) return
      this.setData({
        recommendations: res.cards || [],
        recommendBatch: batch,
        recommendHasMore: !!res.hasMore,
        recommendLoading: false
      })
    } catch (error) {
      if (this._alive !== false) {
        this.setData({ recommendLoading: false })
        popup.toast(this, error.message || '推荐加载失败', { tone: 'error' })
      }
    } finally { this._recommendLoading = false }
  },

  // 换一批：取下一批热度榜；没有下一批时回到第一批
  changeRecommendations() {
    if (this.data.recommendLoading) return
    const next = this.data.recommendHasMore ? this.data.recommendBatch + 1 : 0
    this.loadRecommendations(next)
  },

  // 推荐卡按类型跳转：外卖→点餐页，二手→商品详情
  openRecommendation(event) {
    const card = this.data.recommendations[Number(event.currentTarget.dataset.index)]
    if (!card || !card.url) return
    wx.navigateTo({ url: card.url })
  },

  openPublish() {
    popup.sheet(this, {
      title: '选择发布类型',
      actions: [
        { label: '发布闲置' },
        { label: '发布兼职' },
        { label: '发布话题' },
        { label: '发布活动' }
      ],
      action: ({ item }) => {
        wx.navigateTo({ url: '/pages/publish/publish' })
      }
    })
  },

  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})
