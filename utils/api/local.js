// 跨业务域共用的小工具：本地存储、关键词匹配、图片地址补全。
//
// 这些函数被帖子、二手、聊天、圈子几个域同时需要，单独放一层，
// 避免各模块互相 require 绕成环。

const { clone, BASE_URL } = require('./core')

// ---- 本机存储 ----
function readLocal(key, fallback) {
  try { const data = wx.getStorageSync(key); return Array.isArray(data) ? data : clone(fallback) } catch (error) { return clone(fallback) }
}
function saveLocal(key, value) {
  try { wx.setStorageSync(key, value) } catch (error) { throw new Error('本机保存失败，请检查存储空间') }
}

// ---- 关键词匹配 ----
// LIKE %keyword%：与 MySQL 一致支持 % / _ 通配符和反斜线转义；标题与正文分别匹配。
function keywordMatcher(keyword) {
  let pattern = '^'
  const text = '%' + keyword + '%'
  const escape = char => char.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  for (let i = 0; i < text.length; i++) {
    const char = text[i]
    if (char === '\\' && i + 1 < text.length) pattern += escape(text[++i])
    else if (char === '%') pattern += '[\\s\\S]*'
    else if (char === '_') pattern += '[\\s\\S]'
    else pattern += escape(char)
  }
  return new RegExp(pattern + '$', 'iu')
}

// ---- 图片地址 ----
// 上传图片的路径规范化：小程序把以 / 开头的路径当本地资源，只有 http(s) 才走网络。
// 老数据里存的是 /uploads/xxx.png（相对路径），这里补全成完整地址；/assets/ 是项目内置图，保持原样。
const UPLOAD_BASE = BASE_URL.replace(/\/api\/?$/, '')
function assetUrl(path) {
  const value = String(path == null ? '' : path)
  if (!value) return ''
  if (/^https?:\/\//i.test(value)) return value
  if (value.startsWith('/uploads/')) return UPLOAD_BASE + value
  return value
}
function assetUrls(list) {
  return Array.isArray(list) ? list.map(assetUrl) : list
}
// 商品对象里的图片字段统一补全（列表与详情共用）
function normalizeGoods(row) {
  if (!row || typeof row !== 'object') return row
  return { ...row, image: assetUrl(row.image), images: assetUrls(row.images) }
}

module.exports = { readLocal, saveLocal, keywordMatcher, assetUrl, assetUrls, normalizeGoods, UPLOAD_BASE }
