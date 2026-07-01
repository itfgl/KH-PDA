import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
if (args.length < 2) {
  console.error('Usage: node scripts/generate-version-json.mjs <manifestJson> <outputJson>');
  process.exit(1);
}

const [manifestFile, outputFile] = args;
const manifestPath = path.resolve(manifestFile);
const outputPath = path.resolve(outputFile);
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const releases = Array.isArray(manifest.releases) ? manifest.releases : [];
const currentVersionCode = Number.parseInt(String(manifest.currentVersionCode ?? ''), 10);
if (!Number.isFinite(currentVersionCode)) {
  throw new Error(`Invalid currentVersionCode: ${manifest.currentVersionCode ?? ''}`);
}
const currentEntry = releases.find((item) => Number.parseInt(String(item.versionCode ?? ''), 10) === currentVersionCode);
if (!currentEntry) {
  throw new Error(`Release ${currentVersionCode} not found in manifest`);
}

const normalizedHistory = releases
  .filter((item) => item && Number.isFinite(Number.parseInt(String(item.versionCode ?? ''), 10)))
  .map((item) => ({
    versionCode: Number.parseInt(String(item.versionCode), 10),
    versionName: item.versionName || '',
    apkFileName: item.apkFileName || '',
    changelog: item.changelog || '无更新说明',
    releasedAt: item.releasedAt || '',
  }))
  .sort((a, b) => b.versionCode - a.versionCode);

const apkUrl = `/${currentEntry.apkFileName || 'app-release.apk'}`;

const payload = {
  currentVersionCode,
  releases: normalizedHistory,
};

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, `${JSON.stringify(payload, null, 2)}\n`, 'utf8');
console.log(`Wrote ${outputPath}`);
