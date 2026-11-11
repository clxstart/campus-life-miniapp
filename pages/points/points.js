/**
 * 我的积分（V3.1 新页）
 *
 * 一页承接四件事：余额 / 今日签到 + 连签 / 段位与成长值 / 今日任务与明细。
 * 入口在「我的」菜单第 4 行，不进首页首屏（首屏已经很挤）。
 *
 * 数据口径：余额、成长值、段位、签到态全部来自 GET /api/points/me 一个请求，
 * 任务与明细分开发，任一卡片失败只影响自己，不阻塞其他卡片。
 */
const api = require('../../utils/api')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')

// 连签里程碑（共六档，与后端 task_catalog.sign_streak_N 一一对应）
const MILESTONE_DAYS = [3, 5, 7, 15, 20, 30]

// 晋升提示：本地记住上次看到的段位，下次进积分页对比（设计稿 §2.3）
const LAST_TIER_KEY = 'pointsLastTier'

// 八段位说明（与 tier_config 种子一致；enabled=0 的只展示「未开放」）
const TIER_INFO = [
  { code: 'bronze', name: '青铜·萌新', g: 0, enabled: true, perk: '可签到、可兑换' },
  { code: 'silver', name: '白银·圈内熟人', g: 60, enabled: true, perk: '兼职「优先推荐」' },
  { code: 'gold', name: '黄金·活跃分子', g: 200, enabled: true, perk: '发帖免审 + 可建圈子' },
  { code: 'platinum', name: '铂金·风云人物', g: 600, enabled: false, perk: '置顶曝光、抢单提前' },
  { code: 'diamond', name: '钻石·一校红人', g: 1500, enabled: false, perk: '专属铭牌与优先推荐' },
  { code: 'star', name: '星耀·校园顶流', g: 3500, enabled: false, perk: '主页皮肤与专属客服' },
  { code: 'king', name: '王者·城市之星', g: 8000, enabled: false, perk: '跨校优先与品牌合作' },
  { code: 'legend', name: '传奇·校宝名人堂', g: 18000, enabled: false, perk: '名人堂展示' }
]

// 余额 → 「≈¥0.63」：100 积分 = 1 元，只作价值参考
function yuanText(balance) {
  const value = (Number(balance) || 0) / 100
  return `≈¥${value.toFixed(2)}`
}

// 连签进度圆点：只展示「已达成 / 下一个 / 再下一个」三个，不铺满六档
function milestoneDots(streakDays) {
  const streak = Number(streakDays) || 0
  const next = MILESTONE_DAYS.filter(day => day > streak).slice(0, 3)
  const done = MILESTONE_DAYS.filter(day => day <= streak).slice(-1)
  return (done.length ? done : []).concat(next).slice(0, 3).map(day => ({ day, on: day <= streak }))
}

