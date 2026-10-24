/**
 * 宠物模块最小验证（不依赖微信运行时）
 * 运行：node tests/pet-engine.test.js
 *
 * 覆盖：外观校验、确定性随机、命中检测、绘制调用不抛错
 */
const assert = require('assert')
const app = require('../utils/pet-appearance')
const engine = require('../utils/pet-engine')

let pass = 0, fail = 0
function test(name, fn) {
  try { fn(); console.log('  ✅', name); pass++ }
  catch (e) { console.log('  ❌', name, '\n     ', e.message); fail++ }
}

console.log('\n=== 1. 外观定义 ===')
test('默认外观五项齐全', () => {
  const d = app.DEFAULT_APPEARANCE
  for (const k of ['fur', 'pattern', 'eyes', 'mouth', 'accessory']) {
    assert.ok(d[k], `缺少 ${k}`)
  }
})
test('默认外观通过校验', () => {
  assert.strictEqual(app.validateAppearance(app.DEFAULT_APPEARANCE), null)
})
test('非法部件编号被拒绝', () => {
  const bad = { ...app.DEFAULT_APPEARANCE, fur: 'not-exist' }
  assert.ok(app.validateAppearance(bad), '应当拒绝未知 fur')
})
test('未知 eyes 被拒绝', () => {
  const bad = { ...app.DEFAULT_APPEARANCE, eyes: 'laser' }
  assert.ok(app.validateAppearance(bad))
})
test('缺失字段不判错（兼容旧数据）', () => {
  assert.strictEqual(app.validateAppearance({ fur: 'cream' }), null)
})
test('normalizeAppearance 补齐缺省项', () => {
  const n = app.normalizeAppearance({ fur: 'orange' })
  assert.strictEqual(n.fur, 'orange')
  assert.strictEqual(n.eyes, app.DEFAULT_APPEARANCE.eyes)
  assert.ok(app.validateAppearance(n) === null)
})

console.log('\n=== 2. 确定性随机（同 seed 同结果）===')
test('相同 seed 产生相同外观', () => {
  const a = app.randomAppearance(12345)
  const b = app.randomAppearance(12345)
  assert.deepStrictEqual(a, b)
})
test('不同 seed 通常产生不同外观', () => {
  const set = new Set()
  for (let i = 1; i <= 30; i++) set.add(JSON.stringify(app.randomAppearance(i)))
  assert.ok(set.size > 5, `只产出 ${set.size} 种，随机性不足`)
})
test('随机结果都是合法部件编号', () => {
  for (let i = 1; i <= 100; i++) {
    const ap = app.randomAppearance(i)
    assert.strictEqual(app.validateAppearance(ap), null, `seed=${i} 产出非法外观`)
  }
})

console.log('\n=== 3. 命中检测 ===')
test('头部中心命中', () => {
  // 逻辑 (100,70) → 240px 画布下约为 (120,84)
  assert.ok(engine.hitTest(120, 84, 240))
})
test('身体中部命中', () => {
  assert.ok(engine.hitTest(120, 150, 240))
})
test('左上角空白不命中', () => {
  assert.ok(!engine.hitTest(4, 4, 240))
})
test('右下角空白不命中', () => {
  assert.ok(!engine.hitTest(236, 236, 240))
})
test('尺寸为 0 时不命中且不报错', () => {
  assert.strictEqual(engine.hitTest(10, 10, 0), false)
})
test('不同显示尺寸下命中比例一致', () => {
  // 同样是逻辑 (100,70)，在 120px 与 360px 画布上都应命中
  assert.ok(engine.hitTest(60, 42, 120))
  assert.ok(engine.hitTest(180, 126, 360))
})

console.log('\n=== 4. 绘制不抛错（假 ctx）===')
function makeFakeCtx() {
  const noop = () => {}
  return {
    save: noop, restore: noop, scale: noop, translate: noop, rotate: noop,
    beginPath: noop, moveTo: noop, lineTo: noop, arcTo: noop, arc: noop,
    ellipse: noop, quadraticCurveTo: noop, bezierCurveTo: noop, closePath: noop,
    fill: noop, stroke: noop, clip: noop, rect: noop, clearRect: noop,
    set lineWidth(v) {}, set strokeStyle(v) {}, set fillStyle(v) {},
    set globalAlpha(v) {}, set lineJoin(v) {}, set lineCap(v) {}
  }
}
test('绘制默认外观不抛错', () => {
  engine.drawPet(makeFakeCtx(), {
    appearance: app.DEFAULT_APPEARANCE, motion: 'idle', t: 1000, scale: 1.2
  })
})
test('所有毛色 × 所有花纹 × 所有配饰 都能绘制', () => {
  for (const fur of app.FURS) {
    for (const pattern of app.PATTERNS) {
      for (const acc of app.ACCESSORIES) {
        engine.drawPet(makeFakeCtx(), {
          appearance: { fur: fur.id, pattern: pattern.id, eyes: 'round', mouth: 'smile', accessory: acc.id },
          motion: 'idle', t: 500, scale: 1
        })
      }
    }
  }
})
test('所有眼睛 × 所有嘴巴 都能绘制', () => {
  for (const eyes of app.EYES) {
    for (const mouth of app.MOUTHS) {
      engine.drawPet(makeFakeCtx(), {
        appearance: { ...app.DEFAULT_APPEARANCE, eyes: eyes.id, mouth: mouth.id },
        motion: 'happy', t: 800, scale: 1
      })
    }
  }
})
test('所有动作名都能绘制（含未知动作回退 idle）', () => {
  for (const m of ['idle', 'happy', 'sleep', 'eat', 'cheer', 'walk', 'unknown-motion']) {
    engine.drawPet(makeFakeCtx(), { appearance: app.DEFAULT_APPEARANCE, motion: m, t: 100, scale: 1 })
  }
})
test('reduceMotion 时绘制不抛错', () => {
  engine.drawPet(makeFakeCtx(), {
    appearance: app.DEFAULT_APPEARANCE, motion: 'idle', t: 0, scale: 1, reduceMotion: true
  })
})

console.log(`\n=== 结果：${pass} 通过，${fail} 失败 ===\n`)
process.exit(fail === 0 ? 0 : 1)
