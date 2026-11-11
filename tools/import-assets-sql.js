// 生成 media_asset 的导入 SQL（图片 → MySQL LONGBLOB）。
//
// 为什么用 SQL 文件而不是走 HTTP 上传：本机没有 admin token，直接用 X'..hex..' 字面量
// 交给 mysql 客户端一次导入，可重复、可审计，也不需要启动后端。
//
// 用法：node tools/import-assets-sql.js   → 产出 outputs/assets-to-server/import.sql
const fs = require('fs');
const p = require('path');
const crypto = require('crypto');

const ROOT = p.resolve(__dirname, '..');
const SRC = p.join(ROOT, 'outputs', 'assets-to-server');
const OUT = p.join(SRC, 'import.sql');

const MAGIC = [
  ['image/png', Buffer.from([0x89, 0x50, 0x4e, 0x47])],
  ['image/jpeg', Buffer.from([0xff, 0xd8, 0xff])],
  ['image/gif', Buffer.from('GIF8')],
  ['image/webp', Buffer.from('RIFF')]
];

function contentType(buf) {
  for (const [type, sig] of MAGIC) {
    if (buf.subarray(0, sig.length).equals(sig)) {
      if (type === 'image/webp' && buf.subarray(8, 12).toString() !== 'WEBP') continue;
      return type;
    }
  }
  return null;
}

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const fp = p.join(dir, e.name);
    if (e.isDirectory()) walk(fp, out);
    else if (/\.(png|jpe?g|gif|webp)$/i.test(e.name)) out.push(fp);
  }
  return out;
}

const files = walk(SRC).sort();
const seen = new Map();
const rows = [];
for (const fp of files) {
  const buf = fs.readFileSync(fp);
  const ct = contentType(buf);
  if (!ct) {
    console.error('跳过（不是有效图片）:', p.relative(SRC, fp));
    continue;
  }
  const name = p.basename(fp);
  if (seen.has(name)) {
    console.error('重名！同一 asset_name 只能有一个:', name, fp, seen.get(name));
    process.exit(1);
  }
  seen.set(name, fp);
  const key = p
    .relative(SRC, fp)
    .split(p.sep)
    .join('/')
    .replace(/\.[^.]+$/, '');
  rows.push({
    key,
    name,
    ct,
    size: buf.length,
    sha: crypto.createHash('sha256').update(buf).digest('hex'),
    hex: buf.toString('hex')
  });
}

const lines = [
  '-- 由 tools/import-assets-sql.js 生成，勿手工编辑',
  '-- 图片以 LONGBLOB 形式入库（media_asset），可重复执行（按 asset_key / asset_name 幂等覆盖）',
  'SET NAMES utf8mb4;',
  ''
];
for (const r of rows) {
  lines.push(
    `INSERT INTO media_asset (asset_key, asset_name, content_type, byte_size, sha256, body, public_flag) VALUES (` +
      `'${r.key}', '${r.name}', '${r.ct}', ${r.size}, '${r.sha}', X'${r.hex}', 1)` +
      ` ON DUPLICATE KEY UPDATE content_type=VALUES(content_type), byte_size=VALUES(byte_size), ` +
      `sha256=VALUES(sha256), body=VALUES(body), public_flag=VALUES(public_flag);`
  );
}
lines.push('');
lines.push(`-- 合计 ${rows.length} 张，${(rows.reduce((a, r) => a + r.size, 0) / 1024).toFixed(1)} KB`);

fs.writeFileSync(OUT, lines.join('\n'), 'utf8');
console.log('生成', p.relative(ROOT, OUT));
console.table(rows.map((r) => ({ name: r.name, key: r.key, type: r.ct, kb: +(r.size / 1024).toFixed(1) })));
console.log('合计', rows.length, '张，', (rows.reduce((a, r) => a + r.size, 0) / 1024).toFixed(1), 'KB，SQL 体积',
  (fs.statSync(OUT).size / 1024 / 1024).toFixed(2), 'MB');