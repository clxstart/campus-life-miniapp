const api = require('../../utils/api')

// 后端返回的字段带命名空间前缀（contact.* / group.*），页面内部统一用不带前缀的名字。
// 之前直接读 cfg.hint 读不到值 —— 接口明明返回了，页面却是空的，就是这里对不上。
const PREFIX = { contact: 'contact.', group: 'group.' }

function pick(cfg, type, name) {
  if (!cfg) return ''
  const prefix = PREFIX[type] || 'contact.'
  const value = cfg[prefix + name]
  return value === undefined || value === null ? '' : String(value)
}

Page({
  data: {
    type: 'contact',
    loading: true,
    error: '',
    title: '',
    hint: '',
    wechatId: '',
    qrUrl: '',
    // 二维码没配时，页面不空白，给一句能照做的说明
    configured: false
  },

  onLoad(query = {}) {
    this._alive = true
    // 同一个页面服务两种入口：一对一咨询（论文指导）和微信群
    const type = query.type === 'group' ? 'group' : 'contact'
    const fallbackTitle = type === 'group' ? '校园微信群' : '论文指导咨询'
    this.setData({ type, title: fallbackTitle })
    wx.setNavigationBarTitle({ title: fallbackTitle })
    this.loadConfig(type)
  },

  onUnload() { this._alive = false },

  async loadConfig(type = this.data.type) {
    try {
      // 都是公开接口，不需要登录；失败也不该卡住页面
      const cfg = type === 'group'
        ? await api.getGroupConfig()
        : await api.getContactConfig()
      if (!this._alive) return
      const qrUrl = pick(cfg, type, 'wechat_qr')
      const title = pick(cfg, type, 'title') || (type === 'group' ? '校园微信群' : '论文指导咨询')
      this.setData({
        loading: false,
        error: '',
        title,
        hint: pick(cfg, type, 'hint'),
        wechatId: pick(cfg, type, 'wechat_id'),
        qrUrl,
        configured: !!qrUrl
      })
      wx.setNavigationBarTitle({ title })
    } catch (error) {
      if (!this._alive) return
      this.setData({ loading: false, error: error.message || '加载失败，请稍后再试' })
    }
  },

  // 复制微信号：比让用户手抄靠谱（只有一对一咨询有）
  copyWechatId() {
    const id = this.data.wechatId
    if (!id) return
    wx.setClipboardData({
      data: id,
      success: () => wx.showToast({ title: '微信号已复制', icon: 'none' })
    })
  },

  // 点二维码放大：长按可保存/识别，微信里最自然的用法
  previewQr() {
    if (!this.data.qrUrl) return
    wx.previewImage({ urls: [this.data.qrUrl], current: this.data.qrUrl })
  },

  goBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.reLaunch({ url: '/pages/profile/profile' })
  }
})
