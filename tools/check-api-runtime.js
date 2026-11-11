// 运行时检查：真实加载 utils/api.js，对比页面里 api.xxx() 的调用
const fs = require('fs');
const p = require('path');
const ROOT = p.resolve(__dirname, '..');

global.wx = new Proxy(
  {
    getStorageSync: () => '',
    setStorageSync: () => {},
    removeStorageSync: () => {},
    request: () => {},
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } })
  },
  { get: (t, k) => (k in t ? t[k] : () => {}) }
);
global.getApp = () => ({ globalData: {} });

let api;
try {
  api = require(p.join(ROOT, 'utils/api.js'));
} catch (e) {
  console.log('LOAD FAILED:', e.message);
  process.exit(1);
}
const exportsList = Object.keys(api).filter((k) => typeof api[k] === 'function');
const has = new Set(exportsList);

const used = new Map();
function scan(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const fp = p.join(dir, e.name);
    if (e.isDirectory()) { scan(fp); continue; }
    if (!e.name.endsWith('.js')) continue;
    const s = fs.readFileSync(fp, 'utf8');
    for (const m of s.matchAll(/\bapi\.([A-Za-z0-9_]+)\s*\(/g)) {
      const rel = p.relative(ROOT, fp).split(p.sep).join('/');
      if (!used.has(m[1])) used.set(m[1], new Set());
      used.get(m[1]).add(rel);
    }
  }
}
scan(p.join(ROOT, 'pages'));
scan(p.join(ROOT, 'components'));

const missing = [...used.keys()].filter((u) => !has.has(u)).sort();
console.log('api exports (function):', exportsList.length);
console.log('api.* distinct calls:', used.size);
console.log('called but NOT exported:', missing.length);
for (const m of missing) console.log('  -', m, '  <-', [...used.get(m)].slice(0, 3).join(', '));

// 反向：导出了但没人用（可能是死代码提示，仅供参考）
const unused = exportsList.filter((e) => !used.has(e)).sort();
console.log('exported but never called:', unused.length);
if (process.argv.includes('--unused')) console.log(unused.join(', '));