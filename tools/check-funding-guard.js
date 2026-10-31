/**
 * 资金红线检查（fail-closed 断言）
 *
 * 目的：把「不实现通用钱包 / 充值 / 提现 / 余额支付」从口头约定变成可回归的断言。
 * 这个仓库在拿到支付机构书面能力确认前，不允许出现任何可写资金入口或余额表。
 *
 * 检查项：
 *   1. 后端没有任何 wallet 相关的写接口（POST/PUT/DELETE），只允许只读状态接口
 *   2. 全仓库不出现提现 / 充值 / 余额支付接口
 *   3. 迁移脚本里不出现 wallet / balance 表
 *   4. 安全闸门默认全部关闭（enabled=0）
 */
const fs = require('fs')
const path = require('path')

const ROOT = process.cwd()
const BACKEND = path.join(ROOT, 'backend', 'src', 'main', 'java', 'com', 'campuslife')
const DB = path.join(ROOT, 'backend', 'src', 'main', 'resources', 'db')

let pass = 0
let fail = 0
function check(name, ok, detail) {
  if (ok) { console.log(`✅ ${name}`); pass++ }
  else { console.log(`❌ ${name}${detail ? ' — ' + detail : ''}`); fail++ }
}

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walk(full, out)
    else if (entry.name.endsWith('.java')) out.push(full)
  }
  return out
}

const javaFiles = walk(BACKEND)
const sqlFiles = fs.existsSync(DB) ? fs.readdirSync(DB).filter(f => f.endsWith('.sql')).map(f => path.join(DB, f)) : []

// ---- 1. 不存在 wallet 写接口 ----
const walletWrites = []
const forbidWrite = /@(Post|Put|Delete|Patch)Mapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"/
for (const file of javaFiles) {
  const src = fs.readFileSync(file, 'utf8')
  if (!/wallet/i.test(src)) continue
  // 只关心路径里真的带 wallet 的写映射
  for (const line of src.split('\n')) {
    const m = forbidWrite.exec(line)
    if (m && /wallet/i.test(m[2])) walletWrites.push(`${path.basename(file)} ${m[1]}:${m[2]}`)
  }
}
check('后端没有 wallet 写接口（禁止充值/支付/提现）', walletWrites.length === 0, walletWrites.join('; '))

// ---- 2. 不出现提现/充值/余额支付接口 ----
const risky = []
for (const file of javaFiles) {
  const src = fs.readFileSync(file, 'utf8')
  for (const line of src.split('\n')) {
    const m = /@(?:Post|Put|Delete|Patch)Mapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"/.exec(line)
    if (!m) continue
    if (/(withdraw|recharge|deposit|topup|top-up|balance-pay)/i.test(m[1])) risky.push(`${path.basename(file)}:${m[1]}`)
  }
}
check('没有提现/充值/余额支付接口', risky.length === 0, risky.join('; '))

// ---- 3. 迁移脚本里没有钱包/余额表 ----
const badTables = []
for (const file of sqlFiles) {
  const src = fs.readFileSync(file, 'utf8')
  const re = /CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([a-z0-9_]+)`?/gi
  let m
  while ((m = re.exec(src)) !== null) {
    if (/(wallet|balance|withdraw|recharge)/i.test(m[1])) badTables.push(`${path.basename(file)}:${m[1]}`)
  }
}
check('迁移脚本里没有钱包/余额表', badTables.length === 0, badTables.join('; '))

// ---- 4. 安全闸门默认关闭 ----
const gatesFile = path.join(DB, 'module-safety-gates.sql')
let gateOk = false
let gateDetail = '未找到 module-safety-gates.sql'
if (fs.existsSync(gatesFile)) {
  const src = fs.readFileSync(gatesFile, 'utf8')
  const rows = [...src.matchAll(/\('([a-z_.]+)',\s*(\d)/g)].map(m => ({ key: m[1], enabled: m[2] }))
  const mustClose = ['wallet.enabled', 'job.offsite.enabled', 'job.paid.enabled', 'rider.grab.enabled',
                     'job.category.takeover_class.enabled', 'verification.student.enabled']
  const opened = mustClose.filter(k => {
    const row = rows.find(r => r.key === k)
    return !row || row.enabled !== '0'
  })
  gateOk = opened.length === 0
  gateDetail = opened.length ? '未声明或不是 0：' + opened.join(', ') : ''
}
check('安全闸门默认全部关闭', gateOk, gateDetail)

// ---- 5. 钱包接口只读且不含金额字段 ----
const walletController = path.join(BACKEND, 'controller', 'WalletController.java')
let walletOk = false
let walletDetail = '未找到 WalletController.java'
if (fs.existsSync(walletController)) {
  const src = fs.readFileSync(walletController, 'utf8')
  const hasBalanceKey = /put\("(balance|available|withdrawable|amount)"\s*,/i.test(src)
  const alwaysDisabled = /out\.put\("enabled",\s*false\)/.test(src)
  walletOk = !hasBalanceKey && alwaysDisabled
  walletDetail = hasBalanceKey ? '出现了金额字段' : (!alwaysDisabled ? 'enabled 不是固定 false' : '')
}
check('钱包状态接口固定 enabled=false 且不含金额字段', walletOk, walletDetail)

console.log('')
if (fail === 0) { console.log(`✅ 资金红线全部通过（${pass} 项）`); process.exit(0) }
console.log(`❌ 资金红线 ${fail} 项失败`)
process.exit(1)
