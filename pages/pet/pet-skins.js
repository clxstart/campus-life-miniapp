// 宠物形象素材（全部来自开源 emoji 素材包，可商用）
// Twemoji: CC-BY 4.0 | Noto Emoji: Apache 2.0 | OpenMoji: CC BY-SA 4.0
const SKINS = [
  { id: 'noto-robot',        name: '小机器人', pack: 'Noto',     path: '/assets/pet/noto-robot-240.png' },
  { id: 'twemoji-robot',     name: '经典机器人', pack: 'Twemoji', path: '/assets/pet/twemoji-robot-240.png' },
  { id: 'openmoji-robot',    name: '线条机器人', pack: 'OpenMoji', path: '/assets/pet/openmoji-robot-240.png' },
  { id: 'noto-cat-face',     name: '小猫咪', pack: 'Noto',     path: '/assets/pet/noto-cat-face-240.png' },
  { id: 'twemoji-cat-face',  name: '橘猫', pack: 'Twemoji', path: '/assets/pet/twemoji-cat-face-240.png' },
  { id: 'noto-dog-face',     name: '小狗狗', pack: 'Noto',     path: '/assets/pet/noto-dog-face-240.png' },
  { id: 'twemoji-dog-face',  name: '柴犬', pack: 'Twemoji', path: '/assets/pet/twemoji-dog-face-240.png' },
  { id: 'noto-bear',         name: '小熊', pack: 'Noto',     path: '/assets/pet/noto-bear-240.png' },
  { id: 'twemoji-bear',      name: '泰迪熊', pack: 'Twemoji', path: '/assets/pet/twemoji-bear-240.png' },
  { id: 'noto-alien',        name: '小外星人', pack: 'Noto',     path: '/assets/pet/noto-alien-240.png' },
  { id: 'twemoji-alien',     name: '绿外星人', pack: 'Twemoji', path: '/assets/pet/twemoji-alien-240.png' },
  { id: 'openmoji-alien',    name: '简笔外星人', pack: 'OpenMoji', path: '/assets/pet/openmoji-alien-240.png' }
]

const DEFAULT_SKIN = 'noto-robot'

function findSkin(id) {
  return SKINS.find(s => s.id === id) || SKINS.find(s => s.id === DEFAULT_SKIN)
}

module.exports = { SKINS, DEFAULT_SKIN, findSkin }
