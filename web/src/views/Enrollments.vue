<template>
  <div class="space-y-6 organic-enter">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div>
        <h2 class="font-serif text-xl md:text-2xl text-stone-800">报名审核</h2>
        <p class="text-xs text-stone-500 mt-1">
          只有「填表 + 材料」模式的活动会出现在这里。一键报名的活动直接通过，无需审核。
        </p>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <select v-if="isSuper" v-model.number="schoolId" class="no-input !w-auto min-w-[10rem]" @change="load(1)">
          <option :value="0">全部学校</option>
          <option v-for="s in schools" :key="s.id" :value="s.id">{{ s.name }}</option>
        </select>
        <select v-model="status" class="no-input !w-auto min-w-[8rem]" @change="load(1)">
          <option value="pending">待审核</option>
          <option value="approved">已通过</option>
          <option value="rejected">已驳回</option>
          <option value="">全部</option>
        </select>
        <button class="no-btn-ghost no-btn-sm" @click="load(page)">刷新</button>
      </div>
    </div>

    <div v-if="loading" class="py-12 text-center text-stone-500 text-sm">加载中…</div>
    <div v-else-if="!rows.length" class="py-12 text-center text-stone-500 text-sm">
      {{ status === 'pending' ? '没有待审核的报名' : '没有符合条件的报名' }}
    </div>

    <div v-else class="space-y-4">
      <div v-for="row in rows" :key="row.id"
           class="border border-stone-200 rounded-[1.5rem] p-5 space-y-3">
        <!-- 头部：活动 + 状态 -->
        <div class="flex flex-wrap items-center gap-3">
          <div class="flex-1 min-w-[12rem]">
            <p class="font-medium text-stone-800">{{ row.activityTitle }}</p>
            <p class="text-xs text-stone-500 mt-0.5">
              报名人：{{ row.realName || row.nickname || '—' }}
              <span v-if="row.studentNo"> · 学号 {{ row.studentNo }}</span>
              <span v-if="row.phone"> · {{ row.phone }}</span>
            </p>
          </div>
          <span class="no-badge" :class="statusClass(row.status)">{{ statusName(row.status) }}</span>
        </div>

        <!-- 自我介绍 -->
        <p v-if="row.remark" class="text-sm text-stone-600">自我介绍：{{ row.remark }}</p>

        <!-- 材料 -->
        <div v-if="row.materials && row.materials.length" class="space-y-1">
          <p class="text-xs text-stone-500">上传材料（{{ row.materials.length }} 份）</p>
          <div class="flex flex-wrap gap-2">
            <a v-for="m in row.materials" :key="m.id" :href="assetUrl(m.url)" target="_blank"
               class="text-xs text-[#3476f6] hover:underline border border-stone-200 rounded-full px-3 py-1">
              {{ m.fileName || '查看材料' }}
            </a>
          </div>
        </div>
        <p v-else class="text-xs text-stone-400">没有材料</p>

        <p v-if="row.status === 'rejected' && row.auditReason" class="text-xs text-[#a4553f]">
          驳回原因：{{ row.auditReason }}
        </p>

        <!-- 操作 -->
        <div v-if="row.status === 'pending'" class="flex gap-2 pt-1">
          <button class="no-btn-primary no-btn-sm" :disabled="busy" @click="approve(row)">通过</button>
          <button class="no-btn-ghost no-btn-sm !text-[#a4553f]" :disabled="busy" @click="askReject(row)">驳回</button>
        </div>
        <p v-else class="text-xs text-stone-400">
          审核于 {{ formatDate(row.auditedAt) }}
        </p>
      </div>
    </div>

    <!-- 分页 -->
    <div v-if="total > size" class="flex items-center justify-center gap-3">
      <button class="no-btn-ghost no-btn-sm" :disabled="page <= 1" @click="load(page - 1)">上一页</button>
      <span class="text-xs text-stone-500">第 {{ page }} 页 / 共 {{ Math.ceil(total / size) }} 页（{{ total }} 条）</span>
      <button class="no-btn-ghost no-btn-sm" :disabled="page * size >= total" @click="load(page + 1)">下一页</button>
    </div>

    <!-- 驳回弹窗 -->
    <NoModal :open="reject.open" title="驳回报名" :submitting="reject.submitting"
             @close="reject.open = false" @confirm="confirmReject">
      <div class="space-y-3">
        <p class="text-sm text-stone-600">
          驳回「{{ reject.row && (reject.row.realName || reject.row.nickname) }}」的报名，请填写原因（学生能看到）。
        </p>
        <textarea v-model.trim="reject.reason" class="no-input !rounded-[1.5rem] h-24"
                  maxlength="200" placeholder="如：材料不完整，请补充伴奏音频"></textarea>
      </div>
    </NoModal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import http from '../api'
import { useAuthStore } from '../stores/auth'
import { useAutoRefresh } from '../utils/auto-refresh'

const auth = useAuthStore()
const isSuper = computed(() => auth.isSuper)

const schools = ref([])
const schoolId = ref(0)
const status = ref('pending')
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const busy = ref(false)
const reject = reactive({ open: false, row: null, reason: '', submitting: false })

function statusName(s) {
  return { pending: '待审核', approved: '已通过', rejected: '已驳回' }[s] || s
}
function statusClass(s) {
  return {
    pending: 'bg-[#fdf3e2] text-[#8a6320] border-[#eddcbb]',
    approved: 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]',
    rejected: 'bg-[#f7e6e2] text-[#a4553f] border-[#e6c9c2]'
  }[s] || 'bg-[#f0ece5] text-stone-600 border-stone-200'
}
function formatDate(v) {
  if (!v) return '—'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
// 材料地址可能是 /uploads/xxx 相对路径，补成可直接打开的地址
function assetUrl(url) {
  const v = String(url || '')
  if (!v) return ''
  if (/^https?:\/\//i.test(v)) return v
  return v
}

async function loadSchools() {
  if (!isSuper.value) return
  try { schools.value = (await http.get('/admin/schools')) || [] } catch { schools.value = [] }
}

async function load(nextPage = 1) {
  loading.value = true
  try {
    const data = await http.get('/admin/enrollments', {
      params: {
        status: status.value || undefined,
        schoolId: schoolId.value || undefined,
        page: nextPage, size: size.value
      }
    })
    rows.value = data.list || []
    total.value = Number(data.total) || 0
    page.value = Number(data.page) || nextPage
  } catch (e) {
    rows.value = []
    total.value = 0
    alert(e.message)
  } finally {
    loading.value = false
  }
}

async function approve(row) {
  if (busy.value) return
  if (!confirm(`通过「${row.realName || row.nickname}」的报名？通过后会立即计入报名人数。`)) return
  busy.value = true
  try {
    await http.post(`/admin/enrollments/${row.id}/approve`, {})
    await load(page.value)
  } catch (e) {
    alert(e.message)
  } finally {
    busy.value = false
  }
}

function askReject(row) {
  reject.row = row
  reject.reason = ''
  reject.open = true
}

async function confirmReject() {
  const reason = (reject.reason || '').trim()
  if (!reason) { alert('请填写驳回原因'); return }
  reject.submitting = true
  try {
    await http.post(`/admin/enrollments/${reject.row.id}/reject`, { reason })
    reject.open = false
    await load(page.value)
  } catch (e) {
    alert(e.message)
  } finally {
    reject.submitting = false
  }
}

onMounted(async () => {
  await loadSchools()
  load(1)
})

// 切回本页自动刷新（学生提交报名后不用手点刷新）
useAutoRefresh(() => load(page.value))
</script>
