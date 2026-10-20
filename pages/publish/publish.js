const api = require('../../utils/api')
const { GOODS_CATEGORIES, GOODS_CONDITIONS, JOB_SETTLES } = require('../../utils/constants')
const school = require('../../utils/school')
const { getNavigationMetrics } = require('../../utils/navigation')
const popup = require('../../utils/ui-popup-helper')

Page({
  data: {
    ...getNavigationMetrics(),
    // 四个类型各自对应真实的发布流程，不再是「只换一个标签」
    types: [
      { key: 'daily', label: '校园动态', icon: '✦', color: '#4E96F2', desc: '发到校园论坛' },
      { key: 'idle', label: '闲置好物', icon: '♢', color: '#62C78E', desc: '上架二手市场' },
      { key: 'job', label: '兼职信息', icon: '◎', color: '#F0B24E', desc: '发布到兼职列表' },
      { key: 'event', label: '活动组队', icon: '♪', color: '#EF8EAD', desc: '创建校园活动' }
    ],
    activeType: 0,
    title: '',
    content: '',
    media: [],
    // 兼职/活动/闲置专用字段
    daySalary: '',
    settle: '日结',
    timeDesc: '',
    // 闲置专用
    price: '',
    conditionLevel: '九成新',
    category: '其他',
    location: '',
    visibleToSchool: true,
    submitting: false,
    uiPopup: null
  },

  // 切换类型时清掉不适用的字段，避免带脏数据提交
  chooseType(event) {
    const index = Number(event.currentTarget.dataset.index)
    if (index === this.data.activeType) return
    this.setData({ activeType: index, media: [] })
  },

  inputTitle(event) { this.setData({ title: event.detail.value }) },
  inputContent(event) { this.setData({ content: event.detail.value }) },
  inputDaySalary(event) { this.setData({ daySalary: event.detail.value }) },
  inputTimeDesc(event) { this.setData({ timeDesc: event.detail.value }) },
  inputPrice(event) { this.setData({ price: event.detail.value }) },

  // 闲置的成色与分类，选项与二手市场发布页保持一致
  chooseCondition() {
    const levels = GOODS_CONDITIONS
    popup.sheet(this, {
      title: '成色',
      actions: levels.map((label) => ({ label })),
      action: ({ index }) => this.setData({ conditionLevel: levels[index] })
    })
  },

  chooseCategory() {
    const categories = GOODS_CATEGORIES
    popup.sheet(this, {
      title: '分类',
      actions: categories.map((label) => ({ label })),
      action: ({ index }) => this.setData({ category: categories[index] })
    })
  },

  chooseSettle() {
    const settles = JOB_SETTLES
    popup.sheet(this, {
      title: '结算方式',
      actions: settles.map((label) => ({ label })),
      action: ({ index }) => this.setData({ settle: settles[index] })
    })
  },

  chooseMedia() {
    const remaining = 6 - this.data.media.length
    if (remaining <= 0) return
    wx.chooseMedia({
      count: remaining,
      mediaType: ['image'],
      sourceType: ['album', 'camera'],
      success: ({ tempFiles }) => {
        this.setData({ media: this.data.media.concat(tempFiles.map((file) => file.tempFilePath)) })
      },
      fail: (error) => {
        if (error.errMsg && !error.errMsg.includes('cancel')) popup.toast(this, '图片选择失败', { tone: 'error' })
      }
    })
  },

  removeMedia(event) {
    const index = Number(event.currentTarget.dataset.index)
    this.setData({ media: this.data.media.filter((_, i) => i !== index) })
  },

  // 地点按当前选中学校拉取；拉不到时 school.js 会回落到通用地点
  async chooseLocation() {
    const locations = await school.getSelectedLocations()
    if (!this._alive) return
    if (!locations.length) { popup.toast(this, '暂时没有可选地点', { tone: 'warn' }); return }
    popup.sheet(this, {
      title: '选择地点',
      actions: locations.map((label) => ({ label })),
      action: ({ index }) => this.setData({ location: locations[index] })
    })
  },

  toggleVisible(event) { this.setData({ visibleToSchool: event.detail.value }) },

  openDrafts() {
    popup.toast(this, '暂无未完成草稿')
  },

  // 上传图片（失败则不带图发布，不阻断流程）
  async uploadMedia(media) {
    if (!media || !media.length) return []
    try {
      return await api.uploadImages(media)
    } catch (error) {
      popup.toast(this, error.message || '图片上传失败，将不带图发布', { tone: 'warn' })
      return []
    }
  },

  async submitPost() {
    if (this.data.submitting) return
    const { title, content, media, activeType, types, location } = this.data
    const type = types[activeType].key
    if (!title.trim()) { popup.toast(this, '请填写标题', { tone: 'warn' }); return }
    if (!content.trim()) { popup.toast(this, '写一点内容再发布吧', { tone: 'warn' }); return }
    // 兼职必须填日薪，否则发出去没有意义
    if (type === 'job' && !(Number(this.data.daySalary) > 0)) {
      popup.toast(this, '请填写日薪', { tone: 'warn' })
      return
    }
    // 闲置必须填有效价格，避免出现「¥0」这种无意义的上架
    if (type === 'idle') {
      const price = Number(this.data.price)
      if (!(price > 0)) { popup.toast(this, '请填写有效价格', { tone: 'warn' }); return }
      if (price > 99999) { popup.toast(this, '价格不能超过 99999', { tone: 'warn' }); return }
    }

    this.setData({ submitting: true })
    try {
      // 只提交服务端地址；上传失败就是没有图，绝不退回本地临时路径
      const uploaded = await this.uploadMedia(media)
      const firstImage = uploaded.length ? uploaded[0] : ''

      if (type === 'idle') {
        // 闲置：走真实的二手市场发布（goods 表），而不是给帖子打个标签
        await api.publishGoods({
          userId: api.CURRENT_USER_ID,
          title: title.trim(),
          price: String(Number(this.data.price) || 0),
          conditionLevel: this.data.conditionLevel,
          category: this.data.category,
          images: uploaded,
          image: firstImage,
          description: content.trim()
        })
        popup.toast(this, '已上架二手市场', { tone: 'success' })
      } else if (type === 'job') {
        await api.publishJob({
          title: title.trim(),
          daySalary: Number(this.data.daySalary) || 0,
          settle: this.data.settle,
          location,
          timeDesc: this.data.timeDesc.trim(),
          description: content.trim()
        })
        popup.toast(this, '兼职已发布', { tone: 'success' })
      } else if (type === 'event') {
        await api.publishActivity({
          title: title.trim(),
          poster: firstImage,
          timeDesc: this.data.timeDesc.trim() || '时间待定',
          location,
          description: content.trim()
        })
        popup.toast(this, '活动已创建', { tone: 'success' })
      } else {
        // 校园动态：发帖到论坛
        const result = await api.publishPost({
          title: title.trim(),
          content: content.trim(),
          media: uploaded,
          type,
          typeLabel: types[activeType].label,
          location,
          createdAt: Date.now()
        })
        if (result.offline) {
          const saved = wx.getStorageSync('mock_posts') || []
          wx.setStorageSync('mock_posts', [result].concat(saved).slice(0, 20))
        }
        popup.toast(this, result.offline ? '已保存到本机演示帖子，未发布到后端' : '发布成功', { tone: result.offline ? 'warn' : 'success' })
      }
      // 回到内容所属的列表页，让用户马上看到自己刚发的东西
      const target = type === 'idle' ? '/pages/market-list/market-list'
        : type === 'job' ? '/pages/jobs/jobs'
        : type === 'event' ? '/pages/activities/activities'
        : '/pages/community/community'
      setTimeout(() => wx.redirectTo({ url: target }), 700)
    } catch (error) {
      popup.toast(this, error.message || '发布失败，请重试', { tone: 'error' })
    } finally {
      if (this._alive !== false) this.setData({ submitting: false })
    }
  },

  onLoad() { this._alive = true },
  onUnload() { this._alive = false },

  onPopupConfirm(event) { this._popupConfirm && this._popupConfirm(event.detail) },
  onPopupCancel() { this._popupCancel && this._popupCancel() },
  onPopupAction(event) { this._popupAction && this._popupAction(event.detail) },
  onPopupInput(event) { this._popupInput && this._popupInput(event.detail) },
  onPopupClose() { popup.hide(this) }
})
