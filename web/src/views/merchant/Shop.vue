<template>
  <form class="max-w-3xl space-y-6" @submit.prevent="save">
    <div class="flex flex-wrap items-start justify-between gap-3"><div><h2 class="text-xl font-semibold">店铺设置</h2><p class="mt-1 text-sm text-[#77847b]">保存后，学生端将读取最新的店铺资料。</p></div><span class="rounded border border-[#dce5dd] bg-white px-2.5 py-1 text-xs text-[#68766d]">学校：{{ shop.schoolId || '—' }}</span></div>
    <section class="grid gap-5 border-y border-[#dfe7e0] py-5 sm:grid-cols-2">
      <label class="block"><span class="mb-1.5 block text-sm font-medium">店铺名称</span><input v-model.trim="form.name" class="no-input" maxlength="64" required /></label>
      <label class="block"><span class="mb-1.5 block text-sm font-medium">经营分类</span><select v-model="form.category" class="no-input"><option v-for="item in categories" :key="item" :value="item">{{ item }}</option></select></label>
      <label class="block"><span class="mb-1.5 block text-sm font-medium">联系电话</span><input v-model.trim="form.contactPhone" class="no-input" maxlength="20" required /></label>
      <label class="block"><span class="mb-1.5 block text-sm font-medium">店铺地址</span><input v-model.trim="form.address" class="no-input" maxlength="128" required /></label>
      <div class="sm:col-span-2"><span class="mb-1.5 block text-sm font-medium">店铺封面</span><NoImageUpload v-model="form.cover" upload-path="/upload" /></div>
      <label class="block sm:col-span-2"><span class="mb-1.5 block text-sm font-medium">店铺简介</span><textarea v-model.trim="form.intro" class="no-input min-h-24 resize-y" maxlength="300" /></label>
    </section>
    <section class="space-y-4 border-b border-[#dfe7e0] pb-5">
      <h3 class="text-sm font-semibold">配送与优惠</h3>
      <div class="grid gap-4 sm:grid-cols-3">
        <label><span class="mb-1.5 block text-sm text-[#637168]">起送价</span><input v-model.number="form.minPrice" class="no-input" type="number" min="0" step="0.01" /></label>
        <label><span class="mb-1.5 block text-sm text-[#637168]">配送费</span><input v-model.number="form.deliveryFee" class="no-input" type="number" min="0" step="0.01" /></label>
        <label><span class="mb-1.5 block text-sm text-[#637168]">配送时间（分钟）</span><input v-model.number="form.deliveryTime" class="no-input" type="number" min="1" max="240" /></label>
        <label><span class="mb-1.5 block text-sm text-[#637168]">满减门槛</span><input v-model.number="form.discountThreshold" class="no-input" type="number" min="0" step="0.01" /></label>
        <label><span class="mb-1.5 block text-sm text-[#637168]">减免金额</span><input v-model.number="form.discountAmount" class="no-input" type="number" min="0" step="0.01" /></label>
        <label><span class="mb-1.5 block text-sm text-[#637168]">优惠说明</span><input v-model.trim="form.discount" class="no-input" maxlength="32" placeholder="满 30 减 5" /></label>
      </div>
      <label class="block"><span class="mb-1.5 block text-sm text-[#637168]">店铺公告</span><input v-model.trim="form.notice" class="no-input" maxlength="200" /></label>
    </section>
    <section class="flex flex-wrap items-center justify-between gap-4 border-b border-[#dfe7e0] pb-5">
      <div><h3 class="text-sm font-semibold">营业时间</h3><p class="mt-1 text-xs text-[#77847b]">超出营业时间时，学生仍可浏览菜单，但不能下单。</p></div>
      <div class="flex items-center gap-3"><input v-model="form.openTime" class="no-input w-28" type="time" aria-label="开店时间"/><span class="text-[#77847b]">至</span><input v-model="form.closeTime" class="no-input w-28" type="time" aria-label="打烊时间"/></div>
      <label class="flex cursor-pointer items-center gap-2 text-sm"><input v-model="form.isOpen" type="checkbox" class="h-4 w-4 accent-[#34764b]"/>今日营业</label>
    </section>
    <div class="flex items-center gap-3"><button class="rounded bg-[#2f7046] px-5 py-2.5 text-sm font-medium text-white disabled:opacity-50" :disabled="saving">{{ saving ? '保存中…' : '保存更改' }}</button><p v-if="message" class="text-sm" :class="error ? 'text-[#a34743]' : 'text-[#34764b]'">{{ message }}</p></div>
  </form>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { merchantApi } from '../../api'
import NoImageUpload from '../../components/NoImageUpload.vue'
const categories = ['快餐', '奶茶', '水果', '超市', '其他', '快餐便当', '奶茶饮品', '水果轻食', '零食']
const shop = ref({})
const form = reactive({ name: '', category: '快餐', cover: '', contactPhone: '', address: '', intro: '', minPrice: 0, deliveryFee: 0, deliveryTime: 30, notice: '', discount: '', discountThreshold: 0, discountAmount: 0, openTime: '09:00', closeTime: '21:00', isOpen: true })
const saving = ref(false)
const message = ref('')
const error = ref(false)
onMounted(load)
async function load() {
  try {
    const row = await merchantApi.shop(); shop.value = row
    for (const key of Object.keys(form)) form[key] = key === 'isOpen' ? Number(row[key]) === 1 : (row[key] ?? form[key])
  } catch (e) { error.value = true; message.value = e.message || '店铺加载失败' }
}
async function save() {
  saving.value = true; message.value = ''; error.value = false
  try {
    await merchantApi.updateShop({ ...form, isOpen: form.isOpen ? 1 : 0 })
    message.value = '已保存'
  } catch (e) { error.value = true; message.value = e.message || '保存失败' }
  finally { saving.value = false }
}
</script>
