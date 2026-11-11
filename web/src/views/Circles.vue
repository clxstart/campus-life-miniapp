<template>
  <section class="space-y-5">
    <div class="flex flex-wrap items-start justify-between gap-4">
      <div>
        <h2 class="text-base font-semibold text-stone-800">圈子管理</h2>
        <p class="mt-1 text-sm text-stone-600">
          圈子按学校隔离。标记「热门」的圈子会出现在小程序圈子页轮播。
          用户建的圈要先审核：<strong>通过</strong>后才在小程序可见，<strong>驳回</strong>必须填原因（会通知创建者）。
        </p>
      </div>
      <button class="no-btn-primary no-btn-sm" @click="openCreate">新建圈子</button>
    </div>

    <p v-if="error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ error }}</p>
    <p v-if="notice" class="rounded-md border border-[#cfdcc4] bg-[#eef4e8] px-4 py-3 text-sm text-[#4d6b3c]">{{ notice }}</p>

    <div class="flex flex-wrap items-end gap-3">
      <label class="text-sm text-stone-700">
        审核状态
        <select v-model="statusFilter" class="no-input mt-2 max-w-[160px]" @change="load(1)">
          <option value="">全部状态</option>
          <option value="0">待审核</option>
          <option value="1">正常</option>
        </select>
      </label>
      <label v-if="isSuper" class="text-sm text-stone-700">
        学校
        <select v-model.number="schoolId" class="no-input mt-2 min-w-[10rem]" @change="load(1)">
          <option :value="0">全部学校</option>
          <option v-for="s in schools" :key="s.id" :value="s.id">{{ s.name }}</option>
        </select>
      </label>
      <label class="text-sm text-stone-700">
        关键字
        <input v-model.trim="keyword" class="no-input mt-2 max-w-[220px]" maxlength="32" placeholder="搜索圈子名称…" @keyup.enter="load(1)" />
      </label>
      <button class="no-btn-ghost no-btn-sm" @click="load(1)">搜索</button>
      <button class="no-btn-ghost no-btn-sm" @click="load(page)">刷新</button>
      <span class="text-xs text-stone-500">共 {{ total }} 个圈子</span>
    </div>

    <!-- 宽表：横向溢出交给 overflow-x-auto，表体撑开 min-w -->
    <div class="no-card-plain !p-0 overflow-x-auto">
      <table class="w-full min-w-[1180px] text-left text-sm">
        <thead>
          <tr class="bg-[#f3ece4] text-stone-600">
            <th class="px-5 py-4 font-medium">ID</th>
            <th class="px-5 py-4 font-medium">圈子</th>
            <th class="px-5 py-4 font-medium">分类</th>
            <th class="px-5 py-4 font-medium">创建者</th>
            <th class="px-5 py-4 font-medium">成员</th>
            <th class="px-5 py-4 font-medium">帖子</th>
            <th class="px-5 py-4 font-medium">热门</th>
            <th class="px-5 py-4 font-medium">状态</th>
            <th class="px-5 py-4 font-medium">创建时间</th>
            <th class="px-5 py-4 font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="10" class="px-5 py-12 text-center text-stone-500">正在加载…</td></tr>
          <tr v-else-if="!rows.length"><td colspan="10" class="px-5 py-12 text-center text-stone-500">还没有圈子</td></tr>
          <template v-else>
            <tr v-for="row in rows" :key="row.id" class="border-t border-stone-200 align-top">
              <td class="px-5 py-4 font-mono text-xs text-stone-500">#{{ row.id }}</td>
              <td class="px-5 py-4">
                <div class="flex items-center gap-3">
                  <span v-if="row.cover" class="w-9 h-9 rounded-[0.75rem] border border-stone-200 overflow-hidden shrink-0">
                    <img :src="row.cover" alt="" class="w-full h-full object-cover" />
                  </span>
                  <span v-else class="w-2.5 h-2.5 rounded-full shrink-0" :style="{ background: themeColor(row.theme) }"></span>
                  <span class="text-stone-800 font-medium">{{ row.name }}</span>
                </div>
                <div v-if="row.description" class="mt-1 max-w-[24rem] truncate text-xs text-stone-500">{{ row.description }}</div>
                <div v-if="row.auditReason && Number(row.status) !== 1" class="mt-1 text-xs text-[#a4553f]">审核意见：{{ row.auditReason }}</div>
              </td>
              <td class="px-5 py-4">
                <span class="no-badge bg-[#f0ece5] text-stone-600 border-stone-200">{{ row.category || '—' }}</span>
              </td>
              <td class="px-5 py-4">
                <template v-if="creatorId(row)">
                  <div>{{ row.creatorName || row.creatorNickname || '用户' }}</div>
                  <div class="text-xs text-stone-500">#{{ creatorId(row) }}</div>
                </template>
                <span v-else class="text-stone-500">管理端创建</span>
              </td>
              <td class="px-5 py-4">{{ Number(row.memberCount ?? 0) }}</td>
              <td class="px-5 py-4">{{ Number(row.postCount ?? 0) }}</td>
              <td class="px-5 py-4">
                <span v-if="row.hot === 1" class="no-badge bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]">热门</span>
                <span v-else class="text-stone-400">—</span>
              </td>
              <td class="px-5 py-4">
                <span class="no-badge" :class="statusBadge(row)">{{ statusLabel(row) }}</span>
              </td>
              <td class="px-5 py-4 text-stone-500">{{ fmtTime(row.createdAt || row.created_at) }}</td>
              <td class="px-5 py-4">
                <div class="flex flex-wrap gap-2">
                  <!-- 用户建圈待审：通过 / 驳回 -->
                  <template v-if="isPending(row)">
                    <button class="no-btn-ghost no-btn-sm" @click="approve(row)">通过</button>
                    <button class="no-btn-ghost no-btn-sm" @click="openReject(row)">驳回</button>
                  </template>
                  <!-- 正常：编辑 / 解散 -->
                  <template v-else-if="Number(row.status) === 1">
                    <button class="no-btn-ghost no-btn-sm" @click="openEdit(row)">编辑</button>
                    <button class="no-btn-ghost no-btn-sm" @click="toggleStatus(row)">解散</button>
                  </template>
                  <!-- 已解散：恢复 -->
                  <template v-else>
                    <button class="no-btn-ghost no-btn-sm" @click="toggleStatus(row)">恢复</button>
                  </template>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <div class="flex items-center justify-end gap-2 text-sm text-stone-600">
      <button class="no-btn-ghost no-btn-sm" :disabled="page <= 1" @click="load(page - 1)">上一页</button>
      <span>第 {{ page }} 页</span>
      <button class="no-btn-ghost no-btn-sm" :disabled="page * size >= total" @click="load(page + 1)">下一页</button>
    </div>

    <!-- 新建 / 编辑 -->
    <NoModal
      :open="modal.open"
      :title="modal.id ? '编辑圈子' : '新建圈子'"
      hint="标识用于小程序路由，只能用字母数字下划线短横线"
      :submitting="modal.submitting"
      @close="modal.open = false"
      @confirm="save"
    >
      <div class="space-y-5">
        <div v-if="modal.error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ modal.error }}</div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">圈子名称</label>
          <input v-model.trim="modal.form.name" class="no-input" maxlength="32" placeholder="如 王者荣耀" />
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
          <input v-model.trim="modal.form.description" class="no-input" maxlength="128" placeholder="一句话说明这个圈子" />
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

    <!-- 驳回原因 -->
    <NoModal
      :open="rejectModal.open"
      title="驳回圈子"
      :hint="rejectModal.row ? `圈子：${rejectModal.row.name}` : ''"
      :submitting="rejectModal.submitting"
      confirm-text="确认驳回"
      @close="rejectModal.open = false"
      @confirm="submitReject"
    >
      <div class="space-y-4">
        <div v-if="rejectModal.error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ rejectModal.error }}</div>
        <p class="text-sm text-stone-600">
          驳回后圈子保持不可见，创建者会收到带原因的通知，可修改后重新提交（仍占 1 个建圈名额）。
        </p>
        <label class="block text-sm text-stone-700">驳回原因（必填，≤100 字）
          <input v-model.trim="rejectModal.reason" class="no-input mt-2" maxlength="100" placeholder="如 名称含违规词，请修改后重新提交" />
        </label>
      </div>
    </NoModal>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import NoImageUpload from '../components/NoImageUpload.vue'
