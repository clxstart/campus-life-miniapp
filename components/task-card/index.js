/**
 * task-card —— 派遣任务卡
 *
 * 状态：
 *   idle       未开始（可派遣）
 *   running    进行中（显示倒计时，仅展示）
 *   claimable  可领取（服务端判定到期）
 *   claimed    已完成
 *
 * 注意：倒计时只负责「显示」，到没到期、能不能领，都由服务端返回的
 * status / canClaim 决定，客户端不做奖励发放判断。
 */
const STATUS_LABEL = {
  idle: '可派遣',
  running: '进行中',
  claimable: '可领取',
  claimed: '已完成'
}

Component({
  options: { addGlobalClass: true },
  properties: {
    task: { type: Object, value: {} },
    /** idle | running | claimable | claimed */
    status: { type: String, value: 'idle' },
    /** 剩余秒数，由服务端给出 */
    remainSeconds: { type: Number, value: 0 }
  },
  data: {
    expanded: false,
    statusLabel: '',
    countdownText: ''
  },
  observers: {
    status(v) {
      this.setData({ statusLabel: STATUS_LABEL[v] || '' })
    },
    remainSeconds(v) {
      this.setData({ countdownText: formatRemain(v) })
    }
  },
  lifetimes: {
    attached() {
      // 默认展开「进行中」的任务，其它收起
      if (this.data.status === 'running' || this.data.status === 'claimable') {
        this.setData({ expanded: true })
      }
      this._startTick()
    },
    detached() { this._stopTick() }
  },
  pageLifetimes: {
    show() { this._startTick() },
    hide() { this._stopTick() }
  },
  methods: {
    onToggle() { this.setData({ expanded: !this.data.expanded }) },

    onStart() { this.triggerEvent('start', { task: this.data.task }) },
    onClaim() { this.triggerEvent('claim', { task: this.data.task }) },

    /** 本地每秒递减，纯展示用；真实判定在服务端 */
    _startTick() {
      this._stopTick()
      if (this.data.status !== 'running') return
      this._tickTimer = setInterval(() => {
        const next = Math.max(0, (this.data.remainSeconds || 0) - 1)
        this.setData({ remainSeconds: next, countdownText: formatRemain(next) })
        if (next <= 0) {
          this._stopTick()
          // 到期：通知父级去问服务端，而不是本地直接发奖
          this.triggerEvent('due', { task: this.data.task })
        }
      }, 1000)
    },

    _stopTick() {
      if (this._tickTimer) { clearInterval(this._tickTimer); this._tickTimer = null }
    }
  }
})

function formatRemain(sec) {
  const s = Math.max(0, Number(sec) || 0)
  if (s <= 0) return '即将返回'
  const m = Math.floor(s / 60)
  const r = s % 60
  if (m > 0) return `${m} 分 ${r} 秒`
  return `${r} 秒`
}
