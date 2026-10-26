// 时间显示归一化：后端 LocalDateTime 序列化值（2026-10-09T10:34:44）转成「MM-DD HH:mm」
// mock 数据里的「12分钟前」「刚刚」等文案原样返回
function formatTime(value) {
  if (value === null || value === undefined || value === '') return ''
  if (typeof value === 'string') {
    const matched = value.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/)
    if (matched) return `${matched[2]}-${matched[3]} ${matched[4]}:${matched[5]}`
  }
  return value
}

module.exports = { formatTime }
