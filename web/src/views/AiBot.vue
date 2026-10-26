<template>
  <div class="space-y-6 organic-enter">
    <!-- 顶部：说明 + 欢迎语配置 -->
    <div class="no-card-plain">
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div class="min-w-0">
          <h2 class="font-serif text-xl text-stone-800">AI 校园助手</h2>
          <p class="text-sm text-stone-600 mt-1">关键词匹配的陪聊机器人。改完立刻生效，不用重启。</p>
        </div>
        <button class="no-btn-ghost no-btn-sm" @click="openWelcome">改欢迎语</button>
      </div>

      <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mt-6">
        <div class="rounded-[1.25rem] bg-[#faf6f1] p-4">
          <p class="text-xs text-stone-500">总对话</p>
          <p class="font-serif text-2xl text-stone-800 mt-1">{{ stats.total }}</p>
        </div>
        <div class="rounded-[1.25rem] bg-[#e6efe0] p-4">
          <p class="text-xs text-[#4d6b3c]">命中</p>
          <p class="font-serif text-2xl text-[#4d6b3c] mt-1">{{ stats.matched }}</p>
        </div>
        <div class="rounded-[1.25rem] bg-[#f6e7e0] p-4">
          <p class="text-xs text-[#a4553f]">没接住</p>
          <p class="font-serif text-2xl text-[#a4553f] mt-1">{{ stats.unmatchedCount }}</p>
        </div>
        <div class="rounded-[1.25rem] bg-[#f0ece5] p-4">
          <p class="text-xs text-stone-600">命中率</p>
          <p class="font-serif text-2xl text-stone-800 mt-1">{{ hitRate }}%</p>
        </div>
      </div>
    </div>

    <!-- 标签页 -->
    <div class="flex flex-wrap gap-2">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="px-5 py-2.5 rounded-full text-sm border transition-colors duration-300"
        :class="active === tab.key ? 'bg-[#8b9d77] text-stone-900 border-transparent' : 'border-stone-300 text-stone-600 hover:bg-stone-200/60'"
        @click="active = tab.key"
      >{{ tab.label }}<span v-if="tab.key === 'unmatched' && stats.unmatched" class="ml-1">·{{ stats.unmatched.length }}</span></button>
    </div>

    <!-- ============ 规则管理 ============ -->
    <div v-if="active === 'rules'" class="space-y-4">
      <div class="flex flex-wrap items-center gap-3">
        <select v-model="filterCategory" class="no-input !w-auto min-w-[10rem]" @change="loadRules">
          <option value="">全部分类</option>
          <option v-for="c in categories" :key="c.key" :value="c.key">{{ c.label }}</option>
        </select>
        <input v-model.trim="filterKeyword" class="no-input !w-auto min-w-[14rem]" placeholder="搜关键词或话术…" @keyup.enter="loadRules" />
        <button class="no-btn-ghost no-btn-sm" @click="loadRules">搜索</button>
        <button class="no-btn-primary no-btn-sm ml-auto" @click="openRule()">新增规则</button>
      </div>

      <NoTable
        :columns="ruleColumns"
        :rows="rules"
        :searchable="false"
        empty-text="还没有规则，点右上角新增"
      >
        <template #category="{ row }">
          <span class="no-badge bg-[#f0ece5] text-stone-600 border-stone-200">{{ categoryLabel(row.category) }}</span>
        </template>
        <template #keywords="{ row }">
          <span class="text-xs text-stone-600 break-all">{{ row.keywords }}</span>
        </template>
        <template #reply="{ row }">
          <p class="text-xs text-stone-600 max-w-md line-clamp-2">{{ row.reply }}</p>
        </template>
        <template #priority="{ row }">
          <span class="text-xs text-stone-600">{{ row.priority }}</span>
        </template>
        <template #status="{ row }">
          <span class="no-badge" :class="row.status === 1
            ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'
            : 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'">
            {{ row.status === 1 ? '启用' : '停用' }}
          </span>
        </template>
        <template #ops="{ row }">
          <div class="flex items-center gap-2 whitespace-nowrap">
            <button class="no-btn-ghost no-btn-sm" @click="openRule(row)">编辑</button>
            <button class="no-btn-ghost no-btn-sm" @click="toggleRule(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </button>
            <button class="no-btn-ghost no-btn-sm !text-[#a4553f]" @click="removeRule(row)">删除</button>
          </div>
        </template>
      </NoTable>
    </div>

    <!-- ============ 没接住的问题 ============ -->
    <div v-else class="space-y-4">
      <div class="no-card-plain">
        <p class="text-sm text-stone-600">
          下面是用户问了但机器人没接住的话，按出现次数排序。
          <span class="text-stone-500">照着补规则，覆盖率会稳步上升。</span>
        </p>
      </div>

      <div v-if="!stats.unmatched.length" class="no-card-plain py-12 text-center text-stone-500 text-sm">
        还没有没接住的问题，说明当前规则覆盖得不错
      </div>
      <div v-else class="space-y-3">
        <div
          v-for="(item, index) in stats.unmatched"
          :key="index"
          class="no-card-plain !p-5 flex flex-wrap items-center gap-4"
        >
          <span class="no-badge bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf] shrink-0">{{ item.count }} 次</span>
          <p class="flex-1 min-w-[12rem] text-stone-800 break-all">{{ item.message }}</p>
          <button class="no-btn-ghost no-btn-sm shrink-0" @click="ruleFromUnmatched(item.message)">加为规则</button>
        </div>
      </div>

      <div v-if="stats.byCategory.length" class="no-card-plain">
        <h3 class="font-serif text-lg text-stone-800 mb-4">分类命中分布</h3>
        <div class="space-y-2">
          <div v-for="row in stats.byCategory" :key="row.category" class="flex items-center gap-3">
            <span class="text-xs text-stone-600 w-20 shrink-0">{{ categoryLabel(row.category) }}</span>
            <div class="flex-1 h-2 rounded-full bg-stone-100 overflow-hidden">
              <div class="h-full bg-[#8b9d77]" :style="{ width: barWidth(row.count) }"></div>
            </div>
            <span class="text-xs text-stone-500 w-10 text-right">{{ row.count }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 规则编辑弹窗 -->
    <NoModal
      :open="modal.open"
      :title="modal.id ? '编辑规则' : '新增规则'"
      hint="关键词用逗号分隔，命中任意一个即匹配；长关键词优先"
      :submitting="modal.submitting"
      @close="modal.open = false"
      @confirm="saveRule"
    >
      <div class="space-y-5">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">分类</label>
          <select v-model="modal.form.category" class="no-input">
            <option v-for="c in categories" :key="c.key" :value="c.key">{{ c.label }}</option>
          </select>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">关键词</label>
          <input v-model.trim="modal.form.keywords" class="no-input" maxlength="255" placeholder="如：考研,上岸,考研准备" />
          <p class="text-xs text-stone-500 mt-1.5">逗号分隔，命中任意一个即触发</p>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">回复话术</label>
          <textarea v-model.trim="modal.form.reply" class="no-input !rounded-[1.5rem] h-28" maxlength="500" placeholder="机器人要说的话"></textarea>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">快捷追问（选填）</label>
          <input v-model.trim="modal.form.quickReplies" class="no-input" maxlength="255" placeholder="如：考研怎么安排,我焦虑" />
          <p class="text-xs text-stone-500 mt-1.5">会渲染成小程序里可点的按钮</p>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">优先级</label>
          <input v-model.number="modal.form.priority" class="no-input" type="number" />
          <p class="text-xs text-stone-500 mt-1.5">数值越大越优先；关键词更长时优先于优先级</p>
        </div>
      </div>
    </NoModal>

    <!-- 欢迎语弹窗 -->
    <NoModal
      :open="welcomeModal.open"
      title="修改欢迎语"
      hint="用户第一次打开对话页看到的话"
      :submitting="welcomeModal.submitting"
      @close="welcomeModal.open = false"
      @confirm="saveWelcome"
    >
      <div class="space-y-5">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">欢迎语</label>
          <textarea v-model.trim="welcomeModal.reply" class="no-input !rounded-[1.5rem] h-28" maxlength="500"></textarea>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">快捷追问</label>
          <input v-model.trim="welcomeModal.quickReplies" class="no-input" maxlength="255" placeholder="如：今天吃什么,有什么活动" />
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

const categories = [
  { key: 'greeting', label: '问候' },
  { key: 'study', label: '学习' },
  { key: 'food', label: '吃饭' },
  { key: 'sleep', label: '作息' },
  { key: 'mood', label: '情绪' },
  { key: 'campus', label: '校园' },
  { key: 'help', label: '帮助' },
  { key: 'fallback', label: '兜底' }
]

const tabs = [
  { key: 'rules', label: '规则管理' },
  { key: 'unmatched', label: '没接住的问题' }
]

const active = ref('rules')
const rules = ref([])
const filterCategory = ref('')
const filterKeyword = ref('')

const stats = reactive({ total: 0, matched: 0, unmatchedCount: 0, unmatched: [], byCategory: [] })

const modal = reactive({
  open: false, id: null, submitting: false,
  form: { category: 'study', keywords: '', reply: '', quickReplies: '', priority: 0 }
})
const welcomeModal = reactive({ open: false, submitting: false, reply: '', quickReplies: '' })

const ruleColumns = [
  { key: 'category', title: '分类', class: 'w-24' },
  { key: 'keywords', title: '关键词', class: 'w-56' },
  { key: 'reply', title: '回复话术' },
  { key: 'priority', title: '优先级', class: 'w-20' },
  { key: 'status', title: '状态', class: 'w-24' },
  { key: 'ops', title: '操作', class: 'w-52' }
]

const hitRate = computed(() => {
  if (!stats.total) return 0
  return Math.round((stats.matched / stats.total) * 100)
})

function categoryLabel(key) {
  const hit = categories.find(c => c.key === key)
  return hit ? hit.label : key
}

function barWidth(count) {
  const max = Math.max(1, ...stats.byCategory.map(r => Number(r.count) || 0))
  return `${Math.round((Number(count) / max) * 100)}%`
}

async function loadRules() {
  try {
    rules.value = await http.get('/admin/ai-rules', {
      params: { category: filterCategory.value, keyword: filterKeyword.value }
    }) || []
  } catch (e) {
    rules.value = []
    alert(e.message)
  }
}

async function loadStats() {
  try {
    const data = await http.get('/admin/ai-stats')
    stats.total = Number(data.total) || 0
    stats.matched = Number(data.matched) || 0
    stats.unmatchedCount = Number(data.unmatchedCount) || 0
    stats.unmatched = data.unmatched || []
    stats.byCategory = data.byCategory || []
  } catch (e) {
    // 统计失败不影响规则管理
  }
}

function openRule(row) {
  if (row) {
    modal.id = row.id
    modal.form = {
      category: row.category || 'study',
      keywords: row.keywords || '',
      reply: row.reply || '',
      quickReplies: row.quickReplies || '',
      priority: row.priority ?? 0
    }
  } else {
    modal.id = null
    modal.form = { category: 'study', keywords: '', reply: '', quickReplies: '', priority: 0 }
  }
  modal.open = true
}

// 从「没接住的问题」一键建规则：把原话填进关键词
function ruleFromUnmatched(message) {
  modal.id = null
  modal.form = {
    category: 'help',
    keywords: message,
    reply: '',
    quickReplies: '',
    priority: 50
  }
  modal.open = true
}

async function saveRule() {
  const f = modal.form
  if (!f.keywords) { alert('请填写关键词'); return }
  if (!f.reply) { alert('请填写回复话术'); return }
  modal.submitting = true
  try {
    if (modal.id) await http.put(`/admin/ai-rules/${modal.id}`, { ...f, status: 1 })
    else await http.post('/admin/ai-rules', f)
    modal.open = false
    await loadRules()
  } catch (e) {
    alert(e.message)
  } finally {
    modal.submitting = false
  }
}

async function toggleRule(row) {
  const next = row.status === 1 ? 0 : 1
  try {
    await http.post(`/admin/ai-rules/${row.id}/status`, { status: next })
    row.status = next
  } catch (e) {
    alert(e.message)
  }
}

async function removeRule(row) {
  if (!confirm(`确定删除这条规则？\n关键词：${row.keywords}`)) return
  try {
    await http.delete(`/admin/ai-rules/${row.id}`)
    await loadRules()
  } catch (e) {
    alert(e.message)
  }
}

async function openWelcome() {
  try {
    const data = await http.get('/ai/welcome')
    welcomeModal.reply = data.reply || ''
    welcomeModal.quickReplies = (data.quickReplies || []).join(',')
  } catch (e) {
    welcomeModal.reply = ''
    welcomeModal.quickReplies = ''
  }
  welcomeModal.open = true
}

async function saveWelcome() {
  if (!welcomeModal.reply) { alert('请填写欢迎语'); return }
  welcomeModal.submitting = true
  try {
    await http.put('/admin/ai-welcome', {
      reply: welcomeModal.reply,
      quickReplies: welcomeModal.quickReplies
    })
    welcomeModal.open = false
  } catch (e) {
    alert(e.message)
  } finally {
    welcomeModal.submitting = false
  }
}

onMounted(async () => {
  await Promise.all([loadRules(), loadStats()])
})
</script>
