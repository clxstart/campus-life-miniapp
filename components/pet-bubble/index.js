/**
 * pet-bubble —— 陪伴气泡
 * 自动消失，避免常驻占用视野。
 */
Component({
  options: { addGlobalClass: true },
  properties: {
    text: { type: String, value: '' },
    /** 自动隐藏时长（毫秒），0 表示不自动隐藏 */
    duration: { type: Number, value: 2600 }
  },
  data: { visible: false, leaving: false },
  observers: {
    text(v) {
      if (!v) { this.setData({ visible: false }); return }
      this._show()
    }
  },
  lifetimes: {
    detached() { this._clear() }
  },
  methods: {
    _clear() {
      if (this._hideTimer) { clearTimeout(this._hideTimer); this._hideTimer = null }
      if (this._outTimer) { clearTimeout(this._outTimer); this._outTimer = null }
    },
    _show() {
      this._clear()
      this.setData({ visible: true, leaving: false })
      if (this.data.duration > 0) {
        this._hideTimer = setTimeout(() => {
          this.setData({ leaving: true })
          this._outTimer = setTimeout(() => this.setData({ visible: false, leaving: false }), 180)
        }, this.data.duration)
      }
    }
  }
})
