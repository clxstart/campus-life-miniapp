/**
 * 宠物资料缓存
 *
 * 为什么要缓存：悬浮宠物要出现在 38 个页面，
 * 如果每页都请求后端 /api/pet/appearance，会造成大量重复请求。
 * 这里把「名字 + 外观」缓存到本地，各页面直接读缓存，
 * 只有在宠物页真正修改后才写一次。
 */
const KEY = 'pet_float_profile'

const DEFAULT_PROFILE = {
  name: '小云',
  appearance: {
    species: 'cat',
    tint: 'natural',
    accessory: 'scarf',
    mood: 'calm'
  }
}

/** 读取缓存资料；没有则返回默认值 */
function read() {
  try {
    const cached = wx.getStorageSync(KEY)
    if (cached && typeof cached === 'object') {
      return {
        name: cached.name || DEFAULT_PROFILE.name,
        appearance: { ...DEFAULT_PROFILE.appearance, ...(cached.appearance || {}) }
      }
    }
  } catch (e) {}
  return { ...DEFAULT_PROFILE, appearance: { ...DEFAULT_PROFILE.appearance } }
}

/** 写入缓存（外观或名字变化后调用） */
function write(profile) {
  if (!profile) return
  const current = read()
  const next = {
    name: profile.name || current.name,
    appearance: { ...current.appearance, ...(profile.appearance || {}) }
  }
  try { wx.setStorageSync(KEY, next) } catch (e) {}
  return next
}

/** 只更新外观 */
function writeAppearance(appearance) {
  return write({ appearance })
}

module.exports = { read, write, writeAppearance, DEFAULT_PROFILE }
