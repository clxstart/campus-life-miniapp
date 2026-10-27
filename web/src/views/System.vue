<template>
  <div class="space-y-6 organic-enter">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div>
        <h2 class="font-serif text-xl md:text-2xl text-stone-800">系统状态</h2>
        <p class="text-xs text-stone-500 mt-1">
          服务健康、内存、数据库连接、磁盘与业务计数。出问题时先看这一页。
        </p>
      </div>
      <div class="flex items-center gap-2">
        <span v-if="data" class="no-badge" :class="statusClass">{{ data.health.status }}</span>
        <button class="no-btn-ghost no-btn-sm" :disabled="loading" @click="load">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </div>

    <div v-if="error" class="border border-[#e6c9c2] bg-[#f7e6e2] rounded-[1.25rem] p-4 text-sm text-[#a4553f]">
      {{ error }}
    </div>

    <!-- 有问题的项，直接列出来，不用自己找 -->
    <div v-if="problems.length" class="border border-[#eddcbb] bg-[#fdf3e2] rounded-[1.25rem] p-4">
      <p class="text-sm font-medium text-[#8a6320] mb-1">需要处理</p>
      <ul class="text-sm text-[#8a6320] space-y-0.5">
        <li v-for="p in problems" :key="p">· {{ p }}</li>
      </ul>
    </div>

    <div v-if="!data && loading" class="py-16 text-center text-stone-500 text-sm">读取中…</div>

    <div v-else-if="data" class="grid gap-4 md:grid-cols-2">
      <!-- 健康明细 -->
      <div class="border border-stone-200 rounded-[1.5rem] p-5">
        <p class="font-medium text-stone-800 mb-3">健康检查</p>
        <dl class="space-y-2 text-sm">
          <div class="flex justify-between"><dt class="text-stone-500">数据库</dt><dd class="text-stone-800">{{ data.health.database }}</dd></div>
          <div class="flex justify-between"><dt class="text-stone-500">上传目录</dt><dd class="text-stone-800">{{ data.health.uploadDir }}</dd></div>
          <div class="flex justify-between"><dt class="text-stone-500">连接池等待线程</dt><dd class="text-stone-800">{{ data.health.poolAwaiting ?? '—' }}</dd></div>
        </dl>
      </div>

      <!-- 内存 -->
      <div class="border border-stone-200 rounded-[1.5rem] p-5">
        <p class="font-medium text-stone-800 mb-3">JVM</p>
        <dl class="space-y-2 text-sm">
          <div class="flex justify-between"><dt class="text-stone-500">堆内存</dt><dd class="text-stone-800">{{ data.jvm.heapUsedMb }} / {{ data.jvm.heapMaxMb }} MB（{{ data.jvm.heapUsedPercent }}%）</dd></div>
          <div class="flex justify-between"><dt class="text-stone-500">线程数</dt><dd class="text-stone-800">{{ data.jvm.threads }}</dd></div>
          <div class="flex justify-between"><dt class="text-stone-500">已运行</dt><dd class="text-stone-800">{{ data.jvm.uptimeMinutes }} 分钟</dd></div>
        </dl>
        <div class="mt-3 h-2 rounded-full bg-stone-200 overflow-hidden">
          <div class="h-full bg-[#8b9d77]" :style="{ width: Math.min(100, data.jvm.heapUsedPercent || 0) + '%' }"></div>
        </div>
      </div>

      <!-- 连接池 -->
      <div class="border border-stone-200 rounded-[1.5rem] p-5">
        <p class="font-medium text-stone-800 mb-3">数据库连接池</p>
        <dl class="space-y-2 text-sm">
          <div class="flex justify-between"><dt class="text-stone-500">活跃 / 上限</dt><dd class="text-stone-800">{{ data.pool.active ?? '—' }} / {{ data.pool.max ?? '—' }}</dd></div>
          <div class="flex justify-between"><dt class="text-stone-500">空闲</dt><dd class="text-stone-800">{{ data.pool.idle ?? '—' }}</dd></div>
        </dl>
        <div class="mt-3 h-2 rounded-full bg-stone-200 overflow-hidden">
          <div class="h-full" :class="poolBarClass" :style="{ width: poolPercent + '%' }"></div>
        </div>
      </div>

      <!-- 磁盘 -->
      <div class="border border-stone-200 rounded-[1.5rem] p-5">
        <p class="font-medium text-stone-800 mb-3">磁盘</p>
        <dl class="space-y-2 text-sm">
          <div class="flex justify-between"><dt class="text-stone-500">总容量</dt><dd class="text-stone-800">{{ data.disk.totalGb ?? '—' }} GB</dd></div>
          <div class="flex justify-between"><dt class="text-stone-500">剩余</dt><dd :class="diskFreeClass">{{ data.disk.freeGb ?? '—' }} GB</dd></div>
        </dl>
        <div class="mt-3 h-2 rounded-full bg-stone-200 overflow-hidden">
          <div class="h-full" :class="diskBarClass" :style="{ width: (data.disk.usedPercent || 0) + '%' }"></div>
        </div>
      </div>

      <!-- 业务计数 -->
      <div class="border border-stone-200 rounded-[1.5rem] p-5 md:col-span-2">
        <p class="font-medium text-stone-800 mb-3">业务计数</p>
        <div class="grid grid-cols-3 md:grid-cols-6 gap-3 text-center">
          <div v-for="(v, k) in businessItems" :key="k">
            <p class="text-lg font-serif text-stone-800">{{ v ?? '—' }}</p>
            <p class="text-xs text-stone-500">{{ businessLabels[k] || k }}</p>
          </div>
        </div>
      </div>

      <!-- 慢请求 -->
      <div class="border border-stone-200 rounded-[1.5rem] p-5 md:col-span-2">
        <p class="font-medium text-stone-800 mb-3">最近慢请求（&gt; 300ms）</p>
        <p v-if="!data.slowRequests || !data.slowRequests.length" class="text-sm text-stone-500">
          暂无。出现慢请求后这里会列出，也可以直接看服务器 logs/error.log。
        </p>
        <ul v-else class="space-y-1 text-xs text-stone-600 font-mono">
          <li v-for="(line, i) in data.slowRequests" :key="i" class="break-all">{{ line }}</li>
        </ul>
      </div>
    </div>

    <p v-if="data" class="text-xs text-stone-400">采集时间：{{ data.timestamp }}</p>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import http from '../api'
