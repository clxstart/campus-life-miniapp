/**
 * 宠物绘制引擎 —— 纯 Canvas 2D 绘制，无 DOM 依赖
 *
 * 逻辑坐标系固定为 200x200，组件按实际显示尺寸做缩放，
 * 这样不同屏幕、不同 dpr 下比例一致，命中检测也只需换算一次。
 */
const { findFur } = require('./pet-appearance')

/** 逻辑画布尺寸 */
const LOGICAL = 200

/** 动作 → 参数 */
const MOTIONS = {
  idle:    { bob: 3,   speed: 0.0016, tail: 0.0012 },
  happy:   { bob: 7,   speed: 0.0042, tail: 0.004 },
  sleep:   { bob: 1.2, speed: 0.0007, tail: 0.0003 },
  eat:     { bob: 2.5, speed: 0.003,  tail: 0.002 },
  cheer:   { bob: 9,   speed: 0.005,  tail: 0.005 },
  walk:    { bob: 4,   speed: 0.003,  tail: 0.0025 }
}

function motionOf(name) { return MOTIONS[name] || MOTIONS.idle }

/** 圆角矩形路径 */
function roundRect(ctx, x, y, w, h, r) {
  const rr = Math.min(r, w / 2, h / 2)
  ctx.beginPath()
  ctx.moveTo(x + rr, y)
  ctx.arcTo(x + w, y, x + w, y + h, rr)
  ctx.arcTo(x + w, y + h, x, y + h, rr)
  ctx.arcTo(x, y + h, x, y, rr)
  ctx.arcTo(x, y, x + w, y, rr)
  ctx.closePath()
}

/** 椭圆填充 */
function ellipse(ctx, cx, cy, rx, ry, color) {
  ctx.beginPath()
  ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2)
  ctx.fillStyle = color
  ctx.fill()
}

/**
 * 绘制宠物
 * @param {CanvasRenderingContext2D} ctx
 * @param {object} opt
 *   appearance  外观部件编号
 *   motion      动作名
 *   t           时间（毫秒），驱动循环动画
 *   blink       是否闭眼（眨眼）
 *   scale       整体缩放（逻辑坐标 → 画布像素）
 */
function drawPet(ctx, opt) {
  const ap = opt.appearance || {}
  const motion = motionOf(opt.motion)
  const t = opt.t || 0
  const scale = opt.scale || 1

  const fur = findFur(ap.fur)
  const bob = opt.reduceMotion ? 0 : Math.sin(t * motion.speed) * motion.bob
  const tailPhase = opt.reduceMotion ? 0 : Math.sin(t * motion.tail)

  ctx.save()
  ctx.scale(scale, scale)
  // 统一线宽：细线，不用厚黑描边
  ctx.lineJoin = 'round'
  ctx.lineCap = 'round'

  // ===== 接触阴影（先画，压在宠物下方） =====
  ctx.save()
  ctx.globalAlpha = 0.16
  ellipse(ctx, 100, 176 + bob * 0.25, 46 + bob * 0.4, 9, '#203D34')
  ctx.restore()

  // ===== 尾巴（在身体之后，露出侧面） =====
  ctx.save()
  ctx.translate(146, 142 + bob * 0.6)
  ctx.rotate(-0.35 + tailPhase * 0.28)
  ctx.beginPath()
  ctx.moveTo(0, 0)
  ctx.quadraticCurveTo(26, -4, 30, -30)
  ctx.lineWidth = 15
  ctx.strokeStyle = fur.shade
  ctx.stroke()
  ctx.lineWidth = 9
  ctx.strokeStyle = fur.body
  ctx.stroke()
  ctx.restore()

  // ===== 身体（水滴形，坐姿） =====
  ctx.save()
  ctx.translate(100, 118 + bob)
  ctx.beginPath()
  ctx.moveTo(0, -46)
  ctx.bezierCurveTo(38, -40, 46, 6, 40, 30)
  ctx.bezierCurveTo(34, 52, -34, 52, -40, 30)
  ctx.bezierCurveTo(-46, 6, -38, -40, 0, -46)
  ctx.closePath()
  ctx.fillStyle = fur.body
  ctx.fill()
  // 浅色轮廓：保证雪白/奶油白在奶油色背景上仍能看清边界（细线，不厚黑描边）
  ctx.strokeStyle = fur.shade
  ctx.lineWidth = 1.6
  ctx.stroke()
  // 浅色肚皮
  ctx.save()
  ctx.clip()
  ellipse(ctx, 0, 30, 26, 30, fur.belly)
  ctx.restore()
  ctx.restore()

  // ===== 花纹 =====
  if (ap.pattern && ap.pattern !== 'none') {
    ctx.save()
    ctx.translate(100, 118 + bob)
    ctx.beginPath()
    ctx.moveTo(0, -46)
    ctx.bezierCurveTo(38, -40, 46, 6, 40, 30)
    ctx.bezierCurveTo(34, 52, -34, 52, -40, 30)
    ctx.bezierCurveTo(-46, 6, -38, -40, 0, -46)
    ctx.closePath()
    ctx.clip()
    ctx.globalAlpha = 0.5
    if (ap.pattern === 'patch') {
      ellipse(ctx, -16, -6, 20, 17, fur.shade)
      ellipse(ctx, 20, 26, 14, 12, fur.shade)
    } else if (ap.pattern === 'stripe') {
      ctx.strokeStyle = fur.shade
      ctx.lineWidth = 5
      for (let i = -2; i <= 2; i++) {
        ctx.beginPath()
        ctx.moveTo(i * 13 - 4, -34)
        ctx.quadraticCurveTo(i * 13 + 3, -14, i * 13 - 2, 2)
        ctx.stroke()
      }
    } else if (ap.pattern === 'star') {
      ellipse(ctx, 0, -18, 15, 13, '#FFFFFF')
      ellipse(ctx, 26, 14, 9, 8, '#FFFFFF')
      ellipse(ctx, -26, 16, 8, 7, '#FFFFFF')
    }
    ctx.restore()
  }

  // ===== 前爪 =====
  // 身体在本地坐标下底部约到 y=+52，爪子要画在 y≈58 才露得出来；
  // 同时左右拉开到 ±22，避免被身体中段吞掉。
  ctx.save()
  ctx.translate(100, 118 + bob)
  for (const dir of [-1, 1]) {
    ellipse(ctx, dir * 22, 58, 14, 9, fur.shade)
    ellipse(ctx, dir * 22, 57, 12.5, 7.5, fur.belly)
    // 脚趾缝
    ctx.beginPath()
    ctx.moveTo(dir * 22 - 3.2, 53.5)
    ctx.lineTo(dir * 22 - 3.2, 59)
    ctx.moveTo(dir * 22 + 3.2, 53.5)
    ctx.lineTo(dir * 22 + 3.2, 59)
    ctx.strokeStyle = fur.shade
    ctx.lineWidth = 1.4
    ctx.stroke()
  }
  ctx.restore()

  // ===== 头 =====
  const headY = 70 + bob
  ctx.save()
  ctx.translate(100, headY)

  // 耳朵（外层）
  for (const dir of [-1, 1]) {
    ctx.beginPath()
    ctx.moveTo(dir * 30, -26)
    ctx.lineTo(dir * 40, -52)
    ctx.lineTo(dir * 12, -40)
    ctx.closePath()
    ctx.fillStyle = fur.body
    ctx.fill()
    ctx.strokeStyle = fur.shade
    ctx.lineWidth = 1.6
    ctx.stroke()
    // 耳内
    ctx.beginPath()
    ctx.moveTo(dir * 30, -30)
    ctx.lineTo(dir * 36, -46)
    ctx.lineTo(dir * 18, -39)
    ctx.closePath()
    ctx.fillStyle = fur.shade
    ctx.fill()
  }

  // 头部主体
  // 头部主体（填充 + 细描边，浅色毛才不糊在背景上）
  ctx.beginPath()
  ctx.ellipse(0, 0, 44, 40, 0, 0, Math.PI * 2)
  ctx.fillStyle = fur.body
  ctx.fill()
  ctx.strokeStyle = fur.shade
  ctx.lineWidth = 1.6
  ctx.stroke()

  // ===== 眼睛 =====
  const eyeStyle = ap.eyes || 'round'
  const blinkNow = opt.blink && eyeStyle !== 'closed'
  for (const dir of [-1, 1]) {
    const ex = dir * 16
    if (eyeStyle === 'closed' || blinkNow) {
      // 闭眼笑：下弯弧
      ctx.beginPath()
      ctx.moveTo(ex - 8, 0)
      ctx.quadraticCurveTo(ex, 7, ex + 8, 0)
      ctx.lineWidth = 3
      ctx.strokeStyle = '#203D34'
      ctx.stroke()
    } else if (eyeStyle === 'sleepy') {
      // 半眯：上弧压住圆
      ellipse(ctx, ex, 1, 7, 7, '#203D34')
      ctx.beginPath()
      ctx.rect(ex - 9, -8, 18, 7)
      ctx.fillStyle = fur.body
      ctx.fill()
      ctx.beginPath()
      ctx.moveTo(ex - 8, -1)
      ctx.lineTo(ex + 8, -1)
      ctx.lineWidth = 2.5
      ctx.strokeStyle = '#203D34'
      ctx.stroke()
    } else {
      // 圆豆眼 / 星星眼
      ellipse(ctx, ex, 1, 7.5, 8, '#203D34')
      ellipse(ctx, ex - 2.4, -1.6, 2.4, 2.6, '#FFFFFF')
      if (eyeStyle === 'sparkle') {
        ctx.save()
        ctx.globalAlpha = 0.9
        ellipse(ctx, ex + 3, 3.6, 1.6, 1.8, '#FFFFFF')
        ctx.restore()
      }
    }
  }

  // ===== 鼻子 + 嘴巴 =====
  ctx.beginPath()
  ctx.moveTo(-3.4, 15)
  ctx.lineTo(3.4, 15)
  ctx.lineTo(0, 19.5)
  ctx.closePath()
  ctx.fillStyle = '#BD5134'
  ctx.fill()

  const mouth = ap.mouth || 'smile'
  ctx.strokeStyle = '#203D34'
  ctx.lineWidth = 2.2
  ctx.beginPath()
  if (mouth === 'grin') {
    ctx.moveTo(-9, 22)
    ctx.quadraticCurveTo(0, 34, 9, 22)
    ctx.stroke()
    // 舌头
    ctx.save()
    ctx.globalAlpha = 0.85
    ellipse(ctx, 0, 27, 4.2, 3.4, '#E58C7A')
    ctx.restore()
  } else if (mouth === 'meow') {
    ctx.moveTo(0, 19.5)
    ctx.lineTo(0, 24)
    ctx.moveTo(-7, 25)
    ctx.quadraticCurveTo(0, 31, 7, 25)
    ctx.stroke()
  } else if (mouth === 'flat') {
    ctx.moveTo(-6, 24)
    ctx.lineTo(6, 24)
    ctx.stroke()
  } else {
    // 微笑：w 形
    ctx.moveTo(-8, 21)
    ctx.quadraticCurveTo(-4, 26, 0, 22)
    ctx.quadraticCurveTo(4, 26, 8, 21)
    ctx.stroke()
  }

  // 腮红（轻，不抢视觉）
  ctx.save()
  ctx.globalAlpha = 0.35
  ellipse(ctx, -27, 13, 7, 4.5, '#E58C7A')
  ellipse(ctx, 27, 13, 7, 4.5, '#E58C7A')
  ctx.restore()

  // ===== 配饰 =====
  drawAccessory(ctx, ap.accessory, fur, bob)

  ctx.restore() // 头
  ctx.restore() // 整体
}

