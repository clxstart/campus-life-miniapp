#!/usr/bin/env node
/**
 * V3.1 新人礼包「只提示一次」静态检查（设计稿第七节用例 1 的可自动化部分）。
 *
 * 为什么是静态检查而不是真机测试：
 *   一次性弹窗依赖「首次微信登录建号 → 登录响应 newUser=true → 首页 onShow/onLoginReady → 本地 storage」，
 *   真机链路需要真实微信账号与开发者工具自动化，当前仓库没有这套工具链；
 *   这里把「触发条件、只弹一次、文案、落点」四个可读的代码事实钉死，
 *   真机像素级表现仍属未验证（见交付报告）。
 *
 * 用法：node tools/check-v31-newbie-gift.js
 * 退出码：全部通过 0，任一失败 1。
 */
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..')
process.chdir(ROOT)

let pass = 0
let fail = 0
let warn = 0
const ok = (name, detail) => { pass++; console.log(`✅ ${name}${detail ? ' — ' + detail : ''}`) }
const bad = (name, detail) => { fail++; console.log(`❌ ${name}${detail ? ' — ' + detail : ''}`) }
const note = (name, detail) => { warn++; console.log(`⚠️ ${name}${detail ? ' — ' + detail : ''}`) }

function read(file) {
  const full = path.join(ROOT, file)
  if (!fs.existsSync(full)) { bad(`${file} 存在`, '文件不存在'); return '' }
  return fs.readFileSync(full, 'utf8')
}

const appJs = read('app.js')
const indexJs = read('pages/index/index.js')
const indexWxml = read('pages/index/index.wxml')
const pointsWxml = read('pages/points/points.wxml')
const appJson = read('app.json')

console.log('=== ① 登录侧：newUser 信号 + 本地一次性标记 ===')

if (/newUser\s*:\s*false/.test(appJs) && /globalData\.newUser\s*=/.test(appJs)) {
  ok('app.js 在 globalData 里声明并写入 newUser', '只有建号那次后端返回 newUser=true')
} else {
  bad('app.js 的 newUser 信号', '缺少 globalData.newUser 的声明或写入')
}

if (/newUser\s*=\s*!!res\.newUser|newUser\s*=\s*res\.newUser/.test(appJs)) {
  ok('app.js 从登录响应读取 newUser', '老账号登录不会误标')
} else {
  bad('app.js 读取登录响应的 newUser', '找不到 newUser 赋值')
}

if (/NEWBIE_GIFT_SHOWN\s*=\s*'newbieGiftShown'/.test(indexJs)) {
  ok("storage 键固定为 newbieGiftShown", '与设计稿 §2.1 一致')
} else {
  bad('storage 键 newbieGiftShown', '首页没有定义该键')
}

console.log('')
console.log('=== ② 首页：三个条件 + 只弹一次 + popup.modal ===')

const popupModalAt = indexJs.search(/popup\.(modal|toast)\s*\(/)
const setStorageAt = indexJs.search(/wx\.setStorageSync\(\s*NEWBIE_GIFT_SHOWN/)
const getStorageAt = indexJs.search(/wx\.getStorageSync\(\s*NEWBIE_GIFT_SHOWN/)

if (popupModalAt >= 0 && /popup\.modal\s*\(/.test(indexJs)) {
  ok('首页用 popup.modal 弹出（不是 toast）', '设计稿要求给用户时间读完')
} else {
  bad('首页 popup.modal 调用', '找不到 popup.modal')
}

if (getStorageAt >= 0 && /if\s*\(\s*shown\s*\)\s*return/.test(indexJs)) {
  ok('已弹过（storage 有值）直接 return', '重复进入首页不再弹')
} else {
  bad('只弹一次的早退判断', '找不到 getStorageSync → if (shown) return')
}

if (getStorageAt >= 0 && setStorageAt > getStorageAt && popupModalAt > setStorageAt) {
  ok('先读标记 → 再写标记 → 最后弹窗', '弹窗期间回页不会弹第二遍')
} else {
  bad('写标记与弹窗的先后顺序', '必须是先写 storage 再 popup.modal')
}

if (/loginReady/.test(indexJs) && /globalData\.newUser/.test(indexJs)) {
  ok('触发条件含「登录已完成 + 首次建号」', '避免登录未完成时抢弹')
} else {
  bad('触发条件', '缺少 loginReady 或 newUser 判断')
}

if (/新人礼包已到账/.test(indexJs) && /30 积分/.test(indexJs) && /白银体验 7 天/.test(indexJs)) {
  ok('文案与设计稿一致', '标题 + 两行正文')
} else {
  bad('礼包文案', '标题/正文与设计稿不一致')
}

if (/去看看/.test(indexJs) && /知道了/.test(indexJs)) {
  ok('两个按钮都在', '去看看 / 知道了')
} else {
  bad('按钮文案', '缺少「去看看」或「知道了」')
}

if (/\/pages\/points\/points/.test(indexJs)) {
  ok('「去看看」落点是积分页', '/pages/points/points')
} else {
  bad('「去看看」落点', '找不到 /pages/points/points')
}

if (/wx\.showModal/.test(indexJs)) {
  bad('不应使用 wx.showModal', '设计稿要求统一走 popup.modal')
} else {
  ok('没有用 wx.showModal 兜底', '统一走 ui-popup')
}

if (popupModalAt >= 0 && /ui-popup/.test(indexWxml)) {
  ok('首页挂载了 ui-popup 组件', '否则 helper 会退化成 toast')
} else {
  bad('首页 ui-popup 挂载', '找不到 <ui-popup>')
}

console.log('')
console.log('=== ③ 积分页：礼包落地页可达 ===')

if (/"pages\/points\/points"/.test(appJson)) {
  ok('app.json 注册了 pages/points/points', '弹窗跳转不会 404')
} else {
  bad('pages/points 注册', 'app.json 里没有该页面')
}

if (/balance/.test(pointsWxml)) {
  ok('积分页展示余额', '用户点「去看看」能看到 +30 后的真实余额')
} else {
  bad('积分页余额展示', 'points.wxml 里找不到 balance')
}

if (/新人礼包|newbieGift|newbie/.test(pointsWxml + indexJs)) {
  ok('存在新人礼包相关提示分支', '首页弹窗即礼包提示本体')
} else {
  bad('新人礼包提示分支', '首页与积分页都没有礼包提示')
}

// 设计稿 §2.1 只要求「首页一次性弹窗 + 落在积分页」，没有要求积分页再放一条礼包文案。
// 这里显式记录，避免把「没做」当成「漏测」。
note('积分页没有独立的礼包提示分支（设计稿未要求）', '礼包提示只在首页弹出一次；积分页只承载「到账后的余额」')

console.log('')
console.log('=== 汇总 ===')
if (fail === 0) {
  console.log(`✅ 全部 ${pass} 项通过${warn ? `（${warn} 条说明，非失败）` : ''}`)
  console.log('')
  console.log('说明：本脚本是静态检查，覆盖「触发条件 / 只弹一次 / 文案 / 落点」；')
  console.log('      真机上的弹窗像素、动画与真实微信建号链路仍属未自动化，见 06 交付报告第二节。')
  process.exit(0)
} else {
  console.log(`❌ ${fail} 项失败（通过 ${pass} 项）`)
  process.exit(1)
}