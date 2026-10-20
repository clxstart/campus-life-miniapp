// 帖子按校可见（混合模式）集成测试：需要后端已启动，否则跳过。
// 跑法：node --test tests/backend-posts-scope.test.js
const test = require('node:test')
const assert = require('node:assert/strict')

const BASE = process.env.API_BASE || 'http://127.0.0.1:8080/api'

async function serverUp() {
  try {
    const res = await fetch(BASE + '/topics/hot', { signal: AbortSignal.timeout(2000) })
    return res.ok
  } catch { return false }
}

async function getPosts(params) {
  const qs = new URLSearchParams({ userId: '1', page: '1', size: '50', ...params })
  const res = await fetch(`${BASE}/posts?${qs}`, { signal: AbortSignal.timeout(5000) })
  const body = await res.json()
  assert.equal(body.code, 0, `接口应成功：${JSON.stringify(body).slice(0, 120)}`)
  return body.data
}

test('postScope=local：只含本校帖 + 跨校帖（school_id 为空）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const data = await getPosts({ postScope: 'local' })
  const list = data.list || []
  assert.ok(list.length > 0, '本校流应有帖子')
  for (const post of list) {
    // 1 号用户的学校是 school_id=1；跨校帖 school_id 为 null
    assert.ok(
      post.schoolId === 1 || post.schoolId === null || post.schoolId === undefined,
      `local 流不应出现其他学校的帖子（id=${post.id} schoolId=${post.schoolId}）`
    )
  }
})

test('postScope=all：返回全部学校的帖子（是 local 的超集）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const local = await getPosts({ postScope: 'local' })
  const all = await getPosts({ postScope: 'all' })
  assert.ok(Number(all.total) >= Number(local.total), 'all 的总数应 >= local')
  // 如果库里存在跨校帖，它必须同时出现在两种流里
  const cross = (all.list || []).filter(p => p.schoolId === null || p.schoolId === undefined)
  if (cross.length) {
    const localIds = new Set((local.list || []).map(p => String(p.id)))
    for (const post of cross) {
      assert.ok(localIds.has(String(post.id)), `跨校帖 ${post.id} 应出现在 local 流`)
    }
  }
})

test('帖子 DTO 必须带 schoolId 与 crossSchool 标记（供前端展示「跨校」徽标）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const data = await getPosts({ postScope: 'all' })
  const first = (data.list || [])[0]
  assert.ok(first, '应有帖子')
  assert.ok('schoolId' in first, 'DTO 缺 schoolId 字段')
  assert.ok('crossSchool' in first, 'DTO 缺 crossSchool 字段')
})

test('缺省 postScope 等同于 local（本校优先）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const implicit = await getPosts({})
  const local = await getPosts({ postScope: 'local' })
  assert.equal(Number(implicit.total), Number(local.total), '缺省应与 local 一致')
})
