const test = require('node:test')
const assert = require('node:assert/strict')

test('circle chat API uses cursor queries and POST for message delivery', async () => {
  const calls = []
  global.wx = {
    getStorageSync(key) { return key === 'campus_auth_token' ? 'jwt-token' : '' },
    request(options) {
      calls.push(options)
      options.success({ statusCode: 200, data: { code: 0, msg: 'ok', data: { id: 42, list: [] } } })
    }
  }
  const api = require('../utils/api')

  await api.getCircleMessages(8, { afterId: 31, size: 20 })
  assert.match(calls[0].url, /circle-messages\?circleId=8&limit=20&afterId=31$/)
  assert.equal(calls[0].method, 'GET')
  assert.equal(calls[0].header.Authorization, 'Bearer jwt-token')

  await api.getCircleMessages(8, { beforeId: 31, size: 10 })
  assert.match(calls[1].url, /limit=10&beforeId=31$/)

  await api.sendCircleMessage(8, { type: 'text', content: 'hello', clientId: 'client-1' })
  assert.equal(calls[2].url, 'http://127.0.0.1:8080/api/circle-messages')
  assert.equal(calls[2].method, 'POST')
  assert.deepEqual(calls[2].data, { circleId: 8, type: 'text', content: 'hello', image: '', clientId: 'client-1' })
})

test('app owns one circle socket, answers server ping, and forwards messages', async () => {
  let app
  global.App = definition => { app = definition }
  const sent = []
  let socket
  global.wx = {
    getStorageSync(key) { return key === 'campus_auth_token' ? 'jwt-token' : '' },
    connectSocket(options) {
      socket = {
        options,
        onOpen(callback) { this.open = callback },
        onMessage(callback) { this.message = callback },
        onError(callback) { this.error = callback },
        onClose(callback) { this.closeHandler = callback },
        send(message) { sent.push(JSON.parse(message.data)) },
        close() { if (this.closeHandler) this.closeHandler() }
      }
      return socket
    }
  }
  delete require.cache[require.resolve('../app')]
  require('../app')
  app.globalData.socketListeners = []
  const received = []
  const unsubscribe = app.onCircleMessage(message => received.push(message))
  const connected = app.connectCircleChat(12)
  assert.match(socket.options.url, /^ws:\/\/127\.0\.0\.1:8080\/ws\/circle\?token=jwt-token&circleId=12$/)
  socket.open()
  await connected

  socket.message({ data: JSON.stringify({ type: 'ping' }) })
  socket.message({ data: JSON.stringify({ type: 'message', data: { id: 9 } }) })
  assert.deepEqual(sent, [{ type: 'pong' }])
  assert.equal(received.some(message => message.type === 'message' && message.data.id === 9), true)
  unsubscribe()
  app.closeCircleChat()
  assert.equal(app.globalData.socket, null)
})
