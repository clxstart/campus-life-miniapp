<template>
  <div class="space-y-8 organic-enter">
    <!-- 错误提示：避免静默失败（之前 dashboard 接口缺方法，页面静默显示 0，查了很久） -->
    <div v-if="error" class="no-card-plain !bg-[#f6e7e0] !border-[#e3cbbf] flex items-center justify-between gap-4 !py-5">
      <span class="text-[#a4553f] text-sm">{{ error }}</span>
      <button class="no-btn-ghost no-btn-sm" @click="load">重试</button>
    </div>

    <!-- 概览卡片 -->
    <section>
      <h2 class="font-serif text-2xl text-stone-800 mb-5">概览</h2>
      <div class="grid grid-cols-2 lg:grid-cols-4 gap-6 md:gap-8">
        <div v-for="card in cards" :key="card.key" class="no-card-plain">
          <div class="flex items-center justify-between">
            <span class="text-xs text-stone-500">{{ card.label }}</span>
            <span class="organic-blob w-9 h-9 flex items-center justify-center text-sm" :style="{ background: card.tint }">{{ card.icon }}</span>
          </div>
          <p class="mt-4 font-serif text-3xl md:text-4xl text-stone-800">{{ stats[card.key] ?? 0 }}</p>
        </div>
      </div>
    </section>

    <!-- 近 7 天发帖趋势：纯 CSS 柱状，不引入图表库 -->
    <section class="no-card-plain">
      <div class="flex items-baseline justify-between mb-6">
        <h2 class="font-serif text-xl md:text-2xl text-stone-800">近 7 天新增帖子</h2>
        <span class="text-xs text-stone-500">{{ scopeHint }}</span>
      </div>

      <div v-if="!trend.length" class="py-12 text-center text-stone-500 text-sm">暂无数据</div>
      <div v-else class="flex items-end gap-4 md:gap-8 h-48">
        <div v-for="item in trend" :key="item.day" class="flex-1 flex flex-col items-center justify-end gap-3">
          <span class="text-xs text-stone-600">{{ item.total }}</span>
          <!-- 柱子用绝对像素高度：百分比高度在父容器 height:auto 时会退化成 0（曾经导致柱子完全不显示） -->
          <div
            class="w-full max-w-[3rem] bg-[#8b9d77] rounded-t-[1rem] transition-all duration-500"
            :style="{ height: barHeight(item.total) + 'px' }"
            :title="`${item.day}：${item.total} 篇`"
          ></div>
          <span class="text-xs text-stone-500">{{ shortDay(item.day) }}</span>
        </div>
      </div>
    </section>

    <!-- 快捷入口 -->
    <section class="no-card">
      <h2 class="font-serif text-xl md:text-2xl text-stone-800 mb-4">常用操作</h2>
      <div class="flex flex-wrap gap-3">
        <router-link v-for="link in quickLinks" :key="link.to" :to="link.to" class="no-btn-ghost no-btn-sm">
          {{ link.label }}
        </router-link>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { adminApi } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const stats = ref({})
const trend = ref([])
const loading = ref(true)
const error = ref('')

const cards = [
  { key: 'users', label: '用户', icon: '☺', tint: '#e9e0d4' },
  { key: 'posts', label: '帖子', icon: '✎', tint: '#d4a373' },
  { key: 'circles', label: '圈子', icon: '◎', tint: '#8b9d77' },
  { key: 'goods', label: '闲置', icon: '▦', tint: '#75a191' }
]

const quickLinks = computed(() => {
  const base = [
    { to: '/posts', label: '内容管理' },
    { to: '/circles', label: '圈子管理' },
    { to: '/users', label: '用户管理' },
    { to: '/academics', label: '教务管理' },
    { to: '/business', label: '业务管理' },
    { to: '/logs', label: '操作日志' }
  ]
  return auth.isSuper ? [{ to: '/schools', label: '学校管理' }, ...base] : base
})

const scopeHint = computed(() => (auth.isSuper ? '全平台' : '本校数据'))
const maxTrend = computed(() => Math.max(1, ...trend.value.map(t => Number(t.total) || 0)))

// 柱子可用高度（px）。容器 h-48 = 192px，减去顶部数值行、底部日期行和两处 gap
const BAR_MAX_PX = 120

function barHeight(total) {
  // 最少 6px，保证 0 值也有一条可见的基线，而不是彻底消失
  return Math.max(6, Math.round((Number(total) || 0) / maxTrend.value * BAR_MAX_PX))
}

function shortDay(day) {
  const text = String(day || '')
  return text.length >= 10 ? text.slice(5) : text
}

async function load() {
  error.value = ''
  loading.value = true
  try {
    const data = await adminApi.dashboard()
    stats.value = data.stats || {}
    trend.value = (data.postTrend || []).map(row => ({ day: row.day, total: Number(row.total) || 0 }))
  } catch (e) {
    error.value = e.message || '看板数据加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
