<template>
  <section class="space-y-6">
    <div>
      <h2 class="text-base font-semibold text-stone-800">配送调度</h2>
      <p class="mt-1 text-sm text-stone-600">
        订单按地址匹配楼栋后自动派给该楼栋的默认骑手；缺配楼栋、骑手请假或地址匹配不上时进入异常队列，由负责人改派或接管。
        不开放全校自由抢单。
      </p>
    </div>

    <p v-if="error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ error }}</p>
    <p v-if="notice" class="rounded-md border border-[#cfdcc4] bg-[#eef4e8] px-4 py-3 text-sm text-[#4d6b3c]">{{ notice }}</p>

    <!-- 异常待处理队列 -->
    <div class="space-y-3">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <h3 class="text-sm font-semibold text-stone-800">异常待处理队列（{{ queue.length }}）</h3>
        <div class="flex gap-2">
          <button class="no-btn-ghost no-btn-sm" @click="load">刷新</button>
          <button v-if="isSuper" class="no-btn-primary no-btn-sm" :disabled="busy" @click="redispatch">补派一次</button>
        </div>
      </div>
      <div class="no-card-plain !p-0 overflow-x-auto">
        <table class="w-full min-w-[860px] text-left text-sm">
          <thead>
            <tr class="bg-[#f3ece4] text-stone-600">
              <th class="px-5 py-4 font-medium">订单</th>
              <th class="px-5 py-4 font-medium">店铺</th>
              <th class="px-5 py-4 font-medium">地址</th>
              <th class="px-5 py-4 font-medium">状态</th>
              <th class="px-5 py-4 font-medium">原因</th>
              <th class="px-5 py-4 font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading"><td colspan="6" class="px-5 py-12 text-center text-stone-500">正在加载…</td></tr>
            <tr v-else-if="!queue.length"><td colspan="6" class="px-5 py-12 text-center text-stone-500">没有待处理订单</td></tr>
            <tr v-for="row in queue" :key="row.id" class="border-t border-stone-200">
              <td class="px-5 py-4">#{{ row.id }}</td>
              <td class="px-5 py-4">{{ row.shopName || '—' }}</td>
              <td class="px-5 py-4">{{ row.address }}</td>
              <td class="px-5 py-4">{{ statusText(row.status) }}</td>
              <td class="px-5 py-4 text-stone-500">{{ row.note || '—' }}</td>
              <td class="px-5 py-4">
                <button class="no-btn-ghost no-btn-sm" @click="openAssign(row)">改派 / 接管</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 楼栋责任区 -->
    <div class="space-y-3">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <h3 class="text-sm font-semibold text-stone-800">楼栋责任区（{{ buildings.length }}）</h3>
        <button class="no-btn-primary no-btn-sm" @click="openBuilding(null)">新增楼栋</button>
      </div>
      <div class="no-card-plain !p-0 overflow-x-auto">
        <table class="w-full min-w-[860px] text-left text-sm">
          <thead>
            <tr class="bg-[#f3ece4] text-stone-600">
              <th class="px-5 py-4 font-medium">楼栋</th>
              <th class="px-5 py-4 font-medium">匹配关键词</th>
              <th class="px-5 py-4 font-medium">默认骑手</th>
              <th class="px-5 py-4 font-medium">状态</th>
              <th class="px-5 py-4 font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!buildings.length"><td colspan="5" class="px-5 py-12 text-center text-stone-500">还没有配置楼栋，订单会全部进入异常队列</td></tr>
            <tr v-for="row in buildings" :key="row.id" class="border-t border-stone-200">
              <td class="px-5 py-4">{{ row.name }}</td>
              <td class="px-5 py-4 text-stone-500">{{ row.matchKeywords || '—' }}</td>
              <td class="px-5 py-4">{{ riderName(row.defaultRiderId) }}</td>
              <td class="px-5 py-4">
                <span class="no-badge" :class="row.status === 1 ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]' : 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'">
                  {{ row.status === 1 ? '启用' : '停用' }}
                </span>
              </td>
              <td class="px-5 py-4">
                <div class="flex gap-2">
                  <button class="no-btn-ghost no-btn-sm" @click="openBuilding(row)">编辑</button>
                  <button class="no-btn-ghost no-btn-sm" @click="removeBuilding(row)">停用</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 改派弹窗 -->
    <NoModal :open="assign.open" title="改派 / 接管" :hint="assign.hint" :submitting="assign.submitting" confirm-text="确认" @close="assign.open = false" @confirm="submitAssign">
      <div class="space-y-4">
        <label class="block text-sm text-stone-700">负责配送的骑手
          <select v-model.number="assign.form.riderId" class="no-input mt-2">
            <option :value="0" disabled>请选择骑手</option>
            <option v-for="r in riders" :key="r.id" :value="r.id">{{ r.realName }}（{{ r.username }}）{{ r.status === 1 ? '' : ' · 已停用' }}</option>
          </select>
        </label>
        <label class="block text-sm text-stone-700">操作类型
          <select v-model="assign.form.action" class="no-input mt-2">
            <option value="reassign">改派给其他骑手</option>
            <option value="takeover">负责人接管</option>
          </select>
        </label>
        <label class="block text-sm text-stone-700">原因（会写入调度记录）
          <input v-model.trim="assign.form.reason" class="no-input mt-2" maxlength="200" />
        </label>
        <label class="flex items-center gap-2 text-sm text-stone-700">
          <input v-model="assign.form.countPay" type="checkbox" />
          这笔配送费计入该骑手报酬（取消勾选表示不计酬，需在原因里说明）
        </label>
      </div>
    </NoModal>

    <!-- 楼栋弹窗 -->
    <NoModal :open="modal.open" :title="modal.id ? '编辑楼栋' : '新增楼栋'" hint="地址去掉空格后包含关键词即视为匹配；多个关键词用逗号分隔。" :submitting="modal.submitting" confirm-text="保存" @close="modal.open = false" @confirm="saveBuilding">
      <div class="space-y-4">
        <label class="block text-sm text-stone-700">楼栋名称<input v-model.trim="modal.form.name" class="no-input mt-2" maxlength="64" /></label>
        <label class="block text-sm text-stone-700">匹配关键词<input v-model.trim="modal.form.matchKeywords" class="no-input mt-2" maxlength="255" placeholder="如：6号楼,6栋,六号楼" /></label>
        <label class="block text-sm text-stone-700">默认骑手
          <select v-model.number="modal.form.defaultRiderId" class="no-input mt-2">
            <option :value="0">暂不指定（订单会进入异常队列）</option>
            <option v-for="r in riders" :key="r.id" :value="r.id">{{ r.realName }}（{{ r.username }}）</option>
          </select>
        </label>
        <label v-if="isSuper && !modal.id" class="block text-sm text-stone-700">所属学校
          <select v-model.number="modal.form.schoolId" class="no-input mt-2">
            <option :value="0" disabled>请选择学校</option>
            <option v-for="s in schools" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
        </label>
      </div>
    </NoModal>

    <!-- 调度记录 -->
    <div v-if="logs.length" class="space-y-2">
      <h3 class="text-sm font-semibold text-stone-800">最近操作记录</h3>
      <div class="no-card-plain space-y-1 text-sm text-stone-600">
        <p v-for="log in logs" :key="log.id">
          {{ log.createdAt }} · {{ log.actorKind === 'admin' ? '负责人' : '系统' }}
          {{ actionText(log.action) }}
          <span v-if="log.fromRiderId">（原 #{{ log.fromRiderId }} → 新 #{{ log.toRiderId }}）</span>
          {{ log.reason ? '· ' + log.reason : '' }}
        </p>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import http, { dispatchApi, riderApi } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const isSuper = computed(() => auth.profile?.role === 'super')

const queue = ref([])
const buildings = ref([])
const riders = ref([])
const schools = ref([])
const logs = ref([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')

const assign = reactive({ open: false, orderId: null, submitting: false, hint: '', form: { riderId: 0, action: 'reassign', reason: '', countPay: true } })
const modal = reactive({ open: false, id: null, submitting: false, form: {} })

const riderMap = computed(() => Object.fromEntries(riders.value.map(r => [r.id, r.realName || r.username])))
function riderName(id) { return id ? (riderMap.value[id] || `#${id}`) : '未指定' }
function statusText(status) {
  return { accepted: '待派单', delivering: '配送中', picked: '已取货' }[status] || status
}
function actionText(action) {
  return { auto: '自动派单', reassign: '改派', takeover: '接管', queue: '进入队列', release: '退回' }[action] || action
}

async function load() {
  loading.value = true
  try {
    const [q, b, r] = await Promise.all([dispatchApi.queue(), dispatchApi.buildings(), riderApi.riders()])
    queue.value = q || []
    buildings.value = b || []
    riders.value = r || []
    error.value = ''
  } catch (e) { error.value = e.message || '加载失败' }
  finally { loading.value = false }
}

async function redispatch() {
  busy.value = true
  try {
    const res = await dispatchApi.redispatch()
    notice.value = `补派完成，成功派单 ${res?.assigned ?? 0} 单`
    setTimeout(() => { notice.value = '' }, 4000)
    await load()
  } catch (e) { error.value = e.message || '补派失败' }
  finally { busy.value = false }
}

function openAssign(row) {
  assign.orderId = row.id
  assign.hint = `订单 #${row.id} · ${row.address}`
  assign.form = { riderId: 0, action: 'reassign', reason: '', countPay: true }
  assign.open = true
}
async function submitAssign() {
  if (!assign.form.riderId) { error.value = '请选择骑手'; return }
  assign.submitting = true
  try {
    await dispatchApi.assign(assign.orderId, assign.form)
    assign.open = false; error.value = ''
    logs.value = await dispatchApi.orderLog(assign.orderId)
    await load()
  } catch (e) { error.value = e.message || '操作失败' }
  finally { assign.submitting = false }
}

async function openBuilding(row) {
  modal.id = row?.id || null
  modal.form = row
    ? { name: row.name || '', matchKeywords: row.matchKeywords || '', defaultRiderId: row.defaultRiderId || 0, schoolId: row.schoolId || 0 }
    : { name: '', matchKeywords: '', defaultRiderId: 0, schoolId: isSuper.value ? 0 : Number(auth.profile?.schoolId || 0) }
  if (isSuper.value && !schools.value.length) {
    try { schools.value = await http.get('/admin/schools') }
    catch (e) { error.value = e.message || '学校列表加载失败'; return }
  }
  modal.open = true
}
async function saveBuilding() {
  if (!modal.form.name) { error.value = '请填写楼栋名称'; return }
  modal.submitting = true
  try {
    const payload = { name: modal.form.name, matchKeywords: modal.form.matchKeywords, defaultRiderId: modal.form.defaultRiderId, schoolId: modal.form.schoolId }
    if (modal.id) await dispatchApi.updateBuilding(modal.id, payload)
    else await dispatchApi.createBuilding(payload)
    modal.open = false; error.value = ''
    await load()
  } catch (e) { error.value = e.message || '保存失败' }
  finally { modal.submitting = false }
}
async function removeBuilding(row) {
  if (!confirm(`确定停用楼栋「${row.name}」？停用后该楼栋的订单会进入异常队列。`)) return
  try { await dispatchApi.deleteBuilding(row.id); await load() }
  catch (e) { error.value = e.message || '停用失败' }
}

onMounted(load)
</script>
