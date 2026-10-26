const mode = require('../../utils/data-mode')
Component({
  properties: { local: { type: Boolean, value: false } },
  data: { demo: false },
  lifetimes: {
    attached() { this._unsubscribe = mode.subscribe(demo => this.setData({ demo })) },
    detached() { if (this._unsubscribe) this._unsubscribe() }
  }
})
