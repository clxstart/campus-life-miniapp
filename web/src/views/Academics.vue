<template>
  <div class="space-y-8 organic-enter">
    <!-- 学期 -->
    <section class="no-card-plain">
      <div class="flex items-center justify-between gap-4 mb-6">
        <div>
          <h2 class="font-serif text-xl md:text-2xl text-stone-800">学期</h2>
          <p class="text-xs text-stone-500 mt-1">课表与校历都挂在学期下，学生端按当前学期读取</p>
        </div>
        <button class="no-btn-primary no-btn-sm" @click="openTerm()">新增学期</button>
      </div>

      <div v-if="!terms.length" class="py-10 text-center text-stone-500 text-sm">还没有配置学期</div>
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
        </div>
      </div>
    </section>

    <!-- 校历事件 -->
    <section class="no-card-plain">
      <div class="flex items-center justify-between gap-4 mb-6">
        <div>
          <h2 class="font-serif text-xl md:text-2xl text-stone-800">校历事件</h2>
          <p class="text-xs text-stone-500 mt-1">学校统一维护，学生端只读</p>
        </div>
        <button class="no-btn-primary no-btn-sm" :disabled="!terms.length" @click="openEvent()">新增事件</button>
      </div>

      <div v-if="!events.length" class="py-10 text-center text-stone-500 text-sm">
        {{ terms.length ? '还没有校历事件' : '请先配置学期' }}
      </div>
      <div v-else class="space-y-3">
        <div v-for="e in events" :key="e.id" class="flex flex-wrap items-center gap-4 border border-stone-200 rounded-[1.5rem] px-5 py-4">
          <div class="w-24 shrink-0 text-xs text-stone-500">{{ e.startDate }}</div>
          <div class="flex-1 min-w-[10rem]">
            <p class="text-stone-800 font-medium">{{ e.title }}</p>
            <p class="text-xs text-stone-500 mt-0.5">{{ e.note || '—' }}</p>
          </div>
          <div class="flex gap-2">
            <button class="no-btn-ghost no-btn-sm" @click="openEvent(e)">编辑</button>
            <button class="no-btn-ghost no-btn-sm !text-[#a4553f]" @click="removeEvent(e)">删除</button>
          </div>
        </div>
      </div>
    </section>

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

    <!-- 事件弹窗 -->
    <NoModal :open="eventModal.open" :title="eventModal.id ? '编辑事件' : '新增事件'" :submitting="eventModal.submitting" @close="eventModal.open = false" @confirm="saveEvent">
      <div class="space-y-5">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">所属学期</label>
          <select v-model.number="eventModal.form.termId" class="no-input" :disabled="!!eventModal.id">
            <option v-for="t in terms" :key="t.id" :value="t.id">{{ t.termLabel }}</option>
          </select>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">事件名称</label>
          <input v-model.trim="eventModal.form.title" class="no-input" placeholder="如 期中考试周" />
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">开始日期</label>
            <input v-model="eventModal.form.startDate" class="no-input" type="date" />
          </div>
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">结束日期</label>
            <input v-model="eventModal.form.endDate" class="no-input" type="date" />
          </div>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">备注</label>
          <input v-model.trim="eventModal.form.note" class="no-input" placeholder="选填" />
        </div>
      </div>
    </NoModal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import http from '../api'

const terms = ref([])
const events = ref([])

const dateFields = [
  { key: 'startDate', label: '开学日期' },
  { key: 'endDate', label: '结束日期' },
  { key: 'midtermStart', label: '期中开始' },
  { key: 'midtermEnd', label: '期中结束' },
  { key: 'finalStart', label: '期末开始' }
]

const emptyTerm = () => ({ termLabel: '', startDate: '', endDate: '', midtermStart: '', midtermEnd: '', finalStart: '', active: 1 })
const termModal = reactive({ open: false, id: null, submitting: false, form: emptyTerm() })
const eventModal = reactive({ open: false, id: null, submitting: false, form: { termId: 0, title: '', startDate: '', endDate: '', note: '' } })

async function load() {
  try {
    terms.value = await http.get('/admin/terms')
    events.value = await http.get('/admin/calendar-events')
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
    if (termModal.id) await http.put(`/admin/terms/${termModal.id}`, f)
    else await http.post('/admin/terms', f)
    termModal.open = false
    await load()
  } catch (e) {
    alert(e.message)
  } finally {
    termModal.submitting = false
  }
}

function openEvent(row) {
  eventModal.id = row?.id ?? null
  eventModal.form = row
    ? { termId: row.termId, title: row.title, startDate: row.startDate, endDate: row.endDate, note: row.note || '' }
    : { termId: terms.value[0]?.id ?? 0, title: '', startDate: '', endDate: '', note: '' }
  eventModal.open = true
}

async function saveEvent() {
  const f = eventModal.form
  if (!f.termId) { alert('请选择学期'); return }
  if (!f.title) { alert('请填写事件名称'); return }
  if (!f.startDate || !f.endDate) { alert('请填写起止日期'); return }
  eventModal.submitting = true
  try {
    if (eventModal.id) await http.put(`/admin/calendar-events/${eventModal.id}`, f)
    else await http.post('/admin/calendar-events', f)
    eventModal.open = false
    await load()
  } catch (e) {
    alert(e.message)
  } finally {
    eventModal.submitting = false
  }
}

async function removeEvent(row) {
  if (!confirm(`确定删除事件「${row.title}」？`)) return
  try {
    await http.delete(`/admin/calendar-events/${row.id}`)
    await load()
  } catch (e) {
    alert(e.message)
  }
}

onMounted(load)
</script>
