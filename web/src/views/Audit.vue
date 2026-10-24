<template>
  <div class="space-y-6">
    <header>
      <p class="text-sm text-[#737e88]">处理违规内容 · 维护敏感词</p>
    </header>

    <div class="grid grid-cols-2 xl:grid-cols-4 gap-3">
      <button v-for="card in statsCards" :key="card.key" class="rounded-lg border border-slate-100 bg-white p-5 text-left shadow-sm" :class="card.tab ? 'hover:border-slate-300' : 'cursor-default'" @click="card.tab && switchTab(card.tab)">
        <div class="text-2xl font-semibold tabular-nums" :class="card.color">{{ Number(stats[card.key] || 0).toLocaleString() }}</div>
        <div class="mt-1 text-sm text-[#737e88]">{{ card.label }}</div>
      </button>
    </div>

    <nav class="flex gap-6 border-b border-slate-200">
      <button v-for="item in tabs" :key="item.key" class="border-b-2 pb-3 text-sm" :class="tab === item.key ? 'border-[#3476f6] font-medium text-[#18212b]' : 'border-transparent text-[#737e88]'" @click="switchTab(item.key)">
        {{ item.label }}<span v-if="item.key === 'reviewing' && stats.reviewing" class="ml-2 rounded-full bg-orange-100 px-2 py-0.5 text-xs text-orange-700">{{ stats.reviewing }}</span>
      </button>
    </nav>

    <section v-if="tab !== 'words'" class="overflow-hidden rounded-lg border border-slate-100 bg-white shadow-sm">
      <div class="flex flex-wrap items-center gap-3 border-b border-slate-100 p-4">
        <select v-model="filters.type" class="no-input !w-auto !py-2" @change="loadAudit(1)"><option value="">全部类型</option><option v-for="item in types" :key="item.value" :value="item.value">{{ item.label }}</option></select>
        <input v-model.trim="filters.keyword" class="no-input !w-56 !py-2" placeholder="搜索内容摘要" @keyup.enter="loadAudit(1)" />
        <input v-model="filters.startDate" type="date" class="no-input !w-auto !py-2" @change="loadAudit(1)" />
        <span class="text-xs text-slate-400">至</span>
        <input v-model="filters.endDate" type="date" class="no-input !w-auto !py-2" @change="loadAudit(1)" />
        <button class="no-btn-ghost no-btn-sm" @click="resetFilters">重置</button>
        <div v-if="selected.size && tab === 'reviewing'" class="ml-auto flex items-center gap-2">
          <span class="text-xs text-slate-500">已选 {{ selected.size }} 条</span>
          <button class="no-btn-ghost no-btn-sm !text-green-700" @click="askBatchPass">批量通过</button>
          <button class="no-btn-ghost no-btn-sm !text-red-700" @click="openReject(null, true)">批量拒绝</button>
        </div>
      </div>
      <div class="overflow-x-auto">
        <table class="w-full min-w-[1050px] text-sm">
          <thead class="bg-slate-50 text-slate-600"><tr>
            <th class="w-10 px-4 py-3 text-left"><input type="checkbox" :checked="allSelected" @change="selectAll($event.target.checked)" /></th>
            <th class="px-4 py-3 text-left">类型</th><th class="px-4 py-3 text-left">内容摘要</th><th class="px-4 py-3 text-left">{{ tab === 'rejected' ? '拒绝原因' : '命中词' }}</th><th class="px-4 py-3 text-left">提交人</th><th class="px-4 py-3 text-left">学校</th><th class="px-4 py-3 text-left">{{ tab === 'rejected' ? '拒绝时间' : '提交时间' }}</th><th class="px-4 py-3 text-left">操作</th>
          </tr></thead>
          <tbody>
            <tr v-if="loading"><td colspan="8" class="px-4 py-16 text-center text-slate-500">正在加载…</td></tr>
            <tr v-else-if="!rows.length"><td colspan="8" class="px-4 py-16 text-center"><div class="text-2xl text-green-600">✓</div><p class="mt-2 text-sm text-slate-500">{{ tab === 'reviewing' ? '暂时没有待审核内容' : '暂无已拒绝内容' }}</p></td></tr>
            <tr v-for="row in rows" :key="rowKey(row)" class="border-t border-slate-100 hover:bg-slate-50/70">
              <td class="px-4 py-3"><input type="checkbox" :checked="selected.has(rowKey(row))" @change="toggleSelected(row, $event.target.checked)" /></td>
              <td class="px-4 py-3 whitespace-nowrap"><span class="rounded px-2 py-1 text-xs" :class="typeClass(row.type)">{{ row.typeName || typeName(row.type) }}</span></td>
              <td class="max-w-[240px] px-4 py-3"><button class="block max-w-full truncate text-left text-[#18212b] hover:text-[#3476f6]" @click="showDetail(row)">{{ row.snippet || row.title || row.content || '—' }}</button></td>
              <td class="max-w-[180px] px-4 py-3"><template v-if="tab === 'rejected'"><span class="line-clamp-2 text-xs text-red-700">{{ row.auditReason || '—' }}</span></template><template v-else><div v-if="row.matchedWords?.length" class="flex flex-wrap gap-1"><span v-for="word in row.matchedWords" :key="word" class="rounded bg-red-50 px-2 py-1 text-xs text-red-700">{{ word }}</span></div><span v-else class="text-xs text-slate-400">微信判定</span></template></td>
              <td class="whitespace-nowrap px-4 py-3 text-slate-700"><button v-if="row.userId" class="hover:text-[#3476f6]" @click="goUser(row)">{{ row.userName || '用户' }} <span class="text-xs text-slate-400">#{{ row.userId }}</span></button><span v-else>—</span></td>
              <td class="whitespace-nowrap px-4 py-3 text-slate-600">{{ row.schoolName || '—' }}</td>
              <td class="whitespace-nowrap px-4 py-3 text-xs text-slate-500">{{ formatDate(tab === 'rejected' ? row.auditAt : row.createdAt) }}</td>
              <td class="whitespace-nowrap px-4 py-3"><div v-if="tab === 'reviewing'" class="flex gap-2"><button class="text-xs text-green-700 hover:underline" @click="askOne('pass', row)">通过</button><button class="text-xs text-red-700 hover:underline" @click="openReject(row)">拒绝</button><button class="text-xs text-slate-600 hover:underline" @click="showDetail(row)">查看</button></div><div v-else class="flex gap-2"><button class="text-xs text-[#3476f6] hover:underline" @click="askOne('restore', row)">恢复</button><button class="text-xs text-slate-600 hover:underline" @click="showDetail(row)">查看</button></div></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="flex items-center justify-between border-t border-slate-100 px-4 py-3 text-xs text-slate-500"><span>共 {{ total }} 条</span><div class="flex items-center gap-2"><button class="no-btn-ghost no-btn-sm" :disabled="page <= 1" @click="loadAudit(page - 1)">上一页</button><span>{{ page }} / {{ pageCount(total) }}</span><button class="no-btn-ghost no-btn-sm" :disabled="page >= pageCount(total)" @click="loadAudit(page + 1)">下一页</button></div></div>
    </section>

    <section v-else class="overflow-hidden rounded-lg border border-slate-100 bg-white shadow-sm">
      <div class="flex flex-wrap items-center gap-3 border-b border-slate-100 p-4">
        <input v-model.trim="wordFilters.keyword" class="no-input !w-56 !py-2" placeholder="搜索敏感词" @keyup.enter="loadWords(1)" />
        <select v-model="wordFilters.category" class="no-input !w-auto !py-2" @change="loadWords(1)"><option value="">全部分类</option><option value="fraud">诈骗</option><option value="abuse">辱骂</option><option value="politics">涉政</option><option value="custom">自定义</option></select>
        <button class="no-btn-ghost no-btn-sm" @click="resetWordFilters">重置</button>
        <div class="ml-auto flex gap-2"><button class="no-btn-ghost no-btn-sm" @click="openImport">批量导入</button><button class="no-btn-primary no-btn-sm" @click="openWord()">新增词</button></div>
      </div>
      <div class="overflow-x-auto">
        <table class="w-full min-w-[700px] text-sm"><thead class="bg-slate-50 text-slate-600"><tr><th class="px-4 py-3 text-left">敏感词</th><th class="px-4 py-3 text-left">分类</th><th class="px-4 py-3 text-left">状态</th><th class="px-4 py-3 text-left">创建时间</th><th class="px-4 py-3 text-left">操作</th></tr></thead>
          <tbody><tr v-if="wordLoading"><td colspan="5" class="px-4 py-14 text-center text-slate-500">正在加载…</td></tr><tr v-else-if="!wordRows.length"><td colspan="5" class="px-4 py-14 text-center text-slate-500">暂无敏感词</td></tr>
            <tr v-for="word in wordRows" :key="word.id" class="border-t border-slate-100"><td class="px-4 py-3 font-medium text-[#18212b]">{{ word.word }}</td><td class="px-4 py-3"><span class="rounded px-2 py-1 text-xs" :class="categoryClass(word.category)">{{ categoryName(word.category) }}</span></td><td class="px-4 py-3"><button class="relative inline-flex h-5 w-9 items-center rounded-full" :class="Number(word.enabled) ? 'bg-green-500' : 'bg-slate-300'" :aria-label="Number(word.enabled) ? '停用' : '启用'" @click="askWordToggle(word)"><span class="h-4 w-4 rounded-full bg-white transition" :class="Number(word.enabled) ? 'translate-x-4' : 'translate-x-0.5'"></span></button></td><td class="px-4 py-3 text-xs text-slate-500">{{ formatDate(word.createdAt) }}</td><td class="px-4 py-3"><div class="flex gap-3"><button class="text-xs text-[#3476f6] hover:underline" @click="openWord(word)">编辑</button><button class="text-xs text-red-700 hover:underline" @click="askWordDelete(word)">删除</button></div></td></tr>
          </tbody>
        </table>
      </div>
      <div class="flex items-center justify-between border-t border-slate-100 px-4 py-3 text-xs text-slate-500"><span>共 {{ wordTotal }} 条</span><div class="flex items-center gap-2"><button class="no-btn-ghost no-btn-sm" :disabled="wordPage <= 1" @click="loadWords(wordPage - 1)">上一页</button><span>{{ wordPage }} / {{ pageCount(wordTotal) }}</span><button class="no-btn-ghost no-btn-sm" :disabled="wordPage >= pageCount(wordTotal)" @click="loadWords(wordPage + 1)">下一页</button></div></div>
    </section>

    <NoModal :open="confirm.open" :title="confirm.title" :hint="confirm.message" :submitting="confirm.submitting" confirm-text="确认" @close="confirm.open = false" @confirm="performConfirm"><p class="text-sm text-slate-600">{{ confirm.detail }}</p></NoModal>
    <NoModal :open="reject.open" title="拒绝内容" hint="拒绝原因会展示给提交人" :submitting="reject.submitting" confirm-text="确认拒绝" @close="reject.open = false" @confirm="submitReject"><label class="mb-2 block text-sm font-medium text-slate-700">拒绝原因</label><textarea v-model.trim="reject.reason" maxlength="200" class="no-input !rounded-lg min-h-28" placeholder="填写拒绝原因"></textarea><div class="mt-1 text-right text-xs text-slate-400">还可输入 {{ 200 - reject.reason.length }} 字</div></NoModal>
    <NoModal :open="detail.open" title="内容详情" hint="审核记录" hide-footer @close="detail.open = false"><div v-if="detail.loading" class="py-12 text-center text-slate-500">正在加载…</div><div v-else-if="detail.data" class="space-y-4 text-sm"><div v-if="detail.data.title || detail.data.name"><div class="text-xs text-slate-500">标题 / 名称</div><p class="mt-1 whitespace-pre-wrap text-[#18212b]">{{ detail.data.title || detail.data.name }}</p></div><div v-if="detail.data.content || detail.data.description"><div class="text-xs text-slate-500">正文</div><p class="mt-1 whitespace-pre-wrap break-words text-[#18212b]">{{ detail.data.content || detail.data.description }}</p></div><div v-if="detailImages.length" class="grid grid-cols-2 gap-3"><img v-for="image in detailImages" :key="image" :src="image" class="max-h-64 w-full rounded bg-slate-50 object-contain" /></div><dl class="grid grid-cols-2 gap-3 border-t border-slate-100 pt-4 text-xs"><div><dt class="text-slate-400">提交人</dt><dd class="mt-1">{{ detail.data.userName || '—' }} <span v-if="detail.data.userId">#{{ detail.data.userId }}</span></dd></div><div><dt class="text-slate-400">审核状态</dt><dd class="mt-1">{{ statusName(detail.data.auditStatus) }}</dd></div><div><dt class="text-slate-400">审核说明</dt><dd class="mt-1">{{ detail.data.auditReason || '—' }}</dd></div></dl></div></NoModal>
    <NoModal :open="wordModal.open" :title="wordModal.id ? '编辑敏感词' : '新增敏感词'" hint="添加后立即生效，不需要重启服务" :submitting="wordModal.submitting" @close="wordModal.open = false" @confirm="saveWord"><div class="space-y-4"><div><label class="mb-2 block text-sm font-medium text-slate-700">敏感词</label><input v-model.trim="wordModal.word" maxlength="64" class="no-input" placeholder="输入敏感词" /><div class="mt-1 text-right text-xs text-slate-400">{{ wordModal.word.length }} / 64</div></div><div><label class="mb-2 block text-sm font-medium text-slate-700">分类</label><select v-model="wordModal.category" class="no-input"><option value="fraud">诈骗</option><option value="abuse">辱骂</option><option value="politics">涉政</option><option value="custom">自定义</option></select></div><label v-if="wordModal.id" class="flex items-center gap-2 text-sm text-slate-700"><input v-model="wordModal.enabled" type="checkbox" />启用</label></div></NoModal>
    <NoModal :open="importModal.open" title="批量导入敏感词" hint="每行一个词，重复项会自动跳过" :submitting="importModal.submitting" confirm-text="导入" @close="importModal.open = false" @confirm="submitImport"><textarea v-model="importModal.text" class="no-input !rounded-lg min-h-40" placeholder="刷单&#10;保证金&#10;垫付返现"></textarea><div class="mt-3 flex items-center gap-3"><select v-model="importModal.category" class="no-input !w-auto !py-2"><option value="fraud">诈骗</option><option value="abuse">辱骂</option><option value="politics">涉政</option><option value="custom">自定义</option></select><label class="no-btn-ghost no-btn-sm cursor-pointer">选择 TXT<input class="hidden" type="file" accept=".txt,text/plain" @change="readImportFile" /></label></div></NoModal>
    <div v-if="notice.message" class="fixed bottom-5 right-5 z-40 max-w-md rounded px-4 py-3 text-sm shadow" :class="notice.error ? 'bg-red-50 text-red-700' : 'bg-green-50 text-green-700'">{{ notice.message }}</div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import http from '../api'
