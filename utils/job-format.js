/**
 * 兼职域的展示格式化：金额 / 状态 / 计价单位 / 服务费规则。
 *
 * 为什么单独一个文件：
 *   金额在数据库里一律是**分**（payCents=2000 表示 20.00 元），
 *   分→元的换算只要有一处写错（少乘 100、忘了两位小数），用户看到的钱就是错的。
 *   所以只在这里换算一次，页面里禁止再出现 `/100` 或 `toFixed` 拼金额。
 *
 * 另一条约束：服务费与净收入**只展示后端算好的值**（合约结算里的 settlement），
 *   这里不重算公式 —— 费率按接单时的快照，前端重算一定会和账单对不上。
 */

// 计价单位：payUnit 是英文枚举，页面上必须显示成中文
const PAY_UNIT_TEXT = {
  hour: '小时',
  order: '单',
  shift: '班次',
  day: '天',
  custom: '次'
}

// 岗位状态机（与后端 JOB_FLOW 一致）：open/recruiting 才是可接可报的状态
const WORKFLOW_TEXT = {
  open: '招募中',
  recruiting: '招募中',
  taken: '已接单',
  working: '履约中',
  pending_confirm: '待确认工时',
  settled: '已结算',
  disputed: '争议处理中',
  refunded: '已退回',
  cancelled: '已取消'
}
// 只有 open / recruiting 两种状态可以接单或报名
const OPEN_STATUS = ['open', 'recruiting']

// 履约单状态（不是岗位状态：一张岗位可能有多张履约单）
const CONTRACT_TEXT = {
  created: '待开工',
  working: '履约中',
  pending_confirm: '待确认工时',
  settled: '已结算',
  disputed: '争议中',
  refunded: '已退回',
  cancelled: '已取消'
}

// 报名状态（校外岗位由发布者筛选）
const APPLICATION_TEXT = {
  pending: '待筛选',
  accepted: '已录用',
  rejected: '未通过'
}

const SCOPE_TEXT = { campus: '校内互助', offsite: '校外兼职' }
const TAKE_MODE_TEXT = { first_come: '先到先接', apply: '报名筛选' }
const CATEGORY_TEXT = {
  other: '其他',
  takeover_class: '替课',
  tutor: '家教',
  promotion: '推广',
  service: '生活服务',
  delivery: '配送'
}
// 履约账单条目：不是钱包余额、不是已付款
const LEDGER_TEXT = {
  job_payable: '雇主支付额',
  platform_fee: '平台服务费',
  job_income: '兼职者净收入'
}
const LEDGER_STATUS_TEXT = { frozen: '冻结中', settled: '已结算', refunded: '已退回' }

/** 分 → 元，固定两位小数。非法值给 0.00，绝不显示 NaN。 */
function formatCents(cents) {
  const value = Number(cents)
  if (!isFinite(value)) return '0.00'
  return (value / 100).toFixed(2)
}

/** 分 → 「¥20.00」 */
function moneyText(cents) {
  return '¥' + formatCents(cents)
}

/** 计价单位中文 */
function payUnitText(unit) {
  return PAY_UNIT_TEXT[unit] || PAY_UNIT_TEXT.custom
}

/**
 * 报酬展示：「¥80.00/天」。
 * payCents 为 0 时是真实的「无偿」，不能显示成 0.00 元，也不能编一个价格出来。
 */
function payText(cents, unit) {
  const value = Number(cents) || 0
  if (value <= 0) return '无偿'
  return moneyText(value) + '/' + payUnitText(unit)
}

/** 工时展示：2 → 2，2.5 → 2.5，未填写 → '—' */
function hoursText(hours) {
  if (hours === null || hours === undefined || hours === '') return '—'
  const value = Number(hours)
  if (!isFinite(value)) return '—'
  return String(Math.round(value * 100) / 100)
}

/** 岗位状态中文 */
function workflowText(status) {
  return WORKFLOW_TEXT[status] || '状态未知'
}
/** 岗位状态对应的样式名（wxss 里 .st-open / .st-taken 等） */
function workflowClass(status) {
  return 'st-' + (WORKFLOW_TEXT[status] ? status : 'unknown')
}
/** 这个状态还能接单/报名吗 */
function isOpenStatus(status) {
  return OPEN_STATUS.indexOf(status) >= 0
}

function contractText(status) {
  return CONTRACT_TEXT[status] || '状态未知'
}
function contractClass(status) {
  return 'st-' + (CONTRACT_TEXT[status] ? status : 'unknown')
}
function applicationText(status) {
  return APPLICATION_TEXT[status] || '未知状态'
}
function scopeText(scope) {
  return SCOPE_TEXT[scope] || '校内互助'
}
function takeModeText(mode) {
  return TAKE_MODE_TEXT[mode] || TAKE_MODE_TEXT.first_come
}
function categoryText(key) {
  return CATEGORY_TEXT[key] || CATEGORY_TEXT.other
}
function ledgerText(entryType) {
  return LEDGER_TEXT[entryType] || '履约账单'
}
function ledgerStatusText(status) {
  return LEDGER_STATUS_TEXT[status] || status || ''
}

/**
 * 平台服务费规则（只描述规则，不计算金额）。
 * feeValue 是分钟口径的整数：amount 模式单位是「分/计价单位」，rate 模式是万分比（500 = 5%）。
 */
function feeRuleText(feeMode, feeValue) {
  if (feeMode === 'amount') {
    return '每计价单位固定服务费 ' + moneyText(feeValue)
  }
  if (feeMode === 'rate') {
    const percent = (Number(feeValue) || 0) / 100
    return '按报酬的 ' + (Math.round(percent * 100) / 100) + '% 收取服务费'
  }
  return '本岗位暂无平台服务费'
}

/** 名额：「招 3 人 · 已录用 1 人」 */
function headcountText(headcount, acceptedCount) {
  const total = Number(headcount) || 0
  const taken = Number(acceptedCount) || 0
  if (total <= 0) return ''
  return '招 ' + total + ' 人 · 已录用 ' + taken + ' 人'
}

/** 时间：后端给 2026-10-31T21:38:29，展示成 2026-10-31 21:38 */
function dateText(value) {
  if (!value) return ''
  const text = String(value).replace('T', ' ')
  return text.length >= 16 ? text.slice(0, 16) : text
}

/**
 * 合约结算三块金额（§5.2 要求分别展示）。
 * 数值全部来自后端 settlement，前端只做 分→元 格式化。
 */
function settlementView(settlement) {
  const data = settlement || {}
  const hours = Number(data.hours)
  return {
    // 还没提交工时的时候后端给 0：这种情况页面要说「尚未产生」，不能写「按 0 小时结算」
    hasHours: isFinite(hours) && hours > 0,
    hoursText: hoursText(data.hours),
    payableText: moneyText(data.payableCents),
    feeText: moneyText(data.feeCents),
    incomeText: moneyText(data.incomeCents),
    payText: payText(data.payCents, data.payUnit),
    feeRuleText: feeRuleText(data.feeMode, data.feeValue)
  }
}

/** 列表卡片用的一整份派生字段：页面不再各写一遍 */
function jobView(job) {
  const item = job || {}
  const pay = Number(item.payCents) || 0
  return {
    id: item.id,
    title: item.title || '未命名岗位',
    description: item.description || '',
    location: item.location || '',
    timeDesc: item.timeDesc || '',
    scope: item.scope || 'campus',
    scopeText: scopeText(item.scope),
    takeMode: item.takeMode || 'first_come',
    takeModeText: takeModeText(item.takeMode),
    categoryText: categoryText(item.categoryKey),
    settle: item.settle || '',
    payText: payText(item.payCents, item.payUnit),
    // 有偿岗位才需要认证；无偿互助不需要
    paid: pay > 0,
    workflowStatus: item.workflowStatus || '',
    statusText: workflowText(item.workflowStatus),
    statusClass: workflowClass(item.workflowStatus),
    open: isOpenStatus(item.workflowStatus) && item.auditStatus === 'passed',
    headcountText: headcountText(item.headcount, item.acceptedCount),
    createdAtText: dateText(item.createdAt),
    publisherName: item.publisherName || ''
  }
}

module.exports = {
  formatCents,
  moneyText,
  payUnitText,
  payText,
  hoursText,
  workflowText,
  workflowClass,
  isOpenStatus,
  contractText,
  contractClass,
  applicationText,
  scopeText,
  takeModeText,
  categoryText,
  ledgerText,
  ledgerStatusText,
  feeRuleText,
  headcountText,
  dateText,
  settlementView,
  jobView
}
