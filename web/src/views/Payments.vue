<template>
  <div class="space-y-6">
    <div><h2 class="font-serif text-2xl text-stone-800">支付流水</h2><p class="text-sm text-stone-500 mt-1">交易记录 · 对账 · 退款</p></div>
    <div class="grid grid-cols-2 lg:grid-cols-5 gap-4"><div v-for="card in cards" :key="card.key" class="no-card-plain p-5"><p class="text-xs text-stone-500">{{ card.label }}</p><p class="text-2xl font-mono text-stone-800 mt-2">{{ card.money ? '¥' : '' }}{{ card.value }}</p></div></div>
    <div class="no-card-plain p-5 flex flex-wrap items-center gap-3">
      <input v-model="filters.startDate" type="date" class="no-input !py-2.5" /><input v-model="filters.endDate" type="date" class="no-input !py-2.5" />
      <select v-model="filters.payStatus" class="no-input !py-2.5 w-32"><option value="">全部支付状态</option><option value="paid">已支付</option><option value="refunded">已退款</option><option value="closed">已关闭</option></select>
      <select v-model="filters.shopId" class="no-input !py-2.5 w-36"><option value="">全部店铺</option><option v-for="shop in shopOptions" :key="shop.id" :value="shop.id">{{ shop.name || ('店铺 ' + shop.id) }}</option></select><input v-model.trim="filters.keyword" @keyup.enter="load(1)" class="no-input !py-2.5 min-w-[14rem]" placeholder="订单号 / 微信交易号 / 用户" />
      <button class="no-btn-primary !py-2.5" @click="reload">筛选</button><button class="no-btn-ghost !py-2.5" @click="reset">重置</button><button class="no-btn-ghost !py-2.5 ml-auto disabled:opacity-50" :disabled="exporting" @click="exportCsv">{{ exporting ? '正在导出…' : '导出 CSV' }}</button>
    </div>
    <p v-if="exportMessage.text" role="status" :class="['rounded-xl px-4 py-3 text-sm', exportMessage.error ? 'bg-red-100 text-red-700' : 'bg-green-100 text-green-700']">{{ exportMessage.text }}</p>
    <div class="no-card-plain !p-0 overflow-x-auto">
      <table class="w-full text-sm min-w-[1100px]">
        <thead><tr class="bg-[#f3ece4] text-stone-600"><th v-for="col in columns" :key="col" class="text-left font-medium px-5 py-4">{{ col }}</th></tr></thead>
        <tbody><tr v-if="loading"><td colspan="9" class="px-5 py-16 text-center text-stone-500">正在加载…</td></tr><tr v-else-if="!rows.length"><td colspan="9" class="px-5 py-16 text-center text-stone-500">暂时没有流水</td></tr><tr v-for="row in rows" :key="row.id" :class="['border-t border-stone-200 hover:bg-stone-50', Number(row.verified) === 0 ? 'bg-red-50' : '']"><td class="px-5 py-4 font-mono text-xs">{{ row.outTradeNo }}</td><td class="px-5 py-4 font-mono text-xs max-w-[12rem] truncate">{{ row.transactionId || '—' }}</td><td class="px-5 py-4">{{ row.userName || '—' }}<span class="block text-xs text-stone-400">ID {{ row.userId }}</span></td><td class="px-5 py-4">{{ row.shopName || ('店铺 ' + row.shopId) }}</td><td class="px-5 py-4 text-right font-mono">¥{{ money(row.amountCents) }}</td><td class="px-5 py-4"><span class="px-2 py-1 rounded text-xs" :class="payClass(row.payStatus)">{{ payLabel(row.payStatus) }}</span></td><td class="px-5 py-4"><span class="px-2 py-1 rounded text-xs" :class="Number(row.verified) === 0 ? 'bg-red-100 text-red-700' : 'bg-green-100 text-green-700'">{{ Number(row.verified) === 0 ? '异常' : '正常' }}</span></td><td class="px-5 py-4 text-stone-500">{{ format(row.paidAt) }}</td><td class="px-5 py-4"><button class="no-btn-ghost no-btn-sm" @click="openDetail(row)">详情</button><button v-if="['paid'].includes(row.payStatus)" class="no-btn-ghost no-btn-sm !text-red-600 ml-2" @click="openRefund(row)">退款</button></td></tr></tbody>
      </table>
      <div v-if="total > size" class="flex items-center justify-between px-5 py-4 border-t border-stone-200 text-xs text-stone-500"><span>共 {{ total }} 条 · 第 {{ page }} / {{ pages }} 页</span><div class="flex gap-2"><button class="no-btn-ghost no-btn-sm" :disabled="page <= 1" @click="load(page - 1)">上一页</button><button class="no-btn-ghost no-btn-sm" :disabled="page >= pages" @click="load(page + 1)">下一页</button></div></div>
    </div>
    <NoModal :open="detail.open" title="支付流水详情" hint="订单信息、支付回调与退款记录" hide-footer @close="detail.open = false">
      <div v-if="detail.row" class="space-y-5 text-sm">
        <div class="grid grid-cols-2 gap-3"><div><span class="text-stone-500">订单号</span><p class="font-mono">{{ detail.row.outTradeNo }}</p></div><div><span class="text-stone-500">微信交易号</span><p class="font-mono break-all">{{ detail.row.transactionId || '—' }}</p></div><div><span class="text-stone-500">用户</span><p>{{ detail.row.userName }}（{{ detail.row.userId }}）</p></div><div><span class="text-stone-500">店铺</span><p>{{ detail.row.shopName }}</p></div><div><span class="text-stone-500">收货地址</span><p>{{ detail.row.address }}</p></div><div><span class="text-stone-500">实付</span><p class="font-mono">¥{{ money(detail.row.payAmountCents) }}</p></div></div>
        <div v-if="detail.unverified" class="bg-red-100 text-red-700 rounded-xl p-3">这条回调未通过验签，需人工核对。</div>
        <div><h3 class="font-medium text-stone-800 mb-2">商品与金额明细</h3><div v-for="item in (detail.row.items || [])" :key="item.goodsId" class="flex justify-between text-stone-600"><span>{{ item.name }} × {{ item.count }}</span><span>¥{{ Number(item.price || 0) * Number(item.count || 0) }}</span></div><div class="border-t border-stone-200 mt-2 pt-2 space-y-1 text-stone-500"><p>小计 ¥{{ detail.row.subtotal }}</p><p>打包费 ¥{{ detail.row.packingFee }} · 配送费 ¥{{ detail.row.deliveryFee }}</p><p>优惠 ¥{{ detail.row.discountAmount }}</p><p class="font-medium text-stone-800">实付 ¥{{ money(detail.row.payAmountCents) }}</p></div></div>
        <div><h3 class="font-medium text-stone-800 mb-2">支付回调记录</h3><div v-if="!detail.records?.length" class="text-stone-500">暂无记录</div><div v-for="record in detail.records" :key="record.id" class="border-t border-stone-200 py-2"><div class="flex justify-between gap-3"><span>{{ record.tradeState }} · {{ format(record.createdAt) }} · ¥{{ money(record.amountCents) }}</span><span :class="record.verified ? 'text-green-600' : 'text-red-600'">{{ record.verified ? '已验签' : '未验签' }}</span></div><details class="mt-2 text-xs"><summary class="cursor-pointer text-stone-500">查看原始报文</summary><pre class="mt-2 whitespace-pre-wrap bg-stone-100 rounded-xl p-3 overflow-auto">{{ record.rawBody }}</pre></details></div></div>
        <div><h3 class="font-medium text-stone-800 mb-2">退款记录</h3><div v-if="!detail.refunds?.length" class="text-stone-500">无退款记录</div><div v-for="record in detail.refunds" :key="record.id" class="border-t border-stone-200 py-2 flex justify-between gap-3"><span>{{ record.outRefundNo }} · ¥{{ money(record.amountCents) }}</span><span>{{ record.status }}</span></div></div>
      </div>
    </NoModal>
    <NoModal :open="refund.open" title="申请退款" :hint="refund.error || '退款操作需要二次确认'" :submitting="refund.submitting" confirm-text="确认退款" @close="refund.open = false" @confirm="submitRefund"><textarea v-model.trim="refund.reason" class="no-input !rounded-[1.5rem] h-24" placeholder="退款原因"></textarea><p class="text-sm text-stone-500 mt-3">将退款 ¥{{ money(refund.row?.amountCents) }}</p></NoModal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import http, { getToken } from '../api'