import NoModal from '../components/NoModal.vue'

const router = useRouter()
const size = 20
const tab = ref('reviewing')
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const selected = ref(new Set())
const stats = reactive({ today: 0, reviewing: 0, rejected: 0, words: 0 })
const filters = reactive({ type: '', keyword: '', startDate: '', endDate: '' })
const wordLoading = ref(false)
const wordRows = ref([])
const wordTotal = ref(0)
const wordPage = ref(1)
const wordFilters = reactive({ keyword: '', category: '' })
const notice = reactive({ message: '', error: false })
let noticeTimer

const types = [{ value: 'post', label: '发帖' }, { value: 'comment', label: '评论' }, { value: 'goods', label: '二手' }, { value: 'job', label: '兼职' }, { value: 'chat', label: '私信' }, { value: 'circle', label: '圈子' }]
const tabs = [{ key: 'reviewing', label: '待人工审核' }, { key: 'rejected', label: '已拒绝' }, { key: 'words', label: '敏感词管理' }]
const statsCards = [{ key: 'today', label: '今日审核', color: 'text-slate-800' }, { key: 'reviewing', label: '待人工审核', color: 'text-orange-600', tab: 'reviewing' }, { key: 'rejected', label: '已拒绝', color: 'text-red-600', tab: 'rejected' }, { key: 'words', label: '启用敏感词', color: 'text-slate-800', tab: 'words' }]
const allSelected = computed(() => rows.value.length > 0 && rows.value.every(row => selected.value.has(rowKey(row))))
const detail = reactive({ open: false, loading: false, data: null })
const detailImages = computed(() => {
  const data = detail.data
  if (!data) return []
  const images = [data.image, data.cover]
  if (Array.isArray(data.images)) images.push(...data.images)
  else if (typeof data.images === 'string') { try { images.push(...JSON.parse(data.images)) } catch { images.push(data.images) } }
  return [...new Set(images.filter(value => typeof value === 'string' && value))]
})
const confirm = reactive({ open: false, title: '', message: '', detail: '', action: null, submitting: false })
const reject = reactive({ open: false, submitting: false, reason: '', row: null, batch: false })
const wordModal = reactive({ open: false, submitting: false, id: null, word: '', category: 'custom', enabled: true })
const importModal = reactive({ open: false, submitting: false, text: '', category: 'fraud' })

