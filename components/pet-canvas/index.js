/**
 * pet-canvas —— 宠物主渲染组件
 *
 * 实现：底层复用 pet-avatar（小橘猫分部件 Canvas 动画）。
 * 本组件负责：尺寸适配、动作映射、点击转发、生命周期控制。
 *
 * 美术与引擎来源：orange-pet-source（用户提供）
 *   引擎支持 idle / happy / sad / sleepy / celebrate，
 *   本组件对外只开放 idle / happy / sad 三个（sleepy 与 celebrate 已按要求下线）。
 *
 * 关于配饰：原引擎只画猫本体，不含配饰图层。经确认不做 CSS 手画配饰，
 * 因此 appearance.accessory 不参与渲染。
 */
const ACTION_MAP = {
  idle: 'idle',
  happy: 'happy',
  sad: 'sad',
  // 其他动作统一归到开心，避免调用方传旧动作名时报「不支持的动作」
  cheer: 'happy',
  celebrate: 'happy',
  sleep: 'idle',
  eat: 'happy',
  walk: 'idle'
}

Component({
  options: { addGlobalClass: true },

  properties: {
    /** 保留接口以兼容页面传参；当前形象固定，不参与渲染 */
    appearance: { type: Object, value: {} },
    motion: { type: String, value: 'idle' },
    size: { type: Number, value: 240 },
    reduceMotion: { type: Boolean, value: false },
    interactive: { type: Boolean, value: true },
    autoPlay: { type: Boolean, value: true },
    label: { type: String, value: '小橘，点击互动' }
  },

  data: {
    action: '',
    paused: false,
    speed: 1
  },

  observers: {
    motion(v) {
      const mapped = ACTION_MAP[v] || 'idle'
      // idle 交给自动循环，不必手动触发
      this.setData({ action: mapped === 'idle' ? '' : mapped })
    }
  },

  lifetimes: {
    attached() { this._alive = true },
    detached() { this._alive = false }
  },

  pageLifetimes: {
    // 页面隐藏暂停动画省电，显示恢复
    show() { if (this._alive) this.setData({ paused: false }) },
    hide() { if (this._alive) this.setData({ paused: true }) }
  },

  methods: {
    /** 点击宠物：转发给页面，同时播一个开心反馈 */
    onTouchStart() {
      if (!this.data.interactive) return
      const inner = this.selectComponent('#inner')
      if (inner && inner.play) inner.play('happy')
      this.triggerEvent('pettap', {})
    },

    /** 主动播放某个动作（供页面调用） */
    play(action) {
      const inner = this.selectComponent('#inner')
      if (inner && inner.play) return inner.play(ACTION_MAP[action] || action)
      return false
    },

    onInnerState(e) { this.triggerEvent('statechange', e.detail) },
    onInnerError(e) { this.triggerEvent('error', e.detail) },

    redraw() { /* 动画引擎自行管理重绘 */ }
  }
})