/** 配饰绘制（挂在头/颈上）
 * 注意：调用时已在头部坐标系内（原点 = 头部中心），这里**不要**再做整体平移，
 * 否则配饰会被推到画布外（踩过的坑）。
 * 头半径约 44，所以脖子在 y≈+40，头顶在 y≈-40。
 */
function drawAccessory(ctx, id, fur, bob) {
  if (!id || id === 'none') return
  ctx.save()
  if (id === 'scarf') {
    // 围巾：绕颈一圈 + 垂下的一角（陶土橙）
    ctx.beginPath()
    ctx.ellipse(0, 40, 30, 10, 0, 0, Math.PI * 2)
    ctx.fillStyle = '#BD5134'
    ctx.fill()
    ctx.beginPath()
    ctx.moveTo(17, 42)
    ctx.quadraticCurveTo(28, 54, 22, 64)
    ctx.quadraticCurveTo(14, 58, 12, 47)
    ctx.closePath()
    ctx.fillStyle = '#A94529'
    ctx.fill()
  } else if (id === 'bell') {
    // 铃铛：颈圈 + 小铃铛，贴在脖子处
    ctx.beginPath()
    ctx.ellipse(0, 38, 23, 5.5, 0, 0, Math.PI * 2)
    ctx.strokeStyle = '#C9A227'
    ctx.lineWidth = 3
    ctx.stroke()
    ctx.beginPath()
    ctx.arc(0, 45, 7, 0, Math.PI * 2)
    ctx.fillStyle = '#C9A227'
    ctx.fill()
    ctx.beginPath()
    ctx.arc(0, 43, 2, 0, Math.PI * 2)
    ctx.fillStyle = '#7A6316'
    ctx.fill()
  } else if (id === 'glasses') {
    // 圆眼镜：对准眼睛（眼睛在 ±16, y≈1）
    ctx.strokeStyle = '#3A4A44'
    ctx.lineWidth = 2.6
    for (const dir of [-1, 1]) {
      ctx.beginPath()
      ctx.arc(dir * 16, 1, 11.5, 0, Math.PI * 2)
      ctx.stroke()
    }
    ctx.beginPath()
    ctx.moveTo(-4.5, 1)
    ctx.lineTo(4.5, 1)
    ctx.stroke()
    // 镜腿延伸到耳侧
    ctx.beginPath()
    ctx.moveTo(-27.5, 1)
    ctx.lineTo(-34, -3)
    ctx.moveTo(27.5, 1)
    ctx.lineTo(34, -3)
    ctx.stroke()
  } else if (id === 'leaf') {
    // 叶子发夹：夹在左耳根旁
    ctx.save()
    ctx.translate(-30, -30)
    ctx.rotate(-0.55)
    ctx.beginPath()
    ctx.moveTo(0, 0)
    ctx.quadraticCurveTo(11, -8, 18, 0)
    ctx.quadraticCurveTo(11, 8, 0, 0)
    ctx.closePath()
    ctx.fillStyle = '#6F9E5B'
    ctx.fill()
    // 叶脉
    ctx.beginPath()
    ctx.moveTo(1, 0)
    ctx.lineTo(16, 0)
    ctx.strokeStyle = '#5A8549'
    ctx.lineWidth = 1.2
    ctx.stroke()
    ctx.restore()
  }
  ctx.restore()
}

/**
 * 命中检测：判断一个显示坐标是否落在宠物身体的“触摸热区”内
 * @param {number} x 显示坐标 X（相对组件左上角，px）
 * @param {number} y 显示坐标 Y
 * @param {number} boxSize 组件显示尺寸（px，正方形）
 * @returns {boolean}
 */
function hitTest(x, y, boxSize) {
  if (!boxSize || boxSize <= 0) return false
  // 显示 px → 逻辑坐标
  const lx = (x / boxSize) * LOGICAL
  const ly = (y / boxSize) * LOGICAL
  // 头部：圆心 (100,70) 半径 46；身体：椭圆近似为矩形 60..175
  const headHit = Math.pow(lx - 100, 2) / Math.pow(48, 2) + Math.pow(ly - 70, 2) / Math.pow(44, 2) <= 1
  const bodyHit = lx >= 58 && lx <= 142 && ly >= 78 && ly <= 172
  return headHit || bodyHit
}

module.exports = { LOGICAL, MOTIONS, drawPet, hitTest }
