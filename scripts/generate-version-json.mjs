import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
if (args.length < 2) {
  console.error('Usage: node scripts/generate-version-json.mjs <manifestJson> <outputDir>');
  process.exit(1);
}

const [manifestFile, outputDir] = args;
const manifestPath = path.resolve(manifestFile);
const outputPath = path.resolve(outputDir);
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

const normalizedReleases = releases
  .filter((item) => item && Number.isFinite(Number.parseInt(String(item.versionCode ?? ''), 10)))
  .map((item) => ({
    versionCode: Number.parseInt(String(item.versionCode), 10),
    versionName: item.versionName || '',
    apkFileName: item.apkFileName || '',
    changelog: item.changelog || '无更新说明',
    releasedAt: item.releasedAt || '',
    notes: Array.isArray(item.notes) ? item.notes : [],
  }))
  .sort((a, b) => b.versionCode - a.versionCode);

const indexPayload = {
  currentVersionCode,
  currentVersionFile: `version-${currentVersionCode}.json`,
  releases: normalizedReleases.map((item) => ({
    versionCode: item.versionCode,
    versionName: item.versionName,
    apkFileName: item.apkFileName,
    releasedAt: item.releasedAt,
    versionFile: `version-${item.versionCode}.json`,
  })),
};

fs.mkdirSync(outputPath, { recursive: true });

for (const item of normalizedReleases) {
  const detailPayload = {
    versionCode: item.versionCode,
    versionName: item.versionName,
    apkFileName: item.apkFileName,
    apkUrl: `/app-updates/${item.apkFileName || 'app-release.apk'}`,
    changelog: item.changelog,
    releasedAt: item.releasedAt,
    notes: item.notes,
  };
  const detailPath = path.join(outputPath, `version-${item.versionCode}.json`);
  fs.writeFileSync(detailPath, `${JSON.stringify(detailPayload, null, 2)}\n`, 'utf8');
  console.log(`Wrote ${detailPath}`);
}

const indexPath = path.join(outputPath, 'versions.json');
fs.writeFileSync(indexPath, `${JSON.stringify(indexPayload, null, 2)}\n`, 'utf8');
console.log(`Wrote ${indexPath}`);
