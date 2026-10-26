const TONE_COLORS = {
  info: '#346FF9',
  success: '#2FB77C',
  warn: '#F0A03C',
  error: '#FF5B52'
}

Component({
  options: {
    multipleSlots: false
  },

  properties: {
    visible: {
      type: Boolean,
      value: false
    },
    // 展示形态：toast 轻提示 / modal 对话框 / sheet 底部操作列表
    // 页面在未弹窗时会把 uiPopup 置为 null，导致这些 String 属性收到 undefined。
    // 用 null 作为可选值，避免控制台刷出大量「type-incompatible」警告。
    mode: {
      type: null,
      value: 'toast'
    },
    // 主题色：info / success / warn / error，仅颜色不同，结构完全一致
    tone: {
      type: null,
      value: 'info'
    },
    title: {
      type: null,
      value: ''
    },
    message: {
      type: null,
      value: ''
    },
    // toast 顶部彩色圆形图标
    showIcon: {
      type: Boolean,
      value: true
    },
    // modal 内输入框
    editable: {
      type: Boolean,
      value: false
    },
    placeholder: {
      type: null,
      value: ''
    },
    inputValue: {
      type: null,
      value: ''
    },
    confirmText: {
      type: null,
      value: '确定'
    },
    cancelText: {
      type: null,
      value: '取消'
    },
    showCancel: {
      type: Boolean,
      value: false
    },
    // sheet 操作项：[{ label }]
    actions: {
      type: Array,
      value: []
    },
    // 点击遮罩是否关闭
    maskClosable: {
      type: Boolean,
      value: true
    },
    // toast 自动关闭时长（ms）
    duration: {
      type: Number,
      value: 1600
    }
  },

  data: {
    toneColor: TONE_COLORS.info,
    iconChar: '✓',
    leaving: false
  },

  observers: {
    'tone, visible, mode'(tone, visible, mode) {
      // 页面未弹窗时 uiPopup 为 null，属性可能收到空值；这里归一化，
      // 保证 mode/tone 始终是有效字符串（WXML 里用 === 判断，空值会让弹窗不显示）。
      const safeMode = mode || 'toast'
      const safeTone = tone || 'info'
      const patch = {}
      if (safeMode !== mode) patch.mode = safeMode
      if (safeTone !== tone) patch.tone = safeTone
      const iconChars = { info: 'i', success: '✓', warn: '!', error: '✕' }
      patch.toneColor = TONE_COLORS[safeTone] || TONE_COLORS.info
      patch.iconChar = iconChars[safeTone] || iconChars.info
      this.setData(patch)
      if (this._hideTimer) {
        clearTimeout(this._hideTimer)
        this._hideTimer = null
      }
      if (visible && safeMode === 'toast' && this.data.duration > 0) {
        this._hideTimer = setTimeout(() => this.close(), this.data.duration)
      }
    }
  },

  lifetimes: {
    detached() {
      if (this._hideTimer) clearTimeout(this._hideTimer)
    }
  },

  methods: {
    close() {
      if (this.data.mode === 'toast') {
        this.triggerEvent('close')
        return
      }
      if (!this.data.maskClosable) return
      this.triggerEvent('cancel')
    },

    noop() {},

    onInput(event) {
      // 同步到组件 data，否则 confirm 事件带出去的 value 永远是初始值
      this.setData({ inputValue: event.detail.value })
      this.triggerEvent('input', { value: event.detail.value })
    },

    onCancel() {
      if (this._hideTimer) {
        clearTimeout(this._hideTimer)
        this._hideTimer = null
      }
      this.triggerEvent('cancel')
    },

    onConfirm() {
      if (this._hideTimer) {
        clearTimeout(this._hideTimer)
        this._hideTimer = null
      }
      this.triggerEvent('confirm', { value: this.data.inputValue })
    },

    onAction(event) {
      this.triggerEvent('action', { index: Number(event.currentTarget.dataset.index) })
    }
  }
})
