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
      placeholder="搜索标题或正文…"
      empty-text="没有匹配的帖子"
      @search="load(1)"
      @page="load"
    >
      <template #actions>
        <label class="flex items-center gap-2 text-sm text-stone-600 cursor-pointer select-none">
          <input type="checkbox" v-model="offlineOnly" class="accent-[#8b9d77]" @change="load(1)" />
          只看已下架
        </label>
      </template>

      <template #title="{ row }">
        <div class="max-w-md">
          <p class="text-stone-800 font-medium truncate">{{ row.title }}</p>
          <p class="text-xs text-stone-500 truncate mt-0.5">{{ row.content }}</p>
        </div>
      </template>
      <template #status="{ row }">
        <span class="no-badge" :class="row.status === 1 ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]' : 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'">
          {{ row.status === 1 ? '正常' : '已下架' }}
        </span>
      </template>
      <template #author="{ row }">
        <span class="text-stone-600">{{ row.authorName || '—' }}</span>
      </template>
      <template #metrics="{ row }">
        <span class="text-xs text-stone-500">♥{{ row.likeCount }} ◌{{ row.commentCount }} 👁{{ row.viewCount }}</span>
      </template>
      <template #ops="{ row }">
        <div class="flex items-center gap-2 whitespace-nowrap">
          <button class="no-btn-ghost no-btn-sm" @click="openEdit(row)">编辑</button>
          <button class="no-btn-ghost no-btn-sm" @click="toggle(row)">
            {{ row.status === 1 ? '下架' : '恢复' }}
          </button>
          <button class="no-btn-ghost no-btn-sm !text-[#a4553f]" @click="remove(row)">删除</button>
        </div>
      </template>
    </NoTable>

    <NoModal
      :open="modal.open"
      title="编辑帖子"
      hint="用于修正错别字或更换配图，不会改变作者与发布时间"
      :submitting="modal.submitting"
      @close="modal.open = false"
      @confirm="save"
    >
      <div class="space-y-5">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">标题</label>
          <input v-model.trim="modal.form.title" class="no-input" maxlength="64" />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">正文</label>
          <textarea v-model.trim="modal.form.content" class="no-input !rounded-[1.5rem] h-32" maxlength="5000"></textarea>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">标签</label>
          <input v-model.trim="modal.form.tag" class="no-input" placeholder="如 校园日常" maxlength="16" />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">配图（选填）</label>
          <NoImageUpload v-model="modal.form.image" />
        </div>
      </div>
    </NoModal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import NoTable from '../components/NoTable.vue'
import NoModal from '../components/NoModal.vue'
import NoImageUpload from '../components/NoImageUpload.vue'
import http from '../api'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const keyword = ref('')
const offlineOnly = ref(false)
const modal = reactive({ open: false, id: null, submitting: false, form: { title: '', content: '', tag: '', image: '' } })

function openEdit(row) {
  modal.id = row.id
  modal.form = {
    title: row.title || '',
    content: row.content || '',
    tag: row.tag || '',
    image: row.image || ''
  }
  modal.open = true
}

async function save() {
  if (!modal.form.title) { alert('请填写标题'); return }
  if (!modal.form.content) { alert('请填写正文'); return }
  modal.submitting = true
  try {
    await http.put(`/admin/posts/${modal.id}`, modal.form)
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
  { key: 'title', title: '帖子' },
  { key: 'author', title: '作者' },
  { key: 'status', title: '状态' },
  { key: 'metrics', title: '互动' },
  { key: 'ops', title: '操作', class: 'w-64' }
]

async function load(nextPage = 1) {
  loading.value = true
  try {
    const data = await http.get('/admin/posts', {
      params: { page: nextPage, size: size.value, keyword: keyword.value, offlineOnly: offlineOnly.value }
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

async function toggle(row) {
  const next = row.status === 1 ? 0 : 1
  try {
    await http.post(`/admin/posts/${row.id}/status`, { status: next })
    row.status = next
  } catch (e) {
    alert(e.message)
  }
}

async function remove(row) {
  if (!confirm(`确定删除帖子「${row.title}」？此操作不可恢复。`)) return
  try {
    await http.delete(`/admin/posts/${row.id}`)
    await load(page.value)
  } catch (e) {
    alert(e.message)
  }
}

onMounted(() => load(1))
</script>
