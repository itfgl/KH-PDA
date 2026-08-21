const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(
  path.resolve(__dirname, '../android/app/src/main/java/com/kaihang/scanner/MainActivity.java'),
  'utf8',
);

const declarations = source.match(/protected\s+void\s+onActivityResult\s*\(/g) || [];
assert.equal(declarations.length, 1, 'MainActivity must declare onActivityResult exactly once');

const methodStart = source.indexOf('protected void onActivityResult(');
const methodEnd = source.indexOf('\n    private ', methodStart);
assert.ok(methodStart >= 0 && methodEnd > methodStart, 'onActivityResult method body must be present');
const method = source.slice(methodStart, methodEnd);
assert.match(method, /requestCode\s*==\s*REQUEST_CAMERA_SCAN/, 'camera scan result branch must be retained');
assert.match(method, /requestCode\s*!=\s*REQUEST_EXPORT_LOGS/, 'log export result branch must be retained');
assert.equal((method.match(/super\.onActivityResult\s*\(/g) || []).length, 1, 'super callback must run once');

// 文件选择结果解析已拆分到 ImageUploadHelper，行为断言跟随迁移
const helperSource = fs.readFileSync(
  path.resolve(__dirname, '../android/app/src/main/java/com/kaihang/scanner/ImageUploadHelper.java'),
  'utf8',
);
const chooserMethodStart = helperSource.indexOf('static Uri[] extractFileChooserUris(');
const chooserMethodEnd = helperSource.indexOf('\n    /**', chooserMethodStart + 1) > 0
  ? helperSource.indexOf('\n    /**', chooserMethodStart + 1)
  : helperSource.indexOf('\n    private static ', chooserMethodStart + 1);
assert.ok(chooserMethodStart >= 0 && chooserMethodEnd > chooserMethodStart, 'file chooser URI extractor must be present');
const chooserMethod = helperSource.slice(chooserMethodStart, chooserMethodEnd);
assert.match(chooserMethod, /getClipData\s*\(/, 'multi-file results must be read from Intent ClipData');
assert.match(chooserMethod, /getItemCount\s*\(/, 'all ClipData items must be enumerated');
assert.match(chooserMethod, /getData\s*\(/, 'single-file Intent data must remain supported');
assert.match(chooserMethod, /FileChooserParams\.parseResult\s*\(/, 'WebView result parsing must remain as a compatibility fallback');

console.log('MainActivity onActivityResult: single callback and ClipData multi-file extraction retained');
