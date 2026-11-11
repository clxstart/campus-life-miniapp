const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const { getTermWeek, daysUntil } = require('../../utils/term')
const { formatTime } = require('../../utils/format-time')
const popup = require('../../utils/ui-popup-helper')
const { asset } = require('../../utils/remote-assets')

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
      // V3.1 四个常驻入口：积分 / 宿舍 / 认证 / 论坛榜单
      { key: 'points', icon: '积', title: '我的积分', value: '', tone: 'yellow' },
      { key: 'dorm', icon: '舍', title: '我的宿舍', value: '—', tone: 'green' },
      // 学生认证：右侧状态以后端 GET /api/verification/me 的 label 为准（未认证/审核中/已认证/认证未通过）
      { key: 'cert', icon: '✓', title: '学生认证', value: '未认证', tone: 'blue' },
      { key: 'forum-rank', icon: '榜', title: '论坛榜单', value: '日榜 / 周榜', tone: 'purple' },
      { key: 'food-orders', icon: '餐', title: '我的外卖订单', value: '', tone: 'green' },
      { key: 'timetable', icon: '课', title: '我的课表', value: '', tone: 'blue' },
      { key: 'address', icon: '⌖', title: '地址管理', value: '', tone: 'green' },
      { key: 'coupon', icon: '券', title: '优惠与权益', value: '', tone: 'yellow' },
      { key: 'feedback', icon: '?', title: '帮助与反馈', value: '', tone: 'purple' },
      // 微信群入口：点开是二维码页，扫码进群（二维码由管理端「运营设置」配置）
      { key: 'wechat-group', icon: '群', title: '微信群聊', value: '扫码加入', tone: 'green' },
      { key: 'settings', icon: '⚙', title: '设置', value: '', tone: 'gray' }
    ],
    // 认证状态：默认「未认证」，只有后端明确返回 verified 才展示已认证
    verify: { label: '未认证', verified: false, realNameStatus: 'none', studentStatus: 'none', canTakePaidJob: false },
    // 未认证时昵称旁显示段位（认证后只显示认证标签，避免昵称行挤三个标签）
    tierLabel: '',
    // F10 二手成交（卖家侧）：我发布的物品 + 最近一笔成交，驱动「待确认成交」角标与两个按钮
    myGoods: [],
    goodsLoading: false,
    uiPopup: null
  },

  onLoad() { this._alive = true;
    this.loadProfile()
    this.loadVerification()
    this.loadPoints()
    this.loadDorm()
    this.loadMyGoods()
    this.applyScheduleMeta()
    this.loadCounts()
  },
  onUnload() { this._alive = false },

  // 认证状态一律以后端为准；接口失败时保持「未认证」，绝不乐观展示已认证
  async loadVerification() {
    try {
      const verify = await api.getVerification()
      if (!this._alive || !verify) return
      this.setData({ verify: { ...this.data.verify, ...verify } })
      this.patchMenuValue('cert', verify.label || '未认证')
    } catch (error) {
      if (!this._alive) return
      // 拿不到就按未认证展示（不乐观），但入口保留，点进去会提示真实原因
      this.patchMenuValue('cert', '未认证')
    }
  },

  // 我的积分：菜单右侧显示真实余额；未认证时昵称旁显示段位两字（如「白银」）
  async loadPoints() {
    try {
      const me = await api.getPointsMe()
      if (!this._alive || !me) return
      this.patchMenuValue('points', `${Number(me.balance) || 0} 积分`)
      const tierName = me.tier && me.tier.name ? String(me.tier.name) : ''
      // 只取「白银」两字，不带「·圈内熟人」副名，避免昵称行过长
      this.setData({ tierLabel: tierName ? tierName.split('·')[0] : '' })
    } catch (error) {
      if (this._alive) this.patchMenuValue('points', '—')
    }
  },

  // 我的宿舍：已加入显示宿舍名，后端明确未加入显示「未加入」，读失败显示「—」
  async loadDorm() {
    try {
      const dorm = await api.getDormMe()
      if (!this._alive) return
      if (dorm && dorm.joined && dorm.dorm) this.patchMenuValue('dorm', dorm.dorm.name || '已加入')
      else if (dorm && dorm.dissolved) this.patchMenuValue('dorm', '已解散')
      else this.patchMenuValue('dorm', '未加入')
    } catch (error) {
      if (this._alive) this.patchMenuValue('dorm', '—')
    }
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
    this.setData({ 'services[1].desc': count(favorites, '个收藏'), 'services[2].desc': Array.isArray(views) ? `最近${views.length}条` : '—' })
    // 菜单按 key 定位再写值：菜单顺序调整时不会写到相邻行上（历史 bug）
    this.patchMenuValue('address', count(addresses, '个地址'))
    this.patchMenuValue('coupon', count(coupons, '张可用'))
  },

  // ==================== F10 二手成交（卖家侧） ====================
  //
  // 设计稿 §2.3：卖家在「我的发布」看到「待确认成交」角标，点「确认成交」双方各得 5 积分；
  // 点「没有成交」把这笔 pending 撤掉，商品回到在售，买家可以再次发起。
  // 读接口断网时 api 层回落到空列表 —— 这里绝不本地编一条「待确认成交」出来。

  async loadMyGoods() {
    this.setData({ goodsLoading: true })
    try {
      const result = await api.getMyMarketTrades()
      if (!this._alive) return
      const list = (result && result.list) || []
      this.setData({ myGoods: list.map(item => this.goodsRow(item)), goodsLoading: false })
    } catch (error) {
      if (!this._alive) return
      // 拿不到就整块不显示，不猜也不假装「没有待确认成交」
      this.setData({ myGoods: [], goodsLoading: false })
    }
  },

  /** 成交记录行 → 页面视图。状态文案只由后端的 goodsStatus / tradeStatus 推导 */
  goodsRow(item) {
    const tradeStatus = item.tradeStatus || ''
    const pending = tradeStatus === 'pending'
    const sold = Number(item.goodsStatus) === 2 || tradeStatus === 'confirmed'
    const price = Number(item.price || 0)
    return {
      goodsId: item.goodsId,
      tradeId: item.tradeId,
      title: item.title || '未命名物品',
      cover: item.cover || asset('/assets/home/secondhand-box.webp'),
      priceText: Number.isInteger(price) ? String(price) : price.toFixed(2),
      buyerName: item.buyerNickname || '买家',
      pending,
      statusText: sold ? '已售出' : (pending ? '待确认成交' : '在售')
    }
  },

  /** 卖家点「确认成交」：二次确认后才调接口，成交与发分都在服务端判定 */
  confirmGoodsTrade(event) {
    const tradeId = event.currentTarget.dataset.tradeId
    const buyer = event.currentTarget.dataset.buyer
    if (!tradeId || this._tradeBusy) return
    popup.modal(this, {
      title: '确认成交',
      message: `确认与 ${buyer || '对方'} 成交？确认后双方各得 5 积分`,
      confirmText: '确认成交',
      confirm: async () => {
        this._tradeBusy = true
        try {
          const result = await api.confirmMarketTrade(tradeId)
          if (!this._alive) return
          // 成交本身已经成功；积分可能没发：同一对用户 7 天只计一次，或当日已满 2 笔。
          // 文案按后端 skipReason 分流 —— 不能一律喊「+5 积分」，否则用户去积分明细会对不上。
          if (result && result.skipReason === 'pair7days') {
            popup.modal(this, {
              title: '成交已确认',
              message: '本次成交已完成（同一对用户 7 天内只计一次积分）',
              showCancel: false,
              confirmText: '知道了'
            })
          } else if (result && result.skipReason === 'daily_cap') {
            popup.modal(this, {
              title: '成交已确认',
              message: '今日成交积分已满 2 笔，本次成交成功但没有新增积分',
              showCancel: false,
              confirmText: '知道了'
            })
          } else if (result && result.alreadyConfirmed) {
            popup.toast(this, '这笔成交已经确认过了', { tone: 'info' })
          } else {
            popup.toast(this, '成交成功，+5 积分', { tone: 'success' })
          }
          this.loadMyGoods()
        } catch (error) {
          if (this._alive) this.handleTradeError(error, '确认成交失败')
        } finally {
          this._tradeBusy = false
        }
      }
    })
  },

  /** 卖家点「没有成交」：撤掉待确认的成交，商品回到在售 */
  rejectGoodsTrade(event) {
    const tradeId = event.currentTarget.dataset.tradeId
    if (!tradeId || this._tradeBusy) return
    popup.modal(this, {
      title: '没有成交',
      message: '确认这单没有成交吗？确认后商品会重新回到在售，买家可以再次发起。',
      tone: 'warn',
      confirmText: '没有成交',
      confirm: async () => {
        this._tradeBusy = true
        try {
          await api.cancelMarketTrade(tradeId)
          if (!this._alive) return
          popup.toast(this, '已标记为未成交', { tone: 'info' })
          this.loadMyGoods()
        } catch (error) {
          if (this._alive) this.handleTradeError(error, '标记未成交失败')
        } finally {
          this._tradeBusy = false
        }
      }
    })
  },

  /** 成交相关写接口失败：原样转述后端原因，并刷新一次列表（状态可能已被对方或管理员改过） */
  handleTradeError(error, fallback) {
    popup.modal(this, {
      title: fallback,
      message: (error && error.message) || '操作失败，请稍后重试',
      showCancel: false,
      confirmText: '知道了'
    })
    if (this._alive) this.loadMyGoods()
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
      this.patchMenuValue('timetable', `第${weekNo}周 · 今天${todayCourses ? `${todayCourses}节` : '无课'}`)
    } catch (error) { this.patchMenuValue('timetable', '点击查看课表') }
  },

  /** 菜单行按 key 写 value：位置变了也不会写错行 */
  patchMenuValue(key, value) {
    const index = this.data.menus.findIndex(row => row.key === key)
    if (index < 0) return
    this.setData({ [`menus[${index}].value`]: value })
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
    // V3.1 四个新入口
    if (key === 'points') return wx.navigateTo({ url: '/pages/points/points' })
    if (key === 'dorm') return wx.navigateTo({ url: '/pages/dorm/dorm' })
    if (key === 'cert') return wx.navigateTo({ url: '/pages/verify/verify' })
    if (key === 'forum-rank') return wx.navigateTo({ url: '/pages/forum-rank/forum-rank?type=daily' })
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
    popup.modal(this, {
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
      // 券只在本平台外卖下单时抵扣（设计稿：不在外面用，没有到店核销）
      action: ({ index }) => popup.toast(this, `「${list[index].title}」在外卖下单时自动抵扣`, { tone: 'success' })
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
