/**
 * campus-result-card —— 校园数据结果卡片
 *
 * 必须有来源和更新时间，避免用户把模型生成内容当成权威数据。
 */
const TYPE_NAME = {
  goods: '二手',
  activity: '活动',
  job: '兼职',
  post: '帖子',
  circle: '圈子'
}

Component({
  options: { addGlobalClass: true },
  properties: {
    result: { type: Object, value: null }
  },
  data: {
    typeName: '',
    updatedText: ''
  },
  observers: {
    'result.**': function (r) {
      if (!r) return
      this.setData({
        typeName: TYPE_NAME[r.type] || '校园',
        updatedText: formatTime(r.updatedAt)
      })
    }
  },
  methods: {
    onOpen() {
      const r = this.data.result
      if (!r || !r.targetId) return
      this.triggerEvent('open', { type: r.type, targetId: r.targetId, url: r.url || '' })
    }
  }
})

function formatTime(v) {
  if (!v) return '时间未知'
  const d = new Date(String(v).replace(/-/g, '/'))
  if (isNaN(d.getTime())) return String(v)
  const pad = n => (n < 10 ? '0' + n : '' + n)
  const now = new Date()
  const sameDay = d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth()
    && d.getDate() === now.getDate()
  if (sameDay) return `今天 ${pad(d.getHours())}:${pad(d.getMinutes())}`
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
