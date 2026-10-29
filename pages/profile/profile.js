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
      // 微信群入口：点开是二维码页，扫码进群（二维码由管理端「运营设置」配置）
      { key: 'wechat-group', icon: '群', title: '微信群聊', value: '扫码加入', tone: 'green' },
      { key: 'settings', icon: '⚙', title: '设置', value: '', tone: 'gray' }
    ],
    uiPopup: null
  },

  onLoad() { this._alive = true;
    this.loadProfile()
    this.applyScheduleMeta()
    this.loadCounts()
  },
  onUnload() { this._alive = false },

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

  /**
   * 个人信息卡的统计行：发布 / 关注 / 粉丝
   *
   * wxml 一直绑着 openItem，但这里原本没有这个方法，
   * 微信会报 "does not have a method openItem"，点了完全没反应。
   *
   * 现状：「发布」能进个人主页；
   * 「关注」「粉丝」目前既没有列表页、后端也没有列表接口（只有计数），
   * 所以给明确提示，不假装能进去。
   */
  openItem(event) {
    const label = String(event.currentTarget.dataset.label || '')
    if (label === '我的发布') {
      wx.navigateTo({ url: `/pages/user-home/user-home?id=${api.currentUserId()}` })
      return
    }
    popup.toast(this, '关注和粉丝名单还在开发中，暂时看不了', { tone: 'info' })
  },

  openMenu(event) {
    const key = event.currentTarget.dataset.key
    if (key === 'food-orders') return wx.navigateTo({ url: '/pages/food-orders/food-orders' })
    if (key === 'timetable') return wx.navigateTo({ url: '/pages/timetable/timetable' })
    if (key === 'address') return this.openAddresses()
    if (key === 'coupon') return this.openCoupons()
    if (key === 'feedback') return this.openFeedback()
    // 微信群聊：跳二维码页（type=group），扫码进群
    if (key === 'wechat-group') return wx.navigateTo({ url: '/pages/wechat-qr/wechat-qr?type=group' })
    if (key === 'privacy') return wx.navigateTo({ url: '/pages/agreement/agreement?type=privacy' })
    if (key === 'terms') return wx.navigateTo({ url: '/pages/agreement/agreement?type=terms' })
    if (key === 'settings') return this.openSettings()
    popup.toast(this, event.currentTarget.dataset.label)
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

  // 地址管理：列表 + 新增 + 编辑 + 删除
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
        // 点已有地址：给出编辑/删除
        const item = list[index - 1]
        if (item) this._openAddressActions(item)
      }
    })
  },

  /** 单条地址的操作面板 */
  _openAddressActions(item) {
    popup.sheet(this, {
      title: item.detail,
      actions: [{ label: '编辑地址' }, { label: '删除这条地址' }],
      action: ({ index }) => {
        if (index === 0) this.editAddress(item)
        else if (index === 1) this.confirmDeleteAddress(item)
      }
    })
  },

  /** 编辑单条地址（复用 promptAddress 的弹窗写法） */
  editAddress(item) {
    popup.modal(this, {
      title: '编辑收货地址',
      editable: true,
      placeholder: '如：学生公寓 6 号楼 312',
      inputValue: item.detail || '',
      confirmText: '保存',
      confirm: async (res) => {
        const detail = String((res && res.inputValue) || '').trim()
        if (!detail) {
          popup.toast(this, '地址不能为空', { tone: 'warn' })
          return
        }
        try {
          await api.updateAddress(api.currentUserId(), item.id, detail)
          popup.toast(this, '地址已更新', { tone: 'success' })
          this.openAddresses()
        } catch (error) {
          popup.toast(this, error.message || '更新失败', { tone: 'error' })
        }
      }
    })
  },

  /** 删除单条地址（二次确认） */
  confirmDeleteAddress(item) {
    popup.confirm(this, {
      title: '删除地址',
      message: `确定删除「${item.detail}」吗？`,
      confirmText: '删除',
      confirm: async () => {
        try {
          await api.deleteAddress(api.currentUserId(), item.id)
          popup.toast(this, '已删除', { tone: 'success' })
          this.openAddresses()
        } catch (error) {
          popup.toast(this, error.message || '删除失败', { tone: 'error' })
        }
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

  /**
   * 设置。
   *
   * 注意：这个方法历史上被定义过两次，后者覆盖前者，导致先写的那份成了死代码。
   * 现在只保留这一份，所有入口都在这里。
   */
  openSettings() {
    const items = ['隐私政策', '用户协议', '黑名单', '注销账号']
    popup.sheet(this, {
      title: '设置',
      actions: items.map(label => ({ label })),
      action: ({ item }) => {
        const label = item && item.label
        if (label === '隐私政策') wx.navigateTo({ url: '/pages/agreement/agreement?type=privacy' })
        else if (label === '用户协议') wx.navigateTo({ url: '/pages/agreement/agreement?type=terms' })
        else if (label === '黑名单') this.openBlocks()
        else if (label === '注销账号') this.confirmDeleteAccount()
      }
    })
  },

  /** 黑名单：展示我拉黑的人，可逐个解除。 */
  async openBlocks() {
    let list = []
    try {
      list = await api.getMyBlocks(api.currentUserId()) || []
    } catch (error) {
      popup.toast(this, error.message || '黑名单加载失败', { tone: 'error' })
      return
    }
    if (!list.length) {
      popup.toast(this, '黑名单是空的')
      return
    }
    popup.sheet(this, {
      title: `黑名单（${list.length} 人）`,
      actions: list.map(row => ({ label: row.nickname || `用户#${row.userId}`, userId: row.userId })),
      action: async ({ item }) => {
        const userId = item && item.userId
        if (!userId) return
        try {
          const result = await api.blockUser(userId)
          popup.toast(this, result && result.blocked ? '已重新屏蔽' : '已解除屏蔽', { tone: 'success' })
        } catch (error) {
          popup.toast(this, error.message || '操作失败', { tone: 'error' })
        }
      }
    })
  },

  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})