function notify(message, error = false) { notice.message = message; notice.error = error; clearTimeout(noticeTimer); noticeTimer = setTimeout(() => { notice.message = '' }, 3200) }
function rowKey(row) { return `${row.type}:${row.id}` }
function typeName(type) { return types.find(item => item.value === type)?.label || type }
function typeClass(type) { return { post: 'bg-blue-50 text-blue-700', comment: 'bg-cyan-50 text-cyan-700', goods: 'bg-green-50 text-green-700', job: 'bg-orange-50 text-orange-700', chat: 'bg-violet-50 text-violet-700', circle: 'bg-pink-50 text-pink-700' }[type] || 'bg-slate-100 text-slate-600' }
function categoryName(value) { return { fraud: '诈骗', abuse: '辱骂', politics: '涉政', custom: '自定义' }[value] || value }
function categoryClass(value) { return { fraud: 'bg-orange-50 text-orange-700', abuse: 'bg-red-50 text-red-700', politics: 'bg-violet-50 text-violet-700', custom: 'bg-slate-100 text-slate-700' }[value] || 'bg-slate-100 text-slate-600' }
function statusName(value) { return { pending: '待审核', reviewing: '待人工审核', passed: '已通过', rejected: '已拒绝' }[value] || value || '—' }
function formatDate(value) { if (!value) return '—'; const date = new Date(value); return Number.isNaN(date.getTime()) ? String(value).replace('T', ' ').slice(0, 16) : new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date) }
function pageCount(value) { return Math.max(1, Math.ceil((Number(value) || 0) / size)) }
function switchTab(value) { tab.value = value; selected.value = new Set(); value === 'words' ? loadWords(1) : loadAudit(1) }
function resetFilters() { Object.assign(filters, { type: '', keyword: '', startDate: '', endDate: '' }); loadAudit(1) }
function resetWordFilters() { Object.assign(wordFilters, { keyword: '', category: '' }); loadWords(1) }
function toggleSelected(row, checked) { const next = new Set(selected.value); checked ? next.add(rowKey(row)) : next.delete(rowKey(row)); selected.value = next }
function selectAll(checked) { selected.value = checked ? new Set(rows.value.map(rowKey)) : new Set() }
function goUser(row) { router.push({ name: 'users', query: { keyword: row.userId } }) }

