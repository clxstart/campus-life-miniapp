<template>
  <div class="min-h-screen bg-[#f4f7f4] text-[#24342b] md:flex">
    <aside class="hidden w-60 shrink-0 flex-col border-r border-[#dfe7e0] bg-white md:flex">
      <div class="border-b border-[#e7ece7] px-5 py-5">
        <p class="text-xs font-semibold uppercase tracking-[0.16em] text-[#508565]">CAMPUS FOOD</p>
        <p class="mt-2 truncate text-base font-semibold">{{ shop.name || '商家后台' }}</p>
        <p class="mt-1 text-xs text-[#77847b]">商家管理</p>
      </div>
      <nav class="flex-1 space-y-1 p-3">
        <RouterLink v-for="item in menus" :key="item.path" :to="item.path" class="flex items-center gap-3 rounded px-3 py-2.5 text-sm text-[#59675f] hover:bg-[#f1f6f1]" active-class="!bg-[#eaf3eb] !font-semibold !text-[#2f7046]">
          <span class="inline-flex w-5 justify-center text-base">{{ item.icon }}</span>{{ item.label }}
        </RouterLink>
      </nav>
      <div class="border-t border-[#e7ece7] px-5 py-4"><p class="truncate text-sm font-medium">{{ auth.displayName }}</p><p class="mt-1 text-xs text-[#77847b]">商家账号</p></div>
    </aside>

    <div class="min-w-0 flex-1">
      <header class="flex min-h-16 items-center justify-between gap-4 border-b border-[#dfe7e0] bg-white px-4 sm:px-7">
        <div class="min-w-0"><h1 class="truncate text-lg font-semibold">{{ route.meta.title || '商家后台' }}</h1><p class="truncate text-xs text-[#77847b]">{{ shop.name || '正在加载店铺…' }}</p></div>
        <button class="rounded border border-[#dce5dd] px-3 py-2 text-sm text-[#526159] hover:bg-[#f5f8f5]" @click="logout">退出</button>
      </header>
      <nav class="flex gap-2 overflow-x-auto border-b border-[#dfe7e0] bg-white px-3 py-2 md:hidden">
        <RouterLink v-for="item in menus" :key="item.path" :to="item.path" class="shrink-0 rounded border border-[#e0e7e1] px-3 py-2 text-xs text-[#59675f]" active-class="!border-[#b5d0bb] !bg-[#eaf3eb] !text-[#2f7046]">{{ item.label }}</RouterLink>
      </nav>
      <main class="mx-auto w-full max-w-6xl p-4 sm:p-7"><RouterView /></main>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { merchantApi } from '../api'
import { useAuthStore } from '../stores/auth'

const menus = [{ path: '/merchant', label: '首页', icon: '⌂' }, { path: '/merchant/shop', label: '店铺设置', icon: '▣' }, { path: '/merchant/goods', label: '菜品管理', icon: '◈' }, { path: '/merchant/orders', label: '订单管理', icon: '≡' }]
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const shop = ref({})
onMounted(async () => { try { shop.value = await merchantApi.shop() } catch {} })
function logout() { auth.logout(); router.replace('/login') }
</script>
