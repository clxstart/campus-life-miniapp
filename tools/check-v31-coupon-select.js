// 选券纯逻辑回归：用 stub 加载 pages/food-order/food-order.js，验证「自动选最优 / 门槛过滤 / 换券换幂等键 / 服务端金额校准」。
// 用法：node tools/check-v31-coupon-select.js（无需微信开发者工具）
const fs = require('fs')
const path = require('path')
const Module = require('module')

const ROOT = path.resolve(__dirname, '..')
const target = path.join(ROOT, 'pages/food-order/food-order.js')

let couponsPayload = []
let lastCreated = null

const origLoad = Module._load
Module._load = function (request, parent, isMain) {
  if (request.includes('utils/api')) {
    return {
      currentUserId: () => 1,
      ensureLogin: async () => {},
      getCoupons: async () => couponsPayload,
      createFoodOrder: async (payload) => { lastCreated = payload; return { orderId: 9, total: 95.00, payAmountCents: 9500, couponDiscount: 1.00 } },
      getFoodShops: async () => [], getFoodMenu: async () => {}, getAddresses: async () => [{ detail: 'A', contact: 'c', phone: 'p' }],
      payOrder: async () => ({}), getOrderDetail: async () => ({})
    }
  }
  if (request.includes('utils/navigation')) return { getNavigationMetrics: () => ({}) }
  if (request.includes('utils/food')) return origLoad.call(this, path.join(ROOT, 'utils/food.js'), parent, isMain)
  if (request.includes('utils/ui-popup-helper')) return { toast: (p, m) => { global.__toasts.push(m) }, sheet: (p, o) => { global.__sheet = o }, modal: () => {}, hide: () => {} }
  return origLoad.call(this, request, parent, isMain)
}

global.wx = {
  getStorageSync: () => null, setStorageSync: () => {}, removeStorageSync: () => {},
  navigateTo: () => {}, requestPayment: () => {}, stopPullDownRefresh: () => {}
}
global.__toasts = []
let cfg = null
global.Page = (config) => { cfg = config }
global.getApp = () => ({ globalData: {} })

require(target)

// 手工实例化页面实例
const page = Object.assign({}, cfg)
page.data = JSON.parse(JSON.stringify(cfg.data))
page.setData = function (patch) {
  for (const [k, v] of Object.entries(patch)) {
    if (k.includes('.')) { const [head, tail] = k.split('.'); this.data[head] = { ...this.data[head], [tail]: v } }
    else this.data[k] = v
  }
}

page._alive = true
let fail = 0
const ok = (name, cond, extra) => { if (cond) console.log('✅ ' + name); else { fail++; console.log('❌ ' + name + (extra ? ' → ' + JSON.stringify(extra) : '')) } }

const baseFees = { subtotal: '100.00', discount: '8.00', packing: '2.00', delivery: '2.00', total: '96.00', missing: '0.00' }

// 场景 1：一张 100 分券（满 20 可用）→ 自动选中，实付 95.00
page.data.fees = { ...baseFees }
couponsPayload = [
  { id: 11, title: '外卖立减 1 元', valueCents: 100, thresholdCents: 2000, scope: 'takeout', status: 1, expireOn: '2026-12-01' },
  { id: 12, title: '外卖立减 0.5 元', valueCents: 50, thresholdCents: 20000, scope: 'takeout', status: 1, expireOn: '2026-12-01' }
]
page.data.coupons = []
page.applyCoupon(null) // 初始化展示
;(async () => {
  await page.loadCoupons()
  ok('自动选中抵扣最多的可用券', page.data.coupon && page.data.coupon.id === 11, page.data.coupon)
  ok('券抵扣金额 = 1.00', page.data.couponDiscount === '1.00', page.data.couponDiscount)
  ok('实付 = 95.00（服务端同口径）', page.data.payTotal === '95.00', page.data.payTotal)
  ok('行内文案带券名与抵扣额', /外卖立减 1 元.*1\.00/.test(page.data.couponLabel), page.data.couponLabel)
  ok('不可用券（满 200）没被自动选中', page.data.coupon.id !== 12)

  // 场景 2：签名包含券 id，换券必须换幂等键
  page.data.shop = { id: 3 }
  page.data.address = { detail: 'A' }
  page.data.items = [{ goodsId: 1, count: 2 }]
  const sig1 = page.attemptSignature()
  page.data.coupon = { id: 12 }
  const sig2 = page.attemptSignature()
  page.data.coupon = null
  const sig3 = page.attemptSignature()
  ok('attemptSignature 含 couponId 且换券后不同', sig1 !== sig2 && sig2 !== sig3 && sig1.endsWith('|11') && sig3.endsWith('|0'), { sig1, sig2, sig3 })

  // 场景 3：选券弹层内容（可用在前、不可用带原因、最后不使用）
  page.data.loading = false
  page.data.coupon = { id: 11 }
  page.openCoupons()
  const labels = (global.__sheet.actions || []).map(a => a.label)
  ok('弹层第一项是可用的券', labels[0].includes('外卖立减 1 元') && labels[0].startsWith('✓'), labels)
  ok('不可用券标了不可选与原因', labels[1].includes('不可选') && labels[1].includes('满 200.00 元可用'), labels)
  ok('最后一项是不使用优惠券', labels[labels.length - 1] === '不使用优惠券', labels)
  // 点不可用券：只提示不选中
  page.data.loading = false
  page.data.coupon = { id: 11 }
  global.__toasts = []
  global.__sheet.action({ item: global.__sheet.actions[1] })
  ok('点不可用券只 toast 原因、不改选择', global.__toasts[0] === '满 200.00 元可用' && page.data.coupon.id === 11, global.__toasts)
  // 点「不使用」
  global.__sheet.action({ item: { label: '不使用优惠券', couponId: 0 } })
  ok('选「不使用」后抵扣归零、实付回 96.00', page.data.coupon === null && page.data.couponDiscount === '0.00' && page.data.payTotal === '96.00', page.data.payTotal)

  // 场景 4：没有可用券 → 文案「暂无可用」+ 门槛提示
  couponsPayload = [{ id: 13, title: '外卖立减 1 元', valueCents: 100, thresholdCents: 20000, scope: 'takeout', status: 1 }]
  page.data.fees = { ...baseFees }
  page.data.coupons = []
  page._couponTouched = false
  await page.loadCoupons()
  ok('无可用券显示「暂无可用」', page.data.couponLabel === '暂无可用', page.data.couponLabel)
  ok('门槛提示为「满 200.00 元可用」', page.data.couponHint === '满 200.00 元可用', page.data.couponHint)
  ok('无券时实付 = 96.00', page.data.payTotal === '96.00', page.data.payTotal)

  // 场景 5：提交后用服务端返回金额校准（客户端预估 95.00 → 服务端 95.00；券额 1.00）
  page.calibrateAmount({ total: 95.00, payAmountCents: 9500, couponDiscount: 1.00 })
  ok('校准后实付 95.00 / 券抵扣 1.00', page.data.payTotal === '95.00' && page.data.couponDiscount === '1.00', { p: page.data.payTotal, d: page.data.couponDiscount })

  console.log('')
  console.log(fail === 0 ? '✅ 页面选券逻辑全部通过' : `❌ ${fail} 项失败`)
  process.exit(fail === 0 ? 0 : 1)
})()