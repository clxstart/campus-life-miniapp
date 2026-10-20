// 按接口记录连接失败，避免一个成功请求掩盖其他接口的离线回退。
const failed = new Set()
const listeners = []
let localDemo = false
let demo = false
function publish() {
  const next = localDemo || failed.size > 0
  if (demo === next) return
  demo = next
  listeners.slice().forEach(listener => listener(demo))
}
function keyFor(url) { return url.split('?')[0].replace(/\/conversations\/([^/]+)\/(read|clear|mute)$/, '/conversations/$1/messages').replace('/messages/read-all', '/conversations') }
function connection(url, online) {
  const key = keyFor(url)
  if (online) failed.delete(key)
  else failed.add(key)
  if (online) localDemo = false
  publish()
}
function setDemo(value) { localDemo = value; publish() }
function subscribe(listener) {
  listeners.push(listener)
  listener(demo)
  return () => { const index = listeners.indexOf(listener); if (index >= 0) listeners.splice(index, 1) }
}
module.exports = { setDemo, subscribe, connection }