const columns = ['订单号', '微信交易号', '用户', '店铺', '金额', '支付状态', '对账状态', '支付时间', '操作']
const rows = ref([]), total = ref(0), page = ref(1), size = ref(20), loading = ref(false)
const now = new Date(), week = new Date(now.getTime() - 6 * 86400000)
const iso = date => date.toISOString().slice(0, 10)
const filters = reactive({ startDate: iso(week), endDate: iso(now), payStatus: '', shopId: '', keyword: '' })
const stats = reactive({ todayAmountCents: 0, todayOrderCount: 0, totalAmountCents: 0, unverifiedCount: 0, refundAmountCents: 0 })
const detail = reactive({ open: false, row: null, records: [], refunds: [], unverified: false })
const refund = reactive({ open: false, row: null, reason: '', error: '', submitting: false })
const pages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const shopOptions = computed(() => Array.from(new Map(rows.value.map(row => [row.shopId, { id: row.shopId, name: row.shopName }])).values()))
const cards = computed(() => [{ key: 'today', label: '今日交易额', value: money(stats.todayAmountCents), money: true }, { key: 'count', label: '今日订单数', value: stats.todayOrderCount, money: false }, { key: 'total', label: '累计交易额', value: money(stats.totalAmountCents), money: true }, { key: 'unverified', label: '待对账', value: stats.unverifiedCount, money: false }, { key: 'refund', label: '退款金额', value: money(stats.refundAmountCents), money: true }])
const money = cents => (Number(cents || 0) / 100).toFixed(2)
const format = value => value ? String(value).replace('T', ' ').slice(0, 19) : '—'
const payLabel = value => ({ paid: '已支付', refunded: '已退款', closed: '已关闭' }[value] || value || '未知')
const payClass = value => ({ paid: 'bg-green-100 text-green-700', refunded: 'bg-red-100 text-red-700', closed: 'bg-gray-100 text-gray-600' }[value] || 'bg-gray-100 text-gray-600')
async function load(next = 1) { loading.value = true; try { const data = await http.get('/admin/payments', { params: { ...filters, shopId: filters.shopId || undefined, page: next, size: size.value } }); rows.value = data.list || []; total.value = Number(data.total) || 0; page.value = Number(data.page) || next } catch (e) { rows.value = []; total.value = 0 } finally { loading.value = false } }
async function loadStats() { try { Object.assign(stats, await http.get('/admin/payments/stats', { params: { startDate: filters.startDate, endDate: filters.endDate } })) } catch (e) {} }
function reload() { load(1); loadStats() }
function reset() { Object.assign(filters, { payStatus: '', shopId: '', keyword: '' }); reload() }
async function openDetail(row) { try { const data = await http.get('/admin/payments/' + encodeURIComponent(row.outTradeNo)); detail.row = data; detail.records = data.paymentRecords || []; detail.refunds = data.refundRecords || []; detail.unverified = detail.records.some(r => Number(r.verified) === 0); detail.open = true } catch (e) {} }
function openRefund(row) { refund.row = row; refund.reason = ''; refund.error = ''; refund.open = true }
async function submitRefund() { if (!refund.reason) { refund.error = '请填写退款原因'; return }; refund.error = ''; refund.submitting = true; try { await http.post('/admin/orders/' + refund.row.id + '/refund', { reason: refund.reason }); refund.open = false; reload() } catch (e) { refund.error = e.message || '退款失败' } finally { refund.submitting = false } }
const exporting = ref(false)
const exportMessage = reactive({ text: '', error: false })

