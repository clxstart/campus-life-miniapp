<template>
  <section class="space-y-5">
    <div>
      <h2 class="text-base font-semibold text-stone-800">论坛榜单</h2>
      <p class="mt-1 text-sm text-stone-600">
        快照一旦生成不会变；重结算<strong>幂等</strong>，已发放的奖励不会重复发。日榜取前 10（+10 积分 / +15 成长值），周榜取前 20（+15 积分 / +25 成长值）。
      </p>
      <p class="mt-1 text-xs text-stone-500">
        结算时间：日榜次日 00:10、周榜周一 00:10（Asia/Shanghai）。定时任务可能因重启错过，本页的「重新结算」就是补跑入口。
      </p>
    </div>

    <p v-if="error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ error }}</p>
    <p v-if="notice" class="rounded-md border border-[#cfdcc4] bg-[#eef4e8] px-4 py-3 text-sm text-[#4d6b3c]">{{ notice }}</p>

    <div class="flex flex-wrap items-end gap-3">
      <label class="text-sm text-stone-700">
        榜单类型
        <select v-model="boardType" class="no-input mt-2 max-w-[180px]" @change="onTypeChange">
          <option value="daily">日榜</option>
          <option value="weekly">周榜</option>
        </select>
      </label>
      <label class="text-sm text-stone-700">
        周期
        <input
          v-model="period"
          class="no-input mt-2 max-w-[200px]"
          :type="boardType === 'daily' ? 'date' : 'week'"
          @change="load"
        />
      </label>
      <button class="no-btn-ghost no-btn-sm" @click="load">刷新</button>
      <button class="no-btn-primary no-btn-sm" @click="openResettle(false)">重新结算本周期</button>
      <span class="text-xs text-stone-500">{{ periodLabel }}</span>
    </div>

    <!-- 没有快照时的引导：不算错误，而是还没结算 -->
    <div v-if="!loading && snapshotMissing" class="no-card-plain">
      <p class="text-sm text-stone-700">{{ boardLabel }} {{ period }} 该周期还没有快照。</p>
      <p class="mt-1 text-xs text-stone-500">可以立即结算生成快照；结算会按当时的互动数据计算排名并给未发放的人发奖。</p>
      <button class="no-btn-primary no-btn-sm mt-4" @click="openResettle(true)">立即结算</button>
    </div>

    <div v-if="settledAt && !snapshotMissing" class="text-xs text-stone-500">
      本榜结算时间：{{ fmtTime(settledAt) }} · 共 {{ rows.length }} 人上榜
    </div>

    <div v-if="!snapshotMissing" class="no-card-plain !p-0 overflow-x-auto">
      <table class="w-full min-w-[960px] text-left text-sm">
        <thead>
          <tr class="bg-[#f3ece4] text-stone-600">
            <th class="px-5 py-4 font-medium">名次</th>
            <th class="px-5 py-4 font-medium">用户 ID</th>
            <th class="px-5 py-4 font-medium">昵称</th>
            <th class="px-5 py-4 font-medium">得分</th>
            <th class="px-5 py-4 font-medium">奖励</th>
            <th class="px-5 py-4 font-medium">发放状态</th>
            <th class="px-5 py-4 font-medium">结算时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="7" class="px-5 py-12 text-center text-stone-500">正在加载…</td></tr>
          <tr v-else-if="!rows.length"><td colspan="7" class="px-5 py-12 text-center text-stone-500">该周期没有上榜记录</td></tr>
          <template v-else>
            <tr v-for="(row, index) in rows" :key="row.userId ?? index" class="border-t border-stone-200">
              <td class="px-5 py-4">
                <span class="no-badge" :class="rankBadge(row, index)">第 {{ row.rankNo ?? index + 1 }} 名</span>
              </td>
              <td class="px-5 py-4 font-mono text-xs text-stone-600">#{{ row.userId }}</td>
              <td class="px-5 py-4 text-stone-800">{{ row.nickname || '—' }}</td>
              <td class="px-5 py-4 font-medium">{{ Number(row.score ?? 0) }}</td>
              <td class="px-5 py-4 text-stone-600">{{ rewardText }}</td>
              <td class="px-5 py-4">
                <span class="no-badge" :class="awardBadge(row)">{{ awardedLabel(row) }}</span>
              </td>
              <td class="px-5 py-4 text-stone-500">{{ fmtTime(row.createdAt || row.settledAt || settledAt) }}</td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <!-- 重结算确认 -->
    <NoModal
      :open="modal.open"
      :title="snapshotMissing || modal.fromEmpty ? '立即结算' : '重新结算本周期'"
      :hint="`${boardLabel} ${period}`"
      :submitting="modal.submitting"
      confirm-text="开始结算"
      @close="modal.open = false"
      @confirm="submitResettle"
    >
      <div class="space-y-4">
        <div v-if="modal.error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ modal.error }}</div>
        <p class="text-sm text-stone-600">
          将重算 {{ period }} 的排名并覆盖快照分数；<strong>已发放奖励不会重复发放</strong>，只补发仍未发放的行。
        </p>
        <p class="text-xs text-stone-500">
          算分口径：发帖 ×3 + 评论（≥5 字）×2 + 被赞 ×1；并列按用户 ID 升序，结果稳定可复现。
        </p>
      </div>
    </NoModal>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import http from '../api'

