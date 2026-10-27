<template>
  <div class="space-y-6 organic-enter">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div>
        <h2 class="font-serif text-xl md:text-2xl text-stone-800">内容举报</h2>
        <p class="text-xs text-stone-500 mt-1">
          用户举报不会自动下架内容，需要你判断。处理完请到对应模块下架或忽略。
        </p>
      </div>
      <div class="flex items-center gap-2">
        <select v-model="status" class="no-input !w-auto min-w-[8rem]" @change="load(1)">
          <option value="pending">待处理</option>
          <option value="handled">已处理</option>
          <option value="ignored">已忽略</option>
          <option value="">全部</option>
        </select>
        <select v-model="targetType" class="no-input !w-auto min-w-[8rem]" @change="load(1)">
          <option value="">全部类型</option>
          <option value="post">帖子</option>
          <option value="comment">评论</option>
          <option value="goods">二手</option>
          <option value="message">消息</option>
          <option value="user">用户</option>
        </select>
        <button class="no-btn-ghost no-btn-sm" :disabled="loading" @click="load(page)">刷新</button>
      </div>
    </div>

    <div v-if="loading" class="py-12 text-center text-stone-500 text-sm">加载中…</div>
    <div v-else-if="!rows.length" class="py-12 text-center text-stone-500 text-sm">
      {{ status === 'pending' ? '没有待处理的举报' : '没有符合条件的记录' }}
    </div>

    <div v-else class="space-y-3">
      <div v-for="row in rows" :key="row.id"
           class="border border-stone-200 rounded-[1.5rem] p-5 space-y-2">
        <div class="flex flex-wrap items-center gap-2">
          <span class="no-badge bg-[#f0ece5] text-stone-600 border-stone-200">{{ typeLabel(row.targetType) }}</span>
          <span class="no-badge" :class="reasonClass(row.reason)">{{ row.reason }}</span>
          <span class="no-badge" :class="statusClass(row.status)">{{ statusLabel(row.status) }}</span>
          <span class="text-xs text-stone-400 ml-auto">{{ formatDate(row.createdAt) }}</span>
        </div>

        <p class="text-sm text-stone-800">
          被举报内容：{{ row.targetTitle || ('#' + row.targetId + '（原内容可能已删除）') }}
        </p>
        <p v-if="row.detail" class="text-xs text-stone-600">补充说明：{{ row.detail }}</p>
        <p class="text-xs text-stone-500">举报人：{{ row.reporterName || ('用户#' + row.reporterId || '—') }}</p>

        <div v-if="row.status === 'pending'" class="flex gap-2 pt-1">
          <button class="no-btn-primary no-btn-sm" :disabled="busy === row.id" @click="handle(row, 'handled')">
            标记已处理
          </button>
          <button class="no-btn-ghost no-btn-sm" :disabled="busy === row.id" @click="handle(row, 'ignored')">
            判定不成立
          </button>
          <button v-if="row.targetType === 'post'" class="no-btn-ghost no-btn-sm" @click="goPosts(row)">
            去内容管理
          </button>
        </div>
        <p v-else class="text-xs text-stone-400">
          {{ row.handledBy ? row.handledBy + ' 处理于 ' : '处理于 ' }}{{ formatDate(row.handledAt) }}
        </p>
      </div>
    </div>

    <div v-if="total > size" class="flex items-center justify-center gap-3">
      <button class="no-btn-ghost no-btn-sm" :disabled="page <= 1" @click="load(page - 1)">上一页</button>
      <span class="text-xs text-stone-500">第 {{ page }} 页 / 共 {{ Math.ceil(total / size) }} 页（{{ total }} 条）</span>
      <button class="no-btn-ghost no-btn-sm" :disabled="page * size >= total" @click="load(page + 1)">下一页</button>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import http from '../api'
import { useAutoRefresh } from '../utils/auto-refresh'

const router = useRouter()
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref('pending')
const targetType = ref('')
const loading = ref(false)
const busy = ref(null)

const TYPE_LABELS = { post: '帖子', comment: '评论', goods: '二手', message: '消息', user: '用户' }
function typeLabel(t) { return TYPE_LABELS[t] || t }
function statusLabel(s) { return { pending: '待处理', handled: '已处理', ignored: '已忽略' }[s] || s }
function statusClass(s) {
  return {
    pending: 'bg-[#fdf3e2] text-[#8a6320] border-[#eddcbb]',
    handled: 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]',
    ignored: 'bg-[#f0ece5] text-stone-600 border-stone-200'
  }[s] || 'bg-[#f0ece5] text-stone-600 border-stone-200'
}
function reasonClass(r) {
  return (r === '诈骗' || r === '色情')
    ? 'bg-[#f7e6e2] text-[#a4553f] border-[#e6c9c2]'
    : 'bg-[#f0ece5] text-stone-600 border-stone-200'
}
function formatDate(v) {
  if (!v) return '—'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

async function load(nextPage = 1) {
  loading.value = true
  try {
    const data = await http.get('/admin/reports', {
      params: { status: status.value || undefined, targetType: targetType.value || undefined, page: nextPage, size: size.value }
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

async function handle(row, action) {
  const label = action === 'handled' ? '标记为已处理' : '判定为不成立'
  if (!confirm(`确定${label}？`)) return
  busy.value = row.id
  try {
    await http.post(`/admin/reports/${row.id}/handle`, { action })
    await load(page.value)
  } catch (e) {
    alert(e.message)
  } finally {
    busy.value = null
  }
}

function goPosts(row) {
  router.push('/posts')
}

onMounted(() => load(1))
useAutoRefresh(() => load(page.value))
</script>
