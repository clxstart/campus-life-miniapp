<template>
  <section class="space-y-4">
    <div class="flex flex-wrap items-end gap-3 rounded-md border border-[#dce2df] bg-white p-4">
      <label class="grid gap-1 text-xs text-[#65736f]">开始日期<input v-model="startDate" class="rounded-md border border-[#cbd5d1] px-3 py-2 text-sm text-[#202b2b]" type="date" /></label>
      <label class="grid gap-1 text-xs text-[#65736f]">结束日期<input v-model="endDate" class="rounded-md border border-[#cbd5d1] px-3 py-2 text-sm text-[#202b2b]" type="date" /></label>
      <button class="rounded-md bg-[#166b5b] px-4 py-2 text-sm text-white" @click="load(1)">筛选</button>
    </div>
    <p v-if="error" class="text-sm text-[#a4453d]">{{ error }}</p>
    <div class="overflow-x-auto rounded-md border border-[#dce2df] bg-white">
      <table class="w-full min-w-[680px] text-left text-sm">
        <thead class="bg-[#edf2f0] text-xs text-[#52635f]"><tr><th class="px-4 py-3 font-medium">订单号</th><th class="px-4 py-3 font-medium">店铺</th><th class="px-4 py-3 font-medium">收货地址</th><th class="px-4 py-3 font-medium">送达时间</th><th class="px-4 py-3 text-right font-medium">配送费</th></tr></thead>
        <tbody>
          <tr v-if="loading"><td colspan="5" class="px-4 py-12 text-center text-[#65736f]">正在加载记录…</td></tr>
          <tr v-else-if="!rows.length"><td colspan="5" class="px-4 py-12 text-center text-[#65736f]">没有符合条件的配送记录</td></tr>
          <tr v-for="row in rows" :key="row.id" class="border-t border-[#e6ebe9]"><td class="px-4 py-3">#{{ row.id }}</td><td class="px-4 py-3">{{ row.shopName }}</td><td class="max-w-xs truncate px-4 py-3">{{ row.address }}</td><td class="px-4 py-3">{{ formatDate(row.deliveredAt) }}</td><td class="px-4 py-3 text-right">¥{{ money(row.deliveryFee) }}</td></tr>
        </tbody>
      </table>
    </div>
    <div class="flex flex-wrap items-center justify-between gap-3 text-sm">
      <p class="text-[#52635f]">本月完成 <strong>{{ monthCount }}</strong> 单，共赚 <strong>¥{{ money(monthFee) }}</strong></p>
      <div v-if="total > size" class="flex gap-2"><button class="rounded-md border border-[#cbd5d1] px-3 py-1.5 disabled:opacity-40" :disabled="page <= 1" @click="load(page - 1)">上一页</button><span class="px-2 py-1.5">{{ page }} / {{ pageCount }}</span><button class="rounded-md border border-[#cbd5d1] px-3 py-1.5 disabled:opacity-40" :disabled="page >= pageCount" @click="load(page + 1)">下一页</button></div>
    </div>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { riderApi } from '../../api'

const rows = ref([])
const startDate = ref('')
const endDate = ref('')
const page = ref(1)
const size = 20
const total = ref(0)
const monthCount = ref(0)
const monthFee = ref(0)
const loading = ref(false)
const error = ref('')
const pageCount = ref(1)
function money(value) { return Number(value || 0).toFixed(2) }
function formatDate(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '—' }
async function load(nextPage = page.value) {
  loading.value = true
  try {
    const data = await riderApi.history({ startDate: startDate.value || undefined, endDate: endDate.value || undefined, page: nextPage, size })
    rows.value = data.list || []
    total.value = Number(data.total || 0)
    monthCount.value = Number(data.monthCount || 0)
    monthFee.value = Number(data.monthFee || 0)
    page.value = Number(data.page || nextPage)
    pageCount.value = Math.max(1, Math.ceil(total.value / size))
    error.value = ''
  } catch (e) { error.value = e.message || '配送记录加载失败' }
  finally { loading.value = false }
}
onMounted(() => load(1))
</script>
