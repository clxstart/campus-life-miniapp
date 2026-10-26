<template>
  <div class="space-y-6 organic-enter">
    <div class="flex items-center justify-between gap-4">
      <p class="text-sm text-stone-600">
        轮播位内容。小程序发现页（圈子页）读取 <span class="font-mono text-stone-800">slot=discover</span> 的数据。
      </p>
      <button class="no-btn-primary no-btn-sm" @click="openCreate">新增 Banner</button>
    </div>

    <NoTable
      :columns="columns"
      :rows="rows"
      :searchable="false"
      :total="total"
      :page="page"
      :size="size"
      empty-text="还没有配置 Banner"
      @page="load"
    >
      <template #title="{ row }">
        <div>
          <p class="text-stone-800 font-medium">{{ row.title }}</p>
          <p class="text-xs text-stone-500 mt-0.5">{{ row.subtitle || '—' }}</p>
        </div>
      </template>
      <template #theme="{ row }">
        <span class="inline-flex items-center gap-2 text-xs text-stone-600">
          <span class="w-3 h-3 rounded-full" :style="{ background: themeColor(row.theme) }"></span>
          {{ row.theme }}
        </span>
      </template>
      <template #link="{ row }">
        <span class="text-xs text-stone-500 font-mono">{{ row.linkType === 'page' ? row.linkValue : '无跳转' }}</span>
      </template>
      <template #status="{ row }">
        <span class="no-badge" :class="row.status === 1 ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]' : 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'">
          {{ row.status === 1 ? '上线' : '下线' }}
        </span>
      </template>
      <template #ops="{ row }">
        <div class="flex items-center gap-2">
          <button class="no-btn-ghost no-btn-sm" @click="openEdit(row)">编辑</button>
          <button class="no-btn-ghost no-btn-sm !text-[#a4553f]" @click="remove(row)">删除</button>
        </div>
      </template>
    </NoTable>

    <NoModal :open="modal.open" :title="modal.id ? '编辑 Banner' : '新增 Banner'" :submitting="modal.submitting" @close="modal.open = false" @confirm="save">
      <div class="space-y-5">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">标题</label>
          <input v-model.trim="modal.form.title" class="no-input" placeholder="如 校园歌手大赛" />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">副标题</label>
          <input v-model.trim="modal.form.subtitle" class="no-input" placeholder="一句话说明" />
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">眉标</label>
            <input v-model.trim="modal.form.eyebrow" class="no-input" placeholder="如 CAMPUS LIVE" />
          </div>
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">角标</label>
            <input v-model.trim="modal.form.badge" class="no-input" placeholder="如 12.05" />
          </div>
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">配色</label>
            <select v-model="modal.form.theme" class="no-input">
              <option v-for="t in ['blue', 'green', 'amber', 'pink', 'purple']" :key="t" :value="t">{{ t }}</option>
            </select>
          </div>
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">排序</label>
            <input v-model.number="modal.form.sort" class="no-input" type="number" />
          </div>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">跳转</label>
          <select v-model="modal.form.linkType" class="no-input">
            <option value="none">无跳转（点击提示）</option>
            <option value="page">跳转小程序页面</option>
          </select>
        </div>
        <div v-if="modal.form.linkType === 'page'">
          <label class="block mb-2 text-sm font-medium text-stone-700">页面路径</label>
          <input v-model.trim="modal.form.linkValue" class="no-input font-mono" placeholder="/pages/activities/activities" />
        </div>
        <label class="flex items-center gap-2 text-sm text-stone-700 cursor-pointer">
          <input type="checkbox" class="accent-[#8b9d77]" :checked="modal.form.status === 1" @change="modal.form.status = $event.target.checked ? 1 : 0" />
          立即上线
        </label>
      </div>
    </NoModal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import NoTable from '../components/NoTable.vue'
import NoModal from '../components/NoModal.vue'
import http from '../api'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const modal = reactive({
  open: false,
  id: null,
  submitting: false,
  form: { slot: 'discover', title: '', subtitle: '', eyebrow: '', badge: '', theme: 'blue', linkType: 'none', linkValue: '', sort: 0, status: 1 }
})

const columns = [
  { key: 'id', title: 'ID', class: 'w-20' },
  { key: 'title', title: 'Banner' },
  { key: 'theme', title: '配色' },
  { key: 'link', title: '跳转' },
  { key: 'sort', title: '排序', class: 'w-20' },
  { key: 'status', title: '状态' },
  { key: 'ops', title: '操作', class: 'w-40' }
]

const THEME_COLORS = { blue: '#75a191', green: '#8b9d77', amber: '#d4a373', pink: '#c98a8a', purple: '#9b8aa6' }
function themeColor(theme) { return THEME_COLORS[theme] || '#8b9d77' }

async function load(nextPage = 1) {
  try {
    const data = await http.get('/admin/banners', { params: { page: nextPage, size: size.value } })
    rows.value = data.list || []
    total.value = Number(data.total) || 0
    page.value = Number(data.page) || nextPage
  } catch (e) {
    rows.value = []
    total.value = 0
    alert(e.message)
  }
}

function openCreate() {
  modal.id = null
  modal.form = { slot: 'discover', title: '', subtitle: '', eyebrow: '', badge: '', theme: 'blue', linkType: 'none', linkValue: '', sort: 0, status: 1 }
  modal.open = true
}

function openEdit(row) {
  modal.id = row.id
  modal.form = {
    slot: row.slot || 'discover', title: row.title || '', subtitle: row.subtitle || '',
    eyebrow: row.eyebrow || '', badge: row.badge || '', theme: row.theme || 'blue',
    linkType: row.linkType || 'none', linkValue: row.linkValue || '',
    sort: row.sort ?? 0, status: row.status ?? 1
  }
  modal.open = true
}

async function save() {
  if (!modal.form.title) { alert('请填写标题'); return }
  modal.submitting = true
  try {
    if (modal.id) await http.put(`/admin/banners/${modal.id}`, modal.form)
    else await http.post('/admin/banners', modal.form)
    modal.open = false
    // 保持当前页，避免编辑后跳回第 1 页
    await load(page.value)
  } catch (e) {
    alert(e.message)
  } finally {
    modal.submitting = false
  }
}

async function remove(row) {
  if (!confirm(`确定删除 Banner「${row.title}」？`)) return
  try {
    await http.delete(`/admin/banners/${row.id}`)
    // 删掉当前页最后一条时回退一页，否则会停在空白页
    const nextPage = rows.value.length === 1 && page.value > 1 ? page.value - 1 : page.value
    await load(nextPage)
  } catch (e) {
    alert(e.message)
  }
}

onMounted(load)
</script>
