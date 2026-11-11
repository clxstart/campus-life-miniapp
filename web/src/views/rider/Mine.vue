<template>
  <section class="space-y-7">
    <p v-if="error" class="rounded-md border border-[#edcfca] bg-[#fff1ef] p-3 text-sm text-[#a4453d]">{{ error }}</p>
    <section v-for="group in groups" :key="group.status" class="space-y-3">
      <div class="flex items-center justify-between"><h3 class="font-semibold">{{ group.title }}</h3><span class="text-sm text-[#65736f]">{{ byStatus(group.status).length }} 单</span></div>
      <div v-if="!byStatus(group.status).length" class="rounded-md border border-dashed border-[#cbd5d1] p-6 text-center text-sm text-[#65736f]">暂无订单</div>
      <article v-for="order in byStatus(group.status)" :key="order.id" class="rounded-md border border-[#dce2df] bg-white p-5">
        <div class="flex flex-wrap items-start justify-between gap-3 border-b border-[#e6ebe9] pb-4">
          <div><p class="font-semibold">订单 #{{ order.id }} · {{ order.shopName }}</p><p v-if="group.status === 'delivering'" class="mt-1 text-sm text-[#65736f]">取餐地址：{{ order.shopAddress || '店铺地址待补充' }}<span v-if="order.shopPhone"> · {{ order.shopPhone }}</span></p></div>
          <span class="rounded-sm bg-[#e8f2ef] px-2 py-1 text-xs text-[#155a4e]">{{ group.title }}</span>
        </div>
        <div class="grid gap-x-8 gap-y-4 py-4 sm:grid-cols-2">
          <div><p class="text-xs text-[#76837f]">收货地址</p><p class="mt-1 text-sm">{{ order.address }}</p></div>
          <div><p class="text-xs text-[#76837f]">学生电话</p><div class="mt-1 flex items-center gap-2 text-sm"><span>{{ revealed.get(order.id) || order.contactPhone }}</span><button class="text-[#176b5b] underline disabled:opacity-50" :disabled="revealing === order.id" @click="togglePhone(order.id)">{{ revealed.has(order.id) ? '隐藏' : (revealing === order.id ? '查询中…' : '查看完整号码') }}</button></div></div>
          <div class="sm:col-span-2"><p class="text-xs text-[#76837f]">菜品</p><p class="mt-1 text-sm">{{ order.itemSummary }}</p></div>
          <p class="text-xs text-[#76837f]">下单时间：{{ formatDate(order.createdAt) }}</p>
          <p v-if="order.pickedAt" class="text-xs text-[#76837f]">取货时间：{{ formatDate(order.pickedAt) }}</p>
        </div>
        <div class="flex justify-end border-t border-[#e6ebe9] pt-4"><button class="rounded-md bg-[#166b5b] px-5 py-2.5 text-sm font-medium text-white disabled:opacity-50" :disabled="!!working" @click="requestAction(order, group.action)">{{ working === order.id ? '正在提交…' : group.actionLabel }}</button></div>
      </article>
    </section>
    <NoModal :open="confirm.open" :title="confirm.title" :hint="confirm.hint" :submitting="!!working" confirm-text="确认" @close="confirm.open = false" @confirm="runAction" />
  </section>
</template>

<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import NoModal from '../../components/NoModal.vue'
import { riderApi } from '../../api'

const orders = ref([])
const error = ref('')
const working = ref(null)
// 完整号码不随列表下发，点击时按单向后端查询（仅本人配送中订单可查），只缓存在当前页面内存
const revealed = ref(new Map())
const revealing = ref(null)
const confirm = reactive({ open: false, title: '', hint: '', id: null, action: null })
const groups = [
  { status: 'delivering', title: '待取货', action: 'pick', actionLabel: '已取货' },
  { status: 'picked', title: '配送中', action: 'deliver', actionLabel: '已送达' }
]
function byStatus(status) { return orders.value.filter(order => order.status === status) }
function normalize(row) {
  let items = []
  try { items = JSON.parse(row.items || '[]') } catch {}
  return { ...row, itemSummary: items.map(item => `${item.name}×${item.count}`).join('、') || '菜品信息暂不可用' }
}
function formatDate(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '—' }
async function togglePhone(id) {
  const next = new Map(revealed.value)
  if (next.has(id)) { next.delete(id); revealed.value = next; return }
  revealing.value = id
  try {
    const data = await riderApi.contactPhone(id)
    next.set(id, data.contactPhone)
    revealed.value = next
  } catch (e) { error.value = e.message || '联系电话查询失败' }
  finally { revealing.value = null }
}
async function load() {
  try {
    orders.value = (await riderApi.mine()).map(normalize); error.value = ''
    // 订单离开配送中后，丢弃已缓存的完整号码
    const active = new Set(orders.value.map(order => order.id))
    const kept = new Map([...revealed.value].filter(([id]) => active.has(id)))
    if (kept.size !== revealed.value.size) revealed.value = kept
  }
  catch (e) { error.value = e.message || '配送订单加载失败' }
}
function requestAction(order, action) {
  confirm.id = order.id
  confirm.action = action
  confirm.title = action === 'pick' ? '确认已取到餐？' : '确认订单已送达？'
  confirm.hint = action === 'pick' ? '确认后订单将进入配送中。' : '确认后订单会完成并记录配送费。'
  confirm.open = true
}
async function runAction() {
  const { id, action } = confirm
  working.value = id
  try {
    await riderApi[action](id)
    confirm.open = false
    await load()
  } catch (e) { error.value = e.message || '状态更新失败' }
  finally { working.value = null }
}
let timer
function refreshWhenVisible() { if (document.visibilityState === 'visible') load() }
onMounted(() => { load(); timer = window.setInterval(() => { if (document.visibilityState === 'visible') load() }, 10000); document.addEventListener('visibilitychange', refreshWhenVisible) })
onUnmounted(() => { window.clearInterval(timer); document.removeEventListener('visibilitychange', refreshWhenVisible) })
</script>
