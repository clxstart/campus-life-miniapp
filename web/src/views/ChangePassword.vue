<template>
  <div class="min-h-screen flex items-center justify-center px-6 py-16">
    <div class="w-full max-w-md organic-enter">
      <div class="flex flex-col items-center mb-8">
        <div class="organic-morph w-20 h-20 bg-[#8b9d77] flex items-center justify-center mb-5">
          <span class="font-serif text-3xl text-stone-900">校</span>
        </div>
        <h1 class="font-serif text-3xl md:text-4xl text-stone-800">首次登录改密</h1>
        <p class="mt-2 text-center text-sm text-stone-600">
          账号 {{ username }} 仍在使用一次性初始密码，改密后才能进入工作台。
        </p>
      </div>

      <form class="no-card-plain space-y-5" @submit.prevent="submit">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700" for="oldPassword">当前密码</label>
          <input id="oldPassword" v-model="form.oldPassword" class="no-input" type="password"
                 autocomplete="current-password" placeholder="请输入一次性初始密码" required />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700" for="newPassword">新密码</label>
          <input id="newPassword" v-model="form.newPassword" class="no-input" type="password" minlength="8"
                 maxlength="72" autocomplete="new-password" placeholder="至少 8 位，不能与初始密码相同" required />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700" for="confirmPassword">确认新密码</label>
          <input id="confirmPassword" v-model="form.confirmPassword" class="no-input" type="password" minlength="8"
                 maxlength="72" autocomplete="new-password" placeholder="再次输入新密码" required />
        </div>

        <p v-if="error" class="text-sm text-[#a4553f] bg-[#f6e7e0] border border-[#e3cbbf] rounded-[1.25rem] px-4 py-3">
          {{ error }}
        </p>

        <button class="no-btn-primary w-full" type="submit" :disabled="loading">
          {{ loading ? '正在修改…' : '修改密码并重新登录' }}
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
const username = ref(String(route.query.username || auth.profile?.username || ''))
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const loading = ref(false)
const error = ref('')

async function submit() {
  if (loading.value) return
  error.value = ''
  if (form.newPassword.length < 8) { error.value = '新密码至少 8 位'; return }
  if (form.newPassword !== form.confirmPassword) { error.value = '两次输入的新密码不一致'; return }
  if (form.newPassword === form.oldPassword) { error.value = '新密码不能与当前密码相同'; return }
  loading.value = true
  try {
    await auth.changePassword(form.oldPassword, form.newPassword)
    router.replace({ path: '/login', query: { changed: '1' } })
  } catch (e) {
    error.value = e.message || '修改密码失败'
  } finally {
    loading.value = false
  }
}
</script>