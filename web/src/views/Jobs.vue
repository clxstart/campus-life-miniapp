<template>
  <section class="space-y-6">
    <div>
      <h2 class="text-base font-semibold text-stone-800">兼职管理</h2>
      <p class="mt-1 text-sm text-stone-600">
        岗位审核与下架、逐岗位服务费、履约单与争议裁定都在这里完成。
        这里的「履约账单」只是应付 / 应收记录，<strong>不是钱包余额，也不是已付款，更不能提现</strong>；
        费率一旦被接单就会快照进履约单，之后改价只影响新单。
      </p>
      <p class="mt-1 text-xs text-stone-500">
        校外岗位、替课类目、有偿接单由「系统状态 → 功能开关」控制，默认关闭；本页不能绕过这些闸门。
        <template v-if="auth.isSuper">当前为超级管理员，可管理全部学校。</template>
        <template v-else>当前为学校管理员，只能看到并操作本校岗位。</template>
      </p>
    </div>

    <p v-if="error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ error }}</p>
    <p v-if="notice" class="rounded-md border border-[#cfdcc4] bg-[#eef4e8] px-4 py-3 text-sm text-[#4d6b3c]">{{ notice }}</p>

    <div class="flex flex-wrap items-center gap-2">
      <button class="no-btn-sm" :class="tab === 'jobs' ? 'no-btn-primary' : 'no-btn-ghost'" @click="switchTab('jobs')">岗位审核（{{ jobTotal }}）</button>
      <button class="no-btn-sm" :class="tab === 'contracts' ? 'no-btn-primary' : 'no-btn-ghost'" @click="switchTab('contracts')">履约与争议（{{ contractTotal }}）</button>
    </div>

    <!-- ================= 岗位审核 ================= -->
    <div v-show="tab === 'jobs'" class="space-y-3">
      <div class="flex flex-wrap items-center gap-3">
        <input v-model.trim="jobQuery.keyword" class="no-input max-w-[260px]" maxlength="64" placeholder="搜索岗位标题" @keyup.enter="loadJobs(1)" />
        <label class="flex items-center gap-2 text-sm text-stone-700">
          <input v-model="jobQuery.pending" type="checkbox" @change="loadJobs(1)" />
          只看待审核
        </label>
        <button class="no-btn-ghost no-btn-sm" @click="loadJobs(jobQuery.page)">刷新</button>
        <span class="text-xs text-stone-500">共 {{ jobTotal }} 条</span>
      </div>

      <div class="no-card-plain !p-0 overflow-x-auto">
        <table class="w-full min-w-[1080px] text-left text-sm">
          <thead>
            <tr class="bg-[#f3ece4] text-stone-600">
              <th class="px-5 py-4 font-medium">岗位</th>
              <th class="px-5 py-4 font-medium">分区 / 接单</th>
              <th class="px-5 py-4 font-medium">发布者</th>
              <th class="px-5 py-4 font-medium">报酬</th>
              <th class="px-5 py-4 font-medium">服务费</th>
              <th class="px-5 py-4 font-medium">审核</th>
              <th class="px-5 py-4 font-medium">流程</th>
              <th class="px-5 py-4 font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="jobsLoading"><td colspan="8" class="px-5 py-12 text-center text-stone-500">正在加载…</td></tr>
            <tr v-else-if="!jobs.length"><td colspan="8" class="px-5 py-12 text-center text-stone-500">没有符合条件的岗位</td></tr>
            <tr v-for="row in jobs" :key="row.id" class="border-t border-stone-200 align-top">
              <td class="px-5 py-4">
                <div class="font-medium text-stone-800">{{ row.title }}</div>
                <div class="mt-1 text-xs text-stone-500">
                  #{{ row.id }}<span v-if="row.schoolName"> · {{ row.schoolName }}</span>
                  <span v-if="row.location"> · {{ row.location }}</span>
                </div>
                <div v-if="row.auditReason" class="mt-1 text-xs text-[#a4553f]">审核意见：{{ row.auditReason }}</div>
              </td>
              <td class="px-5 py-4">
                <span class="no-badge" :class="row.scope === 'offsite' ? 'bg-[#f3ece4] text-stone-600 border-stone-300' : 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'">{{ scopeLabel(row.scope) }}</span>
                <div class="mt-1 text-xs text-stone-500">{{ takeLabel(row.takeMode) }}<span v-if="row.categoryKey === 'takeover_class'"> · 替课</span></div>
                <div v-if="row.scope === 'offsite'" class="mt-1 text-xs text-stone-500">
                  已录用 {{ row.acceptedCount || 0 }} / {{ row.headcount || 1 }}
                </div>
              </td>
              <td class="px-5 py-4">
                <div>{{ row.publisherName || '—' }}</div>
                <div class="text-xs text-stone-500">#{{ row.userId }}</div>
              </td>
              <td class="px-5 py-4">
                <div>{{ payText(row.payCents, row.payUnit) }}</div>
                <div v-if="row.deadlineAt" class="text-xs text-stone-500">截止 {{ fmtTime(row.deadlineAt) }}</div>
              </td>
              <td class="px-5 py-4">{{ feeText(row.feeMode, row.feeValue) }}</td>
              <td class="px-5 py-4">
                <span class="no-badge" :class="auditBadge(row.auditStatus)">{{ auditLabel(row.auditStatus) }}</span>
              </td>
              <td class="px-5 py-4">
                <span class="no-badge" :class="workflowBadge(row.workflowStatus)">{{ workflowLabel(row.workflowStatus) }}</span>
              </td>
              <td class="px-5 py-4">
                <div class="flex flex-wrap gap-2">
                  <button v-if="row.auditStatus !== 'passed'" class="no-btn-ghost no-btn-sm" @click="openReview(row, 'approve')">通过</button>
                  <button v-if="row.auditStatus === 'pending'" class="no-btn-ghost no-btn-sm" @click="openReview(row, 'reject')">驳回</button>
                  <button v-if="row.auditStatus === 'passed'" class="no-btn-ghost no-btn-sm" @click="openReview(row, 'reject')">下架</button>
                  <button class="no-btn-ghost no-btn-sm" @click="openFee(row)">费率</button>
                  <button class="no-btn-ghost no-btn-sm" @click="openContractsForJob(row)">履约单</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="flex items-center justify-end gap-2 text-sm text-stone-600">
        <button class="no-btn-ghost no-btn-sm" :disabled="jobQuery.page <= 1" @click="loadJobs(jobQuery.page - 1)">上一页</button>
        <span>第 {{ jobQuery.page }} 页</span>
        <button class="no-btn-ghost no-btn-sm" :disabled="jobQuery.page * jobQuery.size >= jobTotal" @click="loadJobs(jobQuery.page + 1)">下一页</button>
      </div>
    </div>

    <!-- ================= 履约与争议 ================= -->
    <div v-show="tab === 'contracts'" class="space-y-3">
      <div class="flex flex-wrap items-center gap-3">
        <select v-model="contractQuery.status" class="no-input max-w-[200px]" @change="loadContracts(1)">
          <option value="">全部状态</option>
          <option value="disputed">争议中</option>
          <option value="pending_confirm">待双方确认</option>
          <option value="working">履约中</option>
          <option value="settled">已结算</option>
          <option value="cancelled">已取消</option>
          <option value="refunded">已退回</option>
          <option value="created">已承接</option>
        </select>
        <label v-if="contractQuery.jobId" class="flex items-center gap-2 text-sm text-stone-700">
          仅岗位 #{{ contractQuery.jobId }}
          <button class="no-btn-ghost no-btn-sm" @click="clearJobFilter">清除</button>
        </label>
        <button class="no-btn-ghost no-btn-sm" @click="loadContracts(contractQuery.page)">刷新</button>
        <span class="text-xs text-stone-500">共 {{ contractTotal }} 条</span>
      </div>

      <div class="no-card-plain !p-0 overflow-x-auto">
        <table class="w-full min-w-[1120px] text-left text-sm">
          <thead>
            <tr class="bg-[#f3ece4] text-stone-600">
              <th class="px-5 py-4 font-medium">履约单 / 岗位</th>
              <th class="px-5 py-4 font-medium">承接方</th>
              <th class="px-5 py-4 font-medium">发布方</th>
              <th class="px-5 py-4 font-medium">计价数量（承接 / 发布 / 约定）</th>
              <th class="px-5 py-4 font-medium">快照（报酬 / 费率）</th>
              <th class="px-5 py-4 font-medium">状态</th>
              <th class="px-5 py-4 font-medium">争议</th>
              <th class="px-5 py-4 font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="contractsLoading"><td colspan="8" class="px-5 py-12 text-center text-stone-500">正在加载…</td></tr>
            <tr v-else-if="!contracts.length"><td colspan="8" class="px-5 py-12 text-center text-stone-500">没有符合条件的履约单</td></tr>
            <tr v-for="row in contracts" :key="row.id" class="border-t border-stone-200 align-top">
              <td class="px-5 py-4">
                <div class="font-medium text-stone-800">#{{ row.id }} · {{ row.jobTitle }}</div>
                <div class="text-xs text-stone-500">岗位 #{{ row.jobId }} · {{ scopeLabel(row.scope) }}<span v-if="row.schoolName"> · {{ row.schoolName }}</span></div>
              </td>
              <td class="px-5 py-4">
                <div>{{ row.workerName || '—' }}</div>
                <div class="text-xs text-stone-500">#{{ row.workerId }}</div>
              </td>
              <td class="px-5 py-4">
                <div>{{ row.employerName || '—' }}</div>
                <div class="text-xs text-stone-500">#{{ row.employerId }}</div>
              </td>
              <td class="px-5 py-4 text-xs text-stone-600">
                <div>{{ quantity(row.workerHours, row.payUnitSnapshot) }} / {{ quantity(row.employerHours, row.payUnitSnapshot) }} / {{ quantity(row.agreedHours, row.payUnitSnapshot) }}</div>
                <div v-if="row.disputeReason" class="mt-1 text-[#a4553f]">争议：{{ row.disputeReason }}</div>
                <div v-if="row.cancelReason" class="mt-1 text-stone-500">取消：{{ row.cancelReason }}</div>
              </td>
              <td class="px-5 py-4 text-xs text-stone-600">
                <div>{{ money(row.payCentsSnapshot) }} / {{ unitLabel(row.payUnitSnapshot) }}</div>
                <div>{{ feeText(row.feeModeSnapshot, row.feeValueSnapshot) }}</div>
              </td>
              <td class="px-5 py-4">
                <span class="no-badge" :class="contractBadge(row.status)">{{ contractLabel(row.status) }}</span>
              </td>
              <td class="px-5 py-4 text-xs text-stone-600">
                <template v-if="row.disputeId">#{{ row.disputeId }} · {{ row.disputeStatus === 'open' ? '待裁定' : '已处理' }}</template>
                <template v-else>—</template>
              </td>
              <td class="px-5 py-4">
                <div class="flex flex-wrap gap-2">
                  <button class="no-btn-ghost no-btn-sm" @click="openContract(row.id)">详情</button>
                  <button v-if="row.status === 'disputed'" class="no-btn-primary no-btn-sm" @click="openContract(row.id, true)">裁定</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="flex items-center justify-end gap-2 text-sm text-stone-600">
        <button class="no-btn-ghost no-btn-sm" :disabled="contractQuery.page <= 1" @click="loadContracts(contractQuery.page - 1)">上一页</button>
        <span>第 {{ contractQuery.page }} 页</span>
        <button class="no-btn-ghost no-btn-sm" :disabled="contractQuery.page * contractQuery.size >= contractTotal" @click="loadContracts(contractQuery.page + 1)">下一页</button>
      </div>
    </div>

    <!-- ================= 审核 / 下架 ================= -->
    <NoModal
      :open="reviewModal.open"
      :title="reviewModal.mode === 'approve' ? '审核通过' : '驳回 / 下架'"
      :hint="reviewModal.job ? `#${reviewModal.job.id} · ${reviewModal.job.title}` : ''"
      :submitting="reviewModal.submitting"
      :confirm-text="reviewModal.mode === 'approve' ? '确认通过' : '确认下架'"
      @close="closeReview"
      @confirm="submitReview"
    >
      <div class="space-y-4">
        <p v-if="reviewModal.error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-3 py-2 text-sm text-[#a4553f]">{{ reviewModal.error }}</p>
        <p class="text-sm text-stone-600">
          <template v-if="reviewModal.mode === 'approve'">通过后岗位才能被接取 / 报名。审核动作会写入操作日志。</template>
          <template v-else>下架后不能再被接取或录用；已产生的履约单与账单保留，不会被删除。</template>
        </p>
        <label class="block text-sm text-stone-700">原因 / 审核意见
          <textarea v-model.trim="reviewModal.reason" class="no-input mt-2" rows="3" maxlength="200" :placeholder="reviewModal.mode === 'approve' ? '可选' : '请写明下架原因（必填）'"></textarea>
        </label>
      </div>
    </NoModal>

    <!-- ================= 服务费配置 ================= -->
    <NoModal
      :open="feeModal.open"
      title="逐岗位服务费"
      :hint="feeModal.job ? `#${feeModal.job.id} · ${feeModal.job.title}` : ''"
      :submitting="feeModal.submitting"
      confirm-text="保存费率"
      @close="closeFee"
      @confirm="submitFee"
    >
      <div class="space-y-4">
        <p v-if="feeModal.error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-3 py-2 text-sm text-[#a4553f]">{{ feeModal.error }}</p>
        <p class="text-sm text-stone-600">
          服务费只由平台在此配置，发布者无法自填。已被接单的履约单使用接单时的快照，这里改价不会影响旧单。
        </p>
        <label class="block text-sm text-stone-700">计费方式
          <select v-model="feeModal.feeMode" class="no-input mt-2">
            <option value="none">不收取</option>
            <option value="amount">固定金额（分 / 每计价单位）</option>
            <option value="rate">比例（万分比，500 = 5%）</option>
          </select>
        </label>
        <label v-if="feeModal.feeMode !== 'none'" class="block text-sm text-stone-700">
          {{ feeModal.feeMode === 'amount' ? '金额（分）' : '比例（万分比）' }}
          <input v-model.number="feeModal.feeValue" class="no-input mt-2" type="number" min="0" step="1" />
        </label>
        <p class="text-xs text-stone-500">
          预览：{{ feeText(feeModal.feeMode, feeModal.feeValue) }}
          <span v-if="feeModal.feeMode === 'amount'">（100 分 = 1.00 元）</span>
          <span v-else-if="feeModal.feeMode === 'rate'">（500 万分比 = 5%）</span>
        </p>
      </div>
    </NoModal>

    <!-- ================= 履约单详情 / 争议裁定 ================= -->
    <NoModal
      :open="contractModal.open"
      :title="contractModal.detail ? `履约单 #${contractModal.detail.id}` : '履约单详情'"
      :hint="contractModal.detail ? `${contractModal.detail.jobTitle} · 岗位 #${contractModal.detail.jobId}` : ''"
      hide-footer
      @close="closeContract"
    >
      <p v-if="contractModal.error" class="mb-3 rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-3 py-2 text-sm text-[#a4553f]">{{ contractModal.error }}</p>
      <div v-if="contractModal.loading" class="py-10 text-center text-sm text-stone-500">正在加载…</div>
      <div v-else-if="contractModal.detail" class="space-y-5">
        <div class="grid grid-cols-2 gap-3 text-sm">
          <div><span class="text-stone-500">状态：</span>{{ contractLabel(contractModal.detail.status) }}</div>
          <div><span class="text-stone-500">承接方：</span>{{ contractModal.detail.workerName || '—' }}（#{{ contractModal.detail.workerId }}）</div>
          <div><span class="text-stone-500">发布方：</span>{{ contractModal.detail.employerName || '—' }}（#{{ contractModal.detail.employerId }}）</div>
          <div><span class="text-stone-500">创建：</span>{{ fmtTime(contractModal.detail.createdAt) }}</div>
          <div><span class="text-stone-500">承接方数量：</span>{{ quantity(contractModal.detail.workerHours, contractModal.detail.payUnitSnapshot) }}</div>
          <div><span class="text-stone-500">发布方数量：</span>{{ quantity(contractModal.detail.employerHours, contractModal.detail.payUnitSnapshot) }}</div>
          <div><span class="text-stone-500">约定数量：</span>{{ quantity(contractModal.detail.agreedHours, contractModal.detail.payUnitSnapshot) }}</div>
          <div><span class="text-stone-500">结算时间：</span>{{ fmtTime(contractModal.detail.settledAt) }}</div>
          <div><span class="text-stone-500">报酬快照：</span>{{ money(contractModal.detail.payCentsSnapshot) }} / {{ unitLabel(contractModal.detail.payUnitSnapshot) }}</div>
          <div><span class="text-stone-500">费率快照：</span>{{ feeText(contractModal.detail.feeModeSnapshot, contractModal.detail.feeValueSnapshot) }}</div>
        </div>

        <div v-if="contractModal.detail.dispute" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">
          <div class="font-medium">争议记录 #{{ contractModal.detail.dispute.id }} · {{ contractModal.detail.dispute.status === 'open' ? '待裁定' : '已处理' }}</div>
          <div class="mt-1 whitespace-pre-wrap">{{ contractModal.detail.dispute.reason || '（未填写原因）' }}</div>
        </div>

        <div v-if="contractModal.detail.settlement && hasSettlement" class="rounded-md border border-stone-200 bg-[#faf7f2] px-4 py-3 text-sm">
          <div class="mb-2 font-medium text-stone-700">按快照估算（仅供核对，不是付款凭证）</div>
          <div class="grid grid-cols-2 gap-2 text-stone-600">
            <div>应付：{{ money(contractModal.detail.settlement.payableCents) }}</div>
            <div>服务费：{{ money(contractModal.detail.settlement.feeCents) }}</div>
            <div>承接方净收入：{{ money(contractModal.detail.settlement.incomeCents) }}</div>
            <div>计价数量：{{ quantity(contractModal.detail.settlement.hours, contractModal.detail.settlement.payUnit) }}</div>
          </div>
        </div>
        <p v-else-if="contractModal.detail.settlement" class="text-sm text-stone-500">
          尚无双方工时记录，暂不能估算金额；账单也不会在工时确认前生成。
        </p>

        <div>
          <div class="mb-2 text-sm font-medium text-stone-700">履约账单（不是钱包余额）</div>
          <div class="overflow-x-auto">
            <table class="w-full min-w-[560px] text-left text-xs">
              <thead><tr class="bg-[#f3ece4] text-stone-600"><th class="px-3 py-2 font-medium">业务单号</th><th class="px-3 py-2 font-medium">类型</th><th class="px-3 py-2 font-medium">金额</th><th class="px-3 py-2 font-medium">状态</th><th class="px-3 py-2 font-medium">说明</th></tr></thead>
              <tbody>
                <tr v-if="!ledgerRows.length"><td colspan="5" class="px-3 py-4 text-center text-stone-500">还没有账单记录</td></tr>
                <tr v-for="entry in ledgerRows" :key="entry.id" class="border-t border-stone-200">
                  <td class="px-3 py-2 font-mono">{{ entry.bizNo }}</td>
                  <td class="px-3 py-2">{{ entry.entryType }}</td>
                  <td class="px-3 py-2">{{ money(entry.amountCents) }}</td>
                  <td class="px-3 py-2">{{ ledgerStatusLabel(entry.status) }}</td>
                  <td class="px-3 py-2 text-stone-500">{{ entry.note || '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="contractModal.detail.status === 'disputed'" class="rounded-md border border-[#e3cbbf] bg-[#fffaf6] px-4 py-3">
          <div class="mb-2 text-sm font-medium text-stone-800">争议裁定</div>
          <p class="mb-3 text-xs text-stone-500">
            双方申报不一致，系统不会自动采信任一方。请核对下面两个数字后，自己填写确认的计价数量；
            裁定理由与数量都会写入管理员操作日志。金额只改履约账单状态，不产生任何可提现余额。
          </p>
          <div class="mb-3 grid grid-cols-2 gap-2 text-xs text-stone-600">
            <div>承接方申报：{{ quantity(contractModal.detail.workerHours, contractModal.detail.payUnitSnapshot) }}</div>
            <div>发布方申报：{{ quantity(contractModal.detail.employerHours, contractModal.detail.payUnitSnapshot) }}</div>
            <div>报酬快照：{{ money(contractModal.detail.payCentsSnapshot) }} / {{ unitLabel(contractModal.detail.payUnitSnapshot) }}</div>
            <div>费率快照：{{ feeText(contractModal.detail.feeModeSnapshot, contractModal.detail.feeValueSnapshot) }}</div>
          </div>
          <div class="flex flex-wrap items-center gap-3">
            <select v-model="contractModal.decision" class="no-input max-w-[220px]">
              <option value="">请选择裁定方式</option>
              <option value="settled">按确认数量结算</option>
              <option value="refunded">整单退回（不计收入）</option>
            </select>
            <input v-if="contractModal.decision === 'settled'" v-model.trim="contractModal.hours" class="no-input max-w-[180px]" placeholder="确认的计价数量（必填）" />
            <input v-model.trim="contractModal.reason" class="no-input max-w-[320px]" maxlength="200" placeholder="裁定理由（必填）" />
          </div>
          <label class="mt-3 flex items-center gap-2 text-xs text-stone-600">
            <input v-model="contractModal.confirmed" type="checkbox" />
            我已核对双方申报、报酬快照和费率快照
          </label>
          <button class="no-btn-primary no-btn-sm mt-3" :disabled="contractModal.submitting || !contractModal.decision || !contractModal.confirmed" @click="submitArbitrate">
            {{ contractModal.submitting ? '提交中…' : '提交裁定' }}
          </button>
        </div>

        <div class="flex justify-end">
          <button class="no-btn-ghost" @click="closeContract">关闭</button>
        </div>
      </div>
    </NoModal>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import { jobAdminApi } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()

const tab = ref('jobs')
const error = ref('')
const notice = ref('')

const jobs = ref([])
const jobsLoading = ref(false)
const jobTotal = ref(0)
const jobQuery = reactive({ keyword: '', pending: false, page: 1, size: 20 })

const contracts = ref([])
const contractsLoading = ref(false)
const contractTotal = ref(0)
const contractQuery = reactive({ status: '', jobId: null, page: 1, size: 20 })

const reviewModal = reactive({ open: false, job: null, mode: 'approve', reason: '', error: '', submitting: false })
const feeModal = reactive({ open: false, job: null, feeMode: 'none', feeValue: 0, error: '', submitting: false })
const contractModal = reactive({
  open: false, loading: false, detail: null, decision: '', hours: '', confirmed: false,
  reason: '', error: '', submitting: false
})

const ledgerRows = computed(() => (contractModal.detail?.ledger || []))
const hasSettlement = computed(() => Number(contractModal.detail?.settlement?.hours || 0) > 0)

function flash(message) {
  notice.value = message
  setTimeout(() => { if (notice.value === message) notice.value = '' }, 4000)
}

function money(cents) {
  return `¥${(Number(cents || 0) / 100).toFixed(2)}`
}
function payText(cents, unit) {
  if (!Number(cents)) return '无偿'
  return `${money(cents)} / ${unitLabel(unit)}`
}
function quantity(value, unit) {
  if (value === null || value === undefined || value === '') return '—'
  return `${Number(value).toFixed(2)} ${unitLabel(unit) || '单位'}`
}
function fmtTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').replace(/\.\d+$/, '')
}
function feeText(mode, value) {
  if (!mode || mode === 'none' || !Number(value)) return '不收服务费'
  if (mode === 'amount') return `固定 ${money(value)} / 单位`
  return `比例 ${(Number(value) / 100).toFixed(2)}%`
}
function scopeLabel(scope) {
  return { campus: '校内互助', offsite: '校外兼职' }[scope] || scope || '—'
}
function takeLabel(mode) {
  return { first_come: '先到先接', apply: '报名录用' }[mode] || mode || '—'
}
function unitLabel(unit) {
  return { hour: '小时', order: '单', shift: '班次', day: '天', custom: '自定义' }[unit] || unit || ''
}
function auditLabel(status) {
  return { pending: '待审核', passed: '已通过', rejected: '已驳回' }[status] || status || '—'
}
function auditBadge(status) {
  if (status === 'passed') return 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'
  if (status === 'rejected') return 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'
  return 'bg-[#f3ece4] text-stone-600 border-stone-300'
}
function workflowLabel(status) {
  return {
    open: '待接取', taken: '已承接', recruiting: '招募中', working: '履约中',
    pending_confirm: '待确认', settled: '已结算', disputed: '争议中',
    refunded: '已退回', cancelled: '已下架'
  }[status] || status || '—'
}
function workflowBadge(status) {
  if (status === 'settled') return 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'
  if (status === 'disputed') return 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'
  return 'bg-[#f3ece4] text-stone-600 border-stone-300'
}
function contractLabel(status) {
  return {
    created: '已承接', working: '履约中', pending_confirm: '待双方确认',
    settled: '账单已确认', disputed: '争议中', cancelled: '已取消', refunded: '已退回'
  }[status] || status || '—'
}
function ledgerStatusLabel(status) {
  return {
    recorded: '已记账', frozen: '争议冻结', settled: '账单已确认',
    refunded: '已退回', void: '作废（0 元）'
  }[status] || status || '—'
}
function contractBadge(status) {
  if (status === 'settled') return 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'
  if (status === 'disputed') return 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'
  return 'bg-[#f3ece4] text-stone-600 border-stone-300'
}

async function loadJobs(page = 1) {
  jobQuery.page = Math.max(1, page)
  jobsLoading.value = true
  try {
    const data = await jobAdminApi.list({
      keyword: jobQuery.keyword || undefined,
      pending: jobQuery.pending || undefined,
      page: jobQuery.page,
      size: jobQuery.size
    })
    jobs.value = data.list || []
    jobTotal.value = data.total || 0
    error.value = ''
  } catch (e) {
    error.value = e.message || '岗位加载失败'
  } finally {
    jobsLoading.value = false
  }
}

async function loadContracts(page = 1) {
  contractQuery.page = Math.max(1, page)
  contractsLoading.value = true
  try {
    const data = await jobAdminApi.contracts({
      status: contractQuery.status || undefined,
      jobId: contractQuery.jobId || undefined,
      page: contractQuery.page,
      size: contractQuery.size
    })
    contracts.value = data.list || []
    contractTotal.value = data.total || 0
    error.value = ''
  } catch (e) {
    error.value = e.message || '履约单加载失败'
  } finally {
    contractsLoading.value = false
  }
}

function switchTab(next) {
  tab.value = next
  if (next === 'contracts') loadContracts(1)
  else loadJobs(1)
}

function openReview(job, mode) {
  reviewModal.job = job
  reviewModal.mode = mode
  reviewModal.reason = ''
  reviewModal.error = ''
  reviewModal.submitting = false
  reviewModal.open = true
}

function closeReview() {
  reviewModal.open = false
  reviewModal.error = ''
}

async function submitReview() {
  if (reviewModal.mode === 'reject' && !reviewModal.reason) {
    reviewModal.error = '下架 / 驳回必须填写原因'
    return
  }
  reviewModal.submitting = true
  reviewModal.error = ''
  try {
    await jobAdminApi.review(reviewModal.job.id, {
      approve: reviewModal.mode === 'approve',
      reason: reviewModal.reason
    })
    reviewModal.open = false
    flash(`岗位 #${reviewModal.job.id} 已${reviewModal.mode === 'approve' ? '通过审核' : '下架'}`)
    error.value = ''
    await loadJobs(jobQuery.page)
  } catch (e) {
    reviewModal.error = e.message || '操作失败'
  } finally {
    reviewModal.submitting = false
  }
}

function openFee(job) {
  feeModal.job = job
  feeModal.feeMode = job.feeMode || 'none'
  feeModal.feeValue = Number(job.feeValue || 0)
  feeModal.error = ''
  feeModal.submitting = false
  feeModal.open = true
}

function closeFee() {
  feeModal.open = false
  feeModal.error = ''
}

async function submitFee() {
  feeModal.error = ''
  if (feeModal.feeMode !== 'none') {
    const value = Number(feeModal.feeValue)
    if (!Number.isInteger(value) || value < 0) {
      feeModal.error = '费率必须是非负整数（金额单位：分；比例单位：万分比）'
      return
    }
    if (feeModal.feeMode === 'rate' && value > 10000) {
      feeModal.error = '比例费率不能超过 10000（即 100%）'
      return
    }
  }
  feeModal.submitting = true
  try {
    await jobAdminApi.setFee(feeModal.job.id, {
      feeMode: feeModal.feeMode,
      feeValue: feeModal.feeMode === 'none' ? 0 : Number(feeModal.feeValue)
    })
    feeModal.open = false
    flash(`岗位 #${feeModal.job.id} 服务费已更新；旧履约单仍按快照执行`)
    error.value = ''
    await loadJobs(jobQuery.page)
  } catch (e) {
    feeModal.error = e.message || '费率保存失败'
  } finally {
    feeModal.submitting = false
  }
}

function openContractsForJob(job) {
  contractQuery.jobId = job.id
  contractQuery.status = ''
  tab.value = 'contracts'
  loadContracts(1)
}
function clearJobFilter() {
  contractQuery.jobId = null
  loadContracts(1)
}

async function openContract(id, focusArbitrate = false) {
  contractModal.open = true
  contractModal.loading = true
  contractModal.detail = null
  contractModal.decision = ''
  contractModal.hours = ''
  contractModal.confirmed = false
  contractModal.reason = ''
  contractModal.error = ''
  contractModal.submitting = false
  try {
    contractModal.detail = await jobAdminApi.contract(id)
    if (focusArbitrate && contractModal.detail?.status !== 'disputed') {
      flash('该履约单已不是争议状态，无需裁定')
    }
    error.value = ''
  } catch (e) {
    contractModal.error = e.message || '履约单加载失败'
  } finally {
    contractModal.loading = false
  }
}

function closeContract() {
  contractModal.open = false
  contractModal.error = ''
}

async function submitArbitrate() {
  contractModal.error = ''
  if (!contractModal.decision) {
    contractModal.error = '请选择裁定方式'
    return
  }
  if (contractModal.decision === 'settled') {
    const value = Number(contractModal.hours)
    if (!contractModal.hours || !Number.isFinite(value) || value <= 0) {
      contractModal.error = '按确认数量结算时必须填写大于 0 的计价数量'
      return
    }
  }
  if (!contractModal.reason) {
    contractModal.error = '裁定必须填写理由'
    return
  }
  if (!contractModal.confirmed) {
    contractModal.error = '请先核对双方申报、报酬快照和费率快照'
    return
  }
  contractModal.submitting = true
  try {
    contractModal.detail = await jobAdminApi.arbitrate(contractModal.detail.id, {
      decision: contractModal.decision,
      hours: contractModal.decision === 'settled' ? Number(contractModal.hours) : undefined,
      reason: contractModal.reason
    })
    contractModal.reason = ''
    contractModal.hours = ''
    contractModal.confirmed = false
    flash(`履约单 #${contractModal.detail.id} 已裁定：${contractLabel(contractModal.detail.status)}`)
    error.value = ''
    await loadContracts(contractQuery.page)
  } catch (e) {
    contractModal.error = e.message || '裁定失败'
  } finally {
    contractModal.submitting = false
  }
}

onMounted(() => loadJobs(1))
</script>
