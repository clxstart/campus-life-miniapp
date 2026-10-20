<template>
  <div class="space-y-3">
    <!-- 已选图片：缩略图 + 更换/移除 -->
    <div v-if="modelValue" class="flex items-start gap-4">
      <div class="w-24 h-24 rounded-[1.25rem] border border-stone-200 bg-[#faf6f1] overflow-hidden shrink-0">
        <img :src="modelValue" alt="" class="w-full h-full object-cover" @error="onImageError" />
      </div>
      <div class="flex flex-col gap-2 pt-1">
        <button type="button" class="no-btn-ghost no-btn-sm" :disabled="uploading" @click="pick">
          {{ uploading ? '上传中…' : '更换图片' }}
        </button>
        <button type="button" class="no-btn-ghost no-btn-sm !text-[#a4553f]" :disabled="uploading" @click="clear">
          移除
        </button>
      </div>
    </div>

    <!-- 未选图片：上传入口 -->
    <button
      v-else
      type="button"
      class="w-full rounded-[1.5rem] border border-dashed border-stone-300 bg-[#faf6f1] py-8 text-center transition-colors duration-300 hover:bg-stone-100 disabled:opacity-60"
      :disabled="uploading"
      @click="pick"
    >
      <span class="block text-2xl text-stone-400 mb-1">＋</span>
      <span class="text-sm text-stone-600">{{ uploading ? '上传中…' : '点击上传图片' }}</span>
      <span class="block text-xs text-stone-400 mt-1">支持 jpg / png / gif / webp，不超过 5MB</span>
    </button>

    <p v-if="error" class="text-xs text-[#a4553f]">{{ error }}</p>

    <!-- 隐藏的原生文件选择框 -->
    <input
      ref="fileInput"
      type="file"
      accept="image/jpeg,image/png,image/gif,image/webp"
      class="hidden"
      @change="onFileChange"
    />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import http from '../api'

const props = defineProps({
  modelValue: { type: String, default: '' },
  // 提示文案，如「建议 512x512，正方形」
  hint: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])

const MAX_BYTES = 5 * 1024 * 1024
const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']

const fileInput = ref(null)
const uploading = ref(false)
const error = ref('')

function pick() {
  error.value = ''
  fileInput.value && fileInput.value.click()
}

function clear() {
  error.value = ''
  emit('update:modelValue', '')
}

function onImageError() {
  error.value = '图片加载失败，可能是地址已失效，建议重新上传'
}

function validate(file) {
  if (!file) return '请选择图片'
  if (!ALLOWED_TYPES.includes(file.type)) return '仅支持 jpg / png / gif / webp 格式'
  if (file.size > MAX_BYTES) return '图片不能超过 5MB'
  return ''
}

async function onFileChange(event) {
  const file = event.target.files && event.target.files[0]
  // 选完就清空，保证同一张图能再次选择触发 change
  event.target.value = ''
  if (!file) return

  const invalid = validate(file)
  if (invalid) { error.value = invalid; return }

  uploading.value = true
  error.value = ''
  try {
    const form = new FormData()
    form.append('file', file)
    // 必须走管理端鉴权接口 /api/admin/upload，不能用公开的 /api/upload
    const data = await http.post('/admin/upload', form, {
      headers: { 'content-type': 'multipart/form-data' }
    })
    const url = data && (data.url || data.path)
    if (!url) throw new Error('上传失败，请重试')
    emit('update:modelValue', url)
  } catch (e) {
    error.value = e.message || '上传失败，请重试'
  } finally {
    uploading.value = false
  }
}
</script>
