<template>
  <div class="min-h-screen flex items-center justify-center px-6 py-16">
    <div class="w-full max-w-md organic-enter">
      <!-- 品牌区：有机 blob + 衬线标题 -->
      <div class="flex flex-col items-center mb-8">
        <div class="organic-morph w-20 h-20 bg-[#8b9d77] flex items-center justify-center mb-5">
          <span class="font-serif text-3xl text-stone-900">校</span>
        </div>
        <h1 class="font-serif text-3xl md:text-4xl text-stone-800">校园生活 · 管理端</h1>
        <p class="mt-2 text-sm text-stone-600">管理学校、内容与校园服务</p>
      </div>

      <form class="no-card-plain space-y-5" @submit.prevent="submit">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700" for="username">账号</label>
          <input
            id="username"
            v-model.trim="form.username"
            class="no-input"
            type="text"
            autocomplete="username"
            placeholder="请输入管理员账号"
            required
          />
        </div>

        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700" for="password">密码</label>
          <input
            id="password"
            v-model="form.password"
            class="no-input"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
            required
          />
        </div>

        <p v-if="error" class="text-sm text-[#a4553f] bg-[#f6e7e0] border border-[#e3cbbf] rounded-[1.25rem] px-4 py-3">
          {{ error }}
        </p>

        <button class="no-btn-primary w-full" type="submit" :disabled="loading">
          {{ loading ? '正在登录…' : '登录' }}
        </button>
      </form>

    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const error = ref('')

async function submit() {
  if (loading.value) return
  error.value = ''
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    // 三种角色三个入口：商家 → /merchant，骑手 → /rider，管理员 → /dashboard
    const role = auth.profile?.role
    const destination = role === 'merchant' ? '/merchant' : role === 'rider' ? '/rider' : '/dashboard'
    router.replace(route.query.redirect && destination === '/dashboard' ? route.query.redirect : destination)
  } catch (e) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>
