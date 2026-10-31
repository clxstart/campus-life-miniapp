<template>
  <section class="space-y-5">
    <div>
      <h2 class="text-base font-semibold text-stone-800">认证审核</h2>
      <p class="mt-1 text-sm text-stone-600">
        学生与实名认证目前只接受**管理员人工核验**：没有接入可信核验源，系统不会自动把任何登录用户当成已认证。
        未通过核验的用户可以浏览岗位，但不能接取有偿任务。
      </p>
    </div>

    <p v-if="error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ error }}</p>
    <p v-if="notice" class="rounded-md border border-[#cfdcc4] bg-[#eef4e8] px-4 py-3 text-sm text-[#4d6b3c]">{{ notice }}</p>

    <div class="flex flex-wrap items-center gap-3">
      <select v-model="filter" class="no-input max-w-[180px]" @change="load">
        <option value="">全部状态</option>
        <option value="none">未认证</option>
        <option value="pending">审核中</option>
        <option value="verified">已通过</option>
        <option value="rejected">已拒绝</option>
      </select>
      <button class="no-btn-ghost no-btn-sm" @click="load">刷新</button>
    </div>

    <div class="no-card-plain !p-0 overflow-x-auto">
      <table class="w-full min-w-[760px] text-left text-sm">
        <thead>
          <tr class="bg-[#f3ece4] text-stone-600">
            <th class="px-5 py-4 font-medium">用户 ID</th>
            <th class="px-5 py-4 font-medium">实名</th>
            <th class="px-5 py-4 font-medium">学生身份</th>
            <th class="px-5 py-4 font-medium">核验来源</th>
            <th class="px-5 py-4 font-medium">通过时间</th>
            <th class="px-5 py-4 font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="6" class="px-5 py-12 text-center text-stone-500">正在加载…</td></tr>
          <tr v-else-if="!rows.length"><td colspan="6" class="px-5 py-12 text-center text-stone-500">没有符合条件的记录</td></tr>
          <tr v-for="row in rows" :key="row.userId" class="border-t border-stone-200">
            <td class="px-5 py-4">#{{ row.userId }}</td>
            <td class="px-5 py-4"><span class="no-badge" :class="badgeClass(row.realNameStatus)">{{ statusLabel(row.realNameStatus) }}</span></td>
            <td class="px-5 py-4"><span class="no-badge" :class="badgeClass(row.studentStatus)">{{ statusLabel(row.studentStatus) }}</span></td>
            <td class="px-5 py-4 text-stone-500">{{ row.provider === 'manual_admin' ? '人工核验' : '—' }}</td>
            <td class="px-5 py-4 text-stone-500">{{ row.verifiedAt || '—' }}</td>
            <td class="px-5 py-4">
              <div class="flex gap-2">
                <button class="no-btn-ghost no-btn-sm" @click="openReview(row, 'verified')">通过</button>
                <button class="no-btn-ghost no-btn-sm" @click="openReview(row, 'rejected')">拒绝</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <NoModal :open="modal.open" :title="modal.result === 'verified' ? '通过核验' : '拒绝核验'" :hint="`用户 #${modal.userId}`" :submitting="modal.submitting" confirm-text="确认" @close="modal.open = false" @confirm="submit">
      <div class="space-y-4">
        <p class="text-sm text-stone-600">
          这项操作会写入操作日志（操作人、时间、前后状态、原因），不能撤销为"未操作"，只能再次核验覆盖。
        </p>
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
          <input v-model.trim="modal.reason" class="no-input mt-2" maxlength="200" placeholder="拒绝时建议写明原因，用户可见" />
        </label>
      </div>
    </NoModal>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import { verifyApi } from '../api'

const rows = ref([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const filter = ref('')
const modal = reactive({ open: false, userId: null, result: 'verified', realNameStatus: 'verified', studentStatus: 'verified', reason: '', submitting: false })

function statusLabel(status) {
  return { none: '未认证', pending: '审核中', verified: '已通过', rejected: '已拒绝' }[status] || status || '—'
}
function badgeClass(status) {
  if (status === 'verified') return 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'
  if (status === 'rejected') return 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'
  return 'bg-[#f3ece4] text-stone-600 border-stone-300'
}

async function load() {
  loading.value = true
  try {
    rows.value = await verifyApi.list({ status: filter.value || undefined, page: 1, size: 50 })
    error.value = ''
  } catch (e) { error.value = e.message || '加载失败' }
  finally { loading.value = false }
}

function openReview(row, result) {
  modal.userId = row.userId
  modal.result = result
  modal.realNameStatus = result
  modal.studentStatus = result
  modal.reason = ''
  modal.open = true
}

async function submit() {
  modal.submitting = true
  try {
    await verifyApi.review(modal.userId, {
      realNameStatus: modal.realNameStatus,
      studentStatus: modal.studentStatus,
      reason: modal.reason
    })
    modal.open = false
    error.value = ''
    notice.value = `用户 #${modal.userId} 已更新为 ${statusLabel(modal.realNameStatus)} / ${statusLabel(modal.studentStatus)}`
    setTimeout(() => { notice.value = '' }, 4000)
    await load()
  } catch (e) { error.value = e.message || '操作失败' }
  finally { modal.submitting = false }
}

onMounted(load)
</script>
