// 表情面板：只使用 Unicode emoji 字符（方案硬要求：不做图片/动图/自定义表情包）。
// 按使用场景粗分 5 类，每类 14 个，共 70 个，覆盖校园聊天常见表达。
const EMOJI_GROUPS = [
  { name: '表情', items: ['😀', '😄', '😂', '🤣', '😊', '😍', '🥰', '😜', '🤔', '😴', '😭', '😡', '😅', '🥳'] },
  { name: '手势', items: ['👍', '👎', '👏', '🙌', '🤝', '✌️', '👌', '🙏', '💪', '👋', '🤙', '🤞', '👊', '✊'] },
  { name: '生活', items: ['🔥', '✨', '💯', '✅', '❌', '⭐', '🎉', '🎁', '💡', '📚', '✏️', '🌙', '☀️', '🌈'] },
  { name: '食物', items: ['🍚', '🍜', '🍕', '🍔', '🍟', '🍰', '🍎', '🍉', '🍇', '🍓', '🧋', '☕', '🍺', '🍿'] },
  { name: '运动', items: ['⚽', '🏀', '🏃', '🚴', '🏋️', '🏊', '🎾', '🏐', '🥇', '🏆', '🎯', '🏸', '🛹', '🧘'] }
]

Component({
  properties: {
    visible: { type: Boolean, value: false },
    // 面板贴在输入框上方：底部偏移由页面按自己的输入栏高度传入（含 iPhone 底部安全区）。
    // 默认值本身也带 env(safe-area-inset-bottom)，页面忘了传时不会被 Home 指示条压住。
    bottom: { type: String, value: 'calc(100rpx + env(safe-area-inset-bottom))' }
  },

  data: {
    groups: EMOJI_GROUPS,
    activeIndex: 0
  },

  methods: {
    switchGroup(event) {
      const index = Number(event.currentTarget.dataset.index)
      if (!Number.isSafeInteger(index) || index < 0 || index >= this.data.groups.length) return
      if (index === this.data.activeIndex) return
      this.setData({ activeIndex: index })
    },

    pickEmoji(event) {
      const emoji = event.currentTarget.dataset.emoji
      if (!emoji) return
      // 字符由页面自己插入到光标处，组件不持有输入框状态
      this.triggerEvent('pick', { emoji })
    },

    closePanel() { this.triggerEvent('close') }
  }
})