Page({
  data: {
    ...getNavigationMetrics(),
    // 各区块独立的状态：加载 / 失败 / 空，互不影响
    loadingMe: true,
    meError: '',
    me: null,
    balance: 0,
    valueYuan: '≈¥0.00',
    totalEarned: 0,
    signedToday: false,
    streakDays: 0,
    dots: [],
    nextMilestone: null,
    tier: null,
    tierProgressText: '',
    tierGapText: '',
    tasks: [],
    tasksLoading: true,
    tasksError: '',
    logs: [],
    logsLoading: false,
    logsError: '',
    logsPage: 0,
    logsHasMore: true,
    signBusy: false,
    // 签到是否被后台关闭：由任务列表里有没有 daily_sign 推断（关闭的任务后端不下发），
    // 只在任务列表拉取成功时才更新，避免接口报错把它误判成「关闭」
    signDisabled: false,
    offline: false,
    uiPopup: null
  },

  onLoad() {
    this._alive = true
    this.loadMe()
    this.loadTasks()
    this.loadLogs(true)
  },

  // 从兑换页返回时余额/库存可能已变，onShow 重拉一次关键数据
  onShow() {
    if (this._loaded) this.loadMe()
  },

  onUnload() { this._alive = false },

  onPullDownRefresh() {
    Promise.all([this.loadMe(), this.loadTasks(), this.loadLogs(true)])
      .finally(() => wx.stopPullDownRefresh())
  },

  onReachBottom() {
    if (this.data.logsHasMore && !this.data.logsLoading && !this.data.logsError) this.loadLogs(false)
  },

  /** 余额 / 签到 / 段位（同一接口） */
  async loadMe() {
    if (!this._alive) return
    this.setData({ loadingMe: !this.data.me, meError: '' })
    try {
      const me = await api.getPointsMe()
      if (!this._alive) return
      const tier = me.tier || null
      const balance = Number(me.balance) || 0
      this._loaded = true
      this.setData({
        me,
        balance,
        valueYuan: yuanText(balance),
        totalEarned: Number(me.totalEarned) || 0,
        signedToday: !!me.signedToday,
        streakDays: Number(me.streakDays) || 0,
        dots: milestoneDots(me.streakDays),
        nextMilestone: me.nextMilestone || null,
        tier,
        tierProgressText: tier
          ? (tier.maxOpen ? '已完成当前开放段位' : `${Number(tier.gTotal) || 0} / ${Number(tier.nextThreshold) || 0} 成长值`)
          : '',
        tierGapText: tier && !tier.maxOpen
          ? `距${tier.nextName || '下一段位'}还差 ${Math.max(0, (Number(tier.nextThreshold) || 0) - (Number(tier.gTotal) || 0))}`
          : '',
        offline: !!me.offline,
        loadingMe: false
      })
      this.maybeNotifyTierUp(tier)
    } catch (error) {
      if (!this._alive) return
      this.setData({ loadingMe: false, meError: (error && error.message) || '积分没加载出来' })
    }
  },

  /**
   * 晋升提示（设计稿 §2.3）：只在本机见过旧段位、且新段位更高时弹一次。
   * 首次进入只记录不弹，避免新用户被「晋升青铜」打扰。
   */
  maybeNotifyTierUp(tier) {
    if (!this._alive || !tier || !tier.code) return
    let last = ''
    try { last = wx.getStorageSync(LAST_TIER_KEY) || '' } catch (error) {}
    try { wx.setStorageSync(LAST_TIER_KEY, tier.code) } catch (error) {}
    if (!last || last === tier.code) return
    const rank = code => TIER_INFO.findIndex(row => row.code === code)
    // 只提示晋升；段位不会下降，但体验段位到期后可能回落到推导值，回落不弹
    if (rank(tier.code) > rank(last)) {
      popup.toast(this, `晋升${tier.name || tier.code}`, { tone: 'success' })
    }
  },

  /** 今日任务 */
  async loadTasks() {
    if (!this._alive) return
    this.setData({ tasksLoading: !this.data.tasks.length, tasksError: '' })
    try {
      const result = await api.getPointsTasks()
      if (!this._alive) return
      // 后端 GET /points/tasks 直接返回数组；老演示数据曾是 {list}，两种都接
      const list = Array.isArray(result) ? result : ((result && (result.list || result.rows)) || [])
      // 签到开关：/points/tasks 不下发已关闭的任务，所以「列表里没有 daily_sign」= 签到关闭。
      // 只有拉取成功才据此更新；失败时保留上一次判断（不能因为一次网络抖动把按钮永久置灰）。
      const signDisabled = !list.some(task => task && (task.taskKey === 'daily_sign' || task.key === 'daily_sign'))
      this.setData({
        signDisabled,
        tasks: list.map(task => {
          // 后端字段：doneToday（今日已完成次数）+ dailyCap + available；
          // 旧演示字段是 progress / done，两侧都兼容，展示口径一致
          const cap = Number(task.dailyCap) || 0
          const doneToday = Number(task.doneToday !== undefined ? task.doneToday : task.progress) || 0
          const done = task.done !== undefined ? !!task.done : (cap > 0 ? doneToday >= cap : doneToday > 0)
          return {
            ...task,
            progressText: cap > 1 ? `${doneToday}/${cap} 次` : (done ? '已完成' : '未完成'),
            rewardText: `+${Number(task.p) || 0}`,
            done
          }
        }),
        tasksLoading: false
      })
    } catch (error) {
      if (!this._alive) return
      this.setData({ tasksLoading: false, tasksError: (error && error.message) || '任务没加载出来' })
    }
  },

  /** 积分明细（上拉分页） */
  async loadLogs(reset = false) {
    if (!this._alive) return
    const page = reset ? 1 : (this.data.logsPage || 1) + 1
    this.setData({ logsLoading: true, logsError: '' })
    try {
      const result = await api.getPointsLogs({ page, size: 20 })
      if (!this._alive) return
      const list = (result && (result.list || result.rows)) || []
      const mapped = list.map(row => ({
        ...row,
        amountText: `${Number(row.amount) >= 0 ? '+' : ''}${Number(row.amount) || 0}`,
        positive: Number(row.amount) >= 0,
        timeText: row.createdAt || row.time || ''
      }))
      // 翻页按 id 去重：后端补数据时不会出现同一笔流水两行
      const logs = reset ? mapped : this.data.logs.concat(mapped.filter(next => !this.data.logs.some(old => String(old.id) === String(next.id))))
      this.setData({
        logs,
        logsPage: page,
        logsHasMore: result && result.hasMore !== undefined
          ? !!result.hasMore
          : mapped.length >= 20,
        logsLoading: false
      })
    } catch (error) {
      if (!this._alive) return
      this.setData({ logsLoading: false, logsError: (error && error.message) || '明细没加载出来' })
    }
  },

  /**
   * 签到：先本地置灰防连点，再请求。
   * 成功只局部刷新（不整页重载），失败把按钮恢复可点。
   */
  async sign() {
    if (this.data.signBusy || this.data.signedToday || this.data.signDisabled) return
    this.setData({ signBusy: true })
    try {
      const result = await api.signPoints()
      if (!this._alive) return
      const awarded = (result && result.awarded) || []
      const message = (result && result.message) || ''
      // 签到未开放 / 后台已停发时后端返回 code=0 但 awarded 为空：
      // 不能当成功处理，否则用户会被告知「+1 积分」且按钮永久置灰。
      if (!awarded.length) {
        // 后端明确说「暂未开放 / 已关闭」时，把按钮切到关闭态（灰显 + 小字），
        // 而不是只弹一次 toast 让用户回去再点一次。
        const closed = /暂未开放|已关闭|维护/.test(message)
        this.setData({ signBusy: false, signDisabled: closed ? true : this.data.signDisabled })
        popup.toast(this, message || '今天已签到', { tone: 'info' })
        // 重新拉取真实状态：开放状态可能刚被后台改掉，连签/余额以服务端为准
        this.loadMe()
        return
      }
      this.setData({
        signedToday: true,
        streakDays: Number(result && result.streakDays) || this.data.streakDays + 1,
        dots: milestoneDots(result && result.streakDays),
        nextMilestone: (result && result.nextMilestone) || this.data.nextMilestone,
        balance: result && result.balance !== undefined ? Number(result.balance) : this.data.balance,
        valueYuan: yuanText(result && result.balance !== undefined ? result.balance : this.data.balance),
        signBusy: false
      })
      popup.toast(this, '签到成功 +1 积分', { tone: 'success' })
      // 里程碑是额外一笔奖励，用第二条提示说清楚，不合并成一句
      const milestone = awarded.find(row => String(row.taskKey || '').indexOf('sign_streak_') === 0)
      if (milestone) {
        setTimeout(() => {
          if (this._alive) popup.toast(this, `连续签到 ${result.streakDays} 天，额外 +${milestone.p} 积分`, { tone: 'success' })
        }, 1400)
      }
      this.loadTasks()
      this.loadLogs(true)
    } catch (error) {
      if (!this._alive) return
      this.setData({ signBusy: false })
      popup.toast(this, (error && error.message) || '签到失败，请稍后重试', { tone: 'error' })
    }
  },

  /** 段位说明弹层：八行，当前段位加「当前」，未开放加「未开放」 */
  openTierSheet() {
    const tier = this.data.tier
    if (!tier) return
    popup.sheet(this, {
      title: '段位说明（成长值只涨不降）',
      actions: TIER_INFO.map(row => ({
        label: `${row.name} · G ≥ ${row.g}${row.code === tier.code ? '（当前）' : ''}${row.enabled ? '' : '（未开放）'} · ${row.perk}`
      })),
      action: ({ index }) => {
        const row = TIER_INFO[index]
        if (row && !row.enabled) {
          popup.toast(this, '该段位暂未开放，开放后会第一时间通知', { tone: 'info' })
        }
      }
    })
  },

  openRedeem() { wx.navigateTo({ url: '/pages/points-redeem/points-redeem' }) },

  openPublish() { wx.navigateTo({ url: '/pages/publish/publish' }) },

  retryMe() { this.loadMe() },
  retryTasks() { this.loadTasks() },
  retryLogs() { this.loadLogs(true) },

  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})