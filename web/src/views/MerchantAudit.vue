<template>
  <div class="space-y-5 text-[#26342c]">
    <div class="grid grid-cols-3 gap-3">
      <button v-for="item in stats" :key="item.key" class="rounded-md border px-4 py-3 text-left" :class="status === item.key ? 'border-[#548367] bg-[#f0f7f1]' : 'border-[#e1e7e2] bg-white'" @click="selectStatus(item.key)">
        <span class="block text-xs text-[#718078]">{{ item.label }}</span>
        <span class="mt-1 block text-2xl font-semibold">{{ counts[item.key] || 0 }}</span>
      </button>
    </div>

    <div class="flex flex-wrap items-center justify-between gap-3 border-b border-[#e1e7e2]">
      <div class="flex gap-5">
        <button v-for="item in stats" :key="item.key" class="border-b-2 px-1 py-3 text-sm" :class="status === item.key ? 'border-[#34764b] font-semibold text-[#285c3b]' : 'border-transparent text-[#748078]'" @click="selectStatus(item.key)">{{ item.label }}</button>
      </div>
      <span class="pb-3 text-xs text-[#77847b]">共 {{ total }} 条</span>
    </div>

    <div class="overflow-x-auto rounded-md border border-[#e1e7e2] bg-white">
      <table class="w-full min-w-[850px] text-left text-sm">
        <thead class="bg-[#f7f9f7] text-xs text-[#718078]"><tr><th class="px-4 py-3 font-medium">店铺名称</th><th class="px-4 py-3 font-medium">分类</th><th class="px-4 py-3 font-medium">联系人</th><th class="px-4 py-3 font-medium">学校</th><th class="px-4 py-3 font-medium">提交时间</th><th class="px-4 py-3 font-medium">操作</th></tr></thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id" class="border-t border-[#edf0ed]">
            <td class="px-4 py-3 font-medium">{{ row.shopName }}</td>
            <td class="px-4 py-3"><span class="rounded border border-[#dce6dd] bg-[#f5f8f5] px-2 py-1 text-xs">{{ row.category }}</span></td>
            <td class="px-4 py-3"><span class="block">{{ row.contactName }}</span><span class="mt-0.5 block text-xs text-[#718078]">{{ row.contactPhone }}</span></td>
            <td class="px-4 py-3">{{ row.schoolName }}</td>
            <td class="px-4 py-3 text-xs text-[#718078]">{{ formatDate(row.createdAt) }}</td>
            <td class="px-4 py-3"><div class="flex items-center gap-2"><button class="text-sm font-medium text-[#356d49] hover:underline" @click="openDetail(row)">查看</button><template v-if="status === 'pending'"><button class="rounded border border-[#b7d1bd] px-2 py-1 text-xs text-[#2f7046] hover:bg-[#f0f7f1]" @click="approve(row)">通过</button><button class="rounded border border-[#e6c9c7] px-2 py-1 text-xs text-[#a34743] hover:bg-[#fff7f6]" @click="openReject(row)">拒绝</button></template></div></td>
          </tr>
          <tr v-if="!loading && rows.length === 0"><td colspan="6" class="px-4 py-14 text-center text-sm text-[#829087]">暂无{{ currentLabel }}申请</td></tr>
          <tr v-if="loading"><td colspan="6" class="px-4 py-14 text-center text-sm text-[#829087]">正在加载…</td></tr>
        </tbody>
      </table>
    </div>
    <div class="flex justify-end gap-2 text-sm"><button class="rounded border border-[#dce4dd] bg-white px-3 py-1.5 disabled:opacity-40" :disabled="page <= 1" @click="load(page - 1)">上一页</button><span class="px-2 py-1.5 text-[#718078]">{{ page }}</span><button class="rounded border border-[#dce4dd] bg-white px-3 py-1.5 disabled:opacity-40" :disabled="page * size >= total" @click="load(page + 1)">下一页</button></div>

    <div v-if="detail" class="fixed inset-0 z-40 flex justify-end bg-black/35" @click.self="closeDetail">
      <aside class="h-full w-full max-w-xl overflow-y-auto bg-white p-5 shadow-xl sm:p-7">
        <div class="flex items-start justify-between gap-3 border-b border-[#e5ebe6] pb-4"><div><p class="text-xs text-[#678272]">入驻申请 #{{ detail.id }}</p><h2 class="mt-1 text-xl font-semibold">{{ detail.shopName }}</h2></div><button class="rounded p-2 text-[#65736a] hover:bg-[#f1f5f1]" title="关闭" @click="closeDetail">✕</button></div>
        <div class="grid grid-cols-2 gap-x-5 gap-y-4 py-5 text-sm"><DetailField label="经营分类" :value="detail.category"/><DetailField label="所属学校" :value="detail.schoolName"/><DetailField label="联系人" :value="detail.contactName"/><DetailField label="联系电话" :value="detail.contactPhone"/><DetailField label="店铺地址" :value="detail.address"/><DetailField label="营业执照号" :value="detail.licenseNo"/><div class="col-span-2"><DetailField label="店铺简介" :value="detail.intro || '未填写'"/></div></div>
        <div class="space-y-5">
          <div><p class="mb-2 text-sm font-medium">营业执照照片</p><button class="block w-full overflow-hidden rounded border border-[#e1e8e2] bg-[#f6f8f6]" @click="zoomImage(licensePreview)"><img v-if="licensePreview" :src="licensePreview" alt="营业执照" class="max-h-72 w-full object-contain"/><span v-else class="block py-16 text-sm text-[#849087]">{{ imageLoading ? '正在加载…' : '执照图片不可用' }}</span></button></div>
          <div><p class="mb-2 text-sm font-medium">店面照片</p><button class="block w-full overflow-hidden rounded border border-[#e1e8e2] bg-[#f6f8f6]" @click="zoomImage(detail.shopImage)"><img :src="detail.shopImage" alt="店面照片" class="max-h-72 w-full object-contain"/></button></div>
        </div>
        <div v-if="detail.rejectReason" class="mt-5 rounded border border-[#f0d2cf] bg-[#fff8f7] p-3 text-sm text-[#9a4741]">拒绝原因：{{ detail.rejectReason }}</div>
      </aside>
    </div>

    <div v-if="zoomed" class="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4" @click="zoomed = ''"><img :src="zoomed" alt="放大预览" class="max-h-full max-w-full object-contain"/></div>

    <div v-if="rejecting" class="fixed inset-0 z-50 flex items-center justify-center bg-black/35 p-4" @click.self="rejecting = null">
      <form class="w-full max-w-md rounded-md bg-white p-5 shadow-xl" @submit.prevent="reject">
        <h2 class="text-lg font-semibold">拒绝申请</h2><p class="mt-1 text-sm text-[#718078]">{{ rejecting.shopName }}</p>
        <label class="mt-5 block text-sm font-medium">拒绝原因</label><textarea v-model.trim="reason" class="no-input mt-1.5 min-h-28 resize-y" maxlength="200" required placeholder="填写原因，商家可据此补充资料"></textarea>
        <div class="mt-4 flex justify-end gap-2"><button type="button" class="rounded border border-[#dce4dd] px-4 py-2 text-sm" @click="rejecting = null">取消</button><button class="rounded bg-[#a34743] px-4 py-2 text-sm text-white disabled:opacity-50" :disabled="busy">{{ busy ? '提交中…' : '确认拒绝' }}</button></div>
      </form>
    </div>

    <div v-if="credential" class="fixed inset-0 z-50 flex items-center justify-center bg-black/35 p-4">
      <div class="w-full max-w-md rounded-md border border-[#d7e4d9] bg-white p-6 shadow-xl"><div class="text-xs font-semibold uppercase tracking-wide text-[#48865e]">账号已创建</div><h2 class="mt-2 text-xl font-semibold">请将登录信息转告商家</h2><div class="mt-4 rounded border border-[#e1e8e2] bg-[#f8faf8] p-4 font-mono text-sm"><p>账号：{{ credential.username }}</p><p class="mt-2">初始密码：{{ credential.initialPassword }}</p></div><p class="mt-3 text-sm text-[#718078]">请提醒商家首次登录后及时修改密码。</p><button class="mt-5 w-full rounded bg-[#2f7046] px-4 py-2.5 text-sm font-medium text-white" @click="credential = null">知道了</button></div>
    </div>

    <p v-if="error" class="fixed bottom-5 right-5 rounded border border-[#f0d2cf] bg-white px-4 py-3 text-sm text-[#9a4741] shadow-lg">{{ error }} <button class="ml-3" @click="error = ''">×</button></p>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, onBeforeUnmount, onMounted, ref } from 'vue'
import { adminApi } from '../api'

const DetailField = defineComponent({ props: { label: String, value: String }, setup: (props) => () => h('div', [h('div', { class: 'text-xs text-[#819087]' }, props.label), h('div', { class: 'mt-1 break-words font-medium' }, props.value || '—')]) })
const stats = [{ key: 'pending', label: '待审核' }, { key: 'approved', label: '已通过' }, { key: 'rejected', label: '已拒绝' }]
const status = ref('pending')
const rows = ref([])
const counts = ref({})
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const busy = ref(false)
const detail = ref(null)
const licensePreview = ref('')
const imageLoading = ref(false)
const zoomed = ref('')
const rejecting = ref(null)
const reason = ref('')
const credential = ref(null)
const error = ref('')
const currentLabel = computed(() => stats.find(item => item.key === status.value)?.label || '')
let previewUrl = ''

onMounted(() => load(1))
onBeforeUnmount(revokePreview)

async function load(next = 1) {
  loading.value = true
  try {
    const data = await adminApi.merchantApplies({ status: status.value, page: next, size })
    rows.value = data.list || []; total.value = Number(data.total) || 0; page.value = data.page || next; counts.value = data.stats || {}
  } catch (e) { error.value = e.message || '申请列表加载失败'; rows.value = [] }
  finally { loading.value = false }
}
function selectStatus(value) { status.value = value; load(1) }
async function openDetail(row) {
  closeDetail()
  try {
    detail.value = await adminApi.merchantApply(row.id)
    imageLoading.value = true
    try { previewUrl = await adminApi.merchantLicenseImage(row.id); licensePreview.value = previewUrl }
    catch { licensePreview.value = '' }
    finally { imageLoading.value = false }
  } catch (e) { error.value = e.message || '申请详情加载失败' }
}
function revokePreview() { if (previewUrl) URL.revokeObjectURL(previewUrl); previewUrl = ''; licensePreview.value = '' }
function closeDetail() { detail.value = null; revokePreview() }
function zoomImage(src) { if (src) zoomed.value = src }
function formatDate(value) { if (!value) return '—'; return new Date(value).toLocaleString('zh-CN', { hour12: false }) }

async function approve(row) {
  if (!window.confirm('确认通过？将自动创建店铺和商家账号')) return
  busy.value = true
  try { credential.value = await adminApi.approveMerchant(row.id); await load(page.value) }
  catch (e) { error.value = e.message || '审核失败' }
  finally { busy.value = false }
}
function openReject(row) { rejecting.value = row; reason.value = '' }
async function reject() {
  if (!rejecting.value || !reason.value.trim()) return
  busy.value = true
  try { await adminApi.rejectMerchant(rejecting.value.id, reason.value); rejecting.value = null; await load(page.value) }
  catch (e) { error.value = e.message || '拒绝失败' }
  finally { busy.value = false }
}
</script>
