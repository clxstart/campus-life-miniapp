// 金额统一以分运算；满减只作用于商品金额，起送门槛按优惠前商品金额判断。
function cents(value) { const n = Number(value); if (!Number.isFinite(n) || n < 0) throw new Error('金额无效'); return Math.round(n * 100) }
function money(value) { return (cents(value) / 100).toFixed(2) }
function bill(shop, items) {
  const subtotal = items.reduce((sum, item) => sum + cents(item.price) * item.count, 0)
  const rule = String(shop.discount || '').match(/满(\d+(?:\.\d+)?)减(\d+(?:\.\d+)?)/)
  const discount = rule && subtotal >= cents(rule[1]) ? Math.min(subtotal, cents(rule[2])) : 0
  const packing = items.length ? 200 : 0
  const delivery = items.length ? cents(shop.deliveryFee || 0) : 0
  return { subtotal: (subtotal / 100).toFixed(2), discount: (discount / 100).toFixed(2), packing: (packing / 100).toFixed(2), delivery: (delivery / 100).toFixed(2), total: ((subtotal - discount + packing + delivery) / 100).toFixed(2), missing: (Math.max(0, cents(shop.minPrice || 0) - subtotal) / 100).toFixed(2) }
}
function orderItems(menu, wanted) {
  if (!Array.isArray(wanted) || !wanted.length || wanted.length > 50) throw new Error('请先选择商品')
  const goods = {}; menu.forEach(group => group.goods.forEach(g => { goods[g.id] = g }))
  const counts = {}
  wanted.forEach(item => {
    if (!item || !Number.isSafeInteger(Number(item.goodsId)) || Number(item.goodsId) <= 0 || !Number.isInteger(item.count) || item.count < 1 || item.count > 99) throw new Error('商品数量无效')
    const id = Number(item.goodsId)
    if (!goods[id]) throw new Error('部分商品已下架，请重新选购')
    counts[id] = (counts[id] || 0) + item.count
    if (counts[id] > 99) throw new Error('单件商品最多购买99份')
  })
  return Object.keys(counts).map(id => ({ goodsId: Number(id), name: goods[id].name, price: Number(goods[id].price), priceText: money(goods[id].price), image: goods[id].image, count: counts[id], lineTotal: (cents(goods[id].price) * counts[id] / 100).toFixed(2) }))
}
module.exports = { cents, money, bill, orderItems }
