<template>
  <div class="space-y-6 organic-enter">
    <!-- 分类切换 -->
    <div class="flex flex-wrap items-center gap-2">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="px-5 py-2.5 rounded-full text-sm border transition-colors duration-300"
        :class="active === tab.key ? 'bg-[#8b9d77] text-stone-900 border-transparent' : 'border-stone-300 text-stone-600 hover:bg-stone-200/60'"
        @click="switchTab(tab.key)"
      >{{ tab.label }}</button>
      <!-- 多校 SaaS：超管可按校筛选二手/兼职/活动/轮播位 -->
      <select v-if="isSuper" v-model.number="schoolId" class="no-input !w-auto min-w-[10rem] ml-auto" @change="load(1)">
        <option :value="0">全部学校</option>
        <option v-for="s in schools" :key="s.id" :value="s.id">{{ s.name }}</option>
      </select>
    </div>

    <NoTable
      v-model:keyword="keyword"
      :columns="columns"
      :rows="rows"
      :loading="loading"
      :total="total"
      :page="page"
      :size="size"
      :placeholder="placeholder"
      :empty-text="emptyText"
      @search="load(1)"
      @page="load"
    >
      <template #title="{ row }">
        <div class="max-w-md">
          <p class="text-stone-800 font-medium truncate">{{ row.title }}</p>
          <p class="text-xs text-stone-500 truncate mt-0.5">{{ subtitle(row) }}</p>
        </div>
      </template>
      <template #publisher="{ row }">
        <span class="text-stone-600">{{ row.publisherName || row.sellerName || '—' }}</span>
      </template>
      <template #school="{ row }">
        <span class="no-badge bg-[#f0ece5] text-stone-600 border-stone-200">{{ row.schoolName || '跨校' }}</span>
      </template>
      <template #status="{ row }">
        <span class="no-badge" :class="statusClass(row)">{{ statusText(row) }}</span>
      </template>
      <template #ops="{ row }">
        <div class="flex items-center gap-2 whitespace-nowrap">
          <button v-if="active !== 'jobs'" class="no-btn-ghost no-btn-sm" @click="openEdit(row)">编辑</button>
          <button v-if="active !== 'jobs'" class="no-btn-ghost no-btn-sm" @click="toggle(row)">
            {{ isOnline(row) ? '下架' : '上架' }}
          </button>
          <button v-if="active === 'jobs'" class="no-btn-ghost no-btn-sm !text-[#a4553f]" @click="remove(row)">删除</button>
        </div>
      </template>
    </NoTable>

    <!-- 活动编辑 -->
    <NoModal
      :open="modal.open && active === 'activities'"
      title="编辑活动"
      hint="用于修正活动信息或更换海报"
      :submitting="modal.submitting"
      @close="modal.open = false"
      @confirm="save"
    >
      <div class="space-y-5">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">活动名称</label>
          <input v-model.trim="modal.form.title" class="no-input" maxlength="64" />
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">时间说明</label>
            <input v-model.trim="modal.form.timeDesc" class="no-input" placeholder="如 本周六 14:00" maxlength="32" />
          </div>
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">地点</label>
            <input v-model.trim="modal.form.location" class="no-input" placeholder="如 操场" maxlength="64" />
          </div>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">描述</label>
          <textarea v-model.trim="modal.form.description" class="no-input !rounded-[1.5rem] h-24" maxlength="200"></textarea>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">活动海报（选填）</label>
          <NoImageUpload v-model="modal.form.poster" />
        </div>
      </div>
    </NoModal>

    <!-- 二手编辑 -->
    <NoModal
      :open="modal.open && active === 'goods'"
      title="编辑商品"
      hint="用于修正价格或更换商品图"
      :submitting="modal.submitting"
      @close="modal.open = false"
      @confirm="save"
    >
      <div class="space-y-5">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">商品标题</label>
          <input v-model.trim="modal.form.title" class="no-input" maxlength="64" />
        </div>
        <div class="grid grid-cols-3 gap-4">
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">价格</label>
            <input v-model="modal.form.price" class="no-input" type="number" min="0" max="99999" />
          </div>
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">分类</label>
            <select v-model="modal.form.category" class="no-input">
              <option v-for="c in GOODS_CATEGORIES" :key="c" :value="c">{{ c }}</option>
            </select>
          </div>
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">成色</label>
            <select v-model="modal.form.conditionLevel" class="no-input">
              <option v-for="c in GOODS_CONDITIONS" :key="c" :value="c">{{ c }}</option>
            </select>
          </div>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">描述</label>
          <textarea v-model.trim="modal.form.description" class="no-input !rounded-[1.5rem] h-24" maxlength="200"></textarea>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">商品图（选填）</label>
          <NoImageUpload v-model="modal.form.image" />
        </div>
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

