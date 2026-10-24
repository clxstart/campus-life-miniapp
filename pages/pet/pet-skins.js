// 宠物形象素材
// 开源 emoji 素材：Twemoji (CC-BY 4.0) / Noto Emoji (Apache 2.0) / OpenMoji (CC BY-SA 4.0)
// 橘猫系列：AI 生成，按状态切换图片

const ORANGE_CAT_STATES = {
  idle:    '/assets/pet/orange-cat-idle-240.png',
  happy:   '/assets/pet/orange-cat-happy-240.png',
  hungry:  '/assets/pet/orange-cat-hungry-240.png',
  sleep:   '/assets/pet/orange-cat-sleep-240.png',
  levelup: '/assets/pet/orange-cat-levelup-240.png'
}

const SKINS = [
  // 橘猫（AI 生成，支持 5 个状态各一张图）
  {
    id: 'orange-cat',
    name: '橘色小猫',
    pack: 'AI',
    path: '/assets/pet/orange-cat-idle-240.png',
    states: ORANGE_CAT_STATES
  },
  // 以下为开源 emoji 素材，单图 + CSS 动效
  { id: 'noto-robot',     name: '小机器人',   pack: 'Noto',     path: '/assets/pet/noto-robot-240.png' },
  { id: 'twemoji-robot',  name: '经典机器人', pack: 'Twemoji', path: '/assets/pet/twemoji-robot-240.png' },
  { id: 'openmoji-robot', name: '线条机器人', pack: 'OpenMoji',path: '/assets/pet/openmoji-robot-240.png' },
  { id: 'noto-cat-face',  name: '小猫咪',     pack: 'Noto',    path: '/assets/pet/noto-cat-face-240.png' },
  { id: 'twemoji-cat-face', name: '橘猫头像', pack: 'Twemoji', path: '/assets/pet/twemoji-cat-face-240.png' },
  { id: 'noto-dog-face',  name: '小狗狗',     pack: 'Noto',    path: '/assets/pet/noto-dog-face-240.png' },
  { id: 'twemoji-dog-face', name: '柴犬',     pack: 'Twemoji', path: '/assets/pet/twemoji-dog-face-240.png' },
  { id: 'noto-bear',      name: '小熊',       pack: 'Noto',    path: '/assets/pet/noto-bear-240.png' },
  { id: 'twemoji-bear',   name: '泰迪熊',     pack: 'Twemoji', path: '/assets/pet/twemoji-bear-240.png' },
  { id: 'noto-alien',     name: '小外星人',   pack: 'Noto',    path: '/assets/pet/noto-alien-240.png' },
  { id: 'twemoji-alien',  name: '绿外星人',   pack: 'Twemoji', path: '/assets/pet/twemoji-alien-240.png' },
  { id: 'openmoji-alien', name: '简笔外星人', pack: 'OpenMoji',path: '/assets/pet/openmoji-alien-240.png' }
]

// 默认用橘猫（有完整状态图）
const DEFAULT_SKIN = 'orange-cat'

function findSkin(id) {
  return SKINS.find(s => s.id === id) || SKINS.find(s => s.id === DEFAULT_SKIN)
}

// 按状态取图片：有 states 的皮肤用对应状态图，否则用单图
function pathForState(skin, state) {
  if (!skin) return ''
  if (skin.states && skin.states[state]) return skin.states[state]
  return skin.path
}

module.exports = { SKINS, DEFAULT_SKIN, findSkin, pathForState }
