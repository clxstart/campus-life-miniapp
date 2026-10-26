<template>
  <div class="space-y-5">
    <div class="flex flex-wrap items-center justify-between gap-3"><div><h2 class="text-xl font-semibold">订单管理</h2><p class="mt-1 text-sm text-[#77847b]">订单只包含当前店铺的数据。</p></div><button class="rounded border border-[#dce5dd] bg-white px-3 py-2 text-sm" @click="load">↻ 刷新</button></div>
    <div class="flex flex-wrap gap-2 border-b border-[#dfe7e0] pb-3"><button v-for="tab in tabs" :key="tab.value" class="rounded border px-3 py-2 text-xs" :class="status === tab.value ? 'border-[#b5d0bb] bg-[#eaf3eb] text-[#2f7046]' : 'border-[#dce5dd] bg-white text-[#65736a]'" @click="select(tab.value)">{{ tab.label }}</button></div>
    <div class="space-y-3">
      <article v-for="order in rows" :key="order.id" class="rounded-md border border-[#e1e7e2] bg-white p-4 sm:p-5">
        <div class="flex flex-wrap items-start justify-between gap-3 border-b border-[#edf0ed] pb-3"><div><p class="text-xs text-[#77847b]">订单号 #{{ order.id }} · {{ formatDate(order.createdAt) }}</p><p class="mt-1 text-sm font-medium">{{ order.userName || `用户 ${order.userId}` }}</p></div><span class="rounded border border-[#dce5dd] px-2 py-1 text-xs">{{ statusLabel(order.status) }}</span></div>
        <div class="py-3"><p v-for="item in order.items" :key="item.goodsId" class="flex justify-between gap-3 py-1 text-sm"><span>{{ item.name }} × {{ item.count }}</span><span class="text-[#65736a]">¥{{ (Number(item.price) * Number(item.count)).toFixed(2) }}</span></p></div>
        <div class="flex flex-wrap items-center justify-between gap-3 border-t border-[#edf0ed] pt-3"><p class="text-xs text-[#77847b]">{{ order.address }}<span v-if="order.remark"> · 备注：{{ order.remark }}</span></p><div class="flex items-center gap-3"><strong class="text-sm">¥{{ Number(order.total).toFixed(2) }}</strong><button v-if="order.status === 'paid'" class="rounded bg-[#2f7046] px-3 py-2 text-xs text-white" @click="accept(order)">接单</button><button v-if="order.status === 'paid'" class="rounded border border-[#e5cecc] px-3 py-2 text-xs text-[#a34743]" @click="reject(order)">拒单</button><button v-if="order.status === 'accepted' && !order.riderId" class="rounded bg-[#2f7046] px-3 py-2 text-xs text-white" @click="complete(order)">完成订单</button></div></div>
      </article>
      <div v-if="!loading && !rows.length" class="border-y border-[#dfe7e0] py-16 text-center text-sm text-[#829087]">没有{{ tabs.find(tab => tab.value === status)?.label || '' }}订单</div>
      <div v-if="loading" class="py-14 text-center text-sm text-[#829087]">正在加载…</div>
    </div>
    <div class="flex justify-end gap-2 text-sm"><button class="rounded border border-[#dce4dd] bg-white px-3 py-1.5 disabled:opacity-40" :disabled="page <= 1" @click="load(page - 1)">上一页</button><span class="px-2 py-1.5 text-[#718078]">{{ page }}</span><button class="rounded border border-[#dce4dd] bg-white px-3 py-1.5 disabled:opacity-40" :disabled="page * size >= total" @click="load(page + 1)">下一页</button></div>
    <p v-if="error" class="text-sm text-[#a34743]">{{ error }}</p>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { merchantApi } from '../../api'
const tabs = [{ value: '', label: '全部' }, { value: 'paid', label: '待接单' }, { value: 'accepted', label: '已接单' }, { value: 'completed', label: '已完成' }, { value: 'cancelled', label: '已取消' }]
const status = ref('')
const rows = ref([])
const page = ref(1)
const size = 20
const total = ref(0)
const loading = ref(false)
const error = ref('')
onMounted(load)
function select(value) { status.value = value; load(1) }
async function load(next = page.value) { loading.value = true; error.value = ''; try { const data = await merchantApi.orders({ status: status.value, page: next, size }); rows.value = data.list || []; total.value = Number(data.total) || 0; page.value = data.page || next } catch (e) { error.value = e.message || '订单加载失败' } finally { loading.value = false } }
function statusLabel(value) { return ({ created: '待接单', paid: '待接单', accepted: '已接单', completed: '已完成', cancelled: '已取消' })[value] || value }
function formatDate(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
async function accept(order) { try { await merchantApi.acceptOrder(order.id); await load() } catch (e) { error.value = e.message || '接单失败' } }
async function reject(order) { const reason = window.prompt('填写拒单原因（可选）') ?? null; if (reason === null) return; try { const result = await merchantApi.rejectOrder(order.id, reason); await load(); if (result.refundStatus === 'failed') window.alert('订单已取消，退款尚未执行，请联系平台运营处理。') } catch (e) { error.value = e.message || '拒单失败' } }
async function complete(order) { try { await merchantApi.completeOrder(order.id); await load() } catch (e) { error.value = e.message || '完成订单失败' } }
</script>