import { useAutoRefresh } from '../utils/auto-refresh'

const data = ref(null)
const loading = ref(false)
const error = ref('')

const problems = computed(() => (data.value?.health?.problems) || [])
const statusClass = computed(() => data.value?.health?.status === 'UP'
  ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'
  : 'bg-[#f7e6e2] text-[#a4553f] border-[#e6c9c2]')

const poolPercent = computed(() => {
  const p = data.value?.pool || {}
  const max = Number(p.max) || 0
  const active = Number(p.active) || 0
  return max > 0 ? Math.min(100, Math.round(active * 100 / max)) : 0
})
const poolBarClass = computed(() => poolPercent.value >= 80 ? 'bg-[#a4553f]' : 'bg-[#8b9d77]')

const diskBarClass = computed(() => {
  const u = Number(data.value?.disk?.usedPercent) || 0
  return u >= 95 ? 'bg-[#a4553f]' : u >= 85 ? 'bg-[#c99a3f]' : 'bg-[#8b9d77]'
})
const diskFreeClass = computed(() => {
  const u = Number(data.value?.disk?.usedPercent) || 0
  return u >= 95 ? 'text-[#a4553f] font-medium' : 'text-stone-800'
})

const businessLabels = {
  users: '用户', posts: '帖子', goods: '二手', jobs: '兼职', activities: '活动', comments: '评论', orders: '订单'
}
const businessItems = computed(() => data.value?.business || {})

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await http.get('/admin/system/overview')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)
useAutoRefresh(load)
</script>
