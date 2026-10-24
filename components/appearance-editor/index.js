/**
 * appearance-editor —— 捏脸选项面板
 *
 * 每个选项都用 pet-avatar 渲染真实预览（同外观、只替换当前项），
 * 让用户看到真实效果而不是抽象图标。
 */
const {
  FURS, PATTERNS, EYES, MOUTHS, ACCESSORIES, DEFAULT_APPEARANCE
} = require('../../utils/pet-appearance')

const CATEGORIES = [
  { key: 'fur', name: '毛色' },
  { key: 'pattern', name: '花纹' },
  { key: 'eyes', name: '眼睛' },
  { key: 'mouth', name: '嘴巴' },
  { key: 'accessory', name: '配饰' }
]

const SOURCES = {
  fur: FURS,
  pattern: PATTERNS,
  eyes: EYES,
  mouth: MOUTHS,
  accessory: ACCESSORIES
}

Component({
  options: { addGlobalClass: true },
  properties: {
    appearance: { type: Object, value: {} }
  },
  data: {
    categories: CATEGORIES,
    activeTab: 'fur',
    currentOptions: []
  },
  lifetimes: {
    attached() { this._buildOptions() }
  },
  observers: {
    'appearance.**': function () { this._buildOptions() },
    activeTab() { this._buildOptions() }
  },
  methods: {
    onTabChange(e) {
      this.setData({ activeTab: e.detail.value })
    },

    /** 构造当前分类下的选项，并为每项生成预览外观 */
    _buildOptions() {
      const key = this.data.activeTab
      const source = SOURCES[key] || []
      const base = { ...DEFAULT_APPEARANCE, ...(this.data.appearance || {}) }
      const options = source.map(item => ({
        id: item.id,
        name: item.name,
        // 只有当前这一项被替换，其余保持用户当前选择
        preview: { ...base, [key]: item.id }
      }))
      this.setData({ currentOptions: options })
    },

    /** 选中某部件 → 通知父级 */
    onPick(e) {
      const id = e.currentTarget.dataset.id
      const key = this.data.activeTab
      if (!id || !key) return
      this.triggerEvent('pick', { category: key, id })
    }
  }
})
