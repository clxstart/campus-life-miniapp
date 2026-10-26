<template>
  <div class="no-card-plain !p-0 overflow-hidden">
    <!-- 工具栏：搜索 + 右侧操作 -->
    <div v-if="searchable || $slots.actions" class="flex flex-wrap items-center gap-3 px-6 py-5 border-b border-stone-200">
      <div v-if="searchable" class="flex-1 min-w-[12rem] max-w-md">
        <input
          class="no-input !py-2.5"
          :value="keyword"
          :placeholder="placeholder"
          @input="$emit('update:keyword', $event.target.value)"
          @keyup.enter="$emit('search')"
        />
      </div>
      <div class="flex items-center gap-3 ml-auto">
        <button v-if="searchable" class="no-btn-ghost no-btn-sm" @click="$emit('search')">搜索</button>
        <slot name="actions" />
      </div>
    </div>

    <!-- 表格 -->
    <div class="overflow-x-auto">
      <table class="w-full text-sm">
        <thead>
          <tr class="bg-[#f3ece4] text-stone-600">
            <th v-for="col in columns" :key="col.key" class="text-left font-medium px-6 py-4 whitespace-nowrap" :class="col.class">
              {{ col.title }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading">
            <td :colspan="columns.length" class="px-6 py-16 text-center text-stone-500">正在加载…</td>
          </tr>
          <tr v-else-if="!rows.length">
            <td :colspan="columns.length" class="px-6 py-16 text-center text-stone-500">
              <span class="font-serif text-base text-stone-700">{{ emptyText }}</span>
            </td>
          </tr>
          <tr
            v-else
            v-for="(row, i) in rows"
            :key="row.id ?? i"
            class="border-t border-stone-200 transition-colors duration-300 hover:bg-stone-100/60"
          >
            <td v-for="col in columns" :key="col.key" class="px-6 py-4 align-middle" :class="col.class">
              <slot :name="col.key" :row="row" :index="i">{{ row[col.key] ?? '—' }}</slot>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 分页 -->
    <div v-if="total > size" class="flex flex-wrap items-center justify-between gap-4 px-6 py-5 border-t border-stone-200">
      <p class="text-xs text-stone-500">共 {{ total }} 条 · 第 {{ page }} / {{ Math.max(1, Math.ceil(total / size)) }} 页</p>
      <div class="flex items-center gap-2">
        <button class="no-btn-ghost no-btn-sm" :disabled="page <= 1" @click="$emit('page', page - 1)">上一页</button>
        <button class="no-btn-ghost no-btn-sm" :disabled="page >= Math.ceil(total / size)" @click="$emit('page', page + 1)">下一页</button>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  columns: { type: Array, required: true },
  rows: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  size: { type: Number, default: 20 },
  searchable: { type: Boolean, default: true },
  keyword: { type: String, default: '' },
  placeholder: { type: String, default: '搜索…' },
  emptyText: { type: String, default: '暂无数据' }
})
defineEmits(['update:keyword', 'search', 'page'])
</script>
