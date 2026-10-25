/* 小橘 · 午后窗边。保留原图橘猫、奶油胸毛与粉鼻，图层由尾→身体→头→表情→光点。
 * 脚底始终贴住地面；跳跃先蓄力再腾空；头在颈根旋转，尾在尾根摆动。
 * 所有动作由同一时间轴驱动，进入/退出使用零速度平滑曲线，禁止图片轮播。
 * Pure Canvas 2D: shared by WeChat Mini Program, browser preview and frame exporter.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.OrangePet = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const TAU = Math.PI * 2;
  const ACTIONS = Object.freeze({
    idle: { name: '待机眨眼', duration: 4 },
    happy: { name: '开心摇尾', duration: 4 },
    sad: { name: '委屈轻晃', duration: 4 },
    sleepy: { name: '困倦哈欠', duration: 4 },
    celebrate: { name: '雀跃庆祝', duration: 4 }
  });
  const ORDER = Object.keys(ACTIONS);
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  const smooth = (v) => { v = clamp(v, 0, 1); return clamp(v * v * v * (v * (v * 6 - 15) + 10), 0, 1); };
  const envelope = (u) => smooth(u / .22) * smooth((1 - u) / .22);
  const positiveMod = (x, n) => ((x % n) + n) % n;
  const TOTAL = ORDER.reduce((n, k) => n + ACTIONS[k].duration, 0);

  // Two-bone inverse kinematics, with reach clamped below full extension.
  function solveIK(sx, sy, tx, ty, upper, lower, bend) {
    const dx = tx - sx, dy = ty - sy, raw = Math.hypot(dx, dy);
    const d = clamp(raw, Math.abs(upper - lower) + .01, upper + lower - .01);
    const direction = Math.atan2(dy, dx);
    const angle = direction + bend * Math.acos(clamp((upper * upper + d * d - lower * lower) / (2 * upper * d), -1, 1));
    return { x: sx + Math.cos(angle) * upper, y: sy + Math.sin(angle) * upper,
      tx: sx + Math.cos(direction) * d, ty: sy + Math.sin(direction) * d };
  }

  function assertAction(action) {
    if (!Object.prototype.hasOwnProperty.call(ACTIONS, action)) throw new Error('Unknown pet action: ' + action);
  }
  function timeline(time) {
    let t = positiveMod(Number.isFinite(time) ? time : 0, TOTAL);
    for (let i = 0; i < ORDER.length; i++) {
      const action = ORDER[i], d = ACTIONS[action].duration;
      if (t < d || i === ORDER.length - 1) return { action, progress: clamp(t / d, 0, 1) };
      t -= d;
    }
  }
  // Blinks have a smooth close/hold/open phase; the first frame stays fully open.
  function blink(time) {
    const t = positiveMod(time, 4);
    const pulse = (start, end) => {
      if (t < start || t > end) return 0;
      const u = (t - start) / (end - start);
      return smooth(u / .4) * smooth((1 - u) / .4);
    };
    return Math.max(pulse(1.15, 1.51), pulse(3.05, 3.35));
  }

  function pose(action, progress, time) {
    assertAction(action);
    const u = clamp(Number.isFinite(progress) ? progress : 0, 0, 1);
    const t = Number.isFinite(time) ? time : 0;
    const e = action === 'idle' ? 0 : envelope(u);
    const p = {
      action, progress: u, time: t, energy: e,
      breath: Math.sin(t * TAU / 4) * .008,
      head: 0, lean: 0, squash: 0, jump: 0,
      tail: Math.sin(t * TAU / 4) * .055,
      eyes: 1 - blink(t), smile: 0, worry: 0, yawn: 0, stars: 0
    };
    if (action === 'happy') {
      p.head = Math.sin(u * TAU * 2) * .065 * e;
      p.tail += Math.sin(u * TAU * 3) * .24 * e;
      p.squash = Math.sin(u * TAU * 2) * .022 * e;
      p.smile = e; p.eyes *= 1 - e;
    } else if (action === 'sad') {
      p.head = -.09 * e + Math.sin(u * TAU * 2) * .02 * e;
      p.lean = Math.sin(u * TAU * 3) * .012 * e;
      p.worry = e; p.tail *= 1 - .75 * e;
      p.eyes *= 1 - .2 * e;
    } else if (action === 'sleepy') {
      p.head = .09 * e; p.squash = .024 * e;
      p.yawn = e * Math.pow(Math.sin(Math.PI * u), 2);
      p.eyes *= 1 - .94 * e; p.tail *= 1 - .8 * e;
    } else if (action === 'celebrate') {
      // Two small hops. Each launch and landing has zero vertical velocity.
      const hop = (a, b, height) => {
        const h = (u - a) / (b - a);
        return h > 0 && h < 1 ? height * Math.pow(Math.sin(Math.PI * h), 2) : 0;
      };
      p.jump = hop(.22, .58, 39) + hop(.62, .91, 24);
      p.squash = .055 * e * Math.cos(u * TAU * 3);
      p.head = Math.sin(u * TAU * 2) * .06 * e;
      p.tail += Math.sin(u * TAU * 3) * .18 * e;
      p.stars = e; p.smile = .9 * e;
    }
    return p;
  }

  class Controller {
    constructor(options) {
      options = options || {};
      this.auto = options.auto !== false; this.speed = 1; this.time = 0; this.cycleTime = 0;
      this.running = true; this.pending = null; this.active = null;
      this.onChange = options.onChange || function () {};
      this.onComplete = options.onComplete || function () {};
      this._lastAction = null;
    }
    play(action) {
      assertAction(action);
      // Last request wins. A running action returns to neutral before the next.
      if (this.active || (this.auto && timeline(this.cycleTime).action !== 'idle')) this.pending = action;
      else this.active = { action, elapsed: 0 };
      return this;
    }
    setSpeed(value) {
      value = Number(value);
      this.speed = Number.isFinite(value) ? clamp(value, .25, 2) : 1;
    }
    setAuto(value) {
      value = Boolean(value);
      if (this.auto && !value && !this.active) {
        const state = timeline(this.cycleTime);
        if (state.action !== 'idle') this.active = { action: state.action, elapsed: state.progress * ACTIONS[state.action].duration };
      }
      if (!this.auto && value) this.cycleTime = 0;
      this.auto = value;
    }
    reset() {
      this.time = 0; this.cycleTime = 0; this.active = null; this.pending = null; this._lastAction = null;
    }
    _advance(dt) {
      const a = this.active;
      if (!a) {
        if (this.auto && this.pending) {
          const state = timeline(this.cycleTime);
          const remaining = ACTIONS[state.action].duration * (1 - state.progress);
          const consumed = Math.min(dt, remaining);
          this.time += consumed; this.cycleTime += consumed;
          if (consumed + 1e-9 >= remaining) {
            this.active = { action: this.pending, elapsed: 0 }; this.pending = null;
            this.cycleTime = 0;
            if (dt - consumed > 1e-9) this._advance(dt - consumed);
          }
        } else { this.time += dt; if (this.auto) this.cycleTime += dt; }
        return;
      }
      const remaining = ACTIONS[a.action].duration - a.elapsed;
      const consumed = Math.min(dt, remaining);
      a.elapsed += consumed; this.time += consumed;
      if (a.elapsed + 1e-9 >= ACTIONS[a.action].duration) {
        const completed = a.action;
        this.active = this.pending ? { action: this.pending, elapsed: 0 } : null;
        this.pending = null; this.cycleTime = 0;
        this.onComplete(completed);
        const rest = dt - consumed;
        if (rest > 1e-9) this._advance(rest);
      }
    }
    update(dt) {
      // Clamp a resumed background tab to avoid skipping an entire action.
      const safe = Number.isFinite(dt) ? clamp(dt, 0, .1) : 0;
      if (this.running) this._advance(safe * this.speed);
      return this.snapshot();
    }
    snapshot() {
      let state;
      if (this.active) state = { action: this.active.action, progress: this.active.elapsed / ACTIONS[this.active.action].duration };
      else state = this.auto ? timeline(this.cycleTime) : { action: 'idle', progress: positiveMod(this.time, 4) / 4 };
      // Manual actions override an idle base. Auto is restored only after explicit action completes.
      if (state.action !== this._lastAction) {
        this._lastAction = state.action; this.onChange(state.action);
      }
      return pose(state.action, state.progress, this.time);
    }
  }

  function ellipse(ctx, x, y, rx, ry, fill, stroke) {
    ctx.beginPath(); ctx.ellipse(x, y, Math.max(.001, rx), Math.max(.001, ry), 0, 0, TAU);
    if (fill) { ctx.fillStyle = fill; ctx.fill(); }
    if (stroke) { ctx.strokeStyle = stroke; ctx.stroke(); }
  }
  function curve(ctx, points, color, width) {
    ctx.beginPath(); ctx.moveTo(points[0], points[1]);
    ctx.bezierCurveTo.apply(ctx, points.slice(2));
    ctx.strokeStyle = color; ctx.lineWidth = width || 2; ctx.lineCap = 'round'; ctx.stroke();
  }
  function star(ctx, x, y, r, phase, color) {
    ctx.save(); ctx.translate(x, y); ctx.rotate(phase);
    ctx.beginPath();
    for (let i = 0; i < 8; i++) {
      const a = i * Math.PI / 4 - Math.PI / 2, rr = i % 2 ? r * .32 : r;
      if (i) ctx.lineTo(Math.cos(a) * rr, Math.sin(a) * rr);
      else ctx.moveTo(Math.cos(a) * rr, Math.sin(a) * rr);
    }
    ctx.closePath(); ctx.fillStyle = color; ctx.fill(); ctx.restore();
  }
  class Renderer {
    constructor(ctx, images) { this.ctx = ctx; this.images = images; }
    draw(p, width, height, options) {
      const c = this.ctx; options = options || {};
      c.save(); c.clearRect(0, 0, width, height);
      const scale = Math.min(width / 400, height / 440);
      c.translate(width / 2, height / 2); c.scale(scale, scale); c.translate(-200, -220);
      // Contact shadow remains on the floor while the pet leaves it.
      if (options.shadow !== false) {
        const softness = c.createRadialGradient(200, 375, 0, 200, 375, 103);
        softness.addColorStop(0, 'rgba(132,91,53,' + (.16 - p.jump * .0017) + ')');
        softness.addColorStop(1, 'rgba(132,91,53,0)');
        ellipse(c, 200, 375, 101 - p.jump * .4, 15 - p.jump * .08, softness);
      }
      c.save(); c.translate(200, 369 - p.jump); c.scale(1.35, 1.35); c.rotate(p.lean);
      c.translate(-145, -250);
      const sy = 1 + p.breath - p.squash, sx = 1 + p.squash * .55;
      // Tail is anchored behind the body, never behind the whole canvas.
      c.save(); c.translate(195, 221); c.rotate(p.tail); c.translate(-195, -221);
      c.drawImage(this.images.tail, 0, 0); c.restore();
      c.save(); c.translate(145, 250); c.scale(sx, sy); c.translate(-145, -250);
      const articulated = ['sleepy', 'celebrate'].indexOf(p.action) >= 0;
      const rigWeight = articulated ? smooth(p.energy / .25) : 0;
      if (rigWeight > 0) {
        c.save(); c.globalAlpha = rigWeight;
        // Seated belly supports the body between the two original hind paws.
        const belly = c.createLinearGradient(0, 185, 0, 244);
        belly.addColorStop(0, '#ffbf66'); belly.addColorStop(1, '#f5a34f');
        c.beginPath(); c.moveTo(109, 186); c.bezierCurveTo(99, 205, 109, 239, 124, 243);
        c.quadraticCurveTo(144, 250, 165, 242); c.bezierCurveTo(181, 238, 187, 205, 178, 186);
        c.closePath(); c.fillStyle = belly; c.fill(); c.strokeStyle = '#bd783c'; c.lineWidth = 1.5; c.stroke();
        c.beginPath(); c.moveTo(112, 178); c.lineTo(175, 178);
        c.lineTo(171, 192); c.lineTo(167, 189); c.lineTo(165, 200); c.lineTo(160, 196);
        c.lineTo(157, 208); c.lineTo(152, 203); c.lineTo(144, 217); c.lineTo(138, 207);
        c.lineTo(133, 212); c.lineTo(131, 201); c.lineTo(125, 205); c.lineTo(123, 195);
        c.lineTo(117, 198); c.lineTo(118, 187); c.closePath();
        c.fillStyle = '#fff0db'; c.fill();
        c.drawImage(this.images['body-clean'], 0, 0); c.restore();
      }
      if (rigWeight < 1) { c.save(); c.globalAlpha = 1 - rigWeight; c.drawImage(this.images.body, 0, 0); c.restore(); }
      c.save(); c.translate(142, 163); c.rotate(p.head); c.translate(-142, -163);
      c.drawImage(this.images.head, 0, 0);
      this._face(p);
      c.restore();
      if (rigWeight > 0) { c.save(); c.globalAlpha = rigWeight; this._paws(p); c.restore(); }
      c.restore(); c.restore();
      this._effects(p);
      c.restore();
    }
    _paws(p) {
      const c = this.ctx, motion = smooth((p.energy - .25) / .75);
      const targets = [[124, 239], [160, 239]];
      if (p.action === 'sad') targets[0] = [139, 212];
      if (p.action === 'sleepy') {
        // The mouth follows the head rotation about the same neck anchor.
        const a = p.head, dx = 0, dy = -18;
        targets[0] = [142 + dx * Math.cos(a) - dy * Math.sin(a), 163 + dx * Math.sin(a) + dy * Math.cos(a)];
      }
      if (p.action === 'celebrate') { targets[0] = [84, 150]; targets[1] = [195, 173]; }
      for (let i = 0; i < 2; i++) {
        const sx = i ? 166 : 119, sy = 185, base = [i ? 160 : 124, 239];
        const tx = base[0] + (targets[i][0] - base[0]) * motion;
        const ty = base[1] + (targets[i][1] - base[1]) * motion;
        const j = solveIK(sx, sy, tx, ty, 31, 31, i ? -1 : 1);
        const fur = c.createLinearGradient(sx - 14, sy, tx + 12, ty);
        fur.addColorStop(0, i ? '#ed9f4b' : '#f7b256');fur.addColorStop(.5, '#ffc879');fur.addColorStop(1, '#f7ae58');
        const bone = (ax,ay,bx,by,width,color) => {
          c.beginPath();c.moveTo(ax,ay);c.lineTo(bx,by);c.lineWidth=width;
          c.lineCap='round';c.lineJoin='round';c.strokeStyle=color;c.stroke();
        };
        // Paint all outlines first, then continuous fur: no rectangular cuffs
        // or seams at the elbow. Upper segment tapers into the smaller wrist.
        bone(sx,sy,j.x,j.y,27,'#b97639');bone(j.x,j.y,j.tx,j.ty,21,'#b97639');
        bone(sx,sy,j.x,j.y,24,fur);bone(j.x,j.y,j.tx,j.ty,18,fur);
        ellipse(c,j.x,j.y,10,10,fur);
        curve(c,[sx-3,sy-4,(sx+j.x)/2-4,(sy+j.y)/2-3,j.x-3,j.y-3,j.x-2,j.y-1],'#ffd395',1.3);
        c.save();c.translate((sx+j.x)/2,(sy+j.y)/2);c.rotate(Math.atan2(j.y-sy,j.x-sx));
        ellipse(c, 0, 0, 3.2, 10, '#dc893b');c.restore();
        c.save();c.translate(j.tx,j.ty);c.rotate(Math.atan2(j.ty-j.y,j.tx-j.x)-Math.PI/2);
        ellipse(c, 0, 0, 12, 10, '#fff0dd', '#b97639');
        curve(c, [-5, 3, -4, 5, -4, 7, -4, 8], '#d8b18b', .9);
        curve(c, [4, 3, 5, 5, 5, 7, 5, 8], '#d8b18b', .9);
        if (p.action === 'celebrate') {
          c.globalAlpha *= motion;
          ellipse(c, 0, 1, 4.5, 3.6, '#eda08d');
          [-6,0,6].forEach((x,n)=>ellipse(c,x,-4-(n===1?1:0),2.3,2.8,'#efa591'));
        }
        c.restore();
      }
    }
    _eye(x, open, side, p) {
      const c = this.ctx, y = 117;
      // Eye patches use the source fur/cream split. At open=1 no patch is painted:
      // the first frame is the original face, rather than a redrawn substitute.
      if (open >= .999 && p.worry < .001 && p.stars < .001) return;
      c.save();
      c.beginPath(); c.ellipse(x, y, 26, 29, 0, 0, TAU); c.clip();
      c.drawImage(this.images.skin, 0, 0);
      if (open > .1) {
        c.save(); c.translate(x, y); c.rotate(side * p.worry * .1); c.scale(1, open); c.translate(-x, -y);
        c.drawImage(this.images.idle, x - 24, y - 28, 48, 56, x - 24, y - 28, 48, 56);
        c.restore();
      }
      if (open < .75) {
        c.globalAlpha = clamp((.8 - open) / .5, 0, 1);
        if (p.smile > .4) curve(c, [x - 15, y + 2, x - 9, y - 11, x + 9, y - 11, x + 15, y + 2], '#704124', 5);
        else curve(c, [x - 15, y, x - 7, y + 6, x + 7, y + 6, x + 15, y], '#704124', 3.2);
      }
      c.restore();
      if (p.stars > .01 && open > .6) {
        c.save(); c.globalAlpha = p.stars;
        star(c, x + 1, y - 1, 9, .08, '#ffed96'); c.restore();
      }
    }
    _face(p) {
      const c = this.ctx;
      this._eye(107, p.eyes, -1, p); this._eye(177, p.eyes, 1, p);
      if (p.smile > .015 || p.yawn > .015 || p.worry > .015) {
        const alpha = Math.max(p.smile, p.yawn, p.worry);
        c.save(); c.globalAlpha = alpha;
        // Covers only the original mouth, leaving the pink nose untouched.
        ellipse(c, 142, 138, 15, 10, '#fff0de');
        if (p.yawn > .05) {
          ellipse(c, 142, 141, 5 + p.yawn * 3, 3 + p.yawn * 10, '#9d4d35');
          ellipse(c, 142, 147, 5, 3, '#ef967e');
        } else if (p.smile > .05) {
          c.beginPath(); c.moveTo(132, 136); c.quadraticCurveTo(142, 142, 152, 136);
          c.bezierCurveTo(151, 156, 133, 156, 132, 136);
          c.fillStyle = '#ae5135'; c.fill(); ellipse(c, 142, 147, 6, 4, '#ff9e85');
        } else {
          ellipse(c, 142, 141, 3.5, 4.5, '#b36b46');
        }
        c.restore();
      }
      if (p.worry > .01) {
        c.save(); c.globalAlpha = p.worry;
        curve(c, [99, 88, 104, 89, 109, 84, 114, 82], '#a65d2e', 2.5);
        curve(c, [170, 82, 175, 84, 180, 89, 186, 88], '#a65d2e', 2.5);
        c.restore();
      }
    }
    _effects(p) {
      const c = this.ctx, t = p.time;
      c.save(); c.globalAlpha = p.energy;
      if (p.action === 'happy') {
        [0, 1, 2].forEach(i => star(c, 75 + i * 125, 25 + Math.sin(t * 3 + i) * 5,
          6 + i % 2 * 3, Math.sin(t + i) * .2, '#f5bf51'));
      } else if (p.action === 'sad') {
        for (let i = 0; i < 3; i++) {
          const y = 270 + i * 13;
          curve(c, [61, y, 69, y - 8, 76, y + 9, 85, y], '#dc8a63', 3);
        }
      } else if (p.action === 'sleepy') {
        c.fillStyle = '#859ca7'; c.font = 'bold 22px sans-serif';
        for (let i = 0; i < 3; i++) {
          const a = positiveMod(t * .45 + i / 3, 1);
          c.globalAlpha = p.energy * Math.sin(Math.PI * a);
          c.fillText('z', 325 + a * 27, 130 - a * 72);
        }
      } else if (p.action === 'celebrate') {
        const colors = ['#e99b9f', '#f3be55', '#8bbdb3'];
        for (let i = 0; i < 14; i++) {
          const a = i * 2.399 + t * .25, r = 116 + 14 * Math.sin(t * 2 + i);
          const x = 200 + Math.cos(a) * r, y = 200 + Math.sin(a) * r * .95;
          if (i % 3 === 0) star(c, x, y, 6, a, colors[i % 3]);
          else {
            c.save(); c.translate(x, y); c.rotate(a + t);
            c.fillStyle = colors[i % 3]; c.fillRect(-2, -5, 4, 10); c.restore();
          }
        }
      }
      c.restore();
    }
  }

  return { ACTIONS, ORDER, TOTAL, timeline, pose, blink, smooth, envelope, solveIK, Controller, Renderer };
});
