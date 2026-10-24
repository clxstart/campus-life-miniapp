/**
 * wardrobe-item —— 装扮物品卡片
 *
 * 三态：locked（未解锁/可兑换）、owned（已拥有未装备）、equipped（已装备）
 * 缩略图用 pet-avatar 渲染「穿上这件」的真实效果，不是抽象图标。
 */
const STATE_LABEL = {
  locked: '未解锁',
  owned: '已拥有',
  equipped: '已装备'
}

Component({
  options: { addGlobalClass: true },
  properties: {
    item: { type: Object, value: {} },
    /** 宠物当前外观，用于生成「穿上这件」的预览 */
    appearance: { type: Object, value: {} }
  },
  data: {
    stateLabel: '',
    previewAppearance: {}
  },
  observers: {
    'item.**, appearance.**': function (item, appearance) {
      if (!item) return
      this.setData({
        stateLabel: STATE_LABEL[item.state] || '',
        previewAppearance: {
          ...(appearance || {}),
          accessory: item.partId || 'none'
        }
      })
    }
  },
  methods: {
    onBuy() { this.triggerEvent('buy', { item: this.data.item }) },
    onEquip() { this.triggerEvent('equip', { item: this.data.item }) },
    onUnequip() { this.triggerEvent('unequip', { item: this.data.item }) }
  }
})
