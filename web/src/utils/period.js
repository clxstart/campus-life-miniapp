// 作息配置：解析 / 序列化 / 预设 / 校验
//
// 背景：管理端的「作息配置」原来是一个裸 JSON 文本框，管理员要手写
//   {"1":"08:00","2":"10:00","3":"14:00","4":"16:00","5":"19:00"}
// 写错一个引号就存不进去，而且没人知道键 1-5 对应第几节课。
//
// 现在改成「预设下拉 + 每节时间选择器」，底层仍存 JSON 到 school.period_config，
// 保持与后端字段兼容。写入统一用数组格式，因为小程序课表页本地存储用的就是这个格式，
// 以后把学校作息下发给小程序时不需要再做转换。

/** 节次数：与小程序课表页的 PERIODS 保持一致（一~五） */
export const PERIOD_COUNT = 5

/** 每节的默认时间 */
export const DEFAULT_TIMES = ['08:00', '10:00', '14:00', '16:00', '19:00']

/** 节次中文序号，用于界面标签与错误提示 */
export const PERIOD_LABELS = ['一', '二', '三', '四', '五', '六', '七', '八']

/**
 * 预设作息模板。学生/学校常见的几种排法。
 * times 长度必须等于 PERIOD_COUNT。
 */
export const PERIOD_PRESETS = [
  {
    key: 'standard',
    label: '标准作息（8:00 起）',
    hint: '最常见的排法：上午两节、下午两节、晚上一节',
    times: ['08:00', '10:00', '14:00', '16:00', '19:00']
  },
  {
    key: 'early',
    label: '早八紧凑（7:40 起）',
    hint: '第一节课提前到 7:40，适合早课多的学校',
    times: ['07:40', '09:40', '13:40', '15:40', '18:40']
  },
  {
    key: 'late',
    label: '晚起作息（8:30 起）',
    hint: '上午第一节课 8:30 开始',
    times: ['08:30', '10:30', '14:30', '16:30', '19:30']
  },
  {
    key: 'no-evening',
    label: '无晚课（只在白天）',
    hint: '第五节课放在下午 18:00，晚上不上课',
    times: ['08:00', '10:00', '14:00', '16:00', '18:00']
  }
]

const TIME_RE = /^([01]\d|2[0-3]):([0-5]\d)$/

function isTime(value) {
  return typeof value === 'string' && TIME_RE.test(value)
}

/** 把各种可能的存储形态归一成 5 个时间字符串 */
function normalize(raw) {
  const out = DEFAULT_TIMES.slice()

  // 1) 数组格式：["08:00", ...]
  if (Array.isArray(raw)) {
    raw.forEach((value, i) => {
      if (i < PERIOD_COUNT && isTime(value)) out[i] = value
    })
    return out
  }

  // 2) 对象格式：{"1":"08:00", ...}，键从 1 开始
  if (raw && typeof raw === 'object') {
    for (let i = 0; i < PERIOD_COUNT; i += 1) {
      const value = raw[String(i + 1)]
      if (isTime(value)) out[i] = value
    }
    return out
  }

  return out
}

/**
 * 解析数据库里的 period_config 文本。
 * 永远返回长度为 PERIOD_COUNT 的合法时间数组——解析失败就回落到默认作息，
 * 绝不抛异常（管理端表格要能正常渲染）。
 */
export function parsePeriodConfig(raw) {
  if (raw === null || raw === undefined) return DEFAULT_TIMES.slice()

  const text = typeof raw === 'string' ? raw.trim() : raw
  if (text === '') return DEFAULT_TIMES.slice()

  // 已经是对象/数组，直接归一
  if (typeof text !== 'string') return normalize(text)

  let parsed
  try {
    parsed = JSON.parse(text)
  } catch {
    // 非法 JSON（比如管理员手写坏了的历史数据）→ 回落默认
    return DEFAULT_TIMES.slice()
  }

  return normalize(parsed)
}

/**
 * 序列化成存库字符串。统一用数组格式，与小程序课表页一致。
 * 输入不合法时也产出合法 JSON，避免把脏数据写进数据库。
 */
export function serializePeriodConfig(times) {
  return JSON.stringify(parsePeriodConfig(times))
}

/**
 * 判断当前时间表命中哪套预设，命中返回 key，否则 null（=自定义）。
 * 用于界面下拉框回显。
 */
export function matchPreset(times) {
  const normalized = parsePeriodConfig(times)
  const hit = PERIOD_PRESETS.find(p => p.times.every((t, i) => t === normalized[i]))
  return hit ? hit.key : null
}

/**
 * 校验时间表，返回空串表示通过，否则返回中文错误信息。
 */
export function validatePeriods(times) {
  if (!Array.isArray(times) || times.length !== PERIOD_COUNT) {
    return `作息需要配置 ${PERIOD_COUNT} 节课的时间`
  }

  for (let i = 0; i < times.length; i += 1) {
    if (!isTime(times[i])) {
      return `第${PERIOD_LABELS[i]}节的时间格式不对，应为 HH:MM（如 08:00）`
    }
  }

  for (let i = 1; i < times.length; i += 1) {
    if (times[i] <= times[i - 1]) {
      return `第${PERIOD_LABELS[i]}节（${times[i]}）必须晚于第${PERIOD_LABELS[i - 1]}节（${times[i - 1]}），节次时间要递增`
    }
  }

  return ''
}
