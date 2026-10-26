// 学习专区域：话题列表（主键游标分页）与各分类计数。

const { request, wait, currentUserId } = require('./core')

// 学习列表使用主键游标，列表加载期间有新帖或删帖也不造成 offset 错位。
function getLearnTopics(params = {}) {
  const { category = 'daike', schoolId = null, userId = currentUserId(), beforeId = '' } = params
  const parsedSize = Number(params.size)
  const size = Number.isFinite(parsedSize) ? Math.min(50, Math.max(1, Math.floor(parsedSize))) : 10
  const school = schoolId == null ? '' : `&schoolId=${encodeURIComponent(schoolId)}`
  const cursor = beforeId ? `&beforeId=${encodeURIComponent(beforeId)}` : ''
  return request(`/learn/topics?category=${encodeURIComponent(category)}&userId=${encodeURIComponent(userId)}&size=${size}${school}${cursor}`, 'GET')
    .catch(error => {
      if (error.server) throw error
      return wait({ list: [], total: 0, hasMore: false, nextCursor: '', offline: true })
    })
}
function getLearnCounts(schoolId = null, userId = currentUserId()) {
  const school = schoolId == null ? '' : `&schoolId=${encodeURIComponent(schoolId)}`
  return request(`/learn/counts?userId=${encodeURIComponent(userId)}${school}`, 'GET').catch(error => {
    if (error.server) throw error
    return wait({ daike: 0, paper: 0, job: 0, kaoyan: 0, kaogong: 0 }, 80)
  })
}

module.exports = { getLearnTopics, getLearnCounts }
