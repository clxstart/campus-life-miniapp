<template>
  <div class="min-h-screen bg-[#f6f7f5] text-[#202b2b]">
    <header class="border-b border-[#dce2df] bg-white">
      <div class="mx-auto flex max-w-7xl items-center justify-between gap-4 px-5 py-4 md:px-8">
        <div class="flex min-w-0 items-center gap-3">
          <div class="grid h-10 w-10 shrink-0 place-items-center rounded-md bg-[#166b5b] text-lg font-semibold text-white">送</div>
          <div class="min-w-0">
            <p class="text-xs font-medium text-[#60716d]">校园配送</p>
            <h1 class="truncate text-lg font-semibold">骑手工作台</h1>
          </div>
        </div>
        <div class="flex shrink-0 items-center gap-3 text-sm">
          <span class="hidden text-[#52635f] sm:block">{{ auth.displayName }}</span>
          <button class="rounded-md border border-[#cbd5d1] px-3 py-2 hover:bg-[#f1f5f3]" @click="logout">退出</button>
        </div>
      </div>
    </header>
    <div class="mx-auto grid max-w-7xl gap-6 px-4 py-5 md:grid-cols-[200px_minmax(0,1fr)] md:px-8 md:py-8">
      <nav class="flex gap-2 overflow-x-auto md:flex-col md:gap-1">
        <!-- 用 exact-active-class 而不是 active-class：
             /rider 是父路由，active-class 会把它算成所有子页的祖先，导致「工作台」一直高亮。 -->
        <RouterLink v-for="item in links" :key="item.to" :to="item.to" class="shrink-0 rounded-md px-4 py-3 text-sm text-[#52635f] hover:bg-white" exact-active-class="!bg-[#dceee8] !font-semibold !text-[#155a4e]">{{ item.label }}</RouterLink>
      </nav>
      <main class="min-w-0">
        <div class="mb-5 border-b border-[#dce2df] pb-4">
          <h2 class="text-xl font-semibold">{{ route.meta.title }}</h2>
        </div>
        <RouterView />
      </main>
    </div>
  </div>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const links = [
  { to: '/rider', label: '工作台' },
  { to: '/rider/pool', label: '待抢订单' },
  { to: '/rider/mine', label: '我的配送' },
  { to: '/rider/history', label: '配送记录' }
]
function logout() {
  auth.logout()
  router.replace('/login')
}
</script>
