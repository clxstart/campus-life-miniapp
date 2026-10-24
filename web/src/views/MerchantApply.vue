<template>
  <main class="min-h-screen bg-[#f4f7f4] px-4 py-10 text-[#1f3028] sm:px-6">
    <section class="mx-auto w-full max-w-2xl">
      <a href="#/login" class="mb-8 inline-flex items-center gap-2 text-sm text-[#68776f] hover:text-[#274d39]">← 返回登录</a>

      <div class="mb-7">
        <p class="text-xs font-semibold uppercase tracking-[0.18em] text-[#48865e]">CAMPUS FOOD</p>
        <h1 class="mt-2 text-3xl font-semibold">{{ submitted ? '申请已提交' : '商家入驻' }}</h1>
        <p class="mt-2 text-sm text-[#68776f]">加入校园外卖，把店铺和好味道带给更多学生。</p>
      </div>

      <div v-if="submitted" class="rounded-lg border border-[#d7e4d9] bg-white p-7 shadow-sm">
        <div class="flex h-12 w-12 items-center justify-center rounded-full bg-[#e8f4e9] text-xl text-[#36764c]">✓</div>
        <p class="mt-5 text-lg font-medium">申请已提交，我们会在 1-3 个工作日内审核，请留意电话通知。</p>
        <p class="mt-2 text-sm text-[#68776f]">申请编号：{{ applyId }}</p>
        <button class="mt-6 rounded-md bg-[#2f7046] px-4 py-2.5 text-sm font-medium text-white hover:bg-[#245a37]" @click="reset">继续提交</button>
      </div>

      <form v-else class="space-y-6 rounded-lg border border-[#e1e8e2] bg-white p-5 shadow-sm sm:p-8" @submit.prevent="submit">
        <div class="grid gap-5 sm:grid-cols-2">
          <label class="block sm:col-span-2">
            <span class="mb-1.5 block text-sm font-medium">店铺名称 <b class="text-rose-600">*</b></span>
            <input v-model.trim="form.shopName" class="no-input" required minlength="2" maxlength="64" placeholder="填写营业店铺名称" />
          </label>
          <label class="block">
            <span class="mb-1.5 block text-sm font-medium">经营分类 <b class="text-rose-600">*</b></span>
            <select v-model="form.category" class="no-input" required>
              <option disabled value="">请选择分类</option>
              <option v-for="item in categories" :key="item" :value="item">{{ item }}</option>
            </select>
          </label>
          <label class="block">
            <span class="mb-1.5 block text-sm font-medium">所属学校 <b class="text-rose-600">*</b></span>
            <select v-model.number="form.schoolId" class="no-input" required>
              <option disabled :value="0">请选择学校</option>
              <option v-for="school in schools" :key="school.id" :value="school.id">{{ school.name }}</option>
            </select>
          </label>
          <label class="block">
            <span class="mb-1.5 block text-sm font-medium">联系人 <b class="text-rose-600">*</b></span>
            <input v-model.trim="form.contactName" class="no-input" required minlength="2" maxlength="32" placeholder="联系人姓名" />
          </label>
          <label class="block">
            <span class="mb-1.5 block text-sm font-medium">联系电话 <b class="text-rose-600">*</b></span>
            <input v-model.trim="form.contactPhone" class="no-input" required inputmode="numeric" maxlength="11" placeholder="11 位手机号" />
          </label>
          <label class="block sm:col-span-2">
            <span class="mb-1.5 block text-sm font-medium">店铺地址 <b class="text-rose-600">*</b></span>
            <input v-model.trim="form.address" class="no-input" required maxlength="128" placeholder="校区、街道及门牌号" />
          </label>
          <label class="block sm:col-span-2">
            <span class="mb-1.5 block text-sm font-medium">营业执照号 <b class="text-rose-600">*</b></span>
            <input v-model.trim="form.licenseNo" class="no-input" required maxlength="64" placeholder="与营业执照保持一致" />
          </label>
        </div>

        <div class="grid gap-5 sm:grid-cols-2">
          <div>
            <span class="mb-1.5 block text-sm font-medium">营业执照照片 <b class="text-rose-600">*</b></span>
            <input ref="licenseInput" class="sr-only" type="file" accept="image/jpeg,image/png,image/gif,image/webp" @change="uploadImage($event, 'license')" />
            <button type="button" class="flex min-h-36 w-full flex-col items-center justify-center rounded-md border border-dashed border-[#bdcbbf] bg-[#f8faf8] p-3 text-center hover:border-[#57906a]" :disabled="uploading.license" @click="licenseInput?.click()">
              <img v-if="licensePreview" :src="licensePreview" alt="营业执照预览" class="mb-2 h-20 w-28 rounded object-cover" />
              <span class="text-sm font-medium">{{ uploading.license ? '正在上传…' : (form.licenseImage ? '更换执照照片' : '选择营业执照照片') }}</span>
              <span class="mt-1 text-xs text-[#77847b]">图片格式，最大 5MB，私有保存</span>
            </button>
          </div>
          <div>
            <span class="mb-1.5 block text-sm font-medium">店面照片 <b class="text-rose-600">*</b></span>
            <input ref="shopInput" class="sr-only" type="file" accept="image/jpeg,image/png,image/gif,image/webp" @change="uploadImage($event, 'shop')" />
            <button type="button" class="flex min-h-36 w-full flex-col items-center justify-center rounded-md border border-dashed border-[#bdcbbf] bg-[#f8faf8] p-3 text-center hover:border-[#57906a]" :disabled="uploading.shop" @click="shopInput?.click()">
              <img v-if="shopPreview" :src="shopPreview" alt="店面照片预览" class="mb-2 h-20 w-28 rounded object-cover" />
              <span class="text-sm font-medium">{{ uploading.shop ? '正在上传…' : (form.shopImage ? '更换店面照片' : '选择店面照片') }}</span>
              <span class="mt-1 text-xs text-[#77847b]">图片格式，最大 5MB</span>
            </button>
          </div>
        </div>

        <label class="block">
          <span class="mb-1.5 block text-sm font-medium">店铺简介</span>
          <textarea v-model.trim="form.intro" class="no-input min-h-24 resize-y" maxlength="300" placeholder="简单介绍店铺与特色"></textarea>
          <span class="mt-1 block text-right text-xs text-[#87938a]">{{ form.intro.length }}/300</span>
        </label>

        <p v-if="error" class="rounded-md border border-[#f1cccc] bg-[#fff6f5] px-3 py-2 text-sm text-[#a84343]">{{ error }}</p>
        <button class="w-full rounded-md bg-[#2f7046] px-4 py-3 text-sm font-semibold text-white hover:bg-[#245a37] disabled:cursor-wait disabled:opacity-60" :disabled="saving || uploading.license || uploading.shop">
          {{ saving ? '正在提交…' : '提交入驻申请' }}
        </button>
      </form>
    </section>
  </main>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import http from '../api'

const categories = ['快餐', '奶茶', '水果', '超市', '其他']
const schools = ref([])
const submitted = ref(false)
const applyId = ref('')
const saving = ref(false)
const error = ref('')
const licenseInput = ref(null)
const shopInput = ref(null)
const licensePreview = ref('')
const shopPreview = ref('')
const uploading = reactive({ license: false, shop: false })
const form = reactive({ shopName: '', category: '', schoolId: 0, contactName: '', contactPhone: '', address: '', licenseNo: '', licenseImage: '', shopImage: '', intro: '' })
const objectUrls = []

onMounted(loadSchools)
onBeforeUnmount(() => objectUrls.forEach(URL.revokeObjectURL))

async function loadSchools() {
  try { schools.value = await http.get('/schools') || [] }
  catch (e) { error.value = e.message || '学校列表加载失败，请刷新重试' }
}

async function uploadImage(event, kind) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!['image/jpeg', 'image/png', 'image/gif', 'image/webp'].includes(file.type)) { error.value = '仅支持 JPG、PNG、GIF 或 WEBP 图片'; return }
  if (file.size > 5 * 1024 * 1024) { error.value = '图片不能超过 5MB'; return }
  error.value = ''
  const preview = URL.createObjectURL(file)
  objectUrls.push(preview)
  if (kind === 'license') licensePreview.value = preview
  else shopPreview.value = preview
  uploading[kind] = true
  try {
    const payload = new FormData()
    payload.append('file', file)
    const data = await http.post(kind === 'license' ? '/admin/upload/private' : '/upload', payload,
      kind === 'license' ? { skipAuth: true } : undefined)
    if (kind === 'license') form.licenseImage = data.path || ''
    else form.shopImage = data.url || data.path || ''
    if (!(kind === 'license' ? form.licenseImage.startsWith('/private/') : form.shopImage)) throw new Error('上传结果无效，请重试')
  } catch (e) {
    if (kind === 'license') form.licenseImage = ''
    else form.shopImage = ''
    error.value = e.message || '图片上传失败，请重试'
  } finally { uploading[kind] = false }
}

async function submit() {
  error.value = ''
  if (form.shopName.length < 2 || form.shopName.length > 64) return void (error.value = '店铺名称需为 2-64 个字')
  if (form.contactName.length < 2 || form.contactName.length > 32) return void (error.value = '联系人需为 2-32 个字')
  if (!/^1\d{10}$/.test(form.contactPhone)) return void (error.value = '请输入 11 位手机号')
  if (!form.licenseImage || !form.shopImage) return void (error.value = '请先上传营业执照照片和店面照片')
  saving.value = true
  try {
    const result = await http.post('/merchant/apply', { ...form })
    applyId.value = result.id
    submitted.value = true
  } catch (e) { error.value = e.message || '申请提交失败，请检查后重试' }
  finally { saving.value = false }
}

function reset() {
  Object.assign(form, { shopName: '', category: '', schoolId: 0, contactName: '', contactPhone: '', address: '', licenseNo: '', licenseImage: '', shopImage: '', intro: '' })
  licensePreview.value = ''; shopPreview.value = ''; submitted.value = false; applyId.value = ''
}
</script>
