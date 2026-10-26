<template>
  <section class="space-y-5">
    <div class="flex flex-wrap items-center justify-between gap-4 rounded-md border border-[#cfe2dc] bg-[#e5f2ed] p-5">
      <div>
        <p class="text-sm text-[#4e6d65]">我的状态</p>
        <p class="mt-1 text-xl font-semibold text-[#155a4e]">{{ stats.riderStatus === 'busy' ? '配送中' : '空闲中' }}</p>
      </div>
      <span class="h-3 w-3 rounded-full" :class="stats.riderStatus === 'busy' ? 'bg-[#d88a36]' : 'bg-[#16836f]'" aria-label="骑手状态" />
    </div>

    <section class="rounded-md border border-[#dce2df] bg-white p-5">
      <h3 class="font-semibold">今日数据</h3>
      <div class="mt-5 grid grid-cols-1 divide-y divide-[#e6ebe9] sm:grid-cols-3 sm:divide-x sm:divide-y-0">
        <div class="py-3 sm:px-5 sm:py-0"><p class="text-sm text-[#65736f]">已完成</p><p class="mt-1 text-2xl font-semibold">{{ stats.completedToday ?? 0 }} <span class="text-sm font-normal text-[#65736f]">单</span></p></div>
        <div class="py-3 sm:px-5 sm:py-0"><p class="text-sm text-[#65736f]">配送中</p><p class="mt-1 text-2xl font-semibold">{{ stats.activeCount ?? 0 }} <span class="text-sm font-normal text-[#65736f]">单</span></p></div>
        <div class="py-3 sm:px-5 sm:py-0"><p class="text-sm text-[#65736f]">待抢</p><p class="mt-1 text-2xl font-semibold">{{ stats.pendingCount ?? 0 }} <span class="text-sm font-normal text-[#65736f]">单</span></p></div>
      </div>
    </section>

    <section class="rounded-md border border-[#dce2df] bg-white p-5">
      <h3 class="font-semibold">快捷入口</h3>
      <div class="mt-4 flex flex-wrap gap-3">
        <RouterLink to="/rider/pool" class="rounded-md bg-[#166b5b] px-4 py-2.5 text-sm font-medium text-white hover:bg-[#12594d]">查看待抢订单</RouterLink>
        <RouterLink to="/rider/mine" class="rounded-md border border-[#cbd5d1] px-4 py-2.5 text-sm font-medium hover:bg-[#f3f6f5]">打开我的配送</RouterLink>
      </div>
    </section>
    <p v-if="error" class="text-sm text-[#a4453d]">{{ error }}</p>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { riderApi } from '../../api'

const stats = ref({})
const error = ref('')
onMounted(async () => {
  try { stats.value = await riderApi.stats() }
  catch (e) { error.value = e.message || '数据加载失败' }
})
</script>
