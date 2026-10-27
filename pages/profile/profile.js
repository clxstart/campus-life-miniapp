const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { getTermWeek, daysUntil } = require('../../utils/term')
const { formatTime } = require('../../utils/format-time')
const popup = require('../../utils/ui-popup-helper')

// 当前登录用户：登录了就是真实用户 id，未登录回落到演示账号（api.DEMO_USER_ID）

Page({
  data: {
    ...getNavigationMetrics(),
    profile: {},
    services: [
      { key: 'publish', icon: '发', name: '我的发布', desc: '', color: '#579CF2' },
      { key: 'favorites', icon: '藏', name: '我的收藏', desc: '', color: '#62C78E' },
      { key: 'views', icon: '迹', name: '浏览记录', desc: '', color: '#F0B24E' }
    ],
    menus: [
      { key: 'food-orders', icon: '餐', title: '我的外卖订单', value: '', tone: 'green' },
      { key: 'timetable', icon: '课', title: '我的课表', value: '', tone: 'blue' },
      // 校园身份认证：后端与 user 表都还没有认证状态字段，这里如实显示「未开通」，
      // 不再写死「已认证」—— 展示与事实不符比功能缺失更容易误导用户。
      { key: 'cert', icon: '✓', title: '校园身份认证', value: '未开通', tone: 'blue' },
      { key: 'address', icon: '⌖', title: '地址管理', value: '', tone: 'green' },
      { key: 'coupon', icon: '券', title: '优惠与权益', value: '', tone: 'yellow' },
      { key: 'feedback', icon: '?', title: '帮助与反馈', value: '', tone: 'purple' },
      { key: 'settings', icon: '⚙', title: '设置', value: '', tone: 'gray' }
    ],
    uiPopup: null
  },

  onLoad() {
    this.loadProfile()
    this.applyScheduleMeta()
    this.loadCounts()
  },

  // 真实资料：昵称/学校/头像/统计来自后端，学院与学号暂无对应字段沿用本地
  async loadProfile() {
    try {
      const profile = await api.getProfile()
      this.setData({ profile, 'services[0].desc': `${profile.posts || 0}条内容` })
    } catch (error) { popup.toast(this, error.message || '资料加载失败', { tone: 'error' }) }
  },

  // 各入口的真实计数：收藏/浏览/地址/优惠券
  // 这几个接口在未登录/无权限时会抛错（不再伪装成空列表），这里退化成「—」而不是整个页面报错
  async loadCounts() {
    const safe = (promise) => promise.catch(() => null)
    const [favorites, views, addresses, coupons] = await Promise.all([
      safe(api.getFavorites(api.currentUserId())),
      safe(api.getMyViews(api.currentUserId())),
      safe(api.getAddresses(api.currentUserId())),
      safe(api.getCoupons(api.currentUserId()))
    ])
    const count = (list, unit) => (Array.isArray(list) ? `${list.length}${unit}` : '—')
    this.setData({
      'services[1].desc': count(favorites, '个收藏'),
      'services[2].desc': Array.isArray(views) ? `最近${views.length}条` : '—',
      'menus[4].value': count(addresses, '个地址'),
      'menus[5].value': count(coupons, '张可用')
    })
  },

  // 课表菜单行的动态值：教学周、今日课程数（校历功能已下线）
  async applyScheduleMeta() {
    try {
      const { termInfo, courses } = await api.getTimetable()
      const today = new Date()
      today.setHours(0, 0, 0, 0)
      const todayIdx = (today.getDay() + 6) % 7
      const weekNo = getTermWeek(today, termInfo.termStart)
      const todayCourses = courses.filter(course => course.day === todayIdx).length
      this.setData({
        'menus[1].value': `第${weekNo}周 · 今天${todayCourses ? `${todayCourses}节` : '无课'}`
      })
    } catch (error) { this.setData({ 'menus[1].value': '点击查看课表' }) }
  },

  openService(event) {
    const key = event.currentTarget.dataset.key
    if (key === 'publish') {
      wx.navigateTo({ url: `/pages/user-home/user-home?id=${api.currentUserId()}` })
      return
    }
    if (key === 'favorites') this.openFavorites()
    if (key === 'views') this.openViews()
  },

  openMenu(event) {
    const key = event.currentTarget.dataset.key
    if (key === 'food-orders') return wx.navigateTo({ url: '/pages/food-orders/food-orders' })
    if (key === 'timetable') return wx.navigateTo({ url: '/pages/timetable/timetable' })
    if (key === 'address') return this.openAddresses()
    if (key === 'coupon') return this.openCoupons()
    if (key === 'feedback') return this.openFeedback()
    if (key === 'privacy') return wx.navigateTo({ url: '/pages/agreement/agreement?type=privacy' })
    if (key === 'terms') return wx.navigateTo({ url: '/pages/agreement/agreement?type=terms' })
    if (key === 'settings') return this.openSettings()
    popup.toast(this, event.currentTarget.dataset.label)
  },

  // 设置：目前放协议入口与注销账号。注销是不可逆操作，二次确认 + 输入确认词。
  openSettings() {
    const items = ['隐私政策', '用户协议', '注销账号']
    popup.sheet(this, {
      title: '设置',
      actions: items.map((label, index) => ({ label, index })),
      action: ({ item }) => {
        const label = item && item.label
        if (label === '隐私政策') wx.navigateTo({ url: '/pages/agreement/agreement?type=privacy' })
        else if (label === '用户协议') wx.navigateTo({ url: '/pages/agreement/agreement?type=terms' })
        else if (label === '注销账号') this.confirmDeleteAccount()
      }
    })
  },

  confirmDeleteAccount() {
    popup.modal(this, {
      title: '注销账号',
      message: '注销后你的资料、帖子、收藏等将被删除且无法恢复，订单与交易记录按法规要求保留。确定继续吗？',
      showCancel: true,
      confirmText: '继续注销',
      confirm: () => this.doDeleteAccount()
    })
  },

  async doDeleteAccount() {
    try {
      await api.deleteAccount(api.currentUserId())
      api.clearAuth()
      popup.toast(this, '账号已注销', { tone: 'success' })
      setTimeout(() => wx.reLaunch({ url: '/pages/index/index' }), 1200)
    } catch (error) {
      popup.toast(this, error.message || '注销失败，请稍后再试', { tone: 'error' })
    }
  },

  // 收藏列表：点标题直达帖子详情
  async openFavorites() {
    let list
    try { list = (await api.getFavorites(api.currentUserId())) || [] }
    catch (error) { popup.toast(this, error.message || '收藏加载失败', { tone: 'error' }); return }
    if (!list.length) { popup.toast(this, '还没有收藏，去帖子详情点「☆ 收藏」'); return }
    this._favorites = list
    popup.sheet(this, {
      title: `我的收藏 · ${list.length}篇`,
      actions: list.map((item) => ({ label: item.title })),
      action: ({ index }) => {
        const target = this._favorites[index]
        if (target) wx.navigateTo({ url: `/pages/post-detail/post-detail?id=${target.id}` })
      }
    })
  },

  // 浏览记录：最近看过的帖子（同帖去重，按时间倒序）
  async openViews() {
    let list
    try { list = (await api.getMyViews(api.currentUserId())) || [] }
    catch (error) { popup.toast(this, error.message || '浏览记录加载失败', { tone: 'error' }); return }
    if (!list.length) { popup.toast(this, '暂无浏览记录'); return }
    this._views = list
    popup.sheet(this, {
      title: `浏览记录 · 最近${list.length}篇`,
      actions: list.map((item) => ({ label: `${item.title}` })),
      action: ({ index }) => {
        const target = this._views[index]
        if (target) wx.navigateTo({ url: `/pages/post-detail/post-detail?id=${target.id}` })
      }
    })
  },

  // 地址管理：列表 + 新增
  async openAddresses() {
    let list
    try { list = (await api.getAddresses(api.currentUserId())) || [] }
    catch (error) { popup.toast(this, error.message || '地址加载失败', { tone: 'error' }); return }
    this._addresses = list
    popup.sheet(this, {
      title: `地址管理 · ${list.length}个`,
      actions: [{ label: '＋ 新增收货地址' }].concat(list.map((item) => ({ label: `${item.detail}（${item.contact}）` }))),
      action: ({ index }) => {
        if (index === 0) return this.promptAddress()
        popup.toast(this, '演示地址，暂不支持编辑')
      }
    })
  },

  promptAddress() {
    popup.modal(this, {
      title: '新增收货地址',
      editable: true,
      placeholder: '如：学生公寓 6 号楼 312',
      inputValue: '',
      confirmText: '保存',
      confirm: async (res) => {
        const detail = (res.inputValue || '').trim()
        if (!detail) return
        try {
          await api.addAddress(api.currentUserId(), detail)
          popup.toast(this, '地址已保存', { tone: 'success' })
          this.loadCounts()
        } catch (error) { popup.toast(this, error.message || '地址保存失败', { tone: 'error' }) }
      }
    })
  },

  // 优惠券：真实数据列表
  async openCoupons() {
    let list
    try { list = (await api.getCoupons(api.currentUserId())) || [] }
    catch (error) { popup.toast(this, error.message || '优惠券加载失败', { tone: 'error' }); return }
    if (!list.length) { popup.toast(this, '暂无可用优惠'); return }
    popup.sheet(this, {
      title: `优惠与权益 · ${list.length}张可用`,
      actions: list.map((item) => ({ label: `${item.title}（${item.amount} · 至${item.expireAt}）` })),
      action: ({ index }) => popup.toast(this, `已领取「${list[index].title}」，到店出示即可使用`, { tone: 'success' })
    })
  },

  // 帮助与反馈：意见直达后端入库
  openFeedback() {
    popup.modal(this, {
      title: '意见反馈',
      message: '遇到问题或有建议，写下来告诉我们',
      editable: true,
      placeholder: '说说你遇到的问题或建议…',
      inputValue: '',
      confirmText: '提交',
      confirm: async (res) => {
        const content = (res.inputValue || '').trim()
        if (!content) return
        try {
          await api.submitFeedback(api.currentUserId(), content)
          popup.toast(this, '已收到你的反馈，感谢！', { tone: 'success' })
        } catch (error) { popup.toast(this, error.message || '反馈提交失败', { tone: 'error' }) }
      }
    })
  },

  // 编辑昵称：真实保存到后端
  editProfile() {
    popup.modal(this, {
      title: '编辑昵称',
      editable: true,
      placeholder: this.data.profile.name || '输入新昵称',
      inputValue: '',
      confirm: async (res) => {
        const name = (res.inputValue || '').trim()
        if (!name) return
        await api.updateUserProfile(api.currentUserId(), name)
        this.setData({ 'profile.name': name })
        popup.toast(this, '昵称已保存', { tone: 'success' })
      }
    })
  },

  openSettings() {
    popup.sheet(this, {
      title: '设置',
      actions: [
        { label: '账号与安全' },
        { label: '隐私设置' },
        { label: '消息通知' },
        { label: '关于校园生活' }
      ],
      action: () => {}
    })
  },

  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})
