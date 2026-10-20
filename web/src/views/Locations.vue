<template>
  <div class="space-y-6 organic-enter">
    <div class="flex flex-wrap items-center justify-between gap-4">
      <div>
        <p class="text-sm text-stone-600">发帖、发兼职时可选的地点。每所学校维护自己的一套，学生按所选学校看到。</p>
        <p class="text-xs text-stone-500 mt-1">排序值越小越靠前；停用后小程序不再显示。</p>
      </div>
      <div class="flex items-center gap-3">
        <!-- 超管要选学校；校管固定本校，不需要选 -->
        <select v-if="isSuper" v-model.number="schoolId" class="no-input !w-auto min-w-[12rem]" @change="load">
          <option :value="0" disabled>选择学校</option>
          <option v-for="s in schools" :key="s.id" :value="s.id">{{ s.name }}</option>
        </select>
        <button class="no-btn-primary no-btn-sm" :disabled="!schoolId" @click="openCreate">新增地点</button>
      </div>
    </div>

    <div v-if="isSuper && !schoolId" class="no-card-plain text-center py-12 text-stone-500 text-sm">
      请先选择一所学校
    </div>

    <NoTable
      v-else
      :columns="columns"
      :rows="rows"
      :searchable="false"
      empty-text="这所学校还没有配置地点"
    >
      <template #name="{ row }">
        <span class="text-stone-800 font-medium">{{ row.name }}</span>
      </template>
      <template #status="{ row }">
        <span class="no-badge" :class="row.status === 1
          ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'
          : 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'">
          {{ row.status === 1 ? '启用' : '停用' }}
        </span>
      </template>
      <template #ops="{ row }">
        <div class="flex gap-2 whitespace-nowrap">
          <button class="no-btn-ghost no-btn-sm" @click="openEdit(row)">编辑</button>
          <button class="no-btn-ghost no-btn-sm" @click="toggleStatus(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </button>
          <button class="no-btn-ghost no-btn-sm !text-[#a4553f]" @click="remove(row)">删除</button>
        </div>
      </template>
    </NoTable>

    <NoModal
      :open="modal.open"
      :title="modal.id ? '编辑地点' : '新增地点'"
      hint="地点名称建议用校内通用叫法，如「图书馆」「东区食堂」"
      :submitting="modal.submitting"
      @close="modal.open = false"
      @confirm="save"
    >
      <div class="space-y-5">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">地点名称</label>
          <input v-model.trim="modal.form.name" class="no-input" placeholder="如 图书馆" maxlength="32" />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">排序（越小越靠前）</label>
          <input v-model.number="modal.form.sort" class="no-input" type="number" placeholder="如 10" />
        </div>
      </div>
    </NoModal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoTable from '../components/NoTable.vue'
import NoModal from '../components/NoModal.vue'
import http from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const isSuper = computed(() => auth.isSuper)

const schools = ref([])
const schoolId = ref(0)
const rows = ref([])
const modal = reactive({
  open: false,
  id: null,
  submitting: false,
  form: { name: '', sort: 0 }
})

const columns = [
  { key: 'name', title: '地点名称' },
  { key: 'sort', title: '排序', class: 'w-24' },
  { key: 'status', title: '状态', class: 'w-28' },
  { key: 'ops', title: '操作', class: 'w-52' }
]

async function loadSchools() {
  try {
    const list = await http.get('/admin/schools')
    schools.value = list || []
    // 校管没有学校列表权限（403），此时用自己绑定的学校
    if (!schools.value.length && auth.profile?.schoolId) {
      schoolId.value = Number(auth.profile.schoolId)
    } else if (schools.value.length) {
      schoolId.value = Number(schools.value[0].id)
    }
  } catch (e) {
    // 校管调 /admin/schools 会 403，用 profile 里的学校
    if (auth.profile?.schoolId) schoolId.value = Number(auth.profile.schoolId)
  }
}

async function load() {
  if (!schoolId.value) { rows.value = []; return }
  try {
    rows.value = await http.get('/admin/school-locations', { params: { schoolId: schoolId.value } })
  } catch (e) {
    rows.value = []
    alert(e.message)
  }
}

function openCreate() {
  modal.id = null
  modal.form = { name: '', sort: (rows.value.length + 1) * 10 }
  modal.open = true
}

function openEdit(row) {
  modal.id = row.id
  modal.form = { name: row.name || '', sort: row.sort ?? 0 }
  modal.open = true
}

async function save() {
  const name = (modal.form.name || '').trim()
  if (!name) { alert('请填写地点名称'); return }
  if (name.length > 32) { alert('地点名称不能超过 32 字'); return }
  modal.submitting = true
  try {
    if (modal.id) {
      await http.put(`/admin/school-locations/${modal.id}`, {
        name, sort: Number(modal.form.sort) || 0, status: 1
      })
    } else {
      await http.post('/admin/school-locations', {
        schoolId: schoolId.value, name, sort: Number(modal.form.sort) || 0
      })
    }
    modal.open = false
    await load()
  } catch (e) {
    alert(e.message)
  } finally {
    modal.submitting = false
  }
}

async function toggleStatus(row) {
  const next = row.status === 1 ? 0 : 1
  try {
    await http.put(`/admin/school-locations/${row.id}`, {
      name: row.name, sort: row.sort ?? 0, status: next
    })
    await load()
  } catch (e) {
    alert(e.message)
  }
}

async function remove(row) {
  if (!confirm(`确定删除地点「${row.name}」？已发布的帖子不受影响。`)) return
  try {
    await http.delete(`/admin/school-locations/${row.id}`)
    await load()
  } catch (e) {
    alert(e.message)
  }
}

onMounted(async () => {
  await loadSchools()
  await load()
})
</script>
