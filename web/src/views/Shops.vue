<template>
  <div class="space-y-6 organic-enter">
    <div class="flex flex-wrap items-center justify-between gap-4">
      <div>
        <p class="text-sm text-stone-600">外卖店铺与菜品。店铺停用后小程序不再显示；菜品价格改动即时生效。</p>
        <p class="text-xs text-stone-500 mt-1">删除店铺会连同其菜品一起消失，建议优先用「停用」。</p>
      </div>
      <button class="no-btn-primary no-btn-sm" @click="openShop()">新增店铺</button>
    </div>

    <NoTable
      v-model:keyword="keyword"
      :columns="columns"
      :rows="rows"
      :loading="loading"
      :total="total"
      :page="page"
      :size="size"
      placeholder="搜索店铺名称…"
      empty-text="还没有外卖店铺"
      @search="load(1)"
      @page="load"
    >
      <template #name="{ row }">
        <div class="flex items-center gap-3">
          <span v-if="row.cover" class="w-9 h-9 rounded-[0.75rem] border border-stone-200 overflow-hidden shrink-0">
            <img :src="row.cover" alt="" class="w-full h-full object-cover" />
          </span>
          <span v-else class="w-9 h-9 rounded-[0.75rem] bg-[#e9e0d4] flex items-center justify-center text-sm shrink-0">
            {{ (row.name || '店')[0] }}
          </span>
          <div class="min-w-0">
            <p class="text-stone-800 font-medium truncate">{{ row.name }}</p>
            <p class="text-xs text-stone-500 truncate">{{ row.category }} · 起送 ¥{{ row.minPrice }} · 配送 ¥{{ row.deliveryFee }}</p>
          </div>
        </div>
      </template>
      <template #school="{ row }">
        <span class="no-badge bg-[#f0ece5] text-stone-600 border-stone-200">{{ row.schoolName || '跨校' }}</span>
      </template>
      <template #status="{ row }">
        <!-- 状态列显示「真实可接单状态」：区分已下架 / 已打烊 / 已过营业时间 / 可接单 -->
        <div class="flex flex-col gap-1">
          <span class="no-badge" :class="row.orderable
            ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'
            : 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'">
            {{ row.orderable ? '营业中' : '休息中' }}
          </span>
          <span v-if="row.openStateText" class="text-[11px] leading-tight text-[#77847b]">
            {{ row.openStateText }}
          </span>
        </div>
      </template>
      <template #ops="{ row }">
        <div class="flex items-center gap-2 whitespace-nowrap">
          <button class="no-btn-ghost no-btn-sm" @click="openGoods(row)">菜品</button>
          <button class="no-btn-ghost no-btn-sm" @click="openShop(row)">编辑</button>
          <button class="no-btn-ghost no-btn-sm" @click="toggle(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </button>
          <button class="no-btn-ghost no-btn-sm !text-[#a4553f]" @click="remove(row)">删除</button>
        </div>
      </template>
    </NoTable>

    <!-- 店铺编辑 -->
    <NoModal
      :open="shopModal.open"
      :title="shopModal.id ? '编辑店铺' : '新增店铺'"
      hint="封面建议横图，会显示在小程序外卖列表"
      :submitting="shopModal.submitting"
      @close="shopModal.open = false"
      @confirm="saveShop"
    >
      <div class="space-y-5">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">店铺名称</label>
          <input v-model.trim="shopModal.form.name" class="no-input" maxlength="64" placeholder="如 老王砂锅" />
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">分类</label>
            <select v-model="shopModal.form.category" class="no-input">
              <option v-for="c in SHOP_CATEGORIES" :key="c" :value="c">{{ c }}</option>
            </select>
          </div>
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">所属学校</label>
            <select v-if="isSuper" v-model.number="shopModal.form.schoolId" class="no-input">
              <option :value="0" disabled>请选择学校</option>
              <option v-for="s in schools" :key="s.id" :value="s.id">{{ s.name }}</option>
            </select>
            <input v-else class="no-input bg-stone-100" :value="mySchoolName" disabled />
          </div>
        </div>
        <div class="grid grid-cols-3 gap-4">
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">起送价</label>
            <input v-model="shopModal.form.minPrice" class="no-input" type="number" min="0" step="0.01" />
          </div>
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">配送费</label>
            <input v-model="shopModal.form.deliveryFee" class="no-input" type="number" min="0" step="0.01" />
          </div>
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">配送分钟</label>
            <input v-model.number="shopModal.form.deliveryTime" class="no-input" type="number" min="1" />
          </div>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">优惠说明（选填）</label>
          <input v-model.trim="shopModal.form.discount" class="no-input" maxlength="32" placeholder="如 满20减3" />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">公告（选填）</label>
          <input v-model.trim="shopModal.form.notice" class="no-input" maxlength="200" placeholder="如 今日供应到 21:00" />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">店铺封面（选填）</label>
          <NoImageUpload v-model="shopModal.form.cover" />
        </div>
      </div>
    </NoModal>

    <!-- 菜品管理 -->
    <NoModal
      :open="goodsModal.open"
      :title="`菜品管理 · ${goodsModal.shopName}`"
      hint="价格改动即时生效；停用的菜品小程序不显示"
      :submitting="false"
      @close="goodsModal.open = false"
      @confirm="goodsModal.open = false"
    >
      <div class="space-y-5">
        <div class="flex justify-end">
          <button class="no-btn-primary no-btn-sm" @click="openGoodsForm()">新增菜品</button>
        </div>

        <div v-if="!goodsModal.list.length" class="py-8 text-center text-stone-500 text-sm">这家店还没有菜品</div>
        <div v-else class="space-y-3">
          <div
            v-for="g in goodsModal.list"
            :key="g.id"
            class="flex items-center gap-3 border border-stone-200 rounded-[1.25rem] p-3"
          >
            <span v-if="g.image" class="w-12 h-12 rounded-[0.75rem] border border-stone-200 overflow-hidden shrink-0">
              <img :src="g.image" alt="" class="w-full h-full object-cover" />
            </span>
            <span v-else class="w-12 h-12 rounded-[0.75rem] bg-[#e9e0d4] shrink-0"></span>
            <div class="flex-1 min-w-0">
              <p class="text-stone-800 font-medium truncate">{{ g.name }}</p>
              <p class="text-xs text-stone-500">{{ g.category }} · ¥{{ g.price }}</p>
            </div>
            <span class="no-badge shrink-0" :class="g.status === 1
              ? 'bg-[#e6efe0] text-[#4d6b3c] border-[#cfdcc4]'
              : 'bg-[#f6e7e0] text-[#a4553f] border-[#e3cbbf]'">
              {{ g.status === 1 ? '在售' : '停售' }}
            </span>
            <div class="flex gap-2 shrink-0">
              <button class="no-btn-ghost no-btn-sm" @click="openGoodsForm(g)">编辑</button>
              <button class="no-btn-ghost no-btn-sm !text-[#a4553f]" @click="removeGoods(g)">删除</button>
            </div>
          </div>
        </div>
      </div>
    </NoModal>

    <!-- 菜品表单 -->
    <NoModal
      :open="goodsForm.open"
      :title="goodsForm.id ? '编辑菜品' : '新增菜品'"
      :submitting="goodsForm.submitting"
      @close="goodsForm.open = false"
      @confirm="saveGoods"
    >
      <div class="space-y-5">
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">菜品名称</label>
          <input v-model.trim="goodsForm.form.name" class="no-input" maxlength="64" placeholder="如 招牌牛肉面" />
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">分类</label>
            <input v-model.trim="goodsForm.form.category" class="no-input" maxlength="32" placeholder="如 主食" />
          </div>
          <div>
            <label class="block mb-2 text-sm font-medium text-stone-700">价格</label>
            <input v-model="goodsForm.form.price" class="no-input" type="number" min="0.01" step="0.01" />
          </div>
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">排序（越小越靠前）</label>
          <input v-model.number="goodsForm.form.sortNum" class="no-input" type="number" />
        </div>
        <div>
          <label class="block mb-2 text-sm font-medium text-stone-700">菜品图片（选填）</label>
          <NoImageUpload v-model="goodsForm.form.image" />
        </div>
      </div>
    </NoModal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import NoTable from '../components/NoTable.vue'
