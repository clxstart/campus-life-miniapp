<template>
  <section class="space-y-5">
    <div>
      <h2 class="text-base font-semibold text-stone-800">认证审核</h2>
      <p class="mt-1 text-sm text-stone-600">
        学生与实名认证只接受<strong>管理员人工核验</strong>：没有接入可信核验源，系统不会自动把任何登录用户当成已认证。
        未通过核验的用户可以浏览岗位，但不能接取有偿任务。
      </p>
      <p class="mt-1 text-xs text-stone-500">
        抽查提示：无证件影像，按<strong>学号与学院一致性</strong>抽查；同学院 / 同学号段集中提交时重点核。
        驳回必须写原因，用户在小程序「认证」页能看到。
      </p>
    </div>

    <p v-if="error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ error }}</p>
    <p v-if="notice" class="rounded-md border border-[#cfdcc4] bg-[#eef4e8] px-4 py-3 text-sm text-[#4d6b3c]">{{ notice }}</p>

    <!-- 认证规则（平台级，仅超管可见；存 app_config，30 秒内生效） -->
    <div v-if="auth.isSuper" class="no-card-plain">
      <div class="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 class="text-sm font-semibold text-stone-800">认证规则</h3>
          <p class="mt-1 text-xs text-stone-500">
            平台一套规则，改完 30 秒内生效。<strong>改严不追溯</strong>：已通过认证的用户不会因为规则变严而显示「认证未通过」。
            申请总闸在「系统状态 → 功能开关」（verification.student.enabled）。
          </p>
        </div>
        <button class="no-btn-primary no-btn-sm" :disabled="ruleSubmitting" @click="saveRules">
          {{ ruleSubmitting ? '保存中…' : '保存规则' }}
        </button>
      </div>

      <div v-if="ruleError" class="mt-4 rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ ruleError }}</div>
      <div v-if="ruleLoading" class="mt-4 text-sm text-stone-500">规则读取中…</div>

      <div v-else class="mt-4 grid gap-4 md:grid-cols-2">
        <div class="rounded-[1.25rem] border border-stone-200 p-4">
          <p class="text-sm font-medium text-stone-700">必填项</p>
          <div class="mt-3 space-y-2">
            <label v-for="item in requireItems" :key="item.key" class="flex items-center gap-2 text-sm text-stone-700 cursor-pointer">
              <input type="checkbox" class="accent-[#8b9d77]" :checked="rules[item.key] === '1'" @change="rules[item.key] = $event.target.checked ? '1' : '0'" />
              {{ item.label }}
            </label>
          </div>
          <p class="mt-2 text-xs text-stone-500">学院默认选填；打开必填后抽查才有「学号与学院是否匹配」的依据。</p>
        </div>

        <div class="rounded-[1.25rem] border border-stone-200 p-4 space-y-3">
          <label class="block text-sm font-medium text-stone-700">学号正则
            <input v-model.trim="rules.pattern" class="no-input mt-2 font-mono text-xs" maxlength="120" placeholder="^(20[0-9]{2})[0-9]{6,10}$" />
            <span class="mt-1 block text-xs text-stone-500">前端只做格式提示，最终判定以后端为准。</span>
          </label>
          <label class="block text-sm font-medium text-stone-700">审核方式
            <select v-model="rules.reviewMode" class="no-input mt-2">
              <option value="manual">manual：全部人工审核</option>
              <option value="auto">auto：正则命中直接通过</option>
            </select>
          </label>
          <label class="block text-sm font-medium text-stone-700">每人每日提交上限（1–10）
            <input v-model.number="rules.dailyLimit" class="no-input mt-2" type="number" min="1" max="10" step="1" />
          </label>
          <p v-if="rules.reviewMode === 'auto'" class="text-xs text-[#a4553f]">
            正则为空或无法编译时，系统会强制回落为人工审核——不允许被配置成「任何学号都通过」。
          </p>
        </div>
      </div>
    </div>

    <div class="flex flex-wrap items-end gap-3">
      <label class="text-sm text-stone-700">
        状态
        <select v-model="filter" class="no-input mt-2 max-w-[160px]" @change="load">
          <option value="">全部状态</option>
          <option value="none">未认证</option>
          <option value="pending">审核中</option>
          <option value="verified">已通过</option>
          <option value="rejected">已拒绝</option>
        </select>
      </label>
      <label v-if="auth.isSuper" class="text-sm text-stone-700">
        学校
        <select v-model.number="schoolId" class="no-input mt-2 max-w-[200px]" @change="load">
          <option :value="0">全部学校</option>
          <option v-for="s in schools" :key="s.id" :value="s.id">{{ s.name }}</option>
        </select>
      </label>
      <label class="text-sm text-stone-700">
        关键字
        <input v-model.trim="keyword" class="no-input mt-2 max-w-[220px]" maxlength="32" placeholder="姓名 / 学号" @keyup.enter="load" />
      </label>
      <button class="no-btn-ghost no-btn-sm" @click="load">搜索</button>
      <button class="no-btn-ghost no-btn-sm" @click="load">刷新</button>
      <span class="text-xs text-stone-500">共 {{ rows.length }} 条</span>
    </div>

    <div class="no-card-plain !p-0 overflow-x-auto">
      <table class="w-full min-w-[1240px] text-left text-sm">
        <thead>
          <tr class="bg-[#f3ece4] text-stone-600">
            <th class="px-5 py-4 font-medium">用户 ID</th>
            <th class="px-5 py-4 font-medium">学校</th>
            <th class="px-5 py-4 font-medium">学院</th>
            <th class="px-5 py-4 font-medium">姓名</th>
            <th class="px-5 py-4 font-medium">学号</th>
            <th class="px-5 py-4 font-medium">实名</th>
            <th class="px-5 py-4 font-medium">学生身份</th>
            <th class="px-5 py-4 font-medium">核验来源</th>
            <th class="px-5 py-4 font-medium">提交时间</th>
            <th class="px-5 py-4 font-medium">通过时间</th>
            <th class="px-5 py-4 font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="11" class="px-5 py-12 text-center text-stone-500">正在加载…</td></tr>
          <tr v-else-if="!rows.length"><td colspan="11" class="px-5 py-12 text-center text-stone-500">没有符合条件的记录</td></tr>
          <template v-else>
            <tr v-for="row in rows" :key="row.userId" class="border-t border-stone-200">
              <td class="px-5 py-4 font-mono text-xs">#{{ row.userId }}</td>
              <td class="px-5 py-4">{{ row.schoolName || row.school || (row.schoolId ? `#${row.schoolId}` : '—') }}</td>
              <td class="px-5 py-4">{{ row.college || '—' }}</td>
              <td class="px-5 py-4 text-stone-800">{{ row.realName || row.real_name || '—' }}</td>
              <td class="px-5 py-4 font-mono text-xs">{{ row.studentNo || row.student_no || '—' }}</td>
              <td class="px-5 py-4"><span class="no-badge" :class="badgeClass(row.realNameStatus)">{{ statusLabel(row.realNameStatus) }}</span></td>
              <td class="px-5 py-4"><span class="no-badge" :class="badgeClass(row.studentStatus)">{{ statusLabel(row.studentStatus) }}</span></td>
              <td class="px-5 py-4 text-stone-500">{{ sourceLabel(row.source) }}</td>
              <td class="px-5 py-4 text-stone-500">{{ row.submittedAt || row.submitted_at || '—' }}</td>
              <td class="px-5 py-4 text-stone-500">{{ row.verifiedAt || '—' }}</td>
              <td class="px-5 py-4">
                <div class="flex gap-2">
                  <button class="no-btn-ghost no-btn-sm" @click="openReview(row, 'verified')">通过</button>
                  <button class="no-btn-ghost no-btn-sm" @click="openReview(row, 'rejected')">驳回</button>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <NoModal
      :open="modal.open"
      :title="modal.result === 'verified' ? '通过核验' : '驳回核验'"
      :hint="modalHint"
      :submitting="modal.submitting"
      confirm-text="确认"
      @close="modal.open = false"
      @confirm="submit"
    >
      <div class="space-y-4">
        <div v-if="modal.error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ modal.error }}</div>
        <p class="text-sm text-stone-600">
          这项操作会写入操作日志（操作人、时间、前后状态、原因），不能撤销为"未操作"，只能再次核验覆盖。
          驳回时原因会被用户看到，<strong>必填</strong>。
        </p>
        <div class="grid grid-cols-2 gap-4 text-sm">
          <div><span class="text-stone-500">姓名：</span>{{ modal.realName || '—' }}</div>
          <div><span class="text-stone-500">学号：</span>{{ modal.studentNo || '—' }}</div>
          <div><span class="text-stone-500">学院：</span>{{ modal.college || '—' }}</div>
          <div><span class="text-stone-500">手机号：</span>{{ modal.phone || '—' }}</div>
          <div><span class="text-stone-500">学校：</span>{{ modal.school || '—' }}</div>
        </div>
        <label class="block text-sm text-stone-700">实名状态
          <select v-model="modal.realNameStatus" class="no-input mt-2">
            <option value="verified">通过</option>
            <option value="pending">审核中</option>
            <option value="rejected">拒绝</option>
            <option value="none">未认证</option>
          </select>
        </label>
        <label class="block text-sm text-stone-700">学生身份
          <select v-model="modal.studentStatus" class="no-input mt-2">
            <option value="verified">通过</option>
            <option value="pending">审核中</option>
            <option value="rejected">拒绝</option>
            <option value="none">未认证</option>
          </select>
        </label>
        <label class="block text-sm text-stone-700">原因 / 备注
          <input v-model.trim="modal.reason" class="no-input mt-2" maxlength="200" placeholder="驳回时必填，用户可见" />
        </label>
      </div>
    </NoModal>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import http from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()

