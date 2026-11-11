<template>
  <section class="space-y-5">
    <div>
      <h2 class="text-base font-semibold text-stone-800">宿舍管理</h2>
      <p class="mt-1 text-sm text-stone-600">
        解散后成员关系解除，<strong>历史签到记录保留</strong>（只改状态，不物理删除）。
        一人只能属于一个宿舍；全员当日签到才发「宿舍全员签到」奖励。
      </p>
      <p class="mt-1 text-xs text-stone-500">
        楼栋与房间号只对同宿舍成员可见；管理端仅做核查，不对外展示。
        <template v-if="auth.isSuper">当前为超级管理员，可查看全部学校。</template>
        <template v-else>当前为学校管理员，只能看到本校宿舍（后端按 token 限制）。</template>
      </p>
    </div>

    <p v-if="error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ error }}</p>
    <p v-if="notice" class="rounded-md border border-[#cfdcc4] bg-[#eef4e8] px-4 py-3 text-sm text-[#4d6b3c]">{{ notice }}</p>

    <div class="flex flex-wrap items-end gap-3">
      <label v-if="auth.isSuper" class="text-sm text-stone-700">
        学校
        <select v-model.number="schoolId" class="no-input mt-2 max-w-[200px]" @change="load(1)">
          <option :value="0">全部学校</option>
          <option v-for="s in schools" :key="s.id" :value="s.id">{{ s.name }}</option>
        </select>
      </label>
      <label class="text-sm text-stone-700">
        状态
        <select v-model="status" class="no-input mt-2 max-w-[160px]" @change="load(1)">
          <option value="">全部状态</option>
          <option value="1">正常</option>
          <option value="0">已解散</option>
        </select>
      </label>
      <label class="text-sm text-stone-700">
        宿舍名
        <input v-model.trim="keyword" class="no-input mt-2 max-w-[220px]" maxlength="32" placeholder="如 6号楼312" @keyup.enter="load(1)" />
      </label>
      <button class="no-btn-ghost no-btn-sm" @click="load(1)">搜索</button>
      <button class="no-btn-ghost no-btn-sm" @click="load(page)">刷新</button>
      <span class="text-xs text-stone-500">共 {{ total }} 个宿舍</span>
    </div>

    <div class="no-card-plain !p-0 overflow-x-auto">
      <table class="w-full min-w-[1120px] text-left text-sm">
        <thead>
          <tr class="bg-[#f3ece4] text-stone-600">
            <th class="px-5 py-4 font-medium">宿舍名</th>
            <th class="px-5 py-4 font-medium">学校</th>
            <th class="px-5 py-4 font-medium">楼栋 / 房间</th>
            <th class="px-5 py-4 font-medium">成员数</th>
            <th class="px-5 py-4 font-medium">本周分</th>
            <th class="px-5 py-4 font-medium">状态</th>
            <th class="px-5 py-4 font-medium">创建时间</th>
            <th class="px-5 py-4 font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="8" class="px-5 py-12 text-center text-stone-500">正在加载…</td></tr>
          <tr v-else-if="!rows.length"><td colspan="8" class="px-5 py-12 text-center text-stone-500">还没有宿舍</td></tr>
          <template v-else>
            <template v-for="row in rows" :key="row.id">
              <tr class="border-t border-stone-200" :class="Number(row.status) === 1 ? '' : 'bg-stone-50 text-stone-500'">
                <td class="px-5 py-4">
                  <div class="font-medium" :class="Number(row.status) === 1 ? 'text-stone-800' : 'text-stone-500'">{{ row.name || '—' }}</div>
                  <div class="mt-1 text-xs text-stone-500">
                    #{{ row.id }}<span v-if="row.inviteCode"> · 邀请码 {{ row.inviteCode }}</span>
                  </div>
                </td>
                <td class="px-5 py-4">{{ row.schoolName || (row.schoolId ? `#${row.schoolId}` : '—') }}</td>
                <td class="px-5 py-4">
                  <div>{{ row.building || '—' }}</div>
                  <div class="text-xs text-stone-500">{{ row.room || '未填房间' }}</div>
                </td>
                <td class="px-5 py-4">{{ Number(row.memberCount ?? row.member_count ?? 0) }} / {{ Number(row.memberLimit ?? row.member_limit ?? 8) }}</td>
                <td class="px-5 py-4">{{ row.weekScore ?? row.week_score ?? row.score ?? 0 }}</td>
                <td class="px-5 py-4">
                  <span class="no-badge" :class="Number(row.status) === 1 ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]' : 'bg-[#f3ece4] text-stone-500 border-stone-300'">
                    {{ Number(row.status) === 1 ? '正常' : '已解散' }}
                  </span>
                </td>
                <td class="px-5 py-4 text-stone-500">{{ fmtTime(row.createdAt || row.created_at) }}</td>
                <td class="px-5 py-4">
                  <div class="flex flex-wrap gap-2">
                    <button class="no-btn-ghost no-btn-sm" @click="toggleMembers(row)">
                      {{ expandedId === row.id ? '收起成员' : '查看成员' }}
                    </button>
                    <button
                      class="no-btn-ghost no-btn-sm"
                      :disabled="Number(row.status) !== 1"
                      @click="openDismiss(row)"
                    >解散</button>
                  </div>
                </td>
              </tr>
              <!-- 行内展开：成员列表 -->
              <tr v-if="expandedId === row.id" class="border-t border-stone-200 bg-[#faf6f1]">
                <td colspan="8" class="px-5 py-4">
                  <div v-if="memberLoading[row.id]" class="py-4 text-center text-sm text-stone-500">成员加载中…</div>
                  <div v-else-if="memberErrors[row.id]" class="py-4 text-sm text-[#a4553f]">{{ memberErrors[row.id] }}</div>
                  <div v-else-if="!(members[row.id] || []).length" class="py-4 text-sm text-stone-500">该宿舍还没有成员记录</div>
                  <div v-else class="overflow-x-auto">
                    <table class="w-full min-w-[620px] text-left text-sm">
                      <thead>
                        <tr class="text-stone-600">
                          <th class="py-2 pr-4 font-medium">成员</th>
                          <th class="py-2 pr-4 font-medium">用户 ID</th>
                          <th class="py-2 pr-4 font-medium">身份</th>
                          <th class="py-2 pr-4 font-medium">加入时间</th>
                          <th class="py-2 pr-4 font-medium">今日签到</th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr v-for="m in members[row.id]" :key="m.userId ?? m.id" class="border-t border-stone-200">
                          <td class="py-2 pr-4">{{ m.nickname || m.realName || '—' }}</td>
                          <td class="py-2 pr-4 font-mono text-xs text-stone-600">#{{ m.userId }}</td>
                          <td class="py-2 pr-4">{{ m.role === 'leader' ? '舍长' : '成员' }}</td>
                          <td class="py-2 pr-4 text-stone-500">{{ fmtTime(m.joinedAt || m.joined_at) }}</td>
                          <td class="py-2 pr-4">
                            <span class="no-badge" :class="signedToday(m) ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]' : 'bg-[#f3ece4] text-stone-500 border-stone-300'">
                              {{ signedToday(m) ? '已签到' : '未签到' }}
                            </span>
                          </td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </td>
              </tr>
            </template>
          </template>
        </tbody>
      </table>
    </div>

    <div class="flex items-center justify-end gap-2 text-sm text-stone-600">
      <button class="no-btn-ghost no-btn-sm" :disabled="page <= 1" @click="load(page - 1)">上一页</button>
      <span>第 {{ page }} 页</span>
      <button class="no-btn-ghost no-btn-sm" :disabled="page * size >= total" @click="load(page + 1)">下一页</button>
    </div>

    <!-- 解散弹窗：原因必填 -->
    <NoModal
      :open="modal.open"
      title="解散宿舍"
      :hint="modal.row ? `宿舍：${modal.row.name}（#${modal.row.id}）` : ''"
      :submitting="modal.submitting"
      confirm-text="确认解散"
      @close="modal.open = false"
      @confirm="submitDismiss"
    >
      <div class="space-y-4">
        <div v-if="modal.error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ modal.error }}</div>
        <p class="text-sm text-stone-600">
          解散后成员会被移出，无法再在该宿舍签到；<strong>历史签到记录保留</strong>，操作写入操作日志，可在需要时恢复状态。
        </p>
        <label class="block text-sm text-stone-700">解散原因（必填，成员可见）
          <input v-model.trim="modal.reason" class="no-input mt-2" maxlength="200" placeholder="如 宿舍信息重复，请重新创建" />
        </label>
      </div>
    </NoModal>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import http from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const error = ref('')