import http from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const isSuper = computed(() => auth.isSuper)
const schools = ref([])
const schoolId = ref(0)

const rows = ref([])
const keyword = ref('')
const statusFilter = ref('')
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const error = ref('')
const notice = ref('')

const modal = reactive({
  open: false,
  id: null,
  submitting: false,
  error: '',
  form: { name: '', slug: '', category: '兴趣', theme: 'blue', description: '', cover: '', sort: 0, hot: 0, status: 1 }
})
const rejectModal = reactive({ open: false, row: null, reason: '', error: '', submitting: false })

const THEME_COLORS = { blue: '#75a191', green: '#8b9d77', amber: '#d4a373', pink: '#c98a8a', purple: '#9b8aa6' }
function themeColor(theme) { return THEME_COLORS[theme] || '#8b9d77' }
function fmtTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').replace(/\.\d+$/, '')
}
function creatorId(row) { return row.creatorId ?? row.creator_id ?? null }
// 用户建的圈 status=0 视为待审核；管理端建的 status=0 视为已解散
function isPending(row) { return Number(row.status) === 0 && !!creatorId(row) }
function statusLabel(row) {
  if (Number(row.status) === 1) return '正常'
  return isPending(row) ? '待审核' : '已解散'
}
function statusBadge(row) {
  if (Number(row.status) === 1) return 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'
  if (isPending(row)) return 'bg-[#f3e7cf] text-[#8a6a2f] border-[#e3d2ae]'
  return 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'
}
function flash(message) {
  notice.value = message
  setTimeout(() => { if (notice.value === message) notice.value = '' }, 4000)
}

