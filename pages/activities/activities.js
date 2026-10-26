const api = require('../../utils/api')
const school = require('../../utils/school')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')

Page({
  data: { ...getNavigationMetrics(), activities: [], loading: true, enrollingId: 0, schoolScope: 'local', uiPopup: null },
  onLoad() { this._alive = true },
  onShow() { this.loadActivities() },
  onUnload() { this._alive = false },
  onPullDownRefresh() { this.loadActivities().finally(() => wx.stopPullDownRefresh()) },
  async loadActivities() {
    const seq = this._seq = (this._seq || 0) + 1
    this.setData({ loading: true })
    try {
      const activities = await api.getActivities(api.currentUserId(), this.data.schoolScope, school.getSelectedId())
      if (!this._alive || seq !== this._seq) return
      this.setData({ activities: (activities || []).map((item, index) => ({ ...item, isFeatured: index === 0 })) })
    } catch (error) {
      if (this._alive && seq === this._seq) popup.toast(this, error.message || '活动加载失败', { tone: 'error' })
    } finally {
      if (this._alive && seq === this._seq) this.setData({ loading: false })
    }
  },
  async enroll(e) {
    const id = Number(e.currentTarget.dataset.id)
    const item = (this.data.activities || []).find(activity => Number(activity.id) === id)
    if (!item || item.enrolledByMe || this.data.enrollingId) return
    this.setData({ enrollingId: id })
    try {
      await api.enrollActivity(id, api.currentUserId())
      const activities = this.data.activities.map(activity => Number(activity.id) === id ? { ...activity, enrolled: Number(activity.enrolled || 0) + 1, enrolledByMe: true } : activity)
      this.setData({ activities })
      popup.toast(this, '报名成功', { tone: 'success' })
    } catch (error) {
      popup.toast(this, error.message || '报名失败', { tone: 'error' })
    } finally {
      this.setData({ enrollingId: 0 })
    }
  },
  goBack() { wx.navigateBack() },
  // 本校/全部切换：校园活动按校隔离
  switchSchoolScope(e) {
    const schoolScope = e.currentTarget.dataset.scope
    if (!schoolScope || schoolScope === this.data.schoolScope) return
    this.setData({ schoolScope, activities: [] })
    this.loadActivities()
  },
  // 校园评选入口：校草校花与歌手大赛从发现页轮播迁到此处
  openIdol() { wx.navigateTo({ url: '/pages/activity-idol/activity-idol' }) },
  // 歌手大赛属于校园评选，之前误跳到校历页（校历已下线），统一进评选页
  openSinger() { wx.navigateTo({ url: '/pages/activity-idol/activity-idol' }) },
  onPopupConfirm(e) { this._popupConfirm && this._popupConfirm({ detail: e.detail }) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(e) { this._popupAction && this._popupAction({ detail: e.detail }) },
  onPopupInput(e) { this._popupInput && this._popupInput({ detail: e.detail }) },
  onPopupClose() { popup.hide(this) }
})
