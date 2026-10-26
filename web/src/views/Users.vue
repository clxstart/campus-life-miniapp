<template>
  <div class="organic-enter">
    <NoTable
      v-model:keyword="keyword"
      :columns="columns"
      :rows="rows"
      :loading="loading"
      :total="total"
      :page="page"
      :size="size"
      placeholder="搜索昵称或学校…"
      empty-text="没有匹配的用户"
      @search="load(1)"
      @page="load"
    >
      <template #actions>
        <!-- 多校 SaaS：超管可按校筛选用户 -->
        <select v-if="isSuper" v-model.number="schoolId" class="no-input !w-auto min-w-[10rem]" @change="load(1)">
          <option :value="0">全部学校</option>
          <option v-for="s in schools" :key="s.id" :value="s.id">{{ s.name }}</option>
        </select>
      </template>
      <template #nickname="{ row }">
        <div class="flex items-center gap-3">
          <!-- 有头像显示头像，没有就用昵称首字兜底 -->
          <span v-if="row.avatar" class="w-9 h-9 rounded-full border border-stone-200 overflow-hidden shrink-0">
            <img :src="row.avatar" alt="" class="w-full h-full object-cover" />
          </span>
          <span v-else class="organic-blob w-9 h-9 bg-[#e9e0d4] flex items-center justify-center text-sm shrink-0">
            {{ (row.nickname || '同')[0] }}
          </span>
          <span class="text-stone-800 font-medium">{{ row.nickname }}</span>
        </div>
      </template>
      <template #school="{ row }">
        <span class="no-badge bg-[#f0ece5] text-stone-600 border-stone-200">{{ row.school || '未归属' }}</span>
      </template>
      <template #createdAt="{ row }">
        <span class="text-stone-500 whitespace-nowrap">{{ formatTime(row.createdAt) }}</span>
      </template>
      <template #ops="{ row }">
        <button class="no-btn-ghost no-btn-sm whitespace-nowrap" @click="openAvatar(row)">改头像</button>
      </template>
    </NoTable>

    <NoModal
      :open="modal.open"
      title="修改用户头像"
      :hint="`用户：${modal.nickname}`"
      :submitting="modal.submitting"
      @close="modal.open = false"
      @confirm="saveAvatar"
    >
      <div class="space-y-4">
        <NoImageUpload v-model="modal.avatar" />
        <p class="text-xs text-stone-500">留空并保存即可清除头像，列表会回退为昵称首字。</p>
      </div>
    </NoModal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoTable from '../components/NoTable.vue'
import NoModal from '../components/NoModal.vue'
import NoImageUpload from '../components/NoImageUpload.vue'
import http from '../api'
import { useAuthStore } from '../stores/auth'
import { useAutoRefresh } from '../utils/auto-refresh'

const auth = useAuthStore()
const isSuper = computed(() => auth.isSuper)
const schools = ref([])
const schoolId = ref(0)

async function loadSchools() {
  if (!isSuper.value) return
  try { schools.value = (await http.get('/admin/schools')) || [] } catch { schools.value = [] }
}

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const keyword = ref('')
const modal = reactive({ open: false, id: null, nickname: '', avatar: '', submitting: false })

function openAvatar(row) {
  modal.id = row.id
  modal.nickname = row.nickname || ''
  modal.avatar = row.avatar || ''
  modal.open = true
}

async function saveAvatar() {
  modal.submitting = true
  try {
    await http.put(`/admin/users/${modal.id}/avatar`, { avatar: modal.avatar || '' })
    modal.open = false
    await load(page.value)
  } catch (e) {
    alert(e.message)
  } finally {
    modal.submitting = false
  }
}

const columns = [
  { key: 'id', title: 'ID', class: 'w-20' },
  { key: 'nickname', title: '昵称' },
  { key: 'school', title: '学校' },
  { key: 'postCount', title: '发帖数' },
  { key: 'createdAt', title: '注册时间' },
  { key: 'ops', title: '操作', class: 'w-28' }
]

function formatTime(value) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

async function load(nextPage = 1) {
  loading.value = true
  try {
    const data = await http.get('/admin/users', {
      params: { page: nextPage, size: size.value, keyword: keyword.value, schoolId: schoolId.value || undefined }
    })
    rows.value = data.list || []
    total.value = Number(data.total) || 0
    page.value = Number(data.page) || nextPage
  } catch (e) {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadSchools()
  load(1)
})

// 切回本页自动刷新
useAutoRefresh(() => load(page.value))
</script>

