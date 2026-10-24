<template>
  <section class="space-y-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <p class="text-sm text-[#65736f]">{{ orders.length }} 笔待配送订单</p>
      <button class="rounded-md border border-[#cbd5d1] px-3 py-2 text-sm hover:bg-white" @click="load">刷新</button>
    </div>
    <p v-if="error" class="rounded-md border border-[#edcfca] bg-[#fff1ef] p-3 text-sm text-[#a4453d]">{{ error }}</p>
    <div v-if="loading" class="rounded-md border border-[#dce2df] bg-white p-8 text-center text-sm text-[#65736f]">正在加载订单…</div>
    <div v-else-if="!orders.length" class="rounded-md border border-[#dce2df] bg-white p-10 text-center text-sm text-[#65736f]">当前没有待抢订单</div>
    <article v-for="order in orders" :key="order.id" class="rounded-md border border-[#dce2df] bg-white p-5">
      <div class="flex flex-wrap items-start justify-between gap-4 border-b border-[#e6ebe9] pb-4">
        <div><p class="font-semibold">{{ order.shopName }}</p><p class="mt-1 text-sm text-[#65736f]">取餐地址：{{ order.shopAddress || '店铺地址待补充' }}</p></div>
        <p class="whitespace-nowrap text-sm font-medium text-[#155a4e]">配送费 ¥{{ money(order.deliveryFee) }}</p>
      </div>
      <div class="grid gap-x-8 gap-y-4 py-4 sm:grid-cols-2">
        <div><p class="text-xs text-[#76837f]">收货地址</p><p class="mt-1 text-sm">{{ order.address }}</p></div>
        <div><p class="text-xs text-[#76837f]">学生电话</p><div class="mt-1 flex items-center gap-2 text-sm"><span>{{ revealed.has(order.id) ? order.contactPhoneFull : order.contactPhone }}</span><button class="text-[#176b5b] underline" @click="togglePhone(order.id)">{{ revealed.has(order.id) ? '隐藏' : '查看完整号码' }}</button></div></div>
        <div class="sm:col-span-2"><p class="text-xs text-[#76837f]">菜品</p><p class="mt-1 text-sm">{{ order.itemSummary }}</p></div>
        <p class="text-xs text-[#76837f]">下单时间：{{ formatDate(order.createdAt) }}</p>
      </div>
      <div class="flex justify-end border-t border-[#e6ebe9] pt-4"><button class="rounded-md bg-[#166b5b] px-5 py-2.5 text-sm font-medium text-white disabled:opacity-50" :disabled="working === order.id" @click="grab(order)">{{ working === order.id ? '正在抢单…' : '抢单' }}</button></div>
    </article>
  </section>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { riderApi } from '../../api'

const orders = ref([])
const revealed = ref(new Set())
const loading = ref(true)
const error = ref('')
const working = ref(null)
let timer
function normalize(row) {
  let items = []
  try { items = JSON.parse(row.items || '[]') } catch {}
  return { ...row, itemSummary: items.map(item => `${item.name}×${item.count}`).join('、') || '菜品信息暂不可用' }
}
function money(value) { return Number(value || 0).toFixed(2) }
function formatDate(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '—' }
function togglePhone(id) {
  const next = new Set(revealed.value)
  next.has(id) ? next.delete(id) : next.add(id)
  revealed.value = next
}
async function load() {
  try { orders.value = (await riderApi.pool()).map(normalize); error.value = '' }
  catch (e) { error.value = e.message || '订单加载失败' }
  finally { loading.value = false }
}
async function grab(order) {
  working.value = order.id
  try {
    await riderApi.grab(order.id)
    await load()
    location.hash = '#/rider/mine'
  } catch (e) {
    error.value = e.message || '手慢了，这单被抢了'
    await load()
  } finally { working.value = null }
}
function refreshWhenVisible() {
  if (document.visibilityState === 'visible') load()
}
onMounted(() => {
  load()
  timer = window.setInterval(() => { if (document.visibilityState === 'visible') load() }, 10000)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})
onUnmounted(() => {
  window.clearInterval(timer)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
</script>
