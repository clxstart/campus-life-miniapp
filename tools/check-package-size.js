const fs = require('fs');
const p = require('path');
const ROOT = p.resolve(__dirname, '..');
const cfg = JSON.parse(fs.readFileSync(p.join(ROOT, 'project.config.json'), 'utf8'));
const ign = (cfg.packOptions && cfg.packOptions.ignore) || [];

function match(rel, entry) {
  const v = entry.value;
  if (entry.type === 'folder') return rel === v || rel.startsWith(v + '/');
  if (entry.type === 'suffix') return rel.endsWith(v);
  if (entry.type === 'prefix') return rel.startsWith(v);
  if (entry.type === 'file') return rel === v;
  if (entry.type === 'regexp') return new RegExp(v).test(rel);
  return false;
}
function isIgnored(rel) {
  if (rel === '.git' || rel.startsWith('.git/')) return true;
  return ign.some((e) => match(rel, e));
}

let tot = 0;
let n = 0;
const top = {};
function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const fp = p.join(dir, e.name);
    const rel = p.relative(ROOT, fp).split(p.sep).join('/');
    if (e.isDirectory()) {
      if (isIgnored(rel) || isIgnored(rel + '/')) continue;
      walk(fp);
    } else {
      if (isIgnored(rel)) continue;
      const sz = fs.statSync(fp).size;
      tot += sz;
      n++;
      const key = rel.split('/').slice(0, 2).join('/');
      top[key] = (top[key] || 0) + sz;
    }
  }
}
walk(ROOT);
console.log('files:', n, 'MB:', (tot / 1048576).toFixed(2), '(excludes .git, honours packOptions.ignore)');
const arr = Object.entries(top).sort((a, b) => b[1] - a[1]);
for (const [k, v] of arr) console.log((v / 1048576).toFixed(3).padStart(8), 'MB ', k);