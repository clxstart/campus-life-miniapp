// 作息配置的解析 / 序列化 / 预设
// 管理端原来要求管理员手写 JSON，容易写错且没人知道 1-5 对应第几节。
// 目标：改成「预设 + 时间选择器」，底层仍然存 JSON（保持与后端 period_config 字段兼容）。
//
// 运行：node --test tests/period-config.test.js
import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  PERIOD_PRESETS,
  PERIOD_COUNT,
  parsePeriodConfig,
  serializePeriodConfig,
  matchPreset,
  validatePeriods
} from '../web/src/utils/period.js'

// ================= 解析 =================

test('解析：空值回落到标准作息', () => {
  for (const raw of [null, undefined, '', '   ']) {
    const times = parsePeriodConfig(raw)
    assert.equal(times.length, PERIOD_COUNT)
    assert.deepEqual(times, ['08:00', '10:00', '14:00', '16:00', '19:00'])
  }
})

test('解析：数组格式（小程序本地存储用的就是这个）', () => {
  const times = parsePeriodConfig('["08:30","10:30","14:30","16:30","19:30"]')
  assert.deepEqual(times, ['08:30', '10:30', '14:30', '16:30', '19:30'])
})

test('解析：对象格式（旧 placeholder 里写的那个）', () => {
  const times = parsePeriodConfig('{"1":"08:15","2":"10:15","3":"14:15","4":"16:15","5":"19:15"}')
  assert.deepEqual(times, ['08:15', '10:15', '14:15', '16:15', '19:15'])
})

test('解析：对象键顺序乱掉也能按节次排好', () => {
  const times = parsePeriodConfig('{"3":"14:00","1":"08:00","5":"19:00","2":"10:00","4":"16:00"}')
  assert.deepEqual(times, ['08:00', '10:00', '14:00', '16:00', '19:00'])
})

test('解析：非法 JSON 不抛异常，回落默认', () => {
  for (const raw of ['{不是json', '[]', 'null', '123', '{"1":"错的时间"}']) {
    const times = parsePeriodConfig(raw)
    assert.equal(times.length, PERIOD_COUNT, `输入 ${raw} 应回落为默认 5 节`)
    assert.ok(times.every(t => /^\d{2}:\d{2}$/.test(t)), `输入 ${raw} 回落后每个时间都要合法`)
  }
})

test('解析：数组长度不足时用默认值补齐，不报错', () => {
  const times = parsePeriodConfig('["09:00","11:00"]')
  assert.equal(times.length, PERIOD_COUNT)
  assert.equal(times[0], '09:00')
  assert.equal(times[1], '11:00')
  assert.equal(times[2], '14:00', '缺失的节次应回落到默认时间')
})

// ================= 序列化 =================

test('序列化：存成数组格式，与小程序保持一致', () => {
  const out = serializePeriodConfig(['08:00', '10:00', '14:00', '16:00', '19:00'])
  assert.equal(out, '["08:00","10:00","14:00","16:00","19:00"]')
  // 能被 JSON.parse 回数组
  assert.deepEqual(JSON.parse(out), ['08:00', '10:00', '14:00', '16:00', '19:00'])
})

test('往返：序列化后再解析应完全一致', () => {
  const times = ['07:45', '09:55', '13:40', '15:50', '18:30']
  assert.deepEqual(parsePeriodConfig(serializePeriodConfig(times)), times)
})

test('序列化：非法输入也产出合法 JSON（不写坏数据库）', () => {
  const out = serializePeriodConfig(null)
  assert.doesNotThrow(() => JSON.parse(out))
  assert.equal(JSON.parse(out).length, PERIOD_COUNT)
})

// ================= 预设 =================

test('预设：至少包含标准作息，且时间合法', () => {
  assert.ok(PERIOD_PRESETS.length >= 2, '至少要有 2 套预设可选')
  const standard = PERIOD_PRESETS.find(p => p.key === 'standard')
  assert.ok(standard, '必须有 standard 预设')
  assert.deepEqual(standard.times, ['08:00', '10:00', '14:00', '16:00', '19:00'])
  for (const preset of PERIOD_PRESETS) {
    assert.equal(preset.times.length, PERIOD_COUNT, `预设 ${preset.key} 应有 ${PERIOD_COUNT} 节`)
    assert.ok(preset.label, `预设 ${preset.key} 应有中文名`)
    assert.ok(preset.times.every(t => /^\d{2}:\d{2}$/.test(t)), `预设 ${preset.key} 时间格式要合法`)
  }
})

test('预设：每套预设有唯一 key', () => {
  const keys = PERIOD_PRESETS.map(p => p.key)
  assert.equal(new Set(keys).size, keys.length, '预设 key 不能重复')
})

test('matchPreset：能认出标准作息，改一节就认不出', () => {
  const standard = ['08:00', '10:00', '14:00', '16:00', '19:00']
  assert.equal(matchPreset(standard), 'standard')
  assert.equal(matchPreset(['08:00', '10:00', '14:00', '16:00', '19:30']), null, '改过就不再匹配任何预设')
})

// ================= 校验 =================

test('校验：合法时间通过', () => {
  assert.equal(validatePeriods(['08:00', '10:00', '14:00', '16:00', '19:00']), '')
})

test('校验：空值、非法格式、错长度都返回中文错误', () => {
  // 提示语用中文序号（第一节），比「第1节」更自然
  assert.ok(validatePeriods(['', '10:00', '14:00', '16:00', '19:00']).includes('第一节'))
  assert.ok(validatePeriods(['8:00', '10:00', '14:00', '16:00', '19:00']).includes('第一节'), '必须两位数小时')
  assert.ok(validatePeriods(['25:00', '10:00', '14:00', '16:00', '19:00']).includes('第一节'), '小时不能超过 23')
  assert.ok(validatePeriods(['08:70', '10:00', '14:00', '16:00', '19:00']).includes('第一节'), '分钟不能超过 59')
  assert.ok(validatePeriods(['08:00', '10:00']).includes('节'), '节数不对要报错')
})

test('校验：节次时间必须递增（后一节不能早于前一节）', () => {
  const msg = validatePeriods(['10:00', '08:00', '14:00', '16:00', '19:00'])
  assert.ok(msg.includes('递增') || msg.includes('第二节'), `应提示顺序问题，实际：${msg}`)
})
