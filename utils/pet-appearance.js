/**
 * 宠物外观定义 —— 全项目唯一数据源
 *
 * 美术来源：Kenney Cube Pets 2.0（CC0，可商用，无需署名）
 *   https://kenney.nl/assets/cube-pets
 * 渲染：从官方 GLB 模型用 three.js 无头渲染成 512×512 透明 PNG
 *
 * 外观模型（与旧版 Canvas 版本的关键差异）：
 *   species   物种（图片）      —— 决定基础形象
 *   tint      色调（CSS filter）—— 在图片上叠色，实现同物种不同色
 *   accessory 配饰（表情/角标）  —— 用 emoji/CSS 叠加，不再画进图片
 *   mood      情绪表情           —— 由 accessory + 动作共同体现
 */

/** 物种：对应 assets/pets/<id>.png */
const SPECIES = [
  { id: 'cat',     name: '小猫',   file: 'cat.png' },
  { id: 'bunny',   name: '兔子',   file: 'bunny.png' },
  { id: 'dog',     name: '小狗',   file: 'dog.png' },
  { id: 'panda',   name: '熊猫',   file: 'panda.png' },
  { id: 'fox',     name: '狐狸',   file: 'fox.png' },
  { id: 'koala',   name: '考拉',   file: 'koala.png' },
  { id: 'tiger',   name: '老虎',   file: 'tiger.png' },
  { id: 'penguin', name: '企鹅',   file: 'penguin.png' },
  { id: 'chick',   name: '小鸡',   file: 'chick.png' }
]

/** 色调：CSS filter，叠加在原图上 */
const TINTS = [
  { id: 'natural', name: '原色',   filter: 'none' },
  { id: 'warm',    name: '暖橘',   filter: 'sepia(0.42) saturate(1.35) hue-rotate(-12deg)' },
  { id: 'mint',    name: '薄荷',   filter: 'hue-rotate(85deg) saturate(1.1)' },
  { id: 'rose',    name: '玫瑰',   filter: 'hue-rotate(-40deg) saturate(1.2)' },
  { id: 'mono',    name: '素描',   filter: 'grayscale(1) contrast(1.1)' }
]

/** 配饰：叠加在图片右上角/颈部的小元素（emoji 保证跨平台一致） */
const ACCESSORIES = [
  { id: 'none',   name: '不戴',   symbol: '' },
  { id: 'scarf',  name: '陶土围巾', symbol: '🧣' },
  { id: 'bell',   name: '小铃铛',  symbol: '🔔' },
  { id: 'glasses',name: '圆眼镜',  symbol: '👓' },
  { id: 'leaf',   name: '叶子发夹', symbol: '🍃' }
]

/** 情绪：影响动作与气泡文案 */
const MOODS = [
  { id: 'happy',  name: '开心' },
  { id: 'calm',   name: '平静' },
  { id: 'hungry', name: '饿了' },
  { id: 'sleepy', name: '困了' }
]

/** 默认外观：新领养的宠物 */
const DEFAULT_APPEARANCE = {
  species: 'cat',
  tint: 'natural',
  accessory: 'scarf',
  mood: 'calm'
}

const VALID = {
  species: SPECIES.map(x => x.id),
  tint: TINTS.map(x => x.id),
  accessory: ACCESSORIES.map(x => x.id),
  mood: MOODS.map(x => x.id)
}

/** 校验；返回错误原因或 null */
function validateAppearance(value) {
  if (!value || typeof value !== 'object') return '外观数据缺失'
  for (const key of Object.keys(VALID)) {
    const id = value[key]
    if (id == null || id === '') continue
    if (!VALID[key].includes(id)) return `未知的 ${key}：${id}`
  }
  return null
}

/** 补齐缺省项 */
function normalizeAppearance(value) {
  const base = { ...DEFAULT_APPEARANCE }
  if (value && typeof value === 'object') {
    for (const key of Object.keys(VALID)) {
      if (value[key] && VALID[key].includes(value[key])) base[key] = value[key]
    }
  }
  return base
}

/** 确定性随机：同 seed 同结果（种子只用于生成编号，存档仍存编号） */
function randomAppearance(seed) {
  let s = Number(seed)
  if (!Number.isFinite(s)) s = Math.floor(Math.random() * 1e9)
  const pick = (list) => {
    s = (s * 1103515245 + 12345) & 0x7fffffff
    return list[s % list.length].id
  }
  return {
    species: pick(SPECIES),
    tint: pick(TINTS),
    accessory: pick(ACCESSORIES),
    mood: pick(MOODS)
  }
}

function findSpecies(id) { return SPECIES.find(x => x.id === id) || SPECIES[0] }
function findTint(id) { return TINTS.find(x => x.id === id) || TINTS[0] }
function findAccessory(id) { return ACCESSORIES.find(x => x.id === id) || ACCESSORIES[0] }

/** 生成图片路径 */
function imagePath(appearance) {
  const sp = findSpecies((appearance || {}).species)
  return `/assets/pets/${sp.file}`
}

module.exports = {
  SPECIES, TINTS, ACCESSORIES, MOODS,
  DEFAULT_APPEARANCE, VALID,
  validateAppearance, normalizeAppearance, randomAppearance,
  findSpecies, findTint, findAccessory, imagePath
}
