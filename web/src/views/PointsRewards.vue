<template>
  <section class="space-y-5">
    <div>
      <h2 class="text-base font-semibold text-stone-800">兑换商品池</h2>
      <p class="mt-1 text-sm text-stone-600">
        库存为 0 时小程序显示「已兑完」；下架后用户看不到该商品，<strong>已兑换的券不受影响</strong>。
        价格改动只影响之后的兑换，不会改历史兑换记录。
      </p>
      <p class="mt-1 text-xs text-stone-500">
        口径：100 积分 = 1 元，券面额与积分价格严格 1:1；V3.1 只上虚拟券（外卖立减），不做「积分 + 现金」混合兑。
      </p>
    </div>

    <p v-if="error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ error }}</p>
    <p v-if="notice" class="rounded-md border border-[#cfdcc4] bg-[#eef4e8] px-4 py-3 text-sm text-[#4d6b3c]">{{ notice }}</p>

    <div class="flex flex-wrap items-end gap-3">
      <label class="text-sm text-stone-700">
        状态
        <select v-model="filterStatus" class="no-input mt-2 max-w-[180px]">
          <option value="">全部状态</option>
          <option value="online">上架中</option>
          <option value="offline">已下架</option>
          <option value="soldout">已售罄</option>
        </select>
      </label>
      <label class="text-sm text-stone-700">
        关键字
        <input v-model.trim="keyword" class="no-input mt-2 max-w-[220px]" maxlength="32" placeholder="商品键 / 名称 / 券标题" />
      </label>
      <button class="no-btn-ghost no-btn-sm" @click="loadItems">刷新</button>
      <span class="text-xs text-stone-500">共 {{ filteredItems.length }} 个商品</span>
    </div>

    <!-- 商品池宽表 -->
    <div class="no-card-plain !p-0 overflow-x-auto">
      <table class="w-full min-w-[1080px] text-left text-sm">
        <thead>
          <tr class="bg-[#f3ece4] text-stone-600">
            <th class="px-5 py-4 font-medium">商品键</th>
            <th class="px-5 py-4 font-medium">名称</th>
            <th class="px-5 py-4 font-medium">价格（P）</th>
            <th class="px-5 py-4 font-medium">现金部分</th>
            <th class="px-5 py-4 font-medium">库存</th>
            <th class="px-5 py-4 font-medium">每人限购</th>
            <th class="px-5 py-4 font-medium">券标题</th>
            <th class="px-5 py-4 font-medium">状态</th>
            <th class="px-5 py-4 font-medium">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="9" class="px-5 py-12 text-center text-stone-500">正在加载…</td></tr>
          <tr v-else-if="!filteredItems.length"><td colspan="9" class="px-5 py-12 text-center text-stone-500">没有符合条件的商品</td></tr>
          <template v-else>
            <tr v-for="row in filteredItems" :key="row.itemKey" class="border-t border-stone-200">
              <td class="px-5 py-4 font-mono text-xs text-stone-600">{{ row.itemKey }}</td>
              <td class="px-5 py-4">
                <div class="text-stone-800">{{ row.name || '—' }}</div>
                <div class="mt-1 text-xs text-stone-500">{{ zoneLabel(row.zone) }}</div>
              </td>
              <td class="px-5 py-4">
                <div>{{ pricePoints(row) }} 积分</div>
                <div class="text-xs text-stone-500">≈¥{{ yuan(row) }}</div>
              </td>
              <td class="px-5 py-4 text-stone-500">{{ cashText(row) }}</td>
              <td class="px-5 py-4">
                <span :class="Number(row.stock ?? 0) <= 0 ? 'text-[#a4553f]' : 'text-stone-800'">{{ Number(row.stock ?? 0) }}</span>
              </td>
              <td class="px-5 py-4">{{ row.perUserLimit ?? row.per_user_limit ?? 1 }} 件</td>
              <td class="px-5 py-4">
                <div class="text-stone-700">{{ row.couponTitle || '—' }}</div>
                <div class="text-xs text-stone-500">
                  <span v-if="row.couponAmount">面额 {{ row.couponAmount }}</span>
                  <span v-if="row.couponExpire"> · 有效期 {{ row.couponExpire }}</span>
                  <span v-if="!row.couponAmount && !row.couponExpire">—</span>
                </div>
              </td>
              <td class="px-5 py-4">
                <span class="no-badge" :class="statusBadge(row)">{{ statusLabel(row) }}</span>
              </td>
              <td class="px-5 py-4">
                <div class="flex flex-wrap gap-2">
                  <button class="no-btn-ghost no-btn-sm" @click="openEdit(row)">编辑</button>
                  <button class="no-btn-ghost no-btn-sm" @click="toggleEnabled(row)">
                    {{ Number(row.enabled) === 1 ? '下架' : '上架' }}
                  </button>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <!-- 编辑弹窗 -->
    <NoModal
      :open="modal.open"
      title="编辑商品"
      :hint="modal.itemKey ? `商品键：${modal.itemKey}` : ''"
      :submitting="modal.submitting"
      confirm-text="保存"
      @close="closeEdit"
      @confirm="saveItem"
    >
      <div class="space-y-4">
        <div v-if="modal.error" class="rounded-md border border-[#e3cbbf] bg-[#f6e7e0] px-4 py-3 text-sm text-[#a4553f]">{{ modal.error }}</div>
        <p class="text-sm text-stone-600">
          库存填 <strong>0</strong> 即售罄。券面额应与积分价格严格 1:1（100 积分 = 1 元），面额超过积分价值就是平台在亏钱。
        </p>
        <div class="grid grid-cols-2 gap-4">
          <label class="block text-sm text-stone-700">价格（积分）
            <input v-model.number="modal.form.priceP" class="no-input mt-2" type="number" min="1" step="1" />
          </label>
          <label class="block text-sm text-stone-700">库存
            <input v-model.number="modal.form.stock" class="no-input mt-2" type="number" min="0" step="1" />
          </label>
        </div>
        <div class="grid grid-cols-2 gap-4">
          <label class="block text-sm text-stone-700">每人限购（件）
            <input v-model.number="modal.form.perUserLimit" class="no-input mt-2" type="number" min="1" step="1" />
          </label>
          <label class="block text-sm text-stone-700">现金部分（分）
            <input :value="cashCents" class="no-input mt-2 bg-stone-100 text-stone-500" disabled />
          </label>
        </div>
        <label class="block text-sm text-stone-700">券标题
          <input v-model.trim="modal.form.couponTitle" class="no-input mt-2" maxlength="64" placeholder="如 外卖立减 1 元" />
        </label>
        <div class="grid grid-cols-2 gap-4">
          <label class="block text-sm text-stone-700">券面额（展示文案）
            <input v-model.trim="modal.form.couponAmount" class="no-input mt-2" maxlength="16" placeholder="如 1.00 元" />
          </label>
          <label class="block text-sm text-stone-700">券有效期（展示文案）
            <input v-model.trim="modal.form.couponExpire" class="no-input mt-2" maxlength="16" placeholder="如 30 天" />
          </label>
        </div>
        <label class="flex items-center gap-2 text-sm text-stone-700 cursor-pointer">
          <input type="checkbox" class="accent-[#8b9d77]" :checked="modal.form.enabled" @change="modal.form.enabled = $event.target.checked" />
          上架（用户可见）
        </label>
      </div>
    </NoModal>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoModal from '../components/NoModal.vue'
