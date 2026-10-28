// 小程序选学校：动态列表 + 选中态持久化
//
// 背景：首页的学校选择器原来是硬编码 ['内蒙古大学','北京大学','中国人民大学']，
// 和数据库里的 2 所学校对不上；而且选完只写了 globalData.schoolName，
// 没传给任何接口，等于「切了学校数据不变」。
//
// 现在：列表从 /api/schools 拉，选中态存 storage，所有列表接口带上 schoolId。
const api = require('./api')
const { CAMPUS_LOCATIONS } = require('./constants')

const STORAGE_KEY = 'campus_selected_school'

// 后端不可用时的兜底列表（与数据库初始数据一致）
const FALLBACK_SCHOOLS = [
  { id: 1, name: '内蒙古大学', city: '呼和浩特' },
  { id: 2, name: '北京大学', city: '北京' }
]

/** 读取已选学校；未选过返回 null */
function getSelected() {
  try {
    const raw = wx.getStorageSync(STORAGE_KEY)
    if (!raw) return null
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (!parsed || !parsed.id) return null
    return { id: Number(parsed.id), name: String(parsed.name || '') }
  } catch (error) {
    return null
  }
}

/** 写入已选学校（id + name 一起存，避免改名后显示旧名字） */
function setSelected(school) {
  if (!school || !school.id) return null
  const value = { id: Number(school.id), name: String(school.name || '') }
  try { wx.setStorageSync(STORAGE_KEY, JSON.stringify(value)) } catch (error) {}
  try {
    const app = getApp()
    if (app && app.globalData) {
      app.globalData.schoolId = value.id
      app.globalData.schoolName = value.name
    }
  } catch (error) {}
  return value
}

/** 当前生效的学校 id；未选过返回 null（调用方决定怎么兜底） */
function getSelectedId() {
  const selected = getSelected()
  return selected ? selected.id : null
}

/** 当前生效的学校名 */
function getSelectedName() {
  const selected = getSelected()
  return selected ? selected.name : ''
}

/** 是否已经选过学校（决定要不要强制弹选择器） */
function hasSelected() {
  return getSelected() !== null
}

/**
 * 拉取可选学校列表。后端不可用时回落到本地兜底，保证离线也能进得去。
 * 返回值统一带 offline 标记，页面据此显示离线提示。
 */
// 学校列表缓存。
// 首页会同时调用 syncSchoolLogo() 和 ensureSchool()，两者都要学校列表，
// 原来各请求一次 /api/schools。学校数据几乎不变，这里做两件事：
//   1) 同一时刻只发一个请求（_pending 复用），避免并发重复
//   2) 成功后短时缓存，页面来回切换不再重复拉
const CACHE_TTL = 5 * 60 * 1000
let _cache = null
let _cacheAt = 0
let _pending = null

/** 让缓存失效（管理端改了学校信息、切学校时调用） */
function invalidateSchools() {
  _cache = null
  _cacheAt = 0
  _pending = null
}

async function fetchSchools() {
  const now = Date.now()
  if (_cache && now - _cacheAt < CACHE_TTL) return _cache.map(s => ({ ...s }))
  if (_pending) return _pending

  _pending = (async () => {
    try {
      const rows = await api.getSchools()
      if (Array.isArray(rows) && rows.length) {
        const mapped = rows.map(row => ({
          id: Number(row.id),
          name: String(row.name || ''),
          city: String(row.city || ''),
          // 作息配置与 logo 原样保留：作息给课表页用，logo 给首页学校标识用
          periodConfig: row.periodConfig || '',
          logo: String(row.logo || '')
        }))
        _cache = mapped
        _cacheAt = Date.now()
        return mapped
      }
      // 拿到空列表：不缓存，下次重试
      return FALLBACK_SCHOOLS.map(s => ({ ...s }))
    } catch (error) {
      // 网络失败：回落到兜底列表，同样不缓存
      return FALLBACK_SCHOOLS.map(s => ({ ...s }))
    } finally {
      _pending = null
    }
  })()

  return _pending
}

const PERIOD_COUNT = 5
const DEFAULT_PERIOD_TIMES = ['08:00', '10:00', '14:00', '16:00', '19:00']
const TIME_RE = /^([01]\d|2[0-3]):([0-5]\d)$/

/**
 * 解析学校作息配置 → 5 个时间字符串；解析不出来返回 null（调用方回落默认）。
 * 兼容两种存法：
 *   ["08:00",...]                      —— 管理端现在写入的格式
 *   {"1":"08:00","2":"10:00",...}      —— 早期文档里写的格式
 */
function parsePeriodConfig(raw) {
  if (!raw) return null
  let value = raw
  if (typeof value === 'string') {
    const text = value.trim()
    if (!text) return null
    try { value = JSON.parse(text) } catch (error) { return null }
  }
  const times = new Array(PERIOD_COUNT).fill('')
  if (Array.isArray(value)) {
    value.forEach((item, i) => { if (i < PERIOD_COUNT && TIME_RE.test(String(item))) times[i] = String(item) })
  } else if (value && typeof value === 'object') {
    for (let i = 0; i < PERIOD_COUNT; i += 1) {
      const item = value[String(i + 1)]
      if (TIME_RE.test(String(item))) times[i] = String(item)
    }
  }
  // 必须五节都合法才算解析成功，否则整体回落默认，避免半套作息
  return times.every(Boolean) ? times : null
}

/** 当前选中学校的作息时间；没有选中学校或没配作息时返回 null */
async function getSelectedPeriodTimes() {
  const selected = getSelected()
  if (!selected) return null
  const list = await fetchSchools()
  const hit = list.find(row => Number(row.id) === Number(selected.id))
  return hit ? parsePeriodConfig(hit.periodConfig) : null
}

/**
 * 当前选中学校的地点列表。
 * 后端不可用或该校没配地点时回落到通用地点，保证发布页永远有可选项。
 */
async function getSelectedLocations() {
  const selected = getSelected()
  if (!selected) return CAMPUS_LOCATIONS.slice()
  try {
    const rows = await api.getSchoolLocations(selected.id)
    const names = (rows || []).map(row => String(row.name || '').trim()).filter(Boolean)
    if (names.length) return names
  } catch (error) {
    // 拉不到就用通用地点
  }
  return CAMPUS_LOCATIONS.slice()
}

module.exports = {
  STORAGE_KEY,
  FALLBACK_SCHOOLS,
  PERIOD_COUNT,
  DEFAULT_PERIOD_TIMES,
  getSelected,
  setSelected,
  getSelectedId,
  getSelectedName,
  hasSelected,
  fetchSchools,
  invalidateSchools,
  parsePeriodConfig,
  getSelectedPeriodTimes,
  getSelectedLocations
}