const rows = ref([])
const schools = ref([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const filter = ref('')
const schoolId = ref(0)
const keyword = ref('')

const modal = reactive({
  open: false, userId: null, result: 'verified',
  realName: '', studentNo: '', college: '', phone: '', school: '',
  realNameStatus: 'verified', studentStatus: 'verified', reason: '',
  error: '', submitting: false
})

const modalHint = computed(() => (modal.userId ? `用户 #${modal.userId}` : ''))

// ---------- 认证规则 ----------
const ruleKeys = {
  realName: 'verification.require.real_name',
  studentNo: 'verification.require.student_no',
  college: 'verification.require.college',
  phone: 'verification.require.phone',
  pattern: 'verification.student_no_pattern',
  reviewMode: 'verification.review_mode',
  dailyLimit: 'verification.daily_limit'
}
const requireItems = [
  { key: 'realName', label: '真实姓名必填' },
  { key: 'studentNo', label: '学号必填' },
  { key: 'college', label: '学院必填' },
  { key: 'phone', label: '手机号必填' }
]
const rules = reactive({ realName: '1', studentNo: '1', college: '0', phone: '0', pattern: '', reviewMode: 'manual', dailyLimit: '3' })
const ruleLoading = ref(false)
const ruleSubmitting = ref(false)
const ruleError = ref('')

async function loadRules() {
  if (!auth.isSuper) return
  ruleLoading.value = true
  ruleError.value = ''
  try {
    const data = await http.get('/admin/app-config')
    const list = Array.isArray(data) ? data : (data?.list || [])
    for (const row of list) {
      const key = Object.keys(ruleKeys).find(k => ruleKeys[k] === row.configKey)
      if (key && row.configValue !== undefined && row.configValue !== null) rules[key] = String(row.configValue)
    }
  } catch (e) {
    ruleError.value = e.message || '认证规则读取失败'
  } finally {
    ruleLoading.value = false
  }
}

async function saveRules() {
  ruleError.value = ''
  const limit = Number(rules.dailyLimit)
  if (!Number.isInteger(limit) || limit < 1 || limit > 10) {
    ruleError.value = '每日提交上限必须是 1–10 的整数（后端会把越界值截断）'
    return
  }
  if (rules.reviewMode === 'auto') {
    if (!rules.pattern) {
      ruleError.value = '自动审核必须配置学号正则；正则为空时后端会强制回落人工审核，保存也不会生效'
      return
    }
    try {
      new RegExp(rules.pattern)
    } catch (e) {
      ruleError.value = `学号正则无法编译：${e.message}`
      return
    }
  }
  ruleSubmitting.value = true
  try {
    const payload = {}
    for (const [key, configKey] of Object.entries(ruleKeys)) payload[configKey] = String(rules[key])
    await http.put('/admin/app-config', payload)
    flash('认证规则已保存，30 秒内生效（只影响新提交，已认证不追溯）')
    await loadRules()
  } catch (e) {
    ruleError.value = e.message || '认证规则保存失败'
  } finally {
    ruleSubmitting.value = false
  }
}

// ---------- 审核列表 ----------
function statusLabel(status) {
  return { none: '未认证', pending: '审核中', verified: '已通过', rejected: '已拒绝' }[status] || status || '—'
}
function badgeClass(status) {
  if (status === 'verified') return 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'
  if (status === 'rejected') return 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'
  if (status === 'pending') return 'bg-[#f3e7cf] text-[#8a6a2f] border-[#e3d2ae]'
  return 'bg-[#f3ece4] text-stone-600 border-stone-300'
}
function sourceLabel(source) {
  return { self: '用户提交', admin: '管理员核验' }[source] || '人工核验'
}
function flash(message) {
  notice.value = message
  setTimeout(() => { if (notice.value === message) notice.value = '' }, 4000)
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

async function load() {
  loading.value = true
  try {
    const data = await http.get('/admin/verifications', {
      params: {
        status: filter.value || undefined,
        schoolId: schoolId.value || undefined,
        keyword: keyword.value || undefined,
        page: 1,
        size: 50
      }
    })
    rows.value = Array.isArray(data) ? data : (data?.list || [])
    error.value = ''
  } catch (e) {
    rows.value = []
    error.value = e.message || '认证列表加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function openReview(row, result) {
  modal.userId = row.userId
  modal.result = result
  modal.realName = row.realName || row.real_name || ''
  modal.studentNo = row.studentNo || row.student_no || ''
  modal.college = row.college || ''
  // 后端已脱敏（138****1234），前端不做二次处理，避免把原文带到页面
  modal.phone = row.phone || ''
  modal.school = row.schoolName || row.school || (row.schoolId ? `#${row.schoolId}` : '')
  modal.realNameStatus = result
  modal.studentStatus = result
  modal.reason = ''
  modal.error = ''
  modal.submitting = false
  modal.open = true
}

async function submit() {
  modal.error = ''
  // 任一维度驳回都必须写原因——用户在小程序能看到，没原因无法修改重提
  if ((modal.realNameStatus === 'rejected' || modal.studentStatus === 'rejected') && !modal.reason) {
    modal.error = '驳回必须填写原因，用户会看到它'
    return
  }
  modal.submitting = true
  try {
    await http.post(`/admin/verifications/${modal.userId}/review`, {
      realNameStatus: modal.realNameStatus,
      studentStatus: modal.studentStatus,
      reason: modal.reason
    })
    modal.open = false
    flash(`用户 #${modal.userId} 已更新为 ${statusLabel(modal.realNameStatus)} / ${statusLabel(modal.studentStatus)}`)
    error.value = ''
    await load()
  } catch (e) {
    modal.error = e.message || '操作失败'
  } finally {
    modal.submitting = false
  }
}

onMounted(async () => {
  await Promise.all([loadSchools(), loadRules()])
  load()
})
</script>