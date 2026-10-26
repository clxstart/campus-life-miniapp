Component({
  options: { addGlobalClass: true },
  properties: {
    appearance: { type: Object, value: {} },
    motion: { type: String, value: 'idle' },
    size: { type: Number, value: 220 },
    reduceMotion: { type: Boolean, value: false },
    interactive: { type: Boolean, value: true },
    compact: { type: Boolean, value: false },
    bubble: { type: Boolean, value: false }
  },
  methods: {
    onPetTap(e) {
      this.triggerEvent('pettap', e.detail)
    }
  }
})