async function loadStats() { try { Object.assign(stats, await http.get('/admin/audit/stats')) } catch (error) { notify(error.message, true) } }
async function loadAudit(nextPage = 1) {
  loading.value = true
  try { const result = await http.get('/admin/audit/list', { params: { ...filters, status: tab.value, page: nextPage, size } }); rows.value = result.list || []; total.value = Number(result.total) || 0; page.value = Number(result.page) || nextPage; selected.value = new Set() }
  catch (error) { notify(error.message, true) }
  finally { loading.value = false }
}
async function loadWords(nextPage = 1) {
  wordLoading.value = true
  try { const result = await http.get('/admin/sensitive-words', { params: { ...wordFilters, page: nextPage, size } }); wordRows.value = result.list || []; wordTotal.value = Number(result.total) || 0; wordPage.value = Number(result.page) || nextPage }
  catch (error) { notify(error.message, true) }
  finally { wordLoading.value = false }
}
function openConfirm(title, message, detail, action) { Object.assign(confirm, { open: true, title, message, detail, action, submitting: false }) }
function askOne(kind, row) { openConfirm(kind === 'pass' ? '确认通过' : '恢复内容', kind === 'pass' ? '确认通过这条内容？' : '确认恢复这条内容并重新上架？', `${typeName(row.type)} · ${row.snippet || row.title || row.content || `#${row.id}`}`, { kind, rows: [row] }) }
function askBatchPass() { const targets = rows.value.filter(row => selected.value.has(rowKey(row))); if (targets.length) openConfirm('批量通过', `确认通过选中的 ${targets.length} 条内容？`, '通过后内容将恢复展示。', { kind: 'pass', rows: targets }) }
function openReject(row, batch = false) { Object.assign(reject, { open: true, submitting: false, reason: '', row, batch }) }
async function performConfirm() {
  const action = confirm.action
  if (!action) return
  confirm.submitting = true
  try {
    if (action.kind === 'word-delete') await http.delete(`/admin/sensitive-words/${action.word.id}`)
    else if (action.kind === 'word-toggle') await http.put(`/admin/sensitive-words/${action.word.id}`, { word: action.word.word, category: action.word.category, enabled: !Number(action.word.enabled) })
    else for (const row of action.rows) await http.post(`/admin/audit/${row.type}/${row.id}/${action.kind}`)
    confirm.open = false
    notify(action.kind === 'word-delete' ? '敏感词已删除' : action.kind === 'word-toggle' ? '敏感词状态已更新' : action.kind === 'pass' ? '内容已通过' : '内容已恢复')
    await refreshActive()
  } catch (error) { notify(error.message, true) }
  finally { confirm.submitting = false }
}
async function submitReject() {
  if (!reject.reason.trim()) { notify('拒绝原因必填', true); return }
  const targets = reject.batch ? rows.value.filter(row => selected.value.has(rowKey(row))) : [reject.row]
  reject.submitting = true
  try { for (const row of targets) await http.post(`/admin/audit/${row.type}/${row.id}/reject`, { reason: reject.reason.trim() }); reject.open = false; notify(`已拒绝 ${targets.length} 条内容`); await refreshActive() }
  catch (error) { notify(error.message, true) }
  finally { reject.submitting = false }
}
async function showDetail(row) { detail.data = null; detail.open = true; detail.loading = true; try { detail.data = await http.get(`/admin/audit/detail/${row.type}/${row.id}`) } catch (error) { detail.open = false; notify(error.message, true) } finally { detail.loading = false } }
async function refreshActive() { await Promise.all([loadStats(), tab.value === 'words' ? loadWords(wordPage.value) : loadAudit(page.value)]) }
function openWord(row = null) { Object.assign(wordModal, { open: true, submitting: false, id: row?.id || null, word: row?.word || '', category: row?.category || 'custom', enabled: Number(row?.enabled ?? 1) === 1 }) }
async function saveWord() {
  if (!wordModal.word.trim()) { notify('敏感词必填', true); return }
  wordModal.submitting = true
  try { if (wordModal.id) await http.put(`/admin/sensitive-words/${wordModal.id}`, { word: wordModal.word.trim(), category: wordModal.category, enabled: wordModal.enabled }); else await http.post('/admin/sensitive-words', { word: wordModal.word.trim(), category: wordModal.category }); wordModal.open = false; notify(wordModal.id ? '敏感词已更新' : '敏感词已添加'); await refreshActive() }
  catch (error) { notify(error.message, true) }
  finally { wordModal.submitting = false }
}
function askWordToggle(word) { openConfirm(Number(word.enabled) ? '停用敏感词' : '启用敏感词', `确认${Number(word.enabled) ? '停用' : '启用'}「${word.word}」？`, '词库更新后立即生效。', { kind: 'word-toggle', word }) }
function askWordDelete(word) { openConfirm('删除敏感词', `确认删除「${word.word}」？`, '删除后不会再用于本地内容拦截。', { kind: 'word-delete', word }) }
function openImport() { Object.assign(importModal, { open: true, submitting: false, text: '', category: 'fraud' }) }
async function readImportFile(event) { const file = event.target.files?.[0]; if (file) { try { importModal.text = await file.text() } catch { notify('无法读取 TXT 文件', true) } } event.target.value = '' }
async function submitImport() {
  const words = [...new Set(importModal.text.split(/\r?\n/).map(word => word.trim()).filter(Boolean))]
  if (!words.length) { notify('请输入要导入的词语', true); return }
  importModal.submitting = true
  try { const result = await http.post('/admin/sensitive-words/import', { words, category: importModal.category }); importModal.open = false; notify(`成功 ${result.inserted} 个，跳过重复 ${result.duplicate} 个`); await refreshActive() }
  catch (error) { notify(error.message, true) }
  finally { importModal.submitting = false }
}

onMounted(() => { loadStats(); loadAudit(1) })
</script>