// 分类/成色与管理端其他地方保持一致
const GOODS_CATEGORIES = ['数码', '图书', '生活', '运动', '其他']
const GOODS_CONDITIONS = ['全新', '九成新', '七成新', '七成新及以下']

const tabs = [
  { key: 'jobs', label: '兼职' },
  { key: 'activities', label: '活动' },
  { key: 'goods', label: '二手' }
]

const active = ref('jobs')
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const keyword = ref('')
const modal = reactive({
  open: false, id: null, submitting: false,
  form: { title: '', timeDesc: '', location: '', description: '', poster: '', price: 0, category: '其他', conditionLevel: '九成新', image: '' }
})

function openEdit(row) {
  modal.id = row.id
  if (active.value === 'activities') {
    modal.form = {
      title: row.title || '', timeDesc: row.timeDesc || '', location: row.location || '',
      description: row.description || '', poster: row.poster || '',
      price: 0, category: '其他', conditionLevel: '九成新', image: ''
    }
  } else {
    modal.form = {
      title: row.title || '', price: row.price ?? 0,
      category: row.category || '其他', conditionLevel: row.conditionLevel || '九成新',
      description: row.description || '', image: row.image || '',
      timeDesc: '', location: '', poster: ''
    }
  }
  modal.open = true
}

async function save() {
  if (!modal.form.title) { alert('请填写标题'); return }
  modal.submitting = true
  try {
    if (active.value === 'activities') {
      await http.put(`/admin/activities/${modal.id}`, {
        title: modal.form.title, timeDesc: modal.form.timeDesc,
        location: modal.form.location, description: modal.form.description,
        poster: modal.form.poster
      })
    } else {
      const price = Number(modal.form.price)
      if (!(price >= 0) || price > 99999) { alert('价格须为 0 至 99999'); modal.submitting = false; return }
      await http.put(`/admin/goods/${modal.id}`, {
        title: modal.form.title, price, category: modal.form.category,
        conditionLevel: modal.form.conditionLevel,
        description: modal.form.description, image: modal.form.image
      })
    }
    modal.open = false
    await load(page.value)
  } catch (e) {
    alert(e.message)
  } finally {
    modal.submitting = false
  }
}

const columns = computed(() => {
  const base = [
    { key: 'id', title: 'ID', class: 'w-20' },
    { key: 'title', title: '内容' }
  ]
  if (active.value === 'jobs') base.push({ key: 'publisher', title: '发布者' })
  if (active.value === 'goods') base.push({ key: 'publisher', title: '卖家' })
  base.push({ key: 'school', title: '学校' })
  base.push({ key: 'status', title: '状态' })
  base.push({ key: 'ops', title: '操作', class: 'w-44' })
  return base
})

const placeholder = computed(() => ({ jobs: '搜索职位…', activities: '搜索活动…', goods: '搜索商品…' }[active.value]))
const emptyText = computed(() => ({ jobs: '暂无兼职', activities: '暂无活动', goods: '暂无商品' }[active.value]))

function subtitle(row) {
  if (active.value === 'jobs') return `¥${row.daySalary}/天 · ${row.settle || ''} · ${row.location || ''}`
  if (active.value === 'activities') return `${row.timeDesc || ''} · ${row.location || ''} · ${row.enrolled || 0} 人报名`
  return `¥${row.price} · ${row.conditionLevel || ''} · ${row.category || ''}`
}

function isOnline(row) {
  if (active.value === 'jobs') return true
  return Number(row.status) === 1
}

function statusText(row) {
  if (active.value === 'jobs') return row.status || '招聘中'
  return isOnline(row) ? '正常' : '已下架'
}

function statusClass(row) {
  if (active.value === 'jobs') return 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'
  return isOnline(row) ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]' : 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'
}

async function load(nextPage = 1) {
  loading.value = true
  try {
    const data = await http.get(`/admin/${active.value}`, {
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

function switchTab(key) {
  if (active.value === key) return
  active.value = key
  keyword.value = ''
  load(1)
}

async function toggle(row) {
  const next = isOnline(row) ? 0 : 1
  try {
    const path = active.value === 'activities' ? 'activities' : 'goods'
    await http.post(`/admin/${path}/${row.id}/status`, { status: next })
    row.status = next
  } catch (e) {
    alert(e.message)
  }
}

async function remove(row) {
  if (!confirm(`确定删除「${row.title}」？`)) return
  try {
    await http.delete(`/admin/jobs/${row.id}`)
    await load(page.value)
  } catch (e) {
    alert(e.message)
  }
}

onMounted(async () => {
  await loadSchools()
  load(1)
})

// 切回本页自动刷新
useAutoRefresh(() => load(page.value))
</script>

