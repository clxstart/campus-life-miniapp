// 二手按校隔离集成测试：需要后端已启动 + 已执行 tests/fixtures/school-isolation.sql
// 跑法：node --test tests/backend-goods-scope.test.js
const test = require('node:test')
const assert = require('node:assert/strict')

const BASE = process.env.API_BASE || 'http://127.0.0.1:8080/api'

async function serverUp() {
  try {
    const res = await fetch(BASE + '/topics/hot', { signal: AbortSignal.timeout(2000) })
    return res.ok
  } catch { return false }
}

async function getGoods(params) {
  const qs = new URLSearchParams({ mode: '1', userId: '1', ...params })
  const res = await fetch(`${BASE}/goods?${qs}`, { signal: AbortSignal.timeout(5000) })
  const body = await res.json()
  assert.equal(body.code, 0, `接口应成功：${JSON.stringify(body).slice(0, 120)}`)
  return body.data
}

test('schoolScope=local：二手列表只含本校商品（异地无法面交）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const list = await getGoods({ schoolScope: 'local' })
  assert.ok(list.length > 0, '本校应有商品')
  // 按标题断言：只看 schoolId 字段会在字段缺失时假通过
  const titles = list.map(item => item.title || '')
  assert.ok(
    !titles.some(title => title.includes('北大二手教材')),
    `local 流不应出现外校商品，实际返回：${titles.join(' / ')}`
  )
  for (const item of list) {
    if ('schoolId' in item && item.schoolId !== null) {
      assert.notEqual(Number(item.schoolId), 2, `local 流混入外校商品：${item.title}`)
    }
  }
})

test('schoolScope=all：包含外校商品（是 local 的超集）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const local = await getGoods({ schoolScope: 'local' })
  const all = await getGoods({ schoolScope: 'all' })
  assert.ok(all.length > local.length, 'all 应比 local 多出外校商品')
  assert.ok(all.some(item => Number(item.schoolId) === 2), 'all 应包含外校商品')
})

test('缺省 schoolScope 等同 local（本校优先）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const implicit = await getGoods({})
  const local = await getGoods({ schoolScope: 'local' })
  assert.equal(implicit.length, local.length, '缺省应与 local 一致')
})

test('商品 DTO 必须带 schoolId（供前端区分本校/外校）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const list = await getGoods({ schoolScope: 'all' })
  assert.ok(list.length > 0, '应有商品')
  assert.ok('schoolId' in list[0], 'DTO 缺 schoolId 字段')
})

test('发布商品必须记录发布者的学校（否则新商品会变成无主数据）', async (t) => {
  if (!(await serverUp())) return t.skip('后端未启动')
  const title = `E2E校归属测试-${Date.now()}`
  const res = await fetch(`${BASE}/goods`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      userId: 1, mode: 1, title, price: '12', conditionLevel: '九成新', category: '其他',
      description: '测试', images: ['/assets/home/recommend-lamp.jpg']
    }),
    signal: AbortSignal.timeout(5000)
  })
  const created = await res.json()
  assert.equal(created.code, 0, '发布应成功')
  const id = created.data.id

  const detailRes = await fetch(`${BASE}/goods/${id}`, { signal: AbortSignal.timeout(5000) })
  const detail = await detailRes.json()
  assert.equal(detail.code, 0)
  assert.equal(Number(detail.data.schoolId), 1, '新发布的商品应归属发布者的学校（school_id=1）')

  // 清理
  await fetch(`${BASE}/goods/${id}`, { method: 'DELETE', signal: AbortSignal.timeout(5000) }).catch(() => {})
})