async function loadSchools() {
  if (!isSuper.value) return
  try {
    const data = await http.get('/admin/schools')
    schools.value = Array.isArray(data) ? data : (data?.list || [])
  } catch {
    schools.value = []
  }
}

async function load(nextPage = 1) {
  page.value = Math.max(1, nextPage)
  loading.value = true
  try {
    const data = await http.get('/admin/circles', {
      params: {
        keyword: keyword.value || undefined,
        schoolId: schoolId.value || undefined,
        status: statusFilter.value === '' ? undefined : statusFilter.value,
        page: page.value,
        size: size.value
      }
    })
    if (Array.isArray(data)) {
      rows.value = data
      total.value = data.length
    } else {
      rows.value = data?.list || []
      total.value = Number(data?.total) || 0
      page.value = Number(data?.page) || page.value
    }
    error.value = ''
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = e.message || '圈子列表加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function openCreate() {
  modal.id = null
  modal.error = ''
  modal.form = { name: '', slug: '', category: '兴趣', theme: 'blue', description: '', cover: '', sort: 0, hot: 0, status: 1 }
  modal.open = true
}

function openEdit(row) {
  modal.id = row.id
  modal.error = ''
  modal.form = {
    name: row.name || '', slug: row.slug || '', category: row.category || '兴趣',
    theme: row.theme || 'blue', description: row.description || '',
    cover: row.cover || '',
    sort: row.sort ?? 0, hot: row.hot ?? 0, status: row.status ?? 1
  }
  modal.open = true
}

async function save() {
  if (!modal.form.name) { modal.error = '请填写圈子名称'; return }
  modal.submitting = true
  modal.error = ''
  try {
    if (modal.id) await http.put(`/admin/circles/${modal.id}`, modal.form)
    else await http.post('/admin/circles', modal.form)
    modal.open = false
    flash(modal.id ? '圈子已更新' : '圈子已创建')
    await load(page.value)
  } catch (e) {
    modal.error = e.message || '保存失败'
  } finally {
    modal.submitting = false
  }
}

// 上/下架：走设计稿约定的 /admin/circles/{id}/status
async function setCircleStatus(row, status, reason) {
  await http.post(`/admin/circles/${row.id}/status`, { status, reason })
  await load(page.value)
}

async function approve(row) {
  if (!confirm(`通过圈子「${row.name}」？通过后小程序立即可见。`)) return
  try {
    await setCircleStatus(row, 1)
    flash(`圈子「${row.name}」已通过审核`)
    error.value = ''
  } catch (e) {
    error.value = e.message || '审核失败'
  }
}

function openReject(row) {
  rejectModal.row = row
  rejectModal.reason = ''
  rejectModal.error = ''
  rejectModal.submitting = false
  rejectModal.open = true
}

async function submitReject() {
  if (!rejectModal.reason) {
    rejectModal.error = '驳回原因必填，创建者会看到它'
    return
  }
  rejectModal.submitting = true
  rejectModal.error = ''
  try {
    await setCircleStatus(rejectModal.row, 0, rejectModal.reason)
    rejectModal.open = false
    flash(`圈子「${rejectModal.row.name}」已驳回，原因已通知创建者`)
    error.value = ''
  } catch (e) {
    rejectModal.error = e.message || '驳回失败'
  } finally {
    rejectModal.submitting = false
  }
}

// 管理端建的圈：解散 / 恢复仍走原来的编辑接口（只改展示状态）
async function toggleStatus(row) {
  const next = Number(row.status) === 1 ? 0 : 1
  const word = next === 0 ? '解散' : '恢复'
  if (!confirm(`确定${word}圈子「${row.name}」？`)) return
  try {
    await http.put(`/admin/circles/${row.id}`, { ...row, status: next })
    flash(`圈子「${row.name}」已${word}`)
    error.value = ''
    await load(page.value)
  } catch (e) {
    error.value = e.message || `${word}失败`
  }
}

onMounted(async () => {
  await loadSchools()
  load(1)
})
</script>