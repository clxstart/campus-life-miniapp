<template>
  <section class="space-y-5">
    <div class="flex flex-wrap items-center justify-between gap-3"><p class="text-sm text-stone-600">管理骑手账号、所属学校和启用状态。</p><button class="no-btn-primary no-btn-sm" @click="openCreate">新增骑手</button></div>
    <p v-if="error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ error }}</p>
    <div class="no-card-plain !p-0 overflow-x-auto">
      <table class="w-full min-w-[760px] text-left text-sm">
        <thead><tr class="bg-[#f3ece4] text-stone-600"><th class="px-5 py-4 font-medium">账号</th><th class="px-5 py-4 font-medium">姓名</th><th class="px-5 py-4 font-medium">学校</th><th class="px-5 py-4 font-medium">状态</th><th class="px-5 py-4 font-medium">今日单量</th><th class="px-5 py-4 font-medium">操作</th></tr></thead>
        <tbody>
          <tr v-if="loading"><td colspan="6" class="px-5 py-12 text-center text-stone-500">正在加载…</td></tr>
          <tr v-else-if="!rows.length"><td colspan="6" class="px-5 py-12 text-center text-stone-500">暂无骑手账号</td></tr>
          <tr v-for="row in rows" :key="row.id" class="border-t border-stone-200">
            <td class="px-5 py-4">{{ row.username }}</td><td class="px-5 py-4">{{ row.realName }}</td><td class="px-5 py-4">{{ row.schoolName || '—' }}</td>
            <td class="px-5 py-4"><span class="no-badge" :class="row.status === 1 ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]' : 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'">{{ row.status === 1 ? '启用' : '停用' }}</span></td>
            <td class="px-5 py-4">{{ row.todayOrders || 0 }}</td>
            <td class="px-5 py-4"><div class="flex gap-2"><button class="no-btn-ghost no-btn-sm" @click="toggle(row)">{{ row.status === 1 ? '停用' : '启用' }}</button><button class="no-btn-ghost no-btn-sm" @click="openReset(row)">重置密码</button></div></td>
          </tr>
        </tbody>
      </table>
    </div>
    <NoModal :open="modal.open" :title="modal.mode === 'create' ? '新增骑手' : '重置骑手密码'" :hint="modal.mode === 'create' ? '创建后可使用该账号登录骑手工作台。' : `账号：${modal.form.username}`" :submitting="modal.submitting" confirm-text="确认" @close="modal.open = false" @confirm="save">
      <div class="space-y-4">
        <template v-if="modal.mode === 'create'">
          <label class="block text-sm text-stone-700">账号<input v-model.trim="modal.form.username" class="no-input mt-2" maxlength="32" autocomplete="off" /></label>
          <label class="block text-sm text-stone-700">姓名<input v-model.trim="modal.form.realName" class="no-input mt-2" maxlength="32" /></label>
          <label v-if="isSuper" class="block text-sm text-stone-700">学校<select v-model.number="modal.form.schoolId" class="no-input mt-2"><option :value="0" disabled>请选择学校</option><option v-for="school in schools" :key="school.id" :value="school.id">{{ school.name }}</option></select></label>
          <label class="block text-sm text-stone-700">联系电话（选填）<input v-model.trim="modal.form.phone" class="no-input mt-2" maxlength="20" /></label>
        </template>
        <label class="block text-sm text-stone-700">{{ modal.mode === 'create' ? '初始密码' : '新密码' }}<input v-model="modal.form.password" class="no-input mt-2" type="password" minlength="6" autocomplete="new-password" /></label>
      </div>
    </NoModal>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import http, { riderApi } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const isSuper = computed(() => auth.profile?.role === 'super')
const rows = ref([])
const schools = ref([])
const loading = ref(false)
const error = ref('')
const modal = reactive({ open: false, mode: 'create', id: null, submitting: false, form: {} })
async function load() {
  loading.value = true
  try { rows.value = await riderApi.riders(); error.value = '' }
  catch (e) { error.value = e.message || '骑手列表加载失败' }
  finally { loading.value = false }
}
async function openCreate() {
  modal.mode = 'create'; modal.id = null
  modal.form = { username: '', realName: '', password: '', phone: '', schoolId: isSuper.value ? 0 : Number(auth.profile?.schoolId || 0) }
  if (isSuper.value && !schools.value.length) {
    try { schools.value = await http.get('/admin/schools') }
    catch (e) { error.value = e.message || '学校列表加载失败'; return }
  }
  modal.open = true
}
function openReset(row) { modal.mode = 'reset'; modal.id = row.id; modal.form = { username: row.username, password: '' }; modal.open = true }
async function save() {
  if (modal.form.password.length < 6) { error.value = '密码至少6位'; return }
  if (modal.mode === 'create' && (!modal.form.username || !modal.form.realName || !modal.form.schoolId)) { error.value = '请完整填写账号、姓名和学校'; return }
  modal.submitting = true
  try {
    if (modal.mode === 'create') await riderApi.createRider(modal.form)
    else await riderApi.resetRiderPassword(modal.id, modal.form.password)
    modal.open = false; error.value = ''; await load()
  } catch (e) { error.value = e.message || '保存失败' }
  finally { modal.submitting = false }
}
async function toggle(row) {
  try { await riderApi.setRiderStatus(row.id, row.status === 1 ? 0 : 1); await load() }
  catch (e) { error.value = e.message || '账号状态更新失败' }
}
onMounted(load)
</script>
