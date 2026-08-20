import fs from 'node:fs';
import path from 'node:path';

// 将 release-manifest.json 升一个版本：
//   versionCode + 1，versionName 末位 + 1（如 1.2.63 -> 1.2.64），
//   并在 releases 数组头部插入新条目，apkFileName 按现有规则自动生成。
// 用法: node scripts/bump-version.mjs <manifestJson> <changelog>
const args = process.argv.slice(2);
if (args.length < 2) {
  console.error('Usage: node scripts/bump-version.mjs <manifestJson> <changelog>');
  process.exit(1);
}

const [manifestFile, changelog] = args;
const manifestPath = path.resolve(manifestFile);
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));

const currentCode = Number.parseInt(String(manifest.currentVersionCode ?? ''), 10);
if (!Number.isFinite(currentCode)) {
  throw new Error(`Invalid currentVersionCode: ${manifest.currentVersionCode ?? ''}`);
}
const releases = Array.isArray(manifest.releases) ? manifest.releases : [];
const current = releases.find((item) => Number.parseInt(String(item.versionCode ?? ''), 10) === currentCode);
if (!current) {
  throw new Error(`Release ${currentCode} not found in manifest`);
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
  notes: [`Android 版本提升到 ${newName} (${newCode})`, changelog],
};

manifest.currentVersionCode = newCode;
manifest.releases.unshift(entry);

fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
console.log(`Bumped to ${newName} (versionCode ${newCode}), apk=${entry.apkFileName}`);
