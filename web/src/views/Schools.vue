<template>
  <div class="space-y-6 organic-enter">
    <div class="flex items-center justify-between gap-4">
      <p class="text-sm text-stone-600">共 {{ rows.length }} 所学校。停用后该校用户将无法继续使用。</p>
      <button class="no-btn-primary no-btn-sm" @click="openCreate">新增学校</button>
    </div>

    <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-6 md:gap-8">
      <div v-for="row in rows" :key="row.id" class="no-card-plain">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0 flex items-center gap-3">
            <!-- 学校 logo：上传过才显示，没上传不占位 -->
            <span
              v-if="row.logo"
              class="w-11 h-11 rounded-[1rem] border border-stone-200 overflow-hidden bg-[#faf6f1] shrink-0"
            >
              <img :src="row.logo" alt="" class="w-full h-full object-cover" />
            </span>
            <div class="min-w-0">
              <h3 class="font-serif text-xl text-stone-800 truncate">{{ row.name }}</h3>
              <p class="text-xs text-stone-500 mt-1">{{ row.city || '未填写城市' }}</p>
            </div>
          </div>
          <span class="no-badge shrink-0" :class="row.status === 1 ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]' : 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'">
            {{ row.status === 1 ? '启用' : '停用' }}
          </span>
        </div>

        <div class="flex gap-6 mt-5 text-sm">
          <div>
            <p class="text-stone-500 text-xs">用户</p>
            <p class="font-serif text-2xl text-stone-800">{{ row.userCount }}</p>
          </div>
          <div>
            <p class="text-stone-500 text-xs">圈子</p>
            <p class="font-serif text-2xl text-stone-800">{{ row.circleCount }}</p>
          </div>
        </div>

        <div class="flex gap-3 mt-6">
          <button class="no-btn-ghost no-btn-sm" @click="openEdit(row)">编辑</button>
          <button class="no-btn-ghost no-btn-sm" @click="toggleStatus(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </button>
        </div>
      </div>
    </div>

    <NoModal
      :open="modal.open"
      :title="modal.id ? '编辑学校' : '新增学校'"
      hint="作息用于课表默认节次时间"
      :submitting="modal.submitting"
      @close="modal.open = false"
      @confirm="save"
    >
      <div class="space-y-5">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">学校名称</label>
          <input v-model.trim="modal.form.name" class="no-input" placeholder="如 内蒙古大学" />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">城市</label>
          <input v-model.trim="modal.form.city" class="no-input" placeholder="如 呼和浩特" />
        </div>

        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">学校 Logo（选填）</label>
          <NoImageUpload v-model="modal.form.logo" />
          <p class="text-xs text-stone-500 mt-2">建议正方形，会显示在小程序首页学校名旁</p>
        </div>

        <!-- 作息：预设下拉 + 每节时间选择器（不再手写 JSON） -->
        <div class="pt-1 border-t border-stone-200">
          <label class="block mb-2 mt-4 text-sm font-medium text-stone-700">作息配置</label>
          <p class="text-xs text-stone-500 mb-3">选一套预设即可，也可逐节微调。</p>

          <select class="no-input" :value="presetKey" @change="applyPreset($event.target.value)">
            <option v-for="preset in PERIOD_PRESETS" :key="preset.key" :value="preset.key">
              {{ preset.label }}
            </option>
            <option value="custom">自定义（下面手动调）</option>
          </select>
          <p v-if="activePresetHint" class="text-xs text-stone-500 mt-2">{{ activePresetHint }}</p>

          <div class="grid grid-cols-2 sm:grid-cols-3 gap-3 mt-4">
            <div v-for="(time, index) in periodTimes" :key="index">
              <label class="block mb-1.5 text-xs text-stone-600">第{{ PERIOD_LABELS[index] }}节</label>
              <input
                type="time"
                class="no-input font-mono"
                :value="time"
                @input="setPeriodTime(index, $event.target.value)"
              />
            </div>
          </div>

          <p v-if="periodError" class="text-xs text-[#a4553f] mt-3">{{ periodError }}</p>
        </div>
      </div>
    </NoModal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import NoImageUpload from '../components/NoImageUpload.vue'
import http from '../api'
import {
  PERIOD_PRESETS,
  PERIOD_LABELS,
  PERIOD_COUNT,
  parsePeriodConfig,
  serializePeriodConfig,
  matchPreset,
  validatePeriods
} from '../utils/period'

const rows = ref([])
const modal = reactive({
  open: false,
  id: null,
  submitting: false,
  form: { name: '', city: '', logo: '', periodConfig: '', status: 1 }
})

// 作息用独立的响应式状态维护，保存时再序列化写回 form.periodConfig
const periodTimes = ref(parsePeriodConfig(''))
const presetKey = ref(matchPreset(periodTimes.value) || 'custom')
const periodError = ref('')

const activePresetHint = computed(() => {
  const hit = PERIOD_PRESETS.find(p => p.key === presetKey.value)
  return hit ? hit.hint : '手动调整后会自动切到「自定义」。'
})

function resetPeriods(raw) {
  periodTimes.value = parsePeriodConfig(raw)
  presetKey.value = matchPreset(periodTimes.value) || 'custom'
  periodError.value = ''
}

function applyPreset(key) {
  const hit = PERIOD_PRESETS.find(p => p.key === key)
  if (!hit) {
    // 选「自定义」不覆盖当前值，只是允许手动调
    presetKey.value = 'custom'
    return
  }
  periodTimes.value = hit.times.slice()
  presetKey.value = key
  periodError.value = validatePeriods(periodTimes.value)
}

function setPeriodTime(index, value) {
  const next = periodTimes.value.slice()
  next[index] = String(value || '')
  periodTimes.value = next
  // 手改后重新判定命中哪套预设，命中不了就是自定义
  presetKey.value = matchPreset(next) || 'custom'
  periodError.value = validatePeriods(next)
}

async function load() {
  try {
    rows.value = await http.get('/admin/schools')
  } catch (e) {
    rows.value = []
    alert(e.message)
  }
}

function openCreate() {
  modal.id = null
  modal.form = { name: '', city: '', logo: '', periodConfig: '', status: 1 }
  resetPeriods('')
  modal.open = true
}

function openEdit(row) {
  modal.id = row.id
  modal.form = {
    name: row.name || '',
    city: row.city || '',
    logo: row.logo || '',
    periodConfig: row.periodConfig || '',
    status: row.status ?? 1
  }
  resetPeriods(row.periodConfig || '')
  modal.open = true
}

async function save() {
  if (!modal.form.name) { alert('请填写学校名称'); return }
  const err = validatePeriods(periodTimes.value)
  if (err) {
    periodError.value = err
    alert(err)
    return
  }
  modal.submitting = true
  try {
    // 统一序列化成数组格式，与小程序课表页的存储格式一致
    const payload = { ...modal.form, periodConfig: serializePeriodConfig(periodTimes.value) }
    if (modal.id) await http.put(`/admin/schools/${modal.id}`, payload)
    else await http.post('/admin/schools', payload)
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
  const word = next === 1 ? '启用' : '停用'
  if (!confirm(`确定${word}「${row.name}」？`)) return
  try {
    await http.put(`/admin/schools/${row.id}`, { ...row, status: next })
    await load()
  } catch (e) {
    alert(e.message)
  }
}

onMounted(load)
</script>
