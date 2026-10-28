/**
 * skeleton-list —— 列表骨架屏
 *
 * 用法：
 *   <skeleton-list wx:if="{{loading && !list.length}}" rows="{{4}}" avatar="{{true}}" />
 *
 * 为什么用骨架屏而不是转圈：
 *   骨架屏保留了列表的版式，内容到达时不会「跳一下」；
 *   转圈是空白 + 圆点，内容到达时整页重排，视觉上更抖。
 */
Component({
  options: { addGlobalClass: true },
  properties: {
    /** 占位条数 */
    rows: { type: Number, value: 3 },
    /** 是否带左侧圆形头像/缩略图 */
    avatar: { type: Boolean, value: false }
  },
  data: {
    items: [0, 1, 2]
  },
  observers: {
    rows(n) {
      const count = Math.max(1, Math.min(10, Number(n) || 3))
      this.setData({ items: Array.from({ length: count }, (_, i) => i) })
    }
  },
  lifetimes: {
    attached() {
      const count = Math.max(1, Math.min(10, Number(this.properties.rows) || 3))
      this.setData({ items: Array.from({ length: count }, (_, i) => i) })
    }
  }
})
