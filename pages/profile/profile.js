const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { getTermWeek, daysUntil } = require('../../utils/term')
const { formatTime } = require('../../utils/format-time')
const popup = require('../../utils/ui-popup-helper')

const CURRENT_USER_ID = 1

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
      { key: 'timetable', icon: '课', title: '我的课表', value: '', tone: 'blue' },
      { key: 'calendar', icon: '历', title: '校历', value: '', tone: 'green' },
      { key: 'cert', icon: '✓', title: '校园身份认证', value: '已认证', tone: 'blue' },
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
  async loadCounts() {
    const [favorites, views, addresses, coupons] = await Promise.all([
      api.getFavorites(CURRENT_USER_ID),
      api.getMyViews(CURRENT_USER_ID),
      api.getAddresses(CURRENT_USER_ID),
      api.getCoupons(CURRENT_USER_ID)
    ])
    this.setData({
      'services[1].desc': `${(favorites || []).length}个收藏`,
      'services[2].desc': `最近${(views || []).length}条`,
      'menus[3].value': `${(addresses || []).length}个地址`,
      'menus[4].value': `${(coupons || []).length}张可用`
    })
  },

  // 课表/校历菜单行的动态值：教学周、今日课程数、距期中天数
  async applyScheduleMeta() {
    try {
      const { termInfo, courses } = await api.getTimetable()
      const today = new Date()
      today.setHours(0, 0, 0, 0)
      const todayIdx = (today.getDay() + 6) % 7
      const weekNo = getTermWeek(today, termInfo.termStart)
      const todayCourses = courses.filter(course => course.day === todayIdx).length
      this.setData({
        'menus[0].value': `第${weekNo}周 · 今天${todayCourses ? `${todayCourses}节` : '无课'}`,
        'menus[1].value': `距期中还有${daysUntil(termInfo.midtermStart, today)}天`
      })
    } catch (error) { this.setData({ 'menus[0].value': '点击查看课表', 'menus[1].value': '点击查看校历' }) }
  },

  openService(event) {
    const key = event.currentTarget.dataset.key
    if (key === 'publish') {
      wx.navigateTo({ url: `/pages/user-home/user-home?id=${CURRENT_USER_ID}` })
      return
    }
    if (key === 'favorites') this.openFavorites()
    if (key === 'views') this.openViews()
  },

  openMenu(event) {
    const key = event.currentTarget.dataset.key
    if (key === 'timetable') return wx.navigateTo({ url: '/pages/timetable/timetable' })
    if (key === 'calendar') return wx.navigateTo({ url: '/pages/calendar/calendar' })
    if (key === 'address') return this.openAddresses()
    if (key === 'coupon') return this.openCoupons()
    if (key === 'feedback') return this.openFeedback()
    popup.toast(this, event.currentTarget.dataset.label)
  },

  // 收藏列表：点标题直达帖子详情
  async openFavorites() {
    const list = (await api.getFavorites(CURRENT_USER_ID)) || []
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
    const list = (await api.getMyViews(CURRENT_USER_ID)) || []
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
    const list = (await api.getAddresses(CURRENT_USER_ID)) || []
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
        await api.addAddress(CURRENT_USER_ID, detail)
        popup.toast(this, '地址已保存', { tone: 'success' })
        this.loadCounts()
      }
    })
  },

  // 优惠券：真实数据列表
  async openCoupons() {
    const list = (await api.getCoupons(CURRENT_USER_ID)) || []
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
        await api.submitFeedback(CURRENT_USER_ID, content)
        popup.toast(this, '已收到你的反馈，感谢！', { tone: 'success' })
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
        await api.updateUserProfile(CURRENT_USER_ID, name)
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
