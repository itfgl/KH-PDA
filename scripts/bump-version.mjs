import fs from 'node:fs';
import path from 'node:path';

// release-manifest.json 版本管理脚本，两种模式：
//   1) 记录待发布说明（助手改完代码后调用，不改版本号）：
//      node scripts/bump-version.mjs <manifestJson> --note <说明>
//      说明累积到 manifest.pending.notes，构建时一起带出
//   2) 正式升版（构建时由 build-local-release.ps1 调用）：
//      node scripts/bump-version.mjs <manifestJson> [--use-pending | <changelog>]
//      --use-pending 使用累积的 pending 说明；<changelog> 用一行说明
//      升版后 pending 清空
const args = process.argv.slice(2);
if (args.length < 2) {
  console.error('Usage: node scripts/bump-version.mjs <manifestJson> (--note <说明> | [--use-pending | <changelog>])');
  process.exit(1);
}

const [manifestFile, ...rest] = args;
const manifestPath = path.resolve(manifestFile);
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));

const isNoteMode = rest[0] === '--note';
const usePending = rest[0] === '--use-pending';
const inlineChangelog = !isNoteMode && !usePending ? rest.join(' ').trim() : '';

if (isNoteMode) {
  const note = rest.slice(1).join(' ').trim();
  if (!note) {
    console.error('--note 需要说明内容');
    process.exit(1);
  }
  if (!manifest.pending) manifest.pending = { notes: [] };
  if (!Array.isArray(manifest.pending.notes)) manifest.pending.notes = [];
  if (!manifest.pending.notes.includes(note)) manifest.pending.notes.push(note);
  fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
  console.log(`Pending note added (${manifest.pending.notes.length} 条待发布): ${note}`);
  process.exit(0);
}

const currentCode = Number.parseInt(String(manifest.currentVersionCode ?? ''), 10);
if (!Number.isFinite(currentCode)) {
  throw new Error(`Invalid currentVersionCode: ${manifest.currentVersionCode ?? ''}`);
}
const releases = Array.isArray(manifest.releases) ? manifest.releases : [];
const current = releases.find((item) => Number.parseInt(String(item.versionCode ?? ''), 10) === currentCode);
if (!current) {
  throw new Error(`Release ${currentCode} not found in manifest`);
}

const pendingNotes = Array.isArray(manifest.pending?.notes)
  ? manifest.pending.notes.filter((item) => String(item || '').trim())
  : [];

let changelog = inlineChangelog;
let notes = [];
if (usePending) {
  if (!pendingNotes.length) {
    console.error('pending 中没有待发布说明，请改用直接传入 changelog 的方式');
    process.exit(1);
  }
  changelog = pendingNotes[0];
  notes = pendingNotes.slice(0);
} else if (inlineChangelog) {
  notes = [inlineChangelog];
}

const newCode = currentCode + 1;
const parts = String(current.versionName || '1.0.0').split('.');
if (parts.length < 3) parts.push('0');
parts[2] = String(Number.parseInt(parts[2] || '0', 10) + 1);
const newName = parts.join('.');

const entry = {
  versionCode: newCode,
  versionName: newName,
  apkFileName: `kaihang-scanner-v${newName}-${newCode}.apk`,
  changelog,
  releasedAt: new Date().toISOString().slice(0, 10),
  notes: [`Android 版本提升到 ${newName} (${newCode})`, ...notes],
};

manifest.currentVersionCode = newCode;
manifest.releases.unshift(entry);
if (usePending) delete manifest.pending;

fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
console.log(`Bumped to ${newName} (versionCode ${newCode}), apk=${entry.apkFileName}`);
if (usePending) console.log(`Consumed ${pendingNotes.length} pending notes.`);
