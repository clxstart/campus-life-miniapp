<template>
  <div class="space-y-6 organic-enter">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div>
        <h2 class="font-serif text-xl md:text-2xl text-stone-800">运营设置</h2>
        <p class="text-xs text-stone-500 mt-1">
          配置小程序里的咨询入口。改完在小程序「学习」页点那张卡片就能看到。
        </p>
      </div>
      <button class="no-btn-primary no-btn-sm" :disabled="submitting" @click="save">
        {{ submitting ? '保存中…' : '保存' }}
      </button>
    </div>

    <div v-if="error" class="border border-[#e6c9c2] bg-[#f7e6e2] rounded-[1.25rem] p-4 text-sm text-[#a4553f]">
      {{ error }}
    </div>

    <div v-if="loading" class="py-16 text-center text-stone-500 text-sm">读取中…</div>

    <div v-else class="grid gap-4 md:grid-cols-2">
      <div class="border border-stone-200 rounded-[1.5rem] p-5 md:col-span-2">
        <p class="font-medium text-stone-800 mb-1">咨询二维码</p>
        <p class="text-xs text-stone-500 mb-4">
          学生的微信扫码加你。建议用微信「我 → 二维码名片」的图，或者群二维码。PNG/JPG，5MB 以内。
        </p>
        <NoImageUpload v-model="form['contact.wechat_qr']" />
        <p v-if="!form['contact.wechat_qr']" class="mt-2 text-xs text-[#a4553f]">
          还没上传。没上传时小程序那边会显示「咨询入口尚未配置」，不会白屏。
        </p>
      </div>

      <div class="border border-stone-200 rounded-[1.5rem] p-5">
        <label class="block mb-2 text-sm font-medium text-stone-700">微信号</label>
        <input v-model.trim="form['contact.wechat_id']" class="no-input" maxlength="64"
               placeholder="如 xingxing_2026" />
        <p class="mt-1 text-xs text-stone-500">展示给学生，支持一键复制</p>
      </div>

      <div class="border border-stone-200 rounded-[1.5rem] p-5">
        <label class="block mb-2 text-sm font-medium text-stone-700">入口开关</label>
        <select v-model="form['contact.enabled']" class="no-input">
          <option value="1">开启（小程序显示入口）</option>
          <option value="0">收起（小程序不显示入口）</option>
        </select>
        <p class="mt-1 text-xs text-stone-500">临时不接咨询时可以收起，不用改代码</p>
      </div>

      <div class="border border-stone-200 rounded-[1.5rem] p-5">
        <label class="block mb-2 text-sm font-medium text-stone-700">页面标题</label>
        <input v-model.trim="form['contact.title']" class="no-input" maxlength="32"
               placeholder="论文指导咨询" />
      </div>

      <div class="border border-stone-200 rounded-[1.5rem] p-5">
        <label class="block mb-2 text-sm font-medium text-stone-700">二维码下方提示</label>
        <input v-model.trim="form['contact.hint']" class="no-input" maxlength="120"
               placeholder="扫码或搜索微信号添加，备注「年级+专业」通过更快" />
      </div>

      <div class="border border-stone-200 rounded-[1.5rem] p-5 md:col-span-2">
        <p class="font-medium text-stone-800 mb-3">小程序里的样子（论文指导卡片）</p>
        <div class="flex items-center gap-3 rounded-2xl bg-[#f7f3ea] p-4">
          <div class="w-10 h-10 rounded-xl bg-[#e3eefb] flex items-center justify-center text-[#3476f6]">✦</div>
          <div class="min-w-0">
            <p class="text-sm text-stone-800">论文指导，找有经验的人聊聊</p>
            <p class="text-xs text-stone-500 truncate">选题、框架、文献与答辩准备，扫码加微信详细说</p>
          </div>
          <span class="ml-auto text-stone-400">›</span>
        </div>
        <p class="mt-2 text-xs text-stone-400">卡片文案是写在小程序里的；这里配置的是点进去之后的页面。</p>
      </div>

      <!-- ===== 微信群 ===== -->
      <div class="md:col-span-2 pt-2">
        <h3 class="font-serif text-lg text-stone-800">微信群聊</h3>
        <p class="text-xs text-stone-500 mt-1">
          小程序「我的 → 微信群聊」点进去看到的就是这里的内容。
        </p>
      </div>

      <div class="border border-stone-200 rounded-[1.5rem] p-5 md:col-span-2">
        <p class="font-medium text-stone-800 mb-1">群二维码</p>
        <p class="text-xs text-stone-500 mb-4">
          用微信群里的「群二维码」。⚠️ 群二维码**有效期一般 7 天**，过期后学生扫码会提示失效，记得定期回来换新的。
        </p>
        <NoImageUpload v-model="form['group.wechat_qr']" />
        <p v-if="!form['group.wechat_qr']" class="mt-2 text-xs text-[#a4553f]">
          还没上传。没上传时小程序会显示「群二维码尚未配置」，不会白屏。
        </p>
      </div>

      <div class="border border-stone-200 rounded-[1.5rem] p-5">
        <label class="block mb-2 text-sm font-medium text-stone-700">群入口开关</label>
        <select v-model="form['group.enabled']" class="no-input">
          <option value="1">开启（我的页面显示入口）</option>
          <option value="0">收起（我的页面不显示）</option>
        </select>
        <p class="mt-1 text-xs text-stone-500">没群或群满时可以收起，不用改代码</p>
      </div>

      <div class="border border-stone-200 rounded-[1.5rem] p-5">
        <label class="block mb-2 text-sm font-medium text-stone-700">页面标题</label>
        <input v-model.trim="form['group.title']" class="no-input" maxlength="32"
               placeholder="校园微信群" />
      </div>

      <div class="border border-stone-200 rounded-[1.5rem] p-5 md:col-span-2">
        <label class="block mb-2 text-sm font-medium text-stone-700">二维码下方提示</label>
        <input v-model.trim="form['group.hint']" class="no-input" maxlength="120"
               placeholder="扫码进群，和同学一起交流；如二维码过期请找管理员要新的" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import NoImageUpload from '../components/NoImageUpload.vue'
import http from '../api'

const loading = ref(true)
const submitting = ref(false)
const error = ref('')
const form = reactive({
  'contact.wechat_qr': '',
  'contact.wechat_id': '',
  'contact.title': '论文指导咨询',
  'contact.hint': '',
  'contact.enabled': '1',
  'group.wechat_qr': '',
  'group.title': '校园微信群',
  'group.hint': '',
  'group.enabled': '1'
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const rows = (await http.get('/admin/app-config')) || []
    for (const row of rows) {
      const key = row.configKey
      if (key in form) form[key] = row.configValue ?? ''
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function save() {
  submitting.value = true
  error.value = ''
  try {
    await http.put('/admin/app-config', { ...form })
    await load()
    alert('已保存，小程序刷新后生效')
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

