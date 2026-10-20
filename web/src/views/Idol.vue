<template>
  <div class="space-y-6 organic-enter">
    <div class="flex flex-wrap items-center justify-between gap-4">
      <div>
        <p class="text-sm text-stone-600">校草 / 校花参赛选手。停用后小程序评选页不再显示该选手。</p>
        <p class="text-xs text-stone-500 mt-1">票数由小程序投票自动累加，管理端只读。</p>
      </div>
      <button class="no-btn-primary no-btn-sm" @click="openForm()">新增选手</button>
    </div>

    <div class="flex flex-wrap gap-2">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="px-5 py-2.5 rounded-full text-sm border transition-colors duration-300"
        :class="active === tab.key ? 'bg-[#8b9d77] text-stone-900 border-transparent' : 'border-stone-300 text-stone-600 hover:bg-stone-200/60'"
        @click="switchTab(tab.key)"
      >{{ tab.label }}</button>
    </div>

    <div v-if="loading" class="py-12 text-center text-stone-500 text-sm">加载中…</div>
    <div v-else-if="!rows.length" class="no-card-plain py-12 text-center text-stone-500 text-sm">还没有选手</div>
    <div v-else class="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-6">
      <div v-for="row in rows" :key="row.id" class="no-card-plain">
        <div class="flex items-start gap-4">
          <span class="w-16 h-16 rounded-[1.25rem] border border-stone-200 overflow-hidden shrink-0 bg-[#faf6f1]">
            <img v-if="row.photo" :src="row.photo" alt="" class="w-full h-full object-cover" />
          </span>
          <div class="min-w-0 flex-1">
            <h3 class="font-serif text-lg text-stone-800 truncate">{{ row.name }}</h3>
            <p class="text-xs text-stone-500 mt-1 truncate">{{ row.college || '未填写学院' }}</p>
            <p class="text-xs text-stone-500 mt-1">{{ row.gender === 1 ? '男生组' : '女生组' }} · {{ row.voteCount }} 票</p>
          </div>
          <span class="no-badge shrink-0" :class="row.status === 1
            ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'
            : 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'">
            {{ row.status === 1 ? '参选中' : '已停用' }}
          </span>
        </div>

        <p v-if="row.slogan" class="text-xs text-stone-500 mt-4 line-clamp-2">「{{ row.slogan }}」</p>

        <div class="flex gap-2 mt-5">
          <button class="no-btn-ghost no-btn-sm" @click="openForm(row)">编辑</button>
          <button class="no-btn-ghost no-btn-sm" @click="toggle(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </button>
          <button class="no-btn-ghost no-btn-sm !text-[#a4553f]" @click="remove(row)">删除</button>
        </div>
      </div>
    </div>

    <NoModal
      :open="modal.open"
      :title="modal.id ? '编辑选手' : '新增选手'"
      hint="照片建议竖版人像，会显示在小程序评选卡片上"
      :submitting="modal.submitting"
      @close="modal.open = false"
      @confirm="save"
    >
      <div class="space-y-5">
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">姓名</label>
            <input v-model.trim="modal.form.name" class="no-input" maxlength="32" placeholder="如 苏晚" />
          </div>
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">组别</label>
            <select v-model.number="modal.form.gender" class="no-input">
              <option :value="2">女生组</option>
              <option :value="1">男生组</option>
            </select>
          </div>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">学院</label>
          <input v-model.trim="modal.form.college" class="no-input" maxlength="64" placeholder="如 外国语学院" />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">参赛宣言（选填）</label>
          <input v-model.trim="modal.form.slogan" class="no-input" maxlength="128" placeholder="一句话拉票" />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">选手照片</label>
          <NoImageUpload v-model="modal.form.photo" />
        </div>
      </div>
    </NoModal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import NoImageUpload from '../components/NoImageUpload.vue'
import http from '../api'

const tabs = [
  { key: 0, label: '全部' },
  { key: 2, label: '女生组' },
  { key: 1, label: '男生组' }
]

const active = ref(0)
const rows = ref([])
const loading = ref(false)

const modal = reactive({
  open: false, id: null, submitting: false,
  form: { name: '', gender: 2, college: '', slogan: '', photo: '' }
})

async function load() {
  loading.value = true
  try {
    const params = active.value ? { gender: active.value } : {}
    rows.value = await http.get('/admin/idol-contestants', { params }) || []
  } catch (e) {
    rows.value = []
    alert(e.message)
  } finally {
    loading.value = false
  }
}

function switchTab(key) {
  if (active.value === key) return
  active.value = key
  load()
}

function openForm(row) {
  if (row) {
    modal.id = row.id
    modal.form = {
      name: row.name || '', gender: row.gender ?? 2,
      college: row.college || '', slogan: row.slogan || '', photo: row.photo || ''
    }
  } else {
    modal.id = null
    modal.form = { name: '', gender: 2, college: '', slogan: '', photo: '' }
  }
  modal.open = true
}

async function save() {
  const f = modal.form
  if (!f.name) { alert('请填写姓名'); return }
  if (!f.photo) { alert('请上传选手照片'); return }
  modal.submitting = true
  try {
    if (modal.id) await http.put(`/admin/idol-contestants/${modal.id}`, f)
    else await http.post('/admin/idol-contestants', f)
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
  try {
    await http.post(`/admin/idol-contestants/${row.id}/status`, { status: next })
    row.status = next
  } catch (e) {
    alert(e.message)
  }
}

async function remove(row) {
  if (!confirm(`确定删除选手「${row.name}」？其票数记录也会一并清除。`)) return
  try {
    await http.delete(`/admin/idol-contestants/${row.id}`)
    await load()
  } catch (e) {
    alert(e.message)
  }
}

onMounted(load)
</script>