import http from '../api'

const items = ref([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const filterStatus = ref('')
const keyword = ref('')

const modal = reactive({
  open: false, itemKey: '', submitting: false, error: '',
  form: { priceP: 10, stock: 0, perUserLimit: 1, couponTitle: '', couponAmount: '', couponExpire: '', enabled: true }
})

// 现金部分暂不可编辑：V3.1 不做「积分 + 现金」混合兑
const cashCents = computed(() => 0)

const filteredItems = computed(() => {
  const kw = keyword.value.trim()
  return items.value.filter(row => {
    const status = rowStatus(row)
    if (filterStatus.value && status !== filterStatus.value) return false
    if (!kw) return true
    return [row.itemKey, row.name, row.couponTitle].some(v => String(v || '').includes(kw))
  })
})

function zoneLabel(zone) {
  return { virtual: '虚拟券', mixed: '混合兑（本期不支持）', limited: '限量池（本期不支持）' }[zone] || zone || '虚拟券'
}
function pricePoints(row) {
  return Number(row.priceP ?? row.price_p ?? 0)
}
function yuan(row) {
  return (pricePoints(row) / 100).toFixed(2)
}
function cashText(row) {
  const cents = Number(row.cashCents ?? row.cash_cents ?? 0)
  return cents > 0 ? `¥${(cents / 100).toFixed(2)}` : '—'
}
function rowStatus(row) {
  if (Number(row.enabled) !== 1) return 'offline'
  if (Number(row.stock ?? 0) <= 0) return 'soldout'
  return 'online'
}
function statusLabel(row) {
  return { online: '上架中', offline: '已下架', soldout: '已售罄' }[rowStatus(row)]
}
function statusBadge(row) {
  const map = {
    online: 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]',
    offline: 'bg-[#f3ece4] text-stone-600 border-stone-300',
    soldout: 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'
  }
  return map[rowStatus(row)]
}

function flash(message) {
  notice.value = message
  setTimeout(() => { if (notice.value === message) notice.value = '' }, 4000)
}

async function loadItems() {
  loading.value = true
  try {
    const data = await http.get('/admin/reward-item')
    items.value = Array.isArray(data) ? data : (data?.list || [])
    error.value = ''
  } catch (e) {
    items.value = []
    error.value = e.message || '商品池加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  modal.itemKey = row.itemKey
  modal.form = {
    priceP: Number(row.priceP ?? row.price_p ?? 0),
    stock: Number(row.stock ?? 0),
    perUserLimit: Number(row.perUserLimit ?? row.per_user_limit ?? 1),
    couponTitle: row.couponTitle || '',
    couponAmount: row.couponAmount || '',
    couponExpire: row.couponExpire || '',
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
  const { priceP, stock, perUserLimit, couponTitle } = modal.form
  if (!Number.isInteger(Number(priceP)) || Number(priceP) < 1) return '价格必须是不小于 1 的整数积分'
  if (!Number.isInteger(Number(stock)) || Number(stock) < 0) return '库存必须是非负整数（0 表示售罄）'
  if (!Number.isInteger(Number(perUserLimit)) || Number(perUserLimit) < 1) return '每人限购必须是正整数'
  if (!String(couponTitle || '').trim()) return '券标题必填，兑换成功后用户会看到它'
  return ''
}

async function saveItem() {
  const invalid = validate()
  if (invalid) { modal.error = invalid; return }
  modal.submitting = true
  modal.error = ''
  try {
    await http.put(`/admin/reward-item/${encodeURIComponent(modal.itemKey)}`, {
      priceP: Number(modal.form.priceP),
      stock: Number(modal.form.stock),
      perUserLimit: Number(modal.form.perUserLimit),
      couponTitle: modal.form.couponTitle,
      couponAmount: modal.form.couponAmount,
      couponExpire: modal.form.couponExpire,
      enabled: modal.form.enabled
    })
    modal.open = false
    flash(`商品「${modal.itemKey}」已保存`)
    await loadItems()
  } catch (e) {
    modal.error = e.message || '保存失败'
  } finally {
    modal.submitting = false
  }
}

// 上/下架：一次只改 enabled，失败保持原状（不做乐观更新）
async function toggleEnabled(row) {
  const next = Number(row.enabled) === 1 ? 0 : 1
  const tip = next === 1
    ? `上架商品「${row.name || row.itemKey}」？上架后用户可在兑换页看到它。`
    : `下架后用户看不到该商品，已兑换的券不受影响。确定下架「${row.name || row.itemKey}」？`
  if (!confirm(tip)) return
  try {
    await http.put(`/admin/reward-item/${encodeURIComponent(row.itemKey)}`, { enabled: next === 1 })
    flash(`商品「${row.itemKey}」已${next === 1 ? '上架' : '下架'}`)
    error.value = ''
    await loadItems()
  } catch (e) {
    error.value = e.message || '状态切换失败，列表保持原状态'
  }
}

onMounted(loadItems)
</script>