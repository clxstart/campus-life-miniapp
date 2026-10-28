// 校验 ai-chat 的 ui-popup 传参是否覆盖组件声明的全部属性
const fs = require('fs')
process.chdir('E:\\goodworlk\\campus-life-miniapp')

const comp = fs.readFileSync('components/ui-popup/index.js', 'utf8')
const pm = comp.match(/properties\s*:\s*\{([\s\S]*?)\n  \},/)
const declared = [...pm[1].matchAll(/^\s{4}([A-Za-z_]+)\s*:/gm)].map(x => x[1])

const wxml = fs.readFileSync('pages/ai-chat/ai-chat.wxml', 'utf8')
const start = wxml.indexOf('<ui-popup')
const end = wxml.indexOf('/>', start)
const block = wxml.slice(start, end)

const passed = [...block.matchAll(/([a-zA-Z][\w-]*)\s*=\s*"/g)]
  .map(x => x[1])
  .filter(n => !n.startsWith('bind') && !n.startsWith('data-'))
  .map(n => n.replace(/-([a-z])/g, (s, c) => c.toUpperCase()))

console.log('组件声明:', declared.join(', '))
console.log('页面传入:', passed.join(', '))

const missing = declared.filter(d => !passed.includes(d))
console.log('\n未传的属性:', missing.length ? missing.join(', ') : '无 ✅')

// 关键：id 是否存在
const hasId = /<ui-popup[^>]*\bid="ui-popup"/.test(wxml)
console.log('id="ui-popup" :', hasId ? '有 ✅（helper 能找到组件）' : '缺失 ❌')
