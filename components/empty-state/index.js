/**
 * empty-state —— 通用空状态
 *
 * 文案要具体（「还没有外卖订单，去点单吧」），不要用「暂无数据」。
 * 检索类场景建议传 actionText="清空筛选"。
 */
Component({
  options: { addGlobalClass: true },
  properties: {
    icon: { type: String, value: '🍃' },
    title: { type: String, value: '这里还什么都没有' },
    desc: { type: String, value: '' },
    actionText: { type: String, value: '' }
  },
  methods: {
    onAction() {
      this.triggerEvent('action')
    }
  }
})
