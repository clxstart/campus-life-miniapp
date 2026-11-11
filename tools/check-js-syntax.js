// 语法自检：把小程序里改过的 JS 全部交给 Node 解析一遍。
//
// 为什么需要它：小程序端没有单元测试，而这次改动横跨 8 个页面，
// 任何一处引号/括号写错，开发者工具里要等编译才报错。用 node --check 先兜住语法层面。
const fs = require('fs');
const p = require('path');
const { execFileSync } = require('child_process');

const ROOT = p.resolve(__dirname, '..');
const targets = [];

function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) {
      if (['node_modules', 'assets', 'docs', 'backend', 'web', 'outputs'].includes(e.name)) continue;
      if (e.name.startsWith('.')) continue;
      walk(p.join(dir, e.name));
      continue;
    }
    if (e.name.endsWith('.js')) targets.push(p.join(dir, e.name));
  }
}
walk(ROOT);

let bad = 0;
for (const file of targets) {
  try {
    execFileSync(process.execPath, ['--check', file], { stdio: 'pipe' });
  } catch (error) {
    bad += 1;
    console.log('语法错误:', p.relative(ROOT, file));
    console.log(String(error.stderr || error.message).split('\n').slice(0, 4).join('\n'));
  }
}
console.log('检查', targets.length, '个 JS，语法错误', bad, '个');
if (bad) process.exitCode = 1;