<template>
  <div class="space-y-8 organic-enter">
    <!-- 学期 -->
    <section class="no-card-plain">
      <div class="flex items-center justify-between gap-4 mb-6">
        <div>
          <h2 class="font-serif text-xl md:text-2xl text-stone-800">学期</h2>
          <p class="text-xs text-stone-500 mt-1">课表挂在学期下，学生端按当前学期读取</p>
        </div>
        <div class="flex items-center gap-2">
          <!-- 多校 SaaS：超管必须先把学校选清楚，否则各校学期会混在一起 -->
          <select v-if="isSuper" v-model.number="schoolId" class="no-input !w-auto min-w-[12rem]" @change="load">
            <option :value="0" disabled>选择学校</option>
            <option v-for="s in schools" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
          <button class="no-btn-primary no-btn-sm" :disabled="!schoolId" @click="openTerm()">新增学期</button>
        </div>
      </div>

      <div v-if="isSuper && !schoolId" class="no-card-plain text-center py-12 text-stone-500 text-sm">
        请先选择要管理的学校
      </div>
      <div v-else-if="!terms.length" class="py-10 text-center text-stone-500 text-sm">该学校还没有配置学期</div>
      <div v-else class="space-y-4">
        <div v-for="t in terms" :key="t.id" class="border border-stone-200 rounded-[1.5rem] p-5 flex flex-wrap items-center gap-4">
          <div class="flex-1 min-w-[12rem]">
            <p class="font-serif text-lg text-stone-800">{{ t.termLabel }}</p>
            <p class="text-xs text-stone-500 mt-1">{{ t.startDate }} → {{ t.endDate }}</p>
          </div>
          <span class="no-badge" :class="t.active === 1 ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]' : 'bg-[#f0ece5] text-stone-600 border-stone-200'">
            {{ t.active === 1 ? '当前学期' : '历史学期' }}
          </span>
          <button class="no-btn-ghost no-btn-sm" @click="openTerm(t)">编辑</button>
          <button class="no-btn-ghost no-btn-sm !text-[#a4553f]" @click="removeTerm(t)">删除</button>
        </div>
      </div>
    </section>

    <!-- 校历事件：已随「校历」功能下线移除 -->

    <!-- 学期弹窗 -->
    <NoModal :open="termModal.open" :title="termModal.id ? '编辑学期' : '新增学期'" :submitting="termModal.submitting" @close="termModal.open = false" @confirm="saveTerm">
      <div class="space-y-5">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">学期名称</label>
          <input v-model.trim="termModal.form.termLabel" class="no-input" placeholder="如 2026-2027学年 第一学期" />
        </div>
        <div v-for="field in dateFields" :key="field.key">
          <label class="block mb-2 text-sm font-medium text-stone-700">{{ field.label }}</label>
          <input v-model="termModal.form[field.key]" class="no-input" type="date" />
        </div>
        <label class="flex items-center gap-2 text-sm text-stone-700 cursor-pointer">
          <input type="checkbox" class="accent-[#8b9d77]" :checked="termModal.form.active === 1" @change="termModal.form.active = $event.target.checked ? 1 : 0" />
          设为当前学期
        </label>
      </div>
    </NoModal>

  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import http from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const isSuper = computed(() => auth.isSuper)

const schools = ref([])
const schoolId = ref(0)
const terms = ref([])

const dateFields = [
  { key: 'startDate', label: '开学日期' },
  { key: 'endDate', label: '结束日期' },
  { key: 'midtermStart', label: '期中开始' },
  { key: 'midtermEnd', label: '期中结束' },
  { key: 'finalStart', label: '期末开始' }
]

const emptyTerm = () => ({ termLabel: '', startDate: '', endDate: '', midtermStart: '', midtermEnd: '', finalStart: '', active: 1 })
const termModal = reactive({ open: false, id: null, submitting: false, form: emptyTerm() })

// 超管需要先选学校：不选就不知道该管哪一所，后端也会把全校数据混着返回
async function loadSchools() {
  if (!isSuper.value) {
    // 校管没有学校列表权限，用自己绑定的学校
    schoolId.value = Number(auth.profile?.schoolId) || 0
    return
  }
  try {
    const list = await http.get('/admin/schools')
    schools.value = list || []
    if (schools.value.length) schoolId.value = Number(schools.value[0].id)
  } catch (e) {
    alert(e.message)
  }
}

async function load() {
  // 超管未选学校时不发请求，避免拿到跨校混合数据
  if (isSuper.value && !schoolId.value) { terms.value = []; return }
  try {
    const params = schoolId.value ? { schoolId: schoolId.value } : {}
    terms.value = await http.get('/admin/terms', { params })
  } catch (e) {
    alert(e.message)
  }
}

function openTerm(row) {
  termModal.id = row?.id ?? null
  termModal.form = row ? { ...row } : emptyTerm()
  termModal.open = true
}

async function saveTerm() {
  const f = termModal.form
  if (!f.termLabel) { alert('请填写学期名称'); return }
  for (const field of dateFields) {
    if (!f[field.key]) { alert(`请填写${field.label}`); return }
  }
  termModal.submitting = true
  try {
    // 新增学期必须归属到学校，否则学生会看不到（多校 SaaS：学期表按 school_id 隔离）
    const payload = { ...f, schoolId: schoolId.value }
    if (termModal.id) await http.put(`/admin/terms/${termModal.id}`, payload)
    else await http.post('/admin/terms', payload)
    termModal.open = false
    await load()
  } catch (e) {
    alert(e.message)
  } finally {
    termModal.submitting = false
  }
}

// 校历事件（openEvent / saveEvent / removeEvent）已随「校历」功能下线移除

// 删除学期
async function removeTerm(row) {
  if (!confirm(`确定删除学期「${row.termLabel}」？`)) return
  try {
    await http.delete(`/admin/terms/${row.id}`)
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


