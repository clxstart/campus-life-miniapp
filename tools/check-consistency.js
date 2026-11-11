// 静态一致性检查：页面注册 / 自定义组件注册。
// api 导出与调用的核对用 tools/check-api-runtime.js（运行时真实加载，更准）。
const fs = require('fs');
const p = require('path');
const ROOT = p.resolve(__dirname, '..');

const app = JSON.parse(fs.readFileSync(p.join(ROOT, 'app.json'), 'utf8'));
const reg = new Set(app.pages);
for (const sp of app.subPackages || app.subpackages || []) {
  for (const pg of sp.pages) reg.add(sp.root.replace(/\/$/, '') + '/' + pg);
}

// 1) 页面：目录 vs app.json 注册
const names = fs
  .readdirSync(p.join(ROOT, 'pages'), { withFileTypes: true })
  .filter((e) => e.isDirectory())
  .map((e) => e.name);
const pageDirs = names.filter(
  (n) => fs.existsSync(p.join(ROOT, 'pages', n, n + '.js')) || fs.existsSync(p.join(ROOT, 'pages', n, 'index.js'))
);
const missingReg = pageDirs
  .filter((n) => !reg.has('pages/' + n + '/' + n) && !reg.has('pages/' + n + '/index'))
  .map((n) => 'pages/' + n);
const regNoFile = [...reg].filter((r) => {
  const seg = r.split('/');
  return (
    !fs.existsSync(p.join(ROOT, r + '.js')) &&
    !fs.existsSync(p.join(ROOT, r, seg[seg.length - 1] + '.js')) &&
    !fs.existsSync(p.join(ROOT, r, 'index.js'))
  );
});

// 2) 组件：.json 里 usingComponents 指向的文件是否存在
const missingComp = [];
function scanJson(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const fp = p.join(dir, e.name);
    if (e.isDirectory()) {
      if (e.name === 'node_modules' || e.name.startsWith('.')) continue;
      scanJson(fp);
      continue;
    }
    if (!e.name.endsWith('.json')) continue;
    let j;
    try {
      j = JSON.parse(fs.readFileSync(fp, 'utf8'));
    } catch {
      continue;
    }
    const uc = j.usingComponents;
    if (!uc) continue;
    for (const [key, ref] of Object.entries(uc)) {
      let target;
      if (ref.startsWith('/')) target = p.join(ROOT, ref.slice(1));
      else if (ref.startsWith('.')) target = p.resolve(p.dirname(fp), ref);
      else continue; // 插件或 npm 组件，跳过
      if (!fs.existsSync(target + '.js')) {
        missingReg; // noop
        missingComp.push(`${p.relative(ROOT, fp).split(p.sep).join('/')} -> ${key} = ${ref}`);
      }
    }
  }
}
scanJson(ROOT);

console.log('=== 页面: app.json 注册', reg.size, '/ 目录', pageDirs.length);
console.log('  未注册的页面目录:', missingReg.length ? missingReg : 'none');
console.log('  注册了但没有 js 文件:', regNoFile.length ? regNoFile : 'none');
console.log('=== 组件: 引用缺失', missingComp.length ? missingComp : 'none');
if (missingReg.length || regNoFile.length || missingComp.length) process.exitCode = 1;