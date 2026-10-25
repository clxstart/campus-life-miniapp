const { Controller, Renderer, ACTIONS } = require('../../utils/orange-pet-engine');

Component({
  properties: {
    auto: { type: Boolean, value: true, observer(value) { if (this._controller) this._controller.setAuto(value); } },
    paused: { type: Boolean, value: false, observer() { this._syncLoop(); } },
    speed: { type: Number, value: 1, observer(value) { if (this._controller) this._controller.setSpeed(value); } },
    // Empty by default. Set from the host page to trigger a one-shot action.
    action: { type: String, value: '', observer(value) { if (value) this.play(value); } },
    assetBase: { type: String, value: '/assets/pet-orange' },
    label: { type: String, value: '小橘，点击互动' }
  },
  data: { ready: false, error: '', state: 'idle' },
  lifetimes: {
    attached() { this._alive = true; this._visible = true; this._last = null; this._loading = false; },
    ready() { this._init(); },
    detached() { this._alive = false; this._stop(); this._renderer = null; this._canvas = null; }
  },
  pageLifetimes: {
    show() { this._visible = true; this._syncLoop(); },
    hide() { this._visible = false; this._stop(); },
    resize() { this.resize(); }
  },
  methods: {
    _init() {
      if (!this._alive || this._loading) return;
      this._loading = true;
      this.createSelectorQuery().select('#petCanvas').fields({ node: true, size: true }).exec(async result => {
        if (!this._alive) return;
        const box = result && result[0];
        if (!box || !box.node || !box.width || !box.height) return this._fail('画布未就绪，请重试');
        this._canvas = box.node; this._width = box.width; this._height = box.height;
        this._setSize();
        try {
          const images = {};
          await Promise.all(['idle', 'head', 'body', 'tail', 'skin', 'body-clean'].map(name => new Promise((resolve, reject) => {
            const img = box.node.createImage();
            img.onload = () => { images[name] = img; resolve(); };
            img.onerror = () => reject(new Error('素材加载失败：' + name));
            img.src = this.properties.assetBase.replace(/\/$/, '') + '/' + name + '.png';
          })));
          if (!this._alive) return;
          this._controller = new Controller({
            auto: this.properties.auto,
            onChange: action => {
              if (!this._alive) return;
              this.setData({ state: action }); this.triggerEvent('statechange', { action, name: ACTIONS[action].name });
            },
            onComplete: action => this.triggerEvent('actionend', { action })
          });
          this._controller.setSpeed(this.properties.speed);
          this._renderer = new Renderer(box.node.getContext('2d'), images);
          this._loading = false; this.setData({ ready: true, error: '' });
          this._draw();
          if (this.properties.action) this.play(this.properties.action);
          this.triggerEvent('ready'); this._syncLoop();
        } catch (error) { this._fail(error.message); }
      });
    },
    _setSize() {
      const info = typeof wx.getWindowInfo === 'function' ? wx.getWindowInfo() : wx.getSystemInfoSync();
      this._dpr = Math.min(info.pixelRatio || 1, 3);
      this._canvas.width = Math.round(this._width * this._dpr);
      this._canvas.height = Math.round(this._height * this._dpr);
      this._canvas.getContext('2d').scale(this._dpr, this._dpr);
    },
    resize() {
      if (!this._canvas || !this._alive) return;
      this.createSelectorQuery().select('#petCanvas').fields({ size: true }).exec(result => {
        const box = result && result[0];
        if (!this._alive || !box || !box.width || !box.height) return;
        this._width = box.width; this._height = box.height; this._setSize(); this._draw();
      });
    },
    _fail(message) {
      this._loading = false;
      if (this._alive) { this.setData({ error: message, ready: false }); this.triggerEvent('error', { message }); }
    },
    retry() { this._stop(); this.setData({ error: '', ready: false }); this._init(); },
    _draw() {
      if (this._renderer && this._controller) this._renderer.draw(this._controller.snapshot(), this._width, this._height);
    },
    _stop() {
      if (this._raf != null && this._canvas) this._canvas.cancelAnimationFrame(this._raf);
      this._raf = null; this._last = null;
    },
    _syncLoop() {
      if (!this._alive || !this._renderer) return;
      this._stop(); this._controller.running = !this.properties.paused;
      if (this.properties.paused || !this._visible) { this._draw(); return; }
      const frame = timestamp => {
        if (!this._alive || !this._visible || this.properties.paused) return;
        const dt = this._last == null ? 0 : (timestamp - this._last) / 1000;
        this._last = timestamp; const p = this._controller.update(dt);
        this._renderer.draw(p, this._width, this._height);
        this._raf = this._canvas.requestAnimationFrame(frame);
      };
      this._raf = this._canvas.requestAnimationFrame(frame);
    },
    play(action) {
      if (!Object.prototype.hasOwnProperty.call(ACTIONS, action)) { this.triggerEvent('error', { message: '不支持的动作：' + action }); return false; }
      if (!this._controller) return false;
      this._controller.play(action); this._draw(); return true;
    },
    restart() { if (this._controller) { this._controller.reset(); this._last = null; this._draw(); } },
    onTap() { this.play('happy'); this.triggerEvent('pettap', { action: 'happy' }); }
  }
});