// finding-09：只有 HTTP 成功且确实是 CSV 时才下载；401/403/413/5xx 读出错误信息显示在页面上，不再把错误页存成 .csv
async function exportFailure(response) {
  let msg = ''
  try {
    const text = await response.text()
    try { const body = JSON.parse(text); msg = body.msg || body.message || '' } catch { /* 非 JSON 错误页 */ }
  } catch { /* 读取失败时用默认文案 */ }
  if (response.status === 401) return '登录已过期，请重新登录'
  if (response.status === 403) return msg || '无权导出支付流水'
  if (response.status >= 500) return '服务异常，导出失败（HTTP ' + response.status + '）'
  return msg || '导出失败（HTTP ' + response.status + '）'
}

async function exportCsv() {
  if (exporting.value) return
  exporting.value = true
  exportMessage.text = ''
  let url = ''
  let anchor = null
  try {
    const qs = new URLSearchParams({ ...filters, shopId: filters.shopId || '' })
    const response = await fetch('/api/admin/payments/export?' + qs.toString(), { headers: { Authorization: 'Bearer ' + getToken() } })
    const type = response.headers.get('Content-Type') || ''
    if (!response.ok || !type.includes('text/csv')) {
      exportMessage.text = response.ok ? '导出失败：服务端返回的不是 CSV 文件' : await exportFailure(response)
      exportMessage.error = true
      return
    }
    const blob = await response.blob()
    url = URL.createObjectURL(blob)
    anchor = document.createElement('a')
    anchor.href = url
    anchor.download = '支付流水_' + filters.startDate + '_' + filters.endDate + '.csv'
    document.body.appendChild(anchor)
    anchor.click()
    const count = response.headers.get('X-Export-Row-Count')
    exportMessage.text = count != null ? '已导出 ' + count + ' 条流水' : '导出完成'
    exportMessage.error = false
  } catch (e) {
    exportMessage.text = '网络异常，导出失败，请稍后重试'
    exportMessage.error = true
  } finally {
    if (anchor) anchor.remove()
    // 延后释放，确保浏览器已开始读取下载内容
    if (url) setTimeout(() => URL.revokeObjectURL(url), 0)
    exporting.value = false
  }
}
onMounted(reload)
</script>

