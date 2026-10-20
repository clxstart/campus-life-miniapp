<template>
  <div class="min-h-screen flex">
    <!-- 侧边栏 -->
    <aside class="hidden md:flex md:flex-col w-64 shrink-0 border-r border-stone-200 bg-[#f3ece4]">
      <div class="px-6 py-7 flex items-center gap-3">
        <div class="organic-blob w-11 h-11 bg-[#8b9d77] flex items-center justify-center shrink-0">
          <span class="font-serif text-lg text-stone-900">校</span>
        </div>
        <div class="min-w-0">
          <p class="font-serif text-lg text-stone-800 leading-tight">校园生活</p>
          <p class="text-xs text-stone-500">管理端</p>
        </div>
      </div>

      <nav class="flex-1 px-4 space-y-1 overflow-y-auto pb-6">
        <router-link
          v-for="item in menus"
          :key="item.name"
          :to="{ name: item.name }"
          class="no-nav-item"
          active-class="no-nav-item-active"
        >
          <span class="w-5 text-center text-base">{{ item.icon }}</span>
          <span class="text-sm">{{ item.title }}</span>
        </router-link>
      </nav>

      <div class="px-6 py-5 border-t border-stone-200">
        <p class="text-xs text-stone-500">已登录</p>
        <p class="text-sm text-stone-800 font-medium truncate">{{ auth.displayName }}</p>
        <p class="text-xs text-stone-500 mt-0.5">{{ auth.roleLabel }}</p>
      </div>
    </aside>

    <!-- 主区域 -->
    <div class="flex-1 min-w-0 flex flex-col">
      <header class="border-b border-stone-200 bg-[#faf6f1]/90 backdrop-blur px-6 md:px-10 py-5 flex items-center justify-between gap-4">
        <div class="min-w-0">
          <h1 class="font-serif text-xl md:text-2xl text-stone-800 truncate">{{ pageTitle }}</h1>
          <p class="text-xs text-stone-500 mt-1 hidden sm:block">{{ pageHint }}</p>
        </div>
        <div class="flex items-center gap-3 shrink-0">
          <button class="no-btn-ghost no-btn-sm" @click="reload">刷新</button>
          <button class="no-btn-ghost no-btn-sm" @click="logout">退出</button>
        </div>
      </header>

      <!-- 移动端菜单 -->
      <div class="md:hidden border-b border-stone-200 bg-[#f3ece4] px-4 py-3 overflow-x-auto">
        <div class="flex gap-2">
          <router-link
            v-for="item in menus"
            :key="item.name"
            :to="{ name: item.name }"
            class="shrink-0 px-4 py-2 rounded-full text-xs border border-stone-300 text-stone-700"
            active-class="bg-[#8b9d77] text-stone-900 border-transparent"
          >{{ item.title }}</router-link>
        </div>
      </div>

      <main class="flex-1 px-6 md:px-10 py-8 overflow-x-hidden">
        <router-view v-slot="{ Component }">
          <component :is="Component" :key="reloadKey" />
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const reloadKey = ref(0)

// 超管才看得到学校/管理员管理；登录页是 public 路由，不能进菜单
const menus = computed(() => {
  const all = router.getRoutes()
    .filter(r => r.meta?.title && r.name && !r.meta.public)
    .map(r => ({ name: r.name, title: r.meta.title, icon: r.meta.icon, superOnly: !!r.meta.superOnly }))
  return all.filter(m => !m.superOnly || auth.isSuper)
})

const pageTitle = computed(() => route.meta?.title || '管理端')
const pageHint = computed(() => (auth.isSuper ? '全平台管理权限' : `本校管理权限 · 学校 ID ${auth.profile?.schoolId ?? '-'}`))

function reload() { reloadKey.value++ }

function logout() {
  auth.logout()
  router.replace('/login')
}
</script>
