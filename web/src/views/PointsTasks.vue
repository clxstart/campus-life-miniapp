<template>
  <section class="space-y-5">
    <div>
      <h2 class="text-base font-semibold text-stone-800">积分任务配置</h2>
      <p class="mt-1 text-sm text-stone-600">
        任务目录全平台一套：改动 30 秒内生效，不需要发版。关闭后用户不再获得该任务积分，<strong>已发的不回收</strong>；
        日上限填 <strong>0</strong> 表示当天不限次数。
      </p>
      <p class="mt-1 text-xs text-stone-500">
        只有平台超管能改；校管能看到这条目但没有写权限（后端 AdminTaskCatalogController 会再拦一次）。
        段位门槛不在这里改——门槛一旦放宽修改，已达成用户会被「改高后掉段」，所以本页只读展示。
      </p>
    </div>

    <p v-if="error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ error }}</p>
    <p v-if="notice" class="rounded-md border border-[#cfdcc4] bg-[#eef4e8] px-4 py-3 text-sm text-[#4d6b3c]">{{ notice }}</p>

    <div class="flex flex-wrap items-end gap-3">
      <label class="text-sm text-stone-700">
        模块
        <select v-model="filters.module" class="no-input mt-2 max-w-[180px]">
          <option value="">全部模块</option>
          <option v-for="m in modules" :key="m.value" :value="m.value">{{ m.label }}</option>
        </select>
      </label>
      <label class="text-sm text-stone-700">
        状态
        <select v-model="filters.enabled" class="no-input mt-2 max-w-[180px]">
          <option value="">全部状态</option>
          <option value="1">启用中</option>
          <option value="0">已停用</option>
        </select>
      </label>
      <button class="no-btn-ghost no-btn-sm" @click="loadTasks">刷新</button>
      <span class="text-xs text-stone-500">共 {{ filteredTasks.length }} 个任务</span>
    </div>

    <!-- 任务目录：宽表，横向溢出交给 overflow-x-auto -->
    <div class="no-card-plain !p-0 overflow-x-auto">
      <table class="w-full min-w-[960px] text-left text-sm">
        <thead>
          <tr class="bg-[#f3ece4] text-stone-600">
            <th class="px-5 py-4 font-medium">任务键</th>
            <th class="px-5 py-4 font-medium">模块</th>
            <th class="px-5 py-4 font-medium">名称</th>
            <th class="px-5 py-4 font-medium">P（积分）</th>
            <th class="px-5 py-4 font-medium">G（成长值）</th>
            <th class="px-5 py-4 font-medium">日上限</th>
            <th class="px-5 py-4 font-medium">启用</th>
            <th class="px-5 py-4 font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="8" class="px-5 py-12 text-center text-stone-500">正在加载…</td></tr>
          <tr v-else-if="!filteredTasks.length"><td colspan="8" class="px-5 py-12 text-center text-stone-500">没有符合条件的任务</td></tr>
          <template v-else>
          <tr v-for="row in filteredTasks" :key="row.taskKey" class="border-t border-stone-200">
            <td class="px-5 py-4 font-mono text-xs text-stone-600">{{ row.taskKey }}</td>
            <td class="px-5 py-4">
              <span class="no-badge bg-[#f0ece5] text-stone-600 border-stone-200">{{ moduleLabel(row.module) }}</span>
            </td>
            <td class="px-5 py-4 text-stone-800">{{ row.name || '—' }}</td>
            <td class="px-5 py-4">{{ row.pReward ?? row.p ?? 0 }}</td>
            <td class="px-5 py-4">{{ row.gReward ?? row.g ?? 0 }}</td>
            <td class="px-5 py-4">
              <template v-if="Number(row.dailyCap ?? row.daily_cap ?? 0) > 0">{{ row.dailyCap ?? row.daily_cap }} 次/日</template>
              <span v-else class="text-stone-500">不限次数</span>
            </td>
            <td class="px-5 py-4">
              <label class="inline-flex items-center gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  class="accent-[#8b9d77]"
                  :checked="Number(row.enabled) === 1"
                  @change="toggleTask(row, $event)"
                />
                <span class="text-xs" :class="Number(row.enabled) === 1 ? 'text-[#4d6b3c]' : 'text-stone-500'">
                  {{ Number(row.enabled) === 1 ? '启用中' : '已停用' }}
                </span>
              </label>
            </td>
            <td class="px-5 py-4">
              <button class="no-btn-ghost no-btn-sm" @click="openEdit(row)">编辑</button>
            </td>
          </tr>
          </template>
        </tbody>
      </table>
    </div>

    <!-- 段位配置：只读 -->
    <div>
      <h3 class="text-sm font-semibold text-stone-800">段位配置（只读）</h3>
      <p class="mt-1 text-xs text-stone-500">
        段位由成长值 G 推导，永不降级。V3.1 只开放青铜 / 白银 / 黄金三段，其余段位仅展示「未开放」。
        要调整门槛请走代码发布，不提供后台编辑。
      </p>
    </div>

    <div class="no-card-plain !p-0 overflow-x-auto">
      <table class="w-full min-w-[760px] text-left text-sm">
        <thead>
          <tr class="bg-[#f3ece4] text-stone-600">
            <th class="px-5 py-4 font-medium">段位</th>
            <th class="px-5 py-4 font-medium">G 门槛</th>
            <th class="px-5 py-4 font-medium">是否开放</th>
            <th class="px-5 py-4 font-medium">特权摘要</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="tierLoading"><td colspan="4" class="px-5 py-12 text-center text-stone-500">正在加载…</td></tr>
          <tr v-else-if="tierError"><td colspan="4" class="px-5 py-12 text-center text-[#a4553f]">{{ tierError }}</td></tr>
          <tr v-else-if="!tiers.length"><td colspan="4" class="px-5 py-12 text-center text-stone-500">没有段位配置</td></tr>
          <template v-else>
            <tr v-for="tier in tiers" :key="tier.code" class="border-t border-stone-200">
            <td class="px-5 py-4 text-stone-800">{{ tier.name || tier.code }}</td>
            <td class="px-5 py-4">{{ tier.gThreshold ?? tier.g ?? tier.threshold ?? 0 }}</td>
            <td class="px-5 py-4">
              <span
                class="no-badge"
                :class="Number(tier.enabled) === 1 ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]' : 'bg-[#f3ece4] text-stone-500 border-stone-300'"
              >{{ Number(tier.enabled) === 1 ? '已开放' : '未开放' }}</span>
            </td>
            <td class="px-5 py-4 text-stone-600">{{ privilegeSummary(tier) }}</td>
          </tr>
          </template>
        </tbody>
      </table>
    </div>

    <!-- 编辑弹窗 -->
    <NoModal
      :open="modal.open"
      title="编辑任务"
      :hint="modal.taskKey ? `任务键：${modal.taskKey}` : ''"
      :submitting="modal.submitting"
      confirm-text="保存"
      @close="closeEdit"
      @confirm="saveTask"
    >
      <div class="space-y-4">
        <div v-if="modal.error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ modal.error }}</div>
        <p class="text-sm text-stone-600">
          P 是积分（消耗品），G 是成长值（只涨不降，决定段位）。<strong>日上限填 0 表示当天不限次数。</strong>
        </p>
        <div class="grid grid-cols-2 gap-4">
          <label class="block text-sm text-stone-700">P（积分）
            <input v-model.number="modal.form.p" class="no-input mt-2" type="number" min="0" max="1000" step="1" />
          </label>
          <label class="block text-sm text-stone-700">G（成长值）
            <input v-model.number="modal.form.g" class="no-input mt-2" type="number" min="0" max="1000" step="1" />
          </label>
        </div>
        <label class="block text-sm text-stone-700">日上限（次/日，0=不限）
          <input v-model.number="modal.form.dailyCap" class="no-input mt-2" type="number" min="0" step="1" />
        </label>
        <label class="flex items-center gap-2 text-sm text-stone-700 cursor-pointer">
          <input type="checkbox" class="accent-[#8b9d77]" :checked="modal.form.enabled" @change="modal.form.enabled = $event.target.checked" />
          启用该任务
        </label>
      </div>
    </NoModal>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import http from '../api'

// 模块标题：与 task_catalog.module 一一对应（见 docs/v3.1/积分体系 4.4 种子）
const modules = [
  { value: 'points', label: '基础' },
  { value: 'forum', label: '论坛' },
  { value: 'commerce', label: '交易' },
  { value: 'jobs', label: '兼职' },
  { value: 'market', label: '二手' },
  { value: 'dorm', label: '宿舍' },
  { value: 'pet', label: '宠物' }
]

const tasks = ref([])
const tiers = ref([])
const loading = ref(false)
const tierLoading = ref(false)
const tierError = ref('')
const error = ref('')
const notice = ref('')
const filters = reactive({ module: '', enabled: '' })

const modal = reactive({ open: false, taskKey: '', submitting: false, error: '', form: { p: 0, g: 0, dailyCap: 1, enabled: true } })

const filteredTasks = computed(() => {
  return tasks.value.filter(row => {
    if (filters.module && row.module !== filters.module) return false
    if (filters.enabled !== '' && Number(row.enabled) !== Number(filters.enabled)) return false
    return true
  })
})

function moduleLabel(module) {
  return modules.find(m => m.value === module)?.label || module || '—'
}

// 段位特权只有白银与黄金两项，其余段位本期未开放
function privilegeSummary(tier) {
  if (Number(tier.enabled) !== 1) return '未开放，不产生任何特权'
  // 后端 configForAdmin() 返回 privilegeSummary 与 code；保留前端兜底，避免旧版本后端字段缺失时整列空白
  if (tier.privilegeSummary) return tier.privilegeSummary
  return { bronze: '无（基础段位）', silver: '快速应答：兼职列表首屏优先推荐 2 条', gold: '发帖免审 + 创建圈子（上限 1 个）' }[tier.code] || '—'
}

function flash(message) {
  notice.value = message
  setTimeout(() => { if (notice.value === message) notice.value = '' }, 4000)
}

// 任务目录是一次性全量拉取（数据量小，前端筛选）
async function loadTasks() {
  loading.value = true
  try {
    const data = await http.get('/admin/task-catalog')
    tasks.value = Array.isArray(data) ? data : (data?.list || [])
    error.value = ''
  } catch (e) {
    tasks.value = []
    error.value = e.message || '任务目录加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function loadTiers() {
  tierLoading.value = true
  tierError.value = ''
  try {
    const data = await http.get('/admin/tier-config')
    tiers.value = Array.isArray(data) ? data : (data?.list || [])
  } catch (e) {
    tiers.value = []
    tierError.value = `${e.message || '段位配置加载失败'}（接口未就绪时此表为空，不代表段位不存在）`
  } finally {
    tierLoading.value = false
  }
}

function openEdit(row) {
  modal.taskKey = row.taskKey
  modal.form = {
    p: Number(row.pReward ?? row.p ?? 0),
    g: Number(row.gReward ?? row.g ?? 0),
    dailyCap: Number(row.dailyCap ?? row.daily_cap ?? 0),
    enabled: Number(row.enabled) === 1
  }
  modal.error = ''
  modal.submitting = false
  modal.open = true
}

function closeEdit() {
  modal.open = false
  modal.error = ''
}

function validate() {
  const { p, g, dailyCap } = modal.form
  if (!Number.isInteger(Number(p)) || Number(p) < 0) return 'P 必须是非负整数'
  if (!Number.isInteger(Number(g)) || Number(g) < 0) return 'G 必须是非负整数'
  if (Number(p) > 1000 || Number(g) > 1000) return '单次奖励超过上限（1000），请核对是否填错'
  if (!Number.isInteger(Number(dailyCap)) || Number(dailyCap) < 0) return '日上限必须是非负整数（0=不限）'
  return ''
}

async function saveTask() {
  const invalid = validate()
  if (invalid) { modal.error = invalid; return }
  modal.submitting = true
  modal.error = ''
  try {
    await http.put(`/admin/task-catalog/${encodeURIComponent(modal.taskKey)}`, {
      p: Number(modal.form.p),
      g: Number(modal.form.g),
      dailyCap: Number(modal.form.dailyCap),
      enabled: modal.form.enabled
    })
    modal.open = false
    flash(`任务「${modal.taskKey}」已保存，30 秒内生效`)
    await loadTasks()
  } catch (e) {
    modal.error = e.message || '保存失败'
  } finally {
    modal.submitting = false
  }
}

// 行内开关：先二次确认；失败时把勾选状态回滚，避免界面与库不一致
async function toggleTask(row, event) {
  const next = event.target.checked
  const nextEnabled = next ? 1 : 0
  const tip = next
    ? `启用任务「${row.name || row.taskKey}」？启用后用户完成该任务可再次获得积分。`
    : `关闭后用户不再获得该任务积分，已发的不回收。确定关闭「${row.name || row.taskKey}」？`
  if (!confirm(tip)) {
    event.target.checked = Number(row.enabled) === 1
    return
  }
  try {
    await http.put(`/admin/task-catalog/${encodeURIComponent(row.taskKey)}`, { enabled: next })
    row.enabled = nextEnabled
    flash(`任务「${row.taskKey}」已${next ? '启用' : '停用'}，30 秒内生效`)
    error.value = ''
  } catch (e) {
    event.target.checked = Number(row.enabled) === 1
    error.value = e.message || '开关保存失败，已回滚到原状态'
  }
}

onMounted(() => {
  loadTasks()
  loadTiers()
})
</script>