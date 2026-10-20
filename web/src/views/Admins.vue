<template>
  <div class="space-y-6 organic-enter">
    <div class="flex items-center justify-between gap-4">
      <p class="text-sm text-stone-600">超级管理员可管理全部学校；学校管理员只能管理本校数据。</p>
      <button class="no-btn-primary no-btn-sm" @click="openCreate">新增管理员</button>
    </div>

    <NoTable
      :columns="columns"
      :rows="rows"
      :searchable="false"
      empty-text="暂无管理员"
    >
      <template #username="{ row }">
        <span class="font-mono text-stone-800">{{ row.username }}</span>
      </template>
      <template #role="{ row }">
        <span class="no-badge" :class="row.role === 'super' ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]' : 'bg-[#f0ece5] text-stone-600 border-stone-200'">
          {{ row.role === 'super' ? '超级管理员' : '学校管理员' }}
        </span>
      </template>
      <template #schoolId="{ row }">
        <span class="text-stone-600">{{ row.schoolId ?? '全部学校' }}</span>
      </template>
      <template #status="{ row }">
        <span class="no-badge" :class="row.status === 1 ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]' : 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'">
          {{ row.status === 1 ? '正常' : '已停用' }}
        </span>
      </template>
      <template #ops="{ row }">
        <button class="no-btn-ghost no-btn-sm" @click="toggle(row)">{{ row.status === 1 ? '停用' : '启用' }}</button>
      </template>
    </NoTable>

    <NoModal
      :open="modal.open"
      title="新增管理员"
      hint="学校管理员必须绑定学校，才能管理本校数据"
      :submitting="modal.submitting"
      @close="modal.open = false"
      @confirm="save"
    >
      <div class="space-y-5">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">账号</label>
          <input v-model.trim="modal.form.username" class="no-input" placeholder="如 zhangsan，仅字母/数字/_.- " />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">初始密码</label>
          <input v-model="modal.form.password" class="no-input" type="text" placeholder="至少 6 位" />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">姓名</label>
          <input v-model.trim="modal.form.realName" class="no-input" placeholder="真实姓名" />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">角色</label>
          <select v-model="modal.form.role" class="no-input">
            <option value="school">学校管理员</option>
            <option value="super">超级管理员</option>
          </select>
        </div>
        <div v-if="modal.form.role === 'school'">
          <label class="block mb-2 text-sm font-medium text-stone-700">所属学校</label>
          <select v-model.number="modal.form.schoolId" class="no-input">
            <option :value="0" disabled>请选择学校</option>
            <option v-for="s in schools" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
        </div>
      </div>
    </NoModal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import NoTable from '../components/NoTable.vue'
import NoModal from '../components/NoModal.vue'
import { adminApi } from '../api'
import http from '../api'

const rows = ref([])
const schools = ref([])
const modal = reactive({
  open: false,
  submitting: false,
  form: { username: '', password: '', realName: '', role: 'school', schoolId: 0 }
})

const columns = [
  { key: 'id', title: 'ID', class: 'w-20' },
  { key: 'username', title: '账号' },
  { key: 'realName', title: '姓名' },
  { key: 'role', title: '角色' },
  { key: 'schoolId', title: '管辖范围' },
  { key: 'status', title: '状态' },
  { key: 'ops', title: '操作', class: 'w-28' }
]

async function load() {
  try {
    rows.value = await adminApi.admins()
  } catch (e) {
    rows.value = []
  }
}

function openCreate() {
  modal.form = { username: '', password: '', realName: '', role: 'school', schoolId: 0 }
  modal.open = true
}

async function save() {
  const f = modal.form
  // 账号只允许 ASCII 字母数字等：与后端 USERNAME_PATTERN 保持一致
  if (!/^[a-zA-Z0-9_.-]{3,32}$/.test(f.username || '')) {
    alert('账号只能用字母、数字、下划线、点或连字符（3-32 位），中文请填「真实姓名」')
    return
  }
  if (!f.password || f.password.length < 6) { alert('密码至少 6 位'); return }
  if (f.role === 'school' && !f.schoolId) { alert('请选择所属学校'); return }
  modal.submitting = true
  try {
    await adminApi.createAdmin({
      username: f.username,
      password: f.password,
      realName: f.realName,
      role: f.role,
      schoolId: f.role === 'school' ? f.schoolId : null
    })
    modal.open = false
    await load()
  } catch (e) {
    alert(e.message)
  } finally {
    modal.submitting = false
  }
}

async function toggle(row) {
  const next = row.status === 1 ? 0 : 1
  if (!confirm(`确定${next === 1 ? '启用' : '停用'}账号「${row.username}」？`)) return
  try {
    await adminApi.setAdminStatus(row.id, next)
    await load()
  } catch (e) {
    alert(e.message)
  }
}

onMounted(async () => {
  await load()
  try { schools.value = await http.get('/admin/schools') } catch { schools.value = [] }
})
</script>