const boardType = ref('daily')
const period = ref(todayKey())
const rows = ref([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const settledAt = ref('')
const snapshotMissing = ref(false)

const modal = reactive({ open: false, submitting: false, error: '', fromEmpty: false })

const boardLabel = computed(() => (boardType.value === 'daily' ? '日榜' : '周榜'))
const periodLabel = computed(() => `${boardLabel.value} · ${period.value || '未选择周期'}`)
const rewardText = computed(() => (boardType.value === 'daily' ? '+10 积分 / +15 成长值' : '+15 积分 / +25 成长值'))

function pad(n) { return String(n).padStart(2, '0') }
function todayKey() {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
// ISO 周：跨年边界按 ISO-8601（周一为一周第一天）
function currentWeekKey() {
  const d = new Date()
  const target = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()))
  const dayNum = target.getUTCDay() || 7
  target.setUTCDate(target.getUTCDate() + 4 - dayNum)
  const yearStart = new Date(Date.UTC(target.getUTCFullYear(), 0, 1))
  const week = Math.ceil(((target - yearStart) / 86400000 + 1) / 7)
  return `${target.getUTCFullYear()}-W${pad(week)}`
}

function fmtTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').replace(/\.\d+$/, '')
}

function rankBadge(row, index) {
  const rank = Number(row.rankNo ?? index + 1)
  if (rank === 1) return 'bg-[#f3e7cf] text-[#8a6a2f] border-[#e3d2ae]'
  if (rank <= 3) return 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'
  return 'bg-[#f3ece4] text-stone-600 border-stone-300'
}
function isAwarded(row) {
  if (row.awarded === true) return true
  const value = Number(row.awarded ?? 0)
  return value === 1 || value > 1
}
function awardedLabel(row) {
  return isAwarded(row) ? '已发放' : '未发放'
}
function awardBadge(row) {
  return isAwarded(row) ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]' : 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'
}

function flash(message) {
  notice.value = message
  setTimeout(() => { if (notice.value === message) notice.value = '' }, 4000)
}

function onTypeChange() {
  period.value = boardType.value === 'daily' ? todayKey() : currentWeekKey()
  load()
}

async function load() {
  if (!period.value) return
  loading.value = true
  snapshotMissing.value = false
  settledAt.value = ''
  error.value = ''
  try {
    const data = await http.get('/admin/forum-rank', { params: { type: boardType.value, period: period.value } })
    if (Array.isArray(data)) {
      rows.value = data
      // 数组响应没有 settled 标记：空数组按「还没有快照」处理，给出立即结算入口
      if (!rows.value.length) snapshotMissing.value = true
    } else {
      rows.value = data?.rows || data?.list || []
      settledAt.value = data?.settledAt || ''
      if (data?.settled === false || (!rows.value.length && data?.settled !== true)) {
        snapshotMissing.value = true
      }
    }
  } catch (e) {
    rows.value = []
    snapshotMissing.value = true
    error.value = `${e.message || '榜单加载失败'}——该周期可能还没有快照，可以直接结算生成`
  } finally {
    loading.value = false
  }
}

function openResettle(fromEmpty) {
  modal.fromEmpty = !!fromEmpty
  modal.error = ''
  modal.submitting = false
  modal.open = true
}

async function submitResettle() {
  modal.submitting = true
  modal.error = ''
  try {
    const data = await http.post('/admin/forum-rank/resettle', { type: boardType.value, periodKey: period.value })
    const count = data?.awarded ?? data?.count ?? data?.issued
    modal.open = false
    flash(count !== undefined ? `重算完成，发放 ${count} 人` : '重算完成，已补发未发放的奖励')
    await load()
  } catch (e) {
    modal.error = e.message || '结算失败，请确认周期格式与后端任务状态'
  } finally {
    modal.submitting = false
  }
}

onMounted(load)
</script>