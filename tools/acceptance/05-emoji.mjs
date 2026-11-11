#!/usr/bin/env node
/**
 * 圈子 / 私聊 emoji 验收（方案 §8.2 / §11.11）
 *
 * 覆盖：
 *   1. emoji 与普通文字混合发送 → 200，返回内容逐字一致
 *   2. WebSocket 推送到达（同一圈子订阅者收到 message 帧）
 *   3. 刷新（重新拉历史）后仍能看到同一条消息，且只出现一次
 *   4. 纯空白消息 → 400
 *   5. 超长消息（1001 个 emoji，按 Unicode 码点计）→ 400
 *   6. 相同 clientId 重试 → 返回同一条消息，不重复落库、不重复推送
 *   7. 相同 clientId 换内容 → 400（不能被当成同一条消息）
 *   8. 私聊同样支持 emoji 混发、重试幂等、空消息/超长消息拒绝
 *
 * 前置：后端跑在 127.0.0.1:8080，带 CAMPUS_DEV_LOGIN_ANY_USER=true；
 *       用户 1 是圈子 2 的成员（测试库既有数据）。
 *
 * 退出码 0 = 全过。
 */
const API = process.env.API || 'http://127.0.0.1:8080'
const WS = process.env.WS || 'ws://127.0.0.1:8080/ws/circle'
const CIRCLE = Number(process.env.CIRCLE || 2)
const PEER = Number(process.env.PEER || 3)

let pass = 0
let fail = 0
function check(name, expected, actual) {
  const ok = JSON.stringify(expected) === JSON.stringify(actual)
  if (ok) { pass += 1; console.log(`  PASS  ${name}  [${JSON.stringify(actual)}]`) }
  else { fail += 1; console.log(`  FAIL  ${name}  (expected [${JSON.stringify(expected)}], got [${JSON.stringify(actual)}])`) }
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

async function api(path, { method = 'GET', token, body } = {}) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(`${API}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  let json = null
  try { json = await response.json() } catch { /* 非 JSON 响应留给断言 */ }
  return { status: response.status, json }
}

async function login(userId) {
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const res = await api('/api/auth/dev-login', { method: 'POST', body: { userId } })
    if (res.status === 200 && res.json?.data?.token) return res.json.data.token
    if (res.status === 429) {
      console.log(`  (dev-login 命中限流，等待 61s 后重试)`)
      await sleep(61000)
    } else {
      throw new Error(`dev-login 失败：HTTP ${res.status} ${JSON.stringify(res.json)}`)
    }
  }
  throw new Error('dev-login 多次失败')
}

/** 发送 + 429 时等一个限流窗口重试一次（脚本可复跑） */
async function send(path, token, body) {
  let res = await api(path, { method: 'POST', token, body })
  if (res.status === 429) {
    console.log('  (发送命中限流，等待 61s 后重试)')
    await sleep(61000)
    res = await api(path, { method: 'POST', token, body })
  }
  return res
}

function waitForPush(socket, predicate, timeoutMs = 8000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.removeEventListener('message', onMessage)
      reject(new Error('等待 WebSocket 推送超时'))
    }, timeoutMs)
    function onMessage(event) {
      try {
        const frame = JSON.parse(String(event.data))
        if (predicate(frame)) {
          clearTimeout(timer)
          socket.removeEventListener('message', onMessage)
          resolve(frame)
        }
      } catch { /* 忽略非 JSON 帧 */ }
    }
    socket.addEventListener('message', onMessage)
  })
}

async function connect(token) {
  const socket = new WebSocket(`${WS}?token=${encodeURIComponent(token)}&circleId=${CIRCLE}`)
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('WebSocket 连接超时')), 8000)
    socket.addEventListener('open', () => { clearTimeout(timer); resolve() }, { once: true })
    socket.addEventListener('error', () => { clearTimeout(timer); reject(new Error('WebSocket 连接失败')) }, { once: true })
  })
  return socket
}

async function main() {
  const run = Date.now()
  console.log('== emoji 验收 ======================================================')
  const token = await login(1)
  console.log(`  token 长度 ${token.length}，圈子 ${CIRCLE}，私聊对象 ${PEER}`)

  const socket = await connect(token)
  console.log('  WebSocket 已连接')

  try {
    // ---------- 1. 圈子：emoji + 文字混发 ----------
    const circleContent = `今晚一起 😀🏀 打球？#${run}`
    const circleClient = `emoji-circle-${run}`
    const pushPromise = waitForPush(socket, frame =>
      frame.type === 'message' && frame.data?.content === circleContent)
    const sent = await send('/api/circle-messages', token, {
      circleId: CIRCLE, type: 'text', content: circleContent, clientId: circleClient
    })
    check('圈子 emoji 混发 -> 200', 200, sent.status)
    check('返回内容与发送内容逐字一致', circleContent, sent.json?.data?.content)
    check('消息类型仍是 text（不是图片/表情包）', 'text', sent.json?.data?.messageType)

    const pushed = await pushPromise
    check('WebSocket 推送到达且内容一致', circleContent, pushed.data.content)

    // ---------- 2. 刷新后仍可见，且只出现一次 ----------
    const history = await api(`/api/circle-messages?circleId=${CIRCLE}&limit=50`, { token })
    const historyRows = history.json?.data?.list || []
    check('重新拉历史 -> 200', 200, history.status)
    check('刷新后能看到该消息，且只出现一次', 1,
      historyRows.filter(row => row.content === circleContent).length)

    // ---------- 3. 空白消息 ----------
    const empty = await send('/api/circle-messages', token, {
      circleId: CIRCLE, type: 'text', content: '   ', clientId: `emoji-empty-${run}`
    })
    check('圈子纯空白消息 -> 400', 400, empty.status)

    // ---------- 4. 超长消息（按 Unicode 码点计 1001） ----------
    const tooLong = '😀'.repeat(1001)
    const longRes = await send('/api/circle-messages', token, {
      circleId: CIRCLE, type: 'text', content: tooLong, clientId: `emoji-long-${run}`
    })
    check('圈子超长消息（1001 码点）-> 400', 400, longRes.status)

    // ---------- 5. 弱网重试：同 clientId 同内容 ----------
    const retry = await send('/api/circle-messages', token, {
      circleId: CIRCLE, type: 'text', content: circleContent, clientId: circleClient
    })
    check('同 clientId 重试 -> 200', 200, retry.status)
    check('重试返回同一条消息 id', sent.json?.data?.id, retry.json?.data?.id)
    const history2 = await api(`/api/circle-messages?circleId=${CIRCLE}&limit=50`, { token })
    check('重试后历史里仍只有一条', 1,
      (history2.json?.data?.list || []).filter(row => row.content === circleContent).length)

    // ---------- 6. 同 clientId 换内容 ----------
    const conflict = await send('/api/circle-messages', token, {
      circleId: CIRCLE, type: 'text', content: `换了内容 😀#${run}`, clientId: circleClient
    })
    check('同 clientId 换内容 -> 400', 400, conflict.status)

    // ---------- 7. 私聊 ----------
    const privateContent = `私聊 emoji 😀🎉#${run}`
    const privateClient = `emoji-private-${run}`
    const first = await api(`/api/conversations/${PEER}/messages`, {
      method: 'POST', token,
      body: { userId: 1, content: privateContent, clientId: privateClient }
    })
    check('私聊 emoji 混发 -> 200', 200, first.status)
    check('私聊返回内容逐字一致', privateContent, first.json?.data?.content)

    const retryPrivate = await api(`/api/conversations/${PEER}/messages`, {
      method: 'POST', token,
      body: { userId: 1, content: privateContent, clientId: privateClient }
    })
    check('私聊同 clientId 重试 -> 200', 200, retryPrivate.status)
    check('私聊重试返回同一条消息 id', first.json?.data?.id, retryPrivate.json?.data?.id)

    const privateConflict = await api(`/api/conversations/${PEER}/messages`, {
      method: 'POST', token,
      body: { userId: 1, content: `私聊换内容 😀#${run}`, clientId: privateClient }
    })
    check('私聊同 clientId 换内容 -> 400', 400, privateConflict.status)

    const privateEmpty = await api(`/api/conversations/${PEER}/messages`, {
      method: 'POST', token,
      body: { userId: 1, content: '   ', clientId: `emoji-private-empty-${run}` }
    })
    check('私聊空白消息 -> 400', 400, privateEmpty.status)

    const privateLong = await api(`/api/conversations/${PEER}/messages`, {
      method: 'POST', token,
      body: { userId: 1, content: '😀'.repeat(1001), clientId: `emoji-private-long-${run}` }
    })
    check('私聊超长消息 -> 400', 400, privateLong.status)

    const privateHistory = await api(`/api/conversations/${PEER}/messages?userId=1&size=50`, { token })
    check('私聊刷新后能看到该消息，且只出现一次', 1,
      (privateHistory.json?.data?.list || []).filter(row => row.content === privateContent).length)
  } finally {
    socket.close()
  }

  console.log('')
  console.log(`  RESULT: ${pass} passed, ${fail} failed`)
  if (fail > 0) process.exit(1)
}

main().catch(error => {
  console.error(`  FATAL: ${error.message}`)
  process.exit(2)
})
