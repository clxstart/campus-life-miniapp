<template>
  <div class="space-y-6">
    <div class="flex flex-wrap items-end justify-between gap-4">
      <div><h2 class="font-serif text-2xl text-stone-800">订单管理</h2><p class="text-sm text-stone-500 mt-1">处理外卖订单流转与售后</p></div>
      <button class="no-btn-ghost no-btn-sm" @click="load(1)">刷新</button>
    </div>
    <div class="no-card-plain p-5 flex flex-wrap items-center gap-3">
      <select v-model="filters.status" class="no-input !py-2.5 w-32"><option value="">全部状态</option><option v-for="s in statuses" :key="s.value" :value="s.value">{{ s.label }}</option></select>
      <select v-model="filters.shopId" class="no-input !py-2.5 w-36"><option value="">全部店铺</option><option v-for="shop in shopOptions" :key="shop.id" :value="shop.id">{{ shop.name || ('店铺 ' + shop.id) }}</option></select>
      <input v-model="filters.startDate" type="date" class="no-input !py-2.5" />
      <input v-model="filters.endDate" type="date" class="no-input !py-2.5" />
      <input v-model.trim="filters.keyword" @keyup.enter="load(1)" class="no-input !py-2.5 min-w-[14rem]" placeholder="订单号 / 用户昵称" />
      <button class="no-btn-primary !py-2.5" @click="load(1)">筛选</button>
      <button class="no-btn-ghost !py-2.5" @click="reset">重置</button>
    </div>
    <div class="no-card-plain !p-0 overflow-x-auto">
      <table class="w-full text-sm min-w-[980px]">
        <thead><tr class="bg-[#f3ece4] text-stone-600"><th v-for="col in columns" :key="col" class="text-left font-medium px-5 py-4">{{ col }}</th></tr></thead>
        <tbody>
          <tr v-if="loading"><td colspan="8" class="px-5 py-16 text-center text-stone-500">正在加载…</td></tr>
          <tr v-else-if="!rows.length"><td colspan="8" class="px-5 py-16 text-center text-stone-500">暂时没有订单</td></tr>
          <tr v-for="row in rows" :key="row.id" class="border-t border-stone-200 hover:bg-stone-50">
            <td class="px-5 py-4 font-mono text-xs">{{ row.outTradeNo || row.id }}</td>
            <td class="px-5 py-4">{{ row.userName || '—' }}<span class="block text-xs text-stone-400">ID {{ row.userId }}</span></td>
            <td class="px-5 py-4">{{ row.shopName || ('店铺 ' + row.shopId) }}</td>
            <td class="px-5 py-4">{{ row.itemCount || 0 }} 件</td>
            <td class="px-5 py-4 text-right font-mono">¥{{ money(row.amountCents, row.amount) }}</td>
            <td class="px-5 py-4"><span class="px-2 py-1 rounded text-xs" :class="statusClass(row.status)">{{ statusLabel(row.status) }}</span></td>
            <td class="px-5 py-4 text-stone-500">{{ format(row.createdAt) }}</td>
            <td class="px-5 py-4"><div class="flex flex-wrap gap-2"><button v-if="row.status === 'paid'" class="no-btn-ghost no-btn-sm" @click="ask('accept', row)">接单</button><button v-if="row.status === 'paid'" class="no-btn-ghost no-btn-sm !text-red-600" @click="ask('reject', row)">拒单</button><button v-if="row.status === 'accepted' || row.status === 'delivering'" class="no-btn-ghost no-btn-sm" @click="ask('complete', row)">完成</button><button v-if="['paid','accepted','delivering','completed'].includes(row.status)" class="no-btn-ghost no-btn-sm !text-red-600" @click="ask('refund', row)">退款</button></div></td>
          </tr>
        </tbody>
      </table>
      <div v-if="total > size" class="flex items-center justify-between px-5 py-4 border-t border-stone-200 text-xs text-stone-500"><span>共 {{ total }} 条 · 第 {{ page }} / {{ pages }} 页</span><div class="flex gap-2"><button class="no-btn-ghost no-btn-sm" :disabled="page <= 1" @click="load(page - 1)">上一页</button><button class="no-btn-ghost no-btn-sm" :disabled="page >= pages" @click="load(page + 1)">下一页</button></div></div>
    </div>
    <NoModal :open="modal.open" :title="modal.title" :hint="modal.hint" :submitting="modal.submitting" :confirm-text="modal.action === 'refund' ? '确认退款' : '确认操作'" @close="modal.open = false" @confirm="execute">
      <div v-if="modal.action === 'refund'"><label class="block text-sm text-stone-600 mb-2">退款原因</label><textarea v-model.trim="modal.reason" class="no-input !rounded-[1.5rem] h-24" maxlength="200" placeholder="请填写退款原因"></textarea><p class="text-sm text-stone-500 mt-3">将退款 <b>¥{{ money(modal.row?.amountCents, modal.row?.amount) }}</b></p></div>
      <p v-else class="text-sm text-stone-600">确认对订单 {{ modal.row?.outTradeNo || modal.row?.id }} 执行“{{ modal.title }}”吗？</p>
    </NoModal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import http from '../api'

const columns = ['订单号', '用户', '店铺', '商品', '金额', '状态', '下单时间', '操作']
const statuses = [{ value: 'created', label: '待支付' }, { value: 'paid', label: '待接单' }, { value: 'accepted', label: '已接单' }, { value: 'delivering', label: '配送中' }, { value: 'completed', label: '已完成' }, { value: 'cancelled', label: '已取消' }, { value: 'refunded', label: '已退款' }]
const rows = ref([]), total = ref(0), page = ref(1), size = ref(20), loading = ref(false)
const filters = reactive({ status: '', shopId: '', startDate: '', endDate: '', keyword: '' })
const modal = reactive({ open: false, action: '', row: null, reason: '', title: '', hint: '', submitting: false })
const pages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const shopOptions = computed(() => Array.from(new Map(rows.value.map(row => [row.shopId, { id: row.shopId, name: row.shopName }])).values()))
const statusLabel = value => statuses.find(s => s.value === value)?.label || value || '未知'
const statusClass = value => ({ created: 'bg-gray-100 text-gray-600', paid: 'bg-orange-100 text-orange-700', accepted: 'bg-blue-100 text-blue-700', delivering: 'bg-indigo-100 text-indigo-700', completed: 'bg-green-100 text-green-700', cancelled: 'bg-gray-100 text-gray-500', refunded: 'bg-red-100 text-red-700' }[value] || 'bg-gray-100 text-gray-600')
const format = value => value ? String(value).replace('T', ' ').slice(0, 16) : '—'
const money = (cents, yuan) => cents != null ? (Number(cents) / 100).toFixed(2) : Number(yuan || 0).toFixed(2)
async function load(next = 1) {
  loading.value = true
  try { const data = await http.get('/admin/orders', { params: { ...filters, shopId: filters.shopId || undefined, page: next, size: size.value } }); rows.value = data.list || []; total.value = Number(data.total) || 0; page.value = Number(data.page) || next } catch (e) { rows.value = []; total.value = 0 } finally { loading.value = false }
}
function reset() { Object.assign(filters, { status: '', shopId: '', startDate: '', endDate: '', keyword: '' }); load(1) }
function ask(action, row) { modal.action = action; modal.row = row; modal.reason = ''; modal.title = action === 'accept' ? '接单' : action === 'reject' ? '拒单' : action === 'complete' ? '完成订单' : '退款'; modal.hint = action === 'refund' ? '退款操作会改变订单状态' : '操作完成后订单会进入下一状态'; modal.open = true }
async function execute() {
  if (modal.action === 'refund' && !modal.reason) return
  modal.submitting = true
  try {
    if (modal.action === 'refund') await http.post('/admin/orders/' + modal.row.id + '/refund', { reason: modal.reason })
    else await http.post('/admin/orders/' + modal.row.id + '/status', { status: { accept: 'accepted', reject: 'refunding', complete: 'completed' }[modal.action] })
    modal.open = false; await load(page.value)
  } catch (e) { modal.hint = e.message || '操作失败' } finally { modal.submitting = false }
}
onMounted(() => load(1))
</script>


