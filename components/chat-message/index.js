Component({
  options: { addGlobalClass: true },
  properties: {
    role: { type: String, value: 'pet' },        // pet | user
    content: { type: String, value: '' },
    status: { type: String, value: 'done' },      // sending | done | failed
    failReason: { type: String, value: '' },
    mock: { type: Boolean, value: false },
    petName: { type: String, value: '小云' },
    appearance: { type: Object, value: {} },
    result: { type: Object, value: null }         // 校园数据结果
  },
  methods: {
    onRetry() { this.triggerEvent('retry', {}) },
    onOpenResult(e) { this.triggerEvent('openresult', e.detail) }
  }
})
