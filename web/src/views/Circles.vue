<template>
  <div class="space-y-6 organic-enter">
    <div class="flex items-center justify-between gap-4">
      <p class="text-sm text-stone-600">圈子按学校隔离。标记「热门」的圈子会出现在小程序圈子页轮播。</p>
      <div class="flex items-center gap-2">
        <!-- 多校 SaaS：超管可切到某一所学校；校管固定本校（后端按 token 限制） -->
        <select v-if="isSuper" v-model.number="schoolId" class="no-input !w-auto min-w-[10rem]" @change="load(1)">
          <option :value="0">全部学校</option>
          <option v-for="s in schools" :key="s.id" :value="s.id">{{ s.name }}</option>
        </select>
        <button class="no-btn-primary no-btn-sm" @click="openCreate">新建圈子</button>
      </div>
    </div>

    <NoTable
      v-model:keyword="keyword"
      :columns="columns"
      :rows="rows"
      :searchable="true"
      :total="total"
      :page="page"
      :size="size"
      placeholder="搜索圈子名称…"
      empty-text="还没有圈子"
      @search="load(1)"
      @page="load"
    >
      <template #name="{ row }">
        <div class="flex items-center gap-3">
          <!-- 封面：上传过才显示缩略图，否则用配色圆点 -->
          <span v-if="row.cover" class="w-9 h-9 rounded-[0.75rem] border border-stone-200 overflow-hidden shrink-0">
            <img :src="row.cover" alt="" class="w-full h-full object-cover" />
          </span>
          <span v-else class="w-2.5 h-2.5 rounded-full shrink-0" :style="{ background: themeColor(row.theme) }"></span>
          <span class="text-stone-800 font-medium">{{ row.name }}</span>
        </div>
      </template>
      <template #category="{ row }">
        <span class="no-badge bg-[#f0ece5] text-stone-600 border-stone-200">{{ row.category }}</span>
      </template>
      <template #hot="{ row }">
        <span v-if="row.hot === 1" class="no-badge bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]">热门</span>
        <span v-else class="text-stone-400">—</span>
      </template>
      <template #status="{ row }">
        <span class="no-badge" :class="row.status === 1 ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]' : 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'">
          {{ row.status === 1 ? '正常' : '已解散' }}
        </span>
      </template>
      <template #ops="{ row }">
        <div class="flex items-center gap-2">
          <button class="no-btn-ghost no-btn-sm" @click="openEdit(row)">编辑</button>
          <button class="no-btn-ghost no-btn-sm" @click="toggleStatus(row)">{{ row.status === 1 ? '解散' : '恢复' }}</button>
        </div>
      </template>
    </NoTable>

    <NoModal
      :open="modal.open"
      :title="modal.id ? '编辑圈子' : '新建圈子'"
      hint="标识用于小程序路由，只能用字母数字下划线短横线"
      :submitting="modal.submitting"
      @close="modal.open = false"
      @confirm="save"
    >
      <div class="space-y-5">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">圈子名称</label>
          <input v-model.trim="modal.form.name" class="no-input" placeholder="如 王者荣耀" />
        </div>
        <div v-if="!modal.id">
          <label class="block mb-2 text-sm font-medium text-stone-700">标识（选填）</label>
          <input v-model.trim="modal.form.slug" class="no-input font-mono" placeholder="如 wzry，留空自动生成" />
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">分类</label>
            <select v-model="modal.form.category" class="no-input">
              <option v-for="c in ['游戏', '学习', '运动', '兴趣', '生活']" :key="c" :value="c">{{ c }}</option>
            </select>
          </div>
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">配色</label>
            <select v-model="modal.form.theme" class="no-input">
              <option v-for="t in ['blue', 'green', 'amber', 'pink', 'purple']" :key="t" :value="t">{{ t }}</option>
            </select>
          </div>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">简介</label>
          <input v-model.trim="modal.form.description" class="no-input" placeholder="一句话说明这个圈子" />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">圈子封面（选填）</label>
          <NoImageUpload v-model="modal.form.cover" />
          <p class="text-xs text-stone-500 mt-2">会显示在小程序圈子卡片上，建议横图</p>
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">排序（越小越前）</label>
            <input v-model.number="modal.form.sort" class="no-input" type="number" />
          </div>
          <div class="flex items-end pb-3">
            <label class="flex items-center gap-2 text-sm text-stone-700 cursor-pointer">
              <input type="checkbox" class="accent-[#8b9d77]" :checked="modal.form.hot === 1" @change="modal.form.hot = $event.target.checked ? 1 : 0" />
              进热门轮播
            </label>
          </div>
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

const auth = useAuthStore()
const isSuper = computed(() => auth.isSuper)
const schools = ref([])
const schoolId = ref(0)

async function loadSchools() {
  if (!isSuper.value) return
  try { schools.value = (await http.get('/admin/schools')) || [] } catch { schools.value = [] }
}

const rows = ref([])
const keyword = ref('')
const total = ref(0)
const page = ref(1)
const size = ref(20)
const modal = reactive({
  open: false,
  id: null,
  submitting: false,
  form: { name: '', slug: '', category: '兴趣', theme: 'blue', description: '', cover: '', sort: 0, hot: 0, status: 1 }
})

const columns = [
  { key: 'id', title: 'ID', class: 'w-20' },
  { key: 'name', title: '圈子' },
  { key: 'category', title: '分类' },
  { key: 'memberCount', title: '成员' },
  { key: 'postCount', title: '帖子' },
  { key: 'hot', title: '热门' },
  { key: 'status', title: '状态' },
  { key: 'ops', title: '操作', class: 'w-40' }
]

const THEME_COLORS = { blue: '#75a191', green: '#8b9d77', amber: '#d4a373', pink: '#c98a8a', purple: '#9b8aa6' }
function themeColor(theme) { return THEME_COLORS[theme] || '#8b9d77' }

async function load(nextPage = 1) {
  try {
    const data = await http.get('/admin/circles', {
      params: { keyword: keyword.value, page: nextPage, size: size.value, schoolId: schoolId.value || undefined }
    })
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
  modal.form = { name: '', slug: '', category: '兴趣', theme: 'blue', description: '', sort: 0, hot: 0, status: 1 }
  modal.open = true
}

function openEdit(row) {
  modal.id = row.id
  modal.form = {
    name: row.name || '', slug: row.slug || '', category: row.category || '兴趣',
    theme: row.theme || 'blue', description: row.description || '',
    cover: row.cover || '',
    sort: row.sort ?? 0, hot: row.hot ?? 0, status: row.status ?? 1
  }
  modal.open = true
}

async function save() {
  if (!modal.form.name) { alert('请填写圈子名称'); return }
  modal.submitting = true
  try {
    if (modal.id) await http.put(`/admin/circles/${modal.id}`, modal.form)
    else await http.post('/admin/circles', modal.form)
    modal.open = false
    // 保持当前页，避免编辑后跳回第 1 页
    await load(page.value)
  } catch (e) {
    alert(e.message)
  } finally {
    modal.submitting = false
  }
}

async function toggleStatus(row) {
  const next = row.status === 1 ? 0 : 1
  const word = next === 0 ? '解散' : '恢复'
  if (!confirm(`确定${word}圈子「${row.name}」？`)) return
  try {
    await http.put(`/admin/circles/${row.id}`, { ...row, status: next })
    await load(page.value)
  } catch (e) {
    alert(e.message)
  }
}

onMounted(async () => {
  await loadSchools()
  load(1)
})
</script>