import NoModal from '../components/NoModal.vue'
import NoImageUpload from '../components/NoImageUpload.vue'
import http from '../api'
import { useAuthStore } from '../stores/auth'

const SHOP_CATEGORIES = ['快餐便当', '奶茶饮品', '水果轻食', '零食', '其他']

const auth = useAuthStore()
const isSuper = computed(() => auth.isSuper)

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const keyword = ref('')
const schools = ref([])

const mySchoolName = computed(() => {
  const hit = schools.value.find(s => Number(s.id) === Number(auth.profile?.schoolId))
  return hit ? hit.name : '本校'
})

const columns = [
  { key: 'name', title: '店铺' },
  { key: 'school', title: '学校', class: 'w-32' },
  { key: 'status', title: '状态', class: 'w-28' },
  { key: 'ops', title: '操作', class: 'w-72' }
]

const shopModal = reactive({
  open: false, id: null, submitting: false,
  form: { name: '', category: '快餐便当', schoolId: 0, minPrice: 0, deliveryFee: 0, deliveryTime: 30, discount: '', notice: '', cover: '' }
})
const goodsModal = reactive({ open: false, shopId: 0, shopName: '', list: [] })
const goodsForm = reactive({
  open: false, id: null, submitting: false,
  form: { category: '主食', name: '', price: '', sortNum: 0, image: '' }
})

