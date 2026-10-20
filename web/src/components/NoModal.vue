<template>
  <Teleport to="body">
    <div v-if="open" class="fixed inset-0 z-50 flex items-center justify-center px-4 py-8" role="dialog" aria-modal="true">
      <!-- 遮罩：暖色半透明，不用纯黑 -->
      <div class="absolute inset-0 bg-stone-800/40 backdrop-blur-[2px]" @click="closeOnMask && $emit('close')"></div>

      <div class="relative w-full max-w-lg no-card-plain organic-enter max-h-full overflow-y-auto">
        <div class="flex items-start justify-between gap-4 mb-6">
          <div>
            <h2 class="font-serif text-xl md:text-2xl text-stone-800">{{ title }}</h2>
            <p v-if="hint" class="text-xs text-stone-500 mt-1">{{ hint }}</p>
          </div>
          <button class="no-btn-ghost no-btn-sm" @click="$emit('close')">关闭</button>
        </div>

        <slot />

        <div v-if="!hideFooter" class="flex items-center justify-end gap-3 mt-8">
          <button class="no-btn-ghost" @click="$emit('close')">取消</button>
          <button class="no-btn-primary" :disabled="submitting" @click="$emit('confirm')">
            {{ submitting ? '保存中…' : confirmText }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '' },
  hint: { type: String, default: '' },
  confirmText: { type: String, default: '保存' },
  submitting: { type: Boolean, default: false },
  hideFooter: { type: Boolean, default: false },
  closeOnMask: { type: Boolean, default: true }
})
defineEmits(['close', 'confirm'])
</script>
