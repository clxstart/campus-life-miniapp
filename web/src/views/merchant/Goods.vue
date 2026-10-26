<template>
  <div class="space-y-5">
    <div class="flex flex-wrap items-start justify-between gap-3"><div><h2 class="text-xl font-semibold">菜品管理</h2><p class="mt-1 text-sm text-[#77847b]">上架的菜品会出现在学生端菜单。</p></div><button class="rounded bg-[#2f7046] px-4 py-2.5 text-sm font-medium text-white" @click="openForm()">＋ 新增菜品</button></div>
    <div class="flex flex-wrap items-center gap-2 border-y border-[#dfe7e0] py-3">
      <label class="flex items-center gap-2 text-sm text-[#66746b]"><input v-model="selectAll" type="checkbox" class="accent-[#34764b]"/>全选</label>
      <button class="rounded border border-[#dce5dd] px-3 py-1.5 text-xs disabled:opacity-40" :disabled="!selected.length" @click="batchStatus(1)">批量上架</button>
      <button class="rounded border border-[#dce5dd] px-3 py-1.5 text-xs disabled:opacity-40" :disabled="!selected.length" @click="batchStatus(0)">批量下架</button>
      <span class="ml-auto text-xs text-[#829087]">{{ rows.length }} 道菜品</span>
    </div>
    <div class="overflow-x-auto rounded-md border border-[#e1e7e2] bg-white">
      <table class="w-full min-w-[780px] text-left text-sm">
        <thead class="bg-[#f7f9f7] text-xs text-[#718078]"><tr><th class="w-10 px-3 py-3"></th><th class="px-3 py-3">菜品</th><th class="px-3 py-3">分类</th><th class="px-3 py-3">价格</th><th class="px-3 py-3">月售</th><th class="px-3 py-3">状态</th><th class="px-3 py-3">排序</th><th class="px-3 py-3">操作</th></tr></thead>
        <tbody><tr v-for="row in rows" :key="row.id" class="border-t border-[#edf0ed]"><td class="px-3 py-3"><input v-model="selected" type="checkbox" :value="row.id" class="accent-[#34764b]"/></td><td class="px-3 py-3"><div class="flex items-center gap-3"><img :src="row.image" alt="" class="h-10 w-10 rounded object-cover"/><span class="font-medium">{{ row.name }}</span></div></td><td class="px-3 py-3">{{ row.category }}</td><td class="px-3 py-3">¥{{ Number(row.price).toFixed(2) }}</td><td class="px-3 py-3">{{ row.monthlySales || 0 }}</td><td class="px-3 py-3"><span class="rounded border px-2 py-1 text-xs" :class="Number(row.status) === 1 ? 'border-[#b6d1bd] bg-[#eff7f0] text-[#2e6f44]' : 'border-[#e2e7e3] bg-[#f7f8f7] text-[#77847b]'">{{ Number(row.status) === 1 ? '已上架' : '已下架' }}</span></td><td class="px-3 py-3"><input v-model.number="row.sortNum" class="w-20 rounded border border-[#dce5dd] px-2 py-1" type="number" @change="saveSort(row)"/></td><td class="px-3 py-3"><div class="flex gap-2"><button class="text-xs font-medium text-[#356d49]" @click="openForm(row)">编辑</button><button class="text-xs text-[#647168]" @click="toggle(row)">{{ Number(row.status) === 1 ? '下架' : '上架' }}</button><button class="text-xs text-[#a34743]" @click="remove(row)">删除</button></div></td></tr>
          <tr v-if="!loading && !rows.length"><td colspan="8" class="px-3 py-16 text-center text-sm text-[#829087]">还没有菜品，新增后即可管理菜单。</td></tr>
          <tr v-if="loading"><td colspan="8" class="px-3 py-16 text-center text-sm text-[#829087]">正在加载…</td></tr>
        </tbody>
      </table>
    </div>

    <div v-if="modal.open" class="fixed inset-0 z-40 flex items-center justify-center bg-black/35 p-4" @click.self="modal.open = false">
      <form class="max-h-[90vh] w-full max-w-lg space-y-4 overflow-y-auto rounded-md bg-white p-5 shadow-xl" @submit.prevent="save">
        <h3 class="text-lg font-semibold">{{ modal.id ? '编辑菜品' : '新增菜品' }}</h3>
        <label class="block"><span class="mb-1 block text-sm">菜品名称</span><input v-model.trim="form.name" class="no-input" maxlength="64" required/></label>
        <div class="grid gap-4 sm:grid-cols-2"><label><span class="mb-1 block text-sm">分类</span><input v-model.trim="form.category" class="no-input" maxlength="32" required/></label><label><span class="mb-1 block text-sm">价格</span><input v-model.number="form.price" class="no-input" type="number" min="0" step="0.01" required/></label></div>
        <label class="block"><span class="mb-1 block text-sm">排序数字</span><input v-model.number="form.sortNum" class="no-input" type="number"/></label>
        <div><span class="mb-1 block text-sm">菜品图片</span><NoImageUpload v-model="form.image" upload-path="/upload"/></div>
        <p v-if="error" class="text-sm text-[#a34743]">{{ error }}</p>
        <div class="flex justify-end gap-2"><button type="button" class="rounded border border-[#dce5dd] px-4 py-2 text-sm" @click="modal.open = false">取消</button><button class="rounded bg-[#2f7046] px-4 py-2 text-sm text-white" :disabled="saving">{{ saving ? '保存中…' : '保存菜品' }}</button></div>
      </form>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { merchantApi } from '../../api'
import NoImageUpload from '../../components/NoImageUpload.vue'
const rows = ref([])
const selected = ref([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const modal = reactive({ open: false, id: null })
const form = reactive({ name: '', category: '', price: '', sortNum: 0, image: '' })
const selectAll = computed({ get: () => rows.value.length > 0 && selected.value.length === rows.value.length, set: value => { selected.value = value ? rows.value.map(row => row.id) : [] } })
onMounted(load)
async function load() { loading.value = true; try { rows.value = await merchantApi.goods() || []; selected.value = [] } catch (e) { error.value = e.message || '菜品加载失败' } finally { loading.value = false } }
function openForm(row) { modal.id = row?.id || null; Object.assign(form, row ? { name: row.name, category: row.category, price: Number(row.price), sortNum: row.sortNum || 0, image: row.image } : { name: '', category: '主食', price: '', sortNum: 0, image: '' }); error.value = ''; modal.open = true }
async function save() { saving.value = true; error.value = ''; try { if (modal.id) await merchantApi.updateGoods(modal.id, { ...form }); else await merchantApi.createGoods({ ...form }); modal.open = false; await load() } catch (e) { error.value = e.message || '保存失败' } finally { saving.value = false } }
async function toggle(row) { try { await merchantApi.setGoodsStatus(row.id, Number(row.status) === 1 ? 0 : 1); await load() } catch (e) { error.value = e.message || '状态更新失败' } }
async function batchStatus(status) { try { await merchantApi.setGoodsStatusBatch(selected.value, status); await load() } catch (e) { error.value = e.message || '批量更新失败' } }
async function saveSort(row) { try { await merchantApi.updateGoods(row.id, { name: row.name, category: row.category, price: row.price, image: row.image, sortNum: row.sortNum }) } catch (e) { error.value = e.message || '排序更新失败' } }
async function remove(row) { if (!window.confirm(`确认删除“${row.name}”？`)) return; try { await merchantApi.deleteGoods(row.id); await load() } catch (e) { error.value = e.message || '删除失败' } }
</script>
