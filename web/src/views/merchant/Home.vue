<template>
  <div v-if="loading" class="py-16 text-center text-sm text-[#78857c]">正在加载店铺数据…</div>
  <div v-else class="space-y-6">
    <div class="flex flex-wrap items-start justify-between gap-3"><div><h2 class="text-xl font-semibold">{{ data.shop?.name || '店铺首页' }}</h2><p class="mt-1 text-sm text-[#77847b]">{{ today }}</p></div><span class="rounded border px-2.5 py-1 text-xs" :class="data.shop?.status === 1 ? (data.shop?.isOpen === 1 ? 'border-[#b6d1bd] bg-[#eff7f0] text-[#2e6f44]' : 'border-[#e5d4ad] bg-[#fff9eb] text-[#8b691d]') : 'border-[#e4c9c7] bg-[#fff5f4] text-[#a34743]'">{{ shopState }}</span></div>

    <section class="grid gap-3 sm:grid-cols-3">
      <article class="rounded-md border border-[#e0e7e1] bg-white p-4"><p class="text-sm text-[#77847b]">今日订单</p><p class="mt-3 text-3xl font-semibold">{{ data.todayOrders || 0 }}</p><p class="mt-2 text-xs text-[#8a968e]">按下单时间统计</p></article>
      <article class="rounded-md border border-[#e0e7e1] bg-white p-4"><p class="text-sm text-[#77847b]">今日营业额</p><p class="mt-3 text-3xl font-semibold">¥{{ money(data.todayRevenue) }}</p><p class="mt-2 text-xs text-[#8a968e]">已接单及已完成订单</p></article>
      <article class="rounded-md border border-[#e0e7e1] bg-white p-4"><p class="text-sm text-[#77847b]">待接单</p><p class="mt-3 text-3xl font-semibold">{{ data.pendingOrders || 0 }}</p><RouterLink to="/merchant/orders" class="mt-2 inline-block text-xs font-medium text-[#34764b]">查看订单 →</RouterLink></article>
    </section>

    <section class="border-y border-[#dfe7e0] py-5">
      <h3 class="text-sm font-semibold">快捷入口</h3>
      <div class="mt-3 grid gap-3 sm:grid-cols-3">
        <RouterLink v-for="link in links" :key="link.to" :to="link.to" class="flex items-center justify-between rounded-md border border-[#dfe7e0] bg-white px-4 py-4 hover:border-[#b9d0be]"><span><span class="block text-sm font-medium">{{ link.label }}</span><span class="mt-1 block text-xs text-[#77847b]">{{ link.hint }}</span></span><span class="text-xl text-[#508565]">{{ link.icon }}</span></RouterLink>
      </div>
    </section>
    <p v-if="error" class="text-sm text-[#a34743]">{{ error }}</p>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { merchantApi } from '../../api'
const data = ref({ shop: {} })
const loading = ref(true)
const error = ref('')
const links = [{ to: '/merchant/shop', label: '店铺设置', hint: '编辑门店资料和营业时间', icon: '›' }, { to: '/merchant/goods', label: '菜品管理', hint: '更新菜单和价格', icon: '›' }, { to: '/merchant/orders', label: '订单管理', hint: '查看订单并处理接单', icon: '›' }]
const today = computed(() => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'full' }).format(new Date()))
const shopState = computed(() => data.value.shop?.status !== 1 ? '待平台上架' : (Number(data.value.shop?.isOpen) === 1 ? '营业中' : '休息中'))
onMounted(async () => { try { data.value = await merchantApi.home() } catch (e) { error.value = e.message || '数据加载失败' } finally { loading.value = false } })
function money(value) { return Number(value || 0).toFixed(2) }
</script>
