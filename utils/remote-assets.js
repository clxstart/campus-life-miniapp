// 图片资源地址解析：包内 assets/ ↔ 服务端 media_asset（DB 存储）。
//
// 为什么要有这一层，而不是把 URL 直接写进页面：
//   1. 图片从包里搬到服务端是「分阶段」的 —— 域名还在备案、对象存储还没上，
//      一次性全改会在域名没通时让整站白图；有了这一层，开关一关就退回包内图，不会炸。
//   2. 以后换成 OSS / CDN，改的是 utils/config.js 里的 asset 基址，页面一行不用动。
//
// 已迁移清单（MIGRATED）里的文件才走远端；表里没有的（图标、头像、还没压缩的图）
// 自动回落成本地路径，所以「部分迁移」期间两种来源可以共存。
//
// 服务端接口：GET {ASSET_BASE_URL}/api/asset/{name}，弱网无 token，强缓存 + ETag。

const { ASSET_BASE_URL, REMOTE_ASSETS } = require('./config')

/**
 * 已迁到服务端的图片：键是文件名（服务端 media_asset.asset_name 与之同名）。
 * 同名去重靠的是「包内 assets/ 下没有重名文件」这一点；
 * 新增迁移时，先跑 node tools/import-assets-sql.js 入库，再把名字加到这里。
 */
const MIGRATED = [
  'food-bowl.webp',
  'food-fresh.webp',
  'food-fruit.webp',
  'food-milktea.webp',
  'food-snacks.webp',
  'basketball.webp',
  'flea-market.webp',
  'library-study.webp',
  'spring-picnic.webp',
  'laptop-silver.webp',
  'parttime-worker.webp',
  'recommend-lamp.jpg',
  'recommend-milktea.jpg',
  'secondhand-box.webp',
  'thesis-books.webp',
  'spring-event.webp',
  'hero.jpg',
  'desk.jpg'
]

const MIGRATED_SET = new Set(MIGRATED)

/** 图片在服务端的地址；name 就是 media_asset.asset_name */
function remoteUrl(name) {
  return ASSET_BASE_URL + '/api/asset/' + name
}

/** 从 '/assets/home/food-snacks.webp' 这种路径里取文件名 */
function baseName(path) {
  if (typeof path !== 'string') return ''
  const cut = path.lastIndexOf('/')
  return cut < 0 ? path : path.slice(cut + 1)
}

/**
 * 把包内图片路径解析成最终可用的 src。
 *
 * - 非字符串 / 空值原样返回（页面里大量 `item.image || 默认图` 的写法，不能在这里抛错）
 * - 已经 http(s) 开头的直接返回（数据库里存的用户上传图就是这种）
 * - 已迁移且开关打开 → 服务端地址；否则 → 原本地路径
 */
function asset(path) {
  if (typeof path !== 'string' || !path) return path
  if (/^https?:\/\//i.test(path)) return path
  if (!REMOTE_ASSETS) return path
  const name = baseName(path)
  return MIGRATED_SET.has(name) ? remoteUrl(name) : path
}

/**
 * 给 wxml 用：wxml 里不能调 JS 函数，只能绑数据，
 * 所以页面 onLoad 时把这些固定图一次性解析进 data。
 */
function assetMap(paths) {
  const out = {}
  Object.keys(paths).forEach(function (key) {
    out[key] = asset(paths[key])
  })
  return out
}

module.exports = {
  asset: asset,
  assetMap: assetMap,
  remoteUrl: remoteUrl,
  MIGRATED: MIGRATED,
  REMOTE_ASSETS: REMOTE_ASSETS
}