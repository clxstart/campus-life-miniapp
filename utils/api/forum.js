// 论坛域：帖子流、帖子详情、点赞/评论/关注/浏览、搜索、热门话题、轮播位、发帖。
//
// 拆分自 utils/api.js。这一域的特殊性在于「本机发布的帖子要并入论坛流」：
// 离线发布的内容先落在 mock_posts，列表里必须看得见，否则会出现
// 「发布成功但社区里找不到」的割裂感。

const mock = require('../mock-data')
const { request, wait, currentUserId } = require('./core')
const { keywordMatcher } = require('./local')

function localPosts() {
  try { return wx.getStorageSync('mock_posts') || [] } catch (error) { return [] }
}
// 本机发布的帖子并入论坛流（作者固定为当前用户），保证 发布 → 社区 → 详情 链路可见
function localForumPosts() {
  return localPosts().map(p => ({
    id: p.id, authorId: currentUserId(), authorNickname: '林知夏', authorAvatar: '/assets/social/profile-avatar.webp',
    title: p.title, content: p.content, image: p.media && p.media[0] ? p.media[0] : '',
    tag: p.typeLabel || '校园日常', location: p.location || '',
    likeCount: p.likeCount || 0, commentCount: p.commentCount || 0, liked: false, followed: true,
    createdAt: '刚刚'
  }))
}
function mockForum(tag, page, size, scope) {
  const seen = new Set()
  const all = localForumPosts().concat((mock.discoveryPosts || []).map(p => ({
    id: p.id, authorId: p.authorId || p.id, authorNickname: p.author || '校园同学', authorAvatar: p.avatar || '', title: p.title,
    content: p.content, image: p.image || '', tag: p.tag || '校园日常', location: p.location || '',
    likeCount: p.likes || 0, commentCount: p.comments || 0, liked: !!p.liked, followed: !!p.followed,
    createdAt: p.time || '刚刚'
  })))
    // 按 id 去重：本机发布的帖子与演示数据 id 可能撞车，
    // 同一篇内容在列表里出现两次会被误认为「发重了」。
    .filter(p => {
      const key = String(p.id)
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
    .filter(p => {
      if (scope === 'followed') return !!p.followed
      return !tag || tag === '推荐' || p.tag.includes(tag) || (tag === '生活' && p.tag === '校园日常') || (tag === '社团' && p.tag === '社团活动') || (tag === '二手' && p.tag === '校园市集')
    })
  const start = (page - 1) * size
  return { list: all.slice(start, start + size), total: all.length }
}

function getForumPosts(params = {}) {
  // postScope：帖子按校可见范围。local=本校+跨校帖（默认），all=全部学校
  // schoolId：小程序里选中的学校，优先级高于账号所属学校
  const { tag = '', scope = '', postScope = 'local', page = 1, size = 10, userId = currentUserId(), schoolId = null } = params
  const school = schoolId ? `&schoolId=${encodeURIComponent(schoolId)}` : ''
  return request(`/posts?tag=${encodeURIComponent(tag)}&scope=${encodeURIComponent(scope)}&postScope=${encodeURIComponent(postScope)}&userId=${userId}&page=${page}&size=${size}${school}`, 'GET')
    .catch(error => {
      // 帖子流是主要页面，绝不能拿演示帖冒充真实内容：
      // 只有网络不通才回落到本地演示数据；服务端 401/500 要原样抛给页面去提示。
      if (error && error.server) throw error
      return wait({ ...mockForum(tag, page, size, scope), offline: true })
    })
}

function searchPosts(params = {}) {
  const keyword = String(params.keyword || '').trim()
  if (!keyword || keyword.length > 64) return Promise.reject(new Error(!keyword ? '请输入搜索关键词' : '关键词不能超过64字'))
  const page = Math.max(1, Math.floor(Number(params.page) || 1))
  const size = Math.min(50, Math.max(1, Math.floor(Number(params.size) || 10)))
  const userId = params.userId || currentUserId()
  return request(`/search?keyword=${encodeURIComponent(keyword)}&page=${page}&size=${size}&userId=${userId}`, 'GET').catch(error => {
    if (error.server) throw error
    const matcher = keywordMatcher(keyword)
    const all = mockForum('', 1, Number.MAX_SAFE_INTEGER, '').list.filter(post => matcher.test(post.title || '') || matcher.test(post.content || ''))
    return wait({ list: all.slice((page - 1) * size, page * size), total: all.length, offline: true })
  })
}

// 单帖详情：优先走后端 /posts/{id}。
// 只有在「网络不通」时才退化为在列表里翻找；服务端明确报错（401/403/500）直接抛出，
// 不能拿一堆列表数据去猜，否则帖子被删和「服务器挂了」在页面上长得一模一样。
// 兜底翻页而不是只取前 50 条：帖子多了以后，深处的内容也能靠 id 找到。
const POST_FALLBACK_PAGE_SIZE = 20
const POST_FALLBACK_MAX_PAGES = 25

async function findPostInList(id, userId) {
  for (let page = 1; page <= POST_FALLBACK_MAX_PAGES; page++) {
    const res = await getForumPosts({ page, size: POST_FALLBACK_PAGE_SIZE, userId })
    const list = res.list || []
    const found = list.find(x => String(x.id) === String(id))
    if (found) return { ...found, offline: !!res.offline }
    // 这一页没满，说明已经到底了，不用再翻
    if (list.length < POST_FALLBACK_PAGE_SIZE) break
  }
  throw new Error('帖子不存在或已删除')
}

function getPost(id, userId = currentUserId()) {
  return request(`/posts/${id}?userId=${userId}`, 'GET').then(p => {
    if (!p) throw new Error('帖子不存在')
    return p
  }).catch(error => {
    if (error && error.server) throw error
    return findPostInList(id, userId)
  })
}
function likePost(id, userId = currentUserId()) { return request(`/posts/${id}/like`, 'POST', { userId }).catch(error => { throw error }) }

function mockComments(id) {
  const pool = [
    { authorNickname: '林知夏', content: '求清单！正好考前复习用 🙋' },
    { authorNickname: '陈嘉树', content: '已留言，谢谢楼主整理' },
    { authorNickname: '苏晚', content: '收藏了，回头来取资料' }
  ]
  return pool.map((c, i) => ({ id: `${id}-${i + 1}`, ...c, createdAt: '刚刚' }))
}
// 评论是公开装饰数据：服务端异常时用演示评论兜底，不至于让详情页空白
function getPostComments(id) { return request(`/posts/${id}/comments`, 'GET').catch(() => wait(mockComments(id))) }
function addComment(id, content, userId = currentUserId()) { return request(`/posts/${id}/comments`, 'POST', { content, userId }).catch(error => { throw error }) }
// 评论点赞：liked=true 点赞，false 取消
function likeComment(commentId, liked) { return request(`/comments/${commentId}/like`, 'POST', { liked }).catch(error => { throw error }) }
function followUser(id, userId = currentUserId()) { return request(`/users/${id}/follow`, 'POST', { userId }).catch(error => { if (error.server) throw error; return wait({ followed: true }) }) }
function getHotTopics() { return request('/topics/hot').catch(() => wait([{ tag: '校园日常', count: 18 }, { tag: '学习', count: 12 }, { tag: '社团活动', count: 9 }])) }
// 活动轮播位：运营在 banner 表加行即可上线，无需发版；失败返回空数组，页面用本地默认
function getBanners(slot = 'discover') { return request(`/banners?slot=${encodeURIComponent(slot)}`, 'GET').catch(() => wait([])) }
// 浏览上报：详情页打开时调用，后端 view_count +1 并写浏览历史
function viewPost(id, userId = currentUserId()) { return request(`/posts/${id}/view?userId=${userId}`, 'POST').catch(error => { if (error.server) throw error; return wait({}) }) }

// 发帖仍复用原接口与签名；学习分类和当前学校是可选字段，网络写失败直接抛出。
function publishPost(payload, userId = currentUserId()) {
  return request('/posts', 'POST', {
    userId,
    title: payload.title,
    content: payload.content,
    image: payload.media && payload.media[0] ? payload.media[0] : '',
    tag: payload.learnCategory ? '学习' : (payload.typeLabel || '校园日常'),
    learnCategory: payload.learnCategory || null,
    schoolId: payload.learnCategory ? (payload.schoolId || null) : null,
    crossSchool: !!payload.crossSchool,
    location: payload.location || '',
    circleId: payload.circleId || null
  }).catch(error => { throw error })
}

module.exports = {
  localPosts, localForumPosts, mockForum,
  getForumPosts, searchPosts, getPost, likePost,
  getPostComments, addComment, likeComment, followUser,
  getHotTopics, getBanners, viewPost, publishPost
}
