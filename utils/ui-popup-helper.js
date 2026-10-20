// 统一弹窗入口：所有页面通过这里调用 ui-popup 组件
// 用法：const popup = require('<相对路径>/ui-popup-helper')
//   popup.toast(this, '已保存')                        // 轻提示
//   popup.toast(this, '发布成功', { tone: 'success' }) // 指定主题色
//   popup.modal(this, { title: '编辑昵称', editable: true, confirm(res) }) // 对话框
//   popup.sheet(this, { actions: [...], action(res) }) // 底部操作列表
const DEFAULTS = {
  visible: false,
  mode: 'toast',
  tone: 'info',
  title: '',
  message: '',
  showIcon: true,
  editable: false,
  placeholder: '',
  inputValue: '',
  confirmText: '确定',
  cancelText: '取消',
  showCancel: false,
  actions: [],
  maskClosable: true,
  duration: 1600
}

function getPopup(page) {
  if (!page || typeof page.selectComponent !== 'function') return null
  return page.selectComponent('#ui-popup')
}

// 把组件事件接到页面处理函数：弹层先关闭，再把参数交给 options 里的同名回调
// confirm/cancel 会收到 {...弹层状态, inputValue: 输入值}，action 额外带 { index, item }
function register(page, options, callbackName) {
  const key = '_popup' + callbackName[0].toUpperCase() + callbackName.slice(1)
  page[key] = (event) => {
    const current = page.data.uiPopup || {}
    hide(page)
    if (typeof options[callbackName] !== 'function') return
    const detail = (event && event.detail) || event || {}
    const payload = { ...current, ...detail }
    if (detail.value !== undefined) payload.inputValue = detail.value
    if (callbackName === 'action' && Array.isArray(current.actions)) payload.item = current.actions[detail.index]
    Promise.resolve().then(() => options[callbackName](payload)).catch(error => {
      module.exports.toast(page, error.message || '操作失败，请重试', { tone: 'error' })
    })
  }
}

function show(page, options, callbackNames) {
  const popup = getPopup(page)
  if (!popup) {
    // 未挂载组件时兜底走系统弹窗
    wx.showToast({ title: options.message || options.title || '', icon: 'none' })
    return
  }
  page.setData({ uiPopup: { ...DEFAULTS, ...options, visible: true } })
  ;(callbackNames || []).forEach((name) => register(page, options, name))
}

function hide(page) {
  if (page.data.uiPopup) page.setData({ 'uiPopup.visible': false })
}

module.exports = {
  // 轻提示：popup.toast(this, '内容', { tone: 'success', duration: 1600 })
  toast(page, message, options = {}) {
    show(page, { ...options, mode: 'toast', message })
  },

  // 对话框：popup.modal(this, { title, message, editable, confirm(res) })
  modal(page, options = {}) {
    show(page, { ...options, mode: 'modal', showCancel: options.showCancel !== false }, ['confirm', 'cancel'])
  },

  // 底部操作列表：popup.sheet(this, { actions: [{ label }], action(res) })
  sheet(page, options = {}) {
    show(page, { ...options, mode: 'sheet' }, ['action', 'cancel'])
  },

  hide
}