const notice = ref('')

const schools = ref([])
const schoolId = ref(0)
const status = ref('')
const keyword = ref('')

const expandedId = ref(null)
const members = reactive({})
const memberLoading = reactive({})
const memberErrors = reactive({})

const modal = reactive({ open: false, row: null, reason: '', error: '', submitting: false })

function flash(message) {
  notice.value = message
  setTimeout(() => { if (notice.value === message) notice.value = '' }, 4000)
}
function fmtTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').replace(/\.\d+$/, '')
}
function signedToday(member) {
  return member.todaySigned === true || Number(member.todaySigned ?? member.signedToday ?? member.checkedToday ?? 0) === 1
}

async function loadSchools() {
  if (!auth.isSuper) return
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
    const data = await http.get('/admin/dorm', {
      params: {
        page: page.value,
        size: size.value,
        schoolId: schoolId.value || undefined,
        status: status.value === '' ? undefined : status.value,
        keyword: keyword.value || undefined
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
    expandedId.value = null
    error.value = ''
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = e.message || '宿舍列表加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function toggleMembers(row) {
  if (expandedId.value === row.id) {
    expandedId.value = null
    return
  }
  expandedId.value = row.id
  // 列表接口可能已带回成员，没有就按需拉一次
  if (Array.isArray(row.members)) {
    members[row.id] = row.members
    return
  }
  if (members[row.id]) return
  memberLoading[row.id] = true
  memberErrors[row.id] = ''
  try {
    const data = await http.get(`/admin/dorm/${row.id}`)
    members[row.id] = data?.members || data?.list || []
  } catch (e) {
    memberErrors[row.id] = e.message || '成员明细加载失败'
  } finally {
    memberLoading[row.id] = false
  }
}

function openDismiss(row) {
  if (Number(row.status) !== 1) return
  modal.row = row
  modal.reason = ''
  modal.error = ''
  modal.submitting = false
  modal.open = true
}

async function submitDismiss() {
  if (!modal.reason) {
    modal.error = '解散原因必填，成员会收到这条说明'
    return
  }
  modal.submitting = true
  modal.error = ''
  try {
    await http.post(`/admin/dorm/${modal.row.id}/dismiss`, { reason: modal.reason })
    modal.open = false
    flash(`宿舍「${modal.row.name}」已解散，历史签到记录保留`)
    await load(page.value)
  } catch (e) {
    modal.error = e.message || '解散失败'
  } finally {
    modal.submitting = false
  }
}

onMounted(async () => {
  await loadSchools()
  load(1)
})
</script>