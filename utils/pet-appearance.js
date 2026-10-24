/**
 * 宠物外观定义 —— 全项目唯一数据源
 *
 * 捏脸页、首页小院、悬浮入口、衣柜、聊天头像全部读这一份配置。
 * 外观保存的是**明确的部件编号**，不是随机种子（种子只用于“随机一下”生成种子→编号）。
 */

/** 毛色（同时决定身体主色与阴影色）
 * 注意：奶油白与雪白容易混淆，故雪白取偏冷白、阴影加深，温度差拉开区分度 */
const FURS = [
  { id: 'cream',  name: '奶油白', body: '#F2E3C9', shade: '#DCC49C', belly: '#FBF3E2' },
  { id: 'orange', name: '橘猫',   body: '#E8A65C', shade: '#C97F36', belly: '#F7DCB8' },
  { id: 'gray',   name: '石墨灰', body: '#9AA5A0', shade: '#6E7A75', belly: '#CBD3CF' },
  { id: 'black',  name: '墨黑',   body: '#5A5F5C', shade: '#3A3F3D', belly: '#8C9390' },
  { id: 'snow',   name: '雪白',   body: '#FBFDFE', shade: '#C9D4D8', belly: '#FFFFFF' }
]

/** 花纹（叠加在身体上，按绘制层序放在毛色之后、五官之前） */
const PATTERNS = [
  { id: 'none',   name: '纯色' },
  { id: 'patch',  name: '奶盖斑' },
  { id: 'stripe', name: '虎斑纹' },
  { id: 'star',   name: '星星白' }
]

/** 眼睛 */
const EYES = [
  { id: 'round',   name: '圆豆眼' },
  { id: 'sleepy',  name: '半眯眼' },
  { id: 'sparkle', name: '星星眼' },
  { id: 'closed',  name: '闭眼笑' }
]

/** 嘴巴 */
const MOUTHS = [
  { id: 'smile',  name: '微笑' },
  { id: 'grin',   name: '开心笑' },
  { id: 'meow',   name: '喵呜' },
  { id: 'flat',   name: '淡定' }
]

/** 配饰（颜色以陶土橙为主点缀，少量其他色） */
const ACCESSORIES = [
  { id: 'none',   name: '不戴' },
  { id: 'scarf',  name: '陶土围巾', color: '#BD5134' },
  { id: 'bell',   name: '小铃铛',   color: '#C9A227' },
  { id: 'glasses',name: '圆眼镜',   color: '#3A4A44' },
  { id: 'leaf',   name: '叶子发夹', color: '#6F9E5B' }
]

/** 默认外观：新领养的宠物 */
const DEFAULT_APPEARANCE = {
  fur: 'cream',
  pattern: 'none',
  eyes: 'round',
  mouth: 'smile',
  accessory: 'scarf'
}

/** 允许的部件编号集合，用于服务端校验 */
const VALID = {
  fur: FURS.map(x => x.id),
  pattern: PATTERNS.map(x => x.id),
  eyes: EYES.map(x => x.id),
  mouth: MOUTHS.map(x => x.id),
  accessory: ACCESSORIES.map(x => x.id)
}

/** 校验一份外观是否合法；不合法返回原因，合法返回 null */
function validateAppearance(value) {
  if (!value || typeof value !== 'object') return '外观数据缺失'
  for (const key of Object.keys(VALID)) {
    const id = value[key]
    // 缺字段时按默认值补齐，不判为错误（兼容旧数据）
    if (id == null || id === '') continue
    if (!VALID[key].includes(id)) return `未知的 ${key} 部件：${id}`
  }
  return null
}

/** 把不完整的外观补齐为完整外观 */
function normalizeAppearance(value) {
  const base = { ...DEFAULT_APPEARANCE }
  if (value && typeof value === 'object') {
    for (const key of Object.keys(VALID)) {
      if (value[key] && VALID[key].includes(value[key])) base[key] = value[key]
    }
  }
  return base
}

/**
 * 随机外观：确定性算法
 * 同样的 seed 永远得到同样的外观，便于复现与调试（参考 cat-avatar-generator 的思路）。
 * 注意：保存时仍然存部件编号，不存 seed。
 */
function randomAppearance(seed) {
  let s = Number(seed)
  if (!Number.isFinite(s)) s = Math.floor(Math.random() * 1e9)
  const pick = (list) => {
    // 线性同余，保证同 seed 同结果
    s = (s * 1103515245 + 12345) & 0x7fffffff
    return list[s % list.length].id
  }
  return {
    fur: pick(FURS),
    pattern: pick(PATTERNS),
    eyes: pick(EYES),
    mouth: pick(MOUTHS),
    accessory: pick(ACCESSORIES)
  }
}

function findFur(id) { return FURS.find(x => x.id === id) || FURS[0] }

module.exports = {
  FURS, PATTERNS, EYES, MOUTHS, ACCESSORIES,
  DEFAULT_APPEARANCE, VALID,
  validateAppearance, normalizeAppearance, randomAppearance, findFur
}