async function loadSchools() {
  try {
    const list = await http.get('/admin/schools')
    schools.value = list || []
  } catch (e) {
    schools.value = []
  }
}

async function load(nextPage = 1) {
  loading.value = true
  try {
    const data = await http.get('/admin/shops', {
      params: { page: nextPage, size: size.value, keyword: keyword.value }
    })
    rows.value = data.list || []
    total.value = Number(data.total) || 0
    page.value = Number(data.page) || nextPage
  } catch (e) {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function openShop(row) {
  if (row) {
    shopModal.id = row.id
    shopModal.form = {
      name: row.name || '', category: row.category || '快餐便当',
      schoolId: Number(row.schoolId) || 0,
      minPrice: row.minPrice ?? 0, deliveryFee: row.deliveryFee ?? 0,
      deliveryTime: row.deliveryTime ?? 30,
      discount: row.discount || '', notice: row.notice || '', cover: row.cover || ''
    }
  } else {
    shopModal.id = null
    shopModal.form = {
      name: '', category: '快餐便当',
      schoolId: isSuper.value ? (schools.value[0]?.id || 0) : Number(auth.profile?.schoolId) || 0,
      minPrice: 0, deliveryFee: 0, deliveryTime: 30, discount: '', notice: '', cover: ''
    }
  }
  shopModal.open = true
}

async function saveShop() {
  const f = shopModal.form
  if (!f.name) { alert('请填写店铺名称'); return }
  if (!f.category) { alert('请选择分类'); return }
  if (isSuper.value && !f.schoolId) { alert('请选择所属学校'); return }
  shopModal.submitting = true
  try {
    if (shopModal.id) {
      await http.put(`/admin/shops/${shopModal.id}`, f)
    } else {
      await http.post('/admin/shops', f)
    }
    shopModal.open = false
    await load(page.value)
  } catch (e) {
    alert(e.message)
  } finally {
    shopModal.submitting = false
  }
}

async function toggle(row) {
  const next = row.status === 1 ? 0 : 1
  try {
    await http.post(`/admin/shops/${row.id}/status`, { status: next })
    row.status = next
  } catch (e) {
    alert(e.message)
  }
}

async function remove(row) {
  if (!confirm(`确定删除店铺「${row.name}」？该店菜品也会一并消失。`)) return
  try {
    await http.delete(`/admin/shops/${row.id}`)
    await load(page.value)
  } catch (e) {
    alert(e.message)
  }
}

async function openGoods(row) {
  goodsModal.shopId = row.id
  goodsModal.shopName = row.name
  goodsModal.open = true
  await loadGoods()
}

async function loadGoods() {
  try {
    goodsModal.list = await http.get(`/admin/shops/${goodsModal.shopId}/goods`) || []
  } catch (e) {
    goodsModal.list = []
    alert(e.message)
  }
}

function openGoodsForm(row) {
  if (row) {
    goodsForm.id = row.id
    goodsForm.form = {
      category: row.category || '主食', name: row.name || '',
      price: row.price ?? '', sortNum: row.sortNum ?? 0, image: row.image || ''
    }
  } else {
    goodsForm.id = null
    goodsForm.form = { category: '主食', name: '', price: '', sortNum: 0, image: '' }
  }
  goodsForm.open = true
}

async function saveGoods() {
  const f = goodsForm.form
  if (!f.name) { alert('请填写菜品名称'); return }
  const price = Number(f.price)
  if (!(price > 0) || price > 99999) { alert('价格须为 0.01 至 99999'); return }
  goodsForm.submitting = true
  try {
    const payload = { ...f, price, shopId: goodsModal.shopId, status: 1 }
    if (goodsForm.id) await http.put(`/admin/shop-goods/${goodsForm.id}`, payload)
    else await http.post('/admin/shop-goods', payload)
    goodsForm.open = false
    await loadGoods()
  } catch (e) {
    alert(e.message)
  } finally {
    goodsForm.submitting = false
  }
}

async function removeGoods(row) {
  if (!confirm(`确定删除菜品「${row.name}」？`)) return
  try {
    await http.delete(`/admin/shop-goods/${row.id}`)
    await loadGoods()
  } catch (e) {
    alert(e.message)
  }
}

onMounted(async () => {
  await loadSchools()
  await load(1)
})
</script>
