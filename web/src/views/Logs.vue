<template>
  <div class="organic-enter">
    <NoTable
      :columns="columns"
      :rows="rows"
      :loading="loading"
      :total="total"
      :page="page"
      :size="size"
      :searchable="false"
      empty-text="暂无操作记录"
      @page="load"
    >
      <template #action="{ row }">
        <span class="no-badge" :class="actionClass(row.action)">{{ actionLabel(row.action) }}</span>
      </template>
      <template #target="{ row }">
        <span class="text-stone-600">{{ row.targetType || '—' }}</span>
        <span v-if="row.targetId" class="text-stone-400"> #{{ row.targetId }}</span>
      </template>
      <template #detail="{ row }">
        <span class="text-stone-700">{{ row.detail || '—' }}</span>
      </template>
      <template #createdAt="{ row }">
        <span class="text-stone-500 whitespace-nowrap">{{ formatTime(row.createdAt) }}</span>
      </template>
    </NoTable>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import NoTable from '../components/NoTable.vue'
import { adminApi } from '../api'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

const columns = [
  { key: 'createdAt', title: '时间' },
  { key: 'adminName', title: '操作人' },
  { key: 'action', title: '动作' },
  { key: 'target', title: '对象' },
  { key: 'detail', title: '详情' },
  { key: 'ip', title: 'IP' }
]

const ACTION_LABEL = { create: '新增', update: '编辑', delete: '删除', offline: '下架', online: '恢复' }

function actionLabel(action) { return ACTION_LABEL[action] || action || '—' }

function actionClass(action) {
  if (action === 'delete' || action === 'offline') return 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'
  if (action === 'create') return 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'
  return 'bg-[#f0ece5] text-stone-600 border-stone-200'
}

function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const pad = n => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

async function load(nextPage = 1) {
  loading.value = true
  try {
    const data = await adminApi.logs({ page: nextPage, size: size.value })
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

onMounted(() => load(1))
</script>
