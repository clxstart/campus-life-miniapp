// 学期小工具：教学周、日期解析（课表 / 校历 / 发现页横幅共用）
const DEFAULT_TERM_START = '2026-08-31'

function parseDate(text) {
  const [year, month, day] = text.split('-').map(Number)
  return new Date(year, month - 1, day)
}

// 返回给定日期所在周的周一（零点）
function startOfWeek(date) {
  const offset = (date.getDay() + 6) % 7
  const monday = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  monday.setDate(monday.getDate() - offset)
  return monday
}

// 第几教学周（按周一算周界，开学前按第 1 周处理）
function getTermWeek(date, termStartText = DEFAULT_TERM_START) {
  const monday = startOfWeek(date)
  const termStart = startOfWeek(parseDate(termStartText))
  return Math.max(1, Math.round((monday - termStart) / 604800000) + 1)
}

// 距某个日期还有几天（已过返回 0）
function daysUntil(text, date) {
  return Math.max(0, Math.ceil((parseDate(text) - date) / 86400000))
}

module.exports = { parseDate, startOfWeek, getTermWeek, daysUntil }
