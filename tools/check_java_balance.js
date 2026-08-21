// Java 源文件括号配平检查（捕获拼接/删改事故类结构错误）
const fs = require('fs');
const path = require('path');

const base = path.resolve(__dirname, '../android/app/src/main/java/com/kaihang/scanner');
const files = [
  'MainActivity.java',
  'NativeWebBridge.java',
  'NativeControlOverlay.java',
  'NativeLogDialog.java',
  'ImageUploadHelper.java',
  'plugins/PrintPlugin.java',
  'plugins/LabelLayoutBuilder.java',
];

let allOk = true;
for (const f of files) {
  const raw = fs.readFileSync(path.join(base, f), 'utf8');
  // 粗剥离字符串/字符/注释后再配平（不追求完美，只求捕获大事故）
  const clean = raw
    .replace(/\\[\\'"/nrt]/g, '')      // 转义序列
    .replace(/"(?:[^"\\]|\\.)*"/g, '""') // 字符串
    .replace(/'(?:[^'\\]|\\.)*'/g, "''") // 字符
    .replace(/\/\*[\s\S]*?\*\//g, '')    // 块注释
    .replace(/\/\/.*$/gm, '');           // 行注释
  let depth = 0, minDepth = 0;
  for (const ch of clean) {
    if (ch === '{') depth++;
    else if (ch === '}') depth--;
    if (depth < minDepth) minDepth = depth;
  }
  const parenDiff = (clean.match(/\(/g) || []).length - (clean.match(/\)/g) || []).length;
  const ok = depth === 0 && minDepth === 0 && parenDiff === 0;
  if (!ok) allOk = false;
  console.log(f.padEnd(26), ok ? 'OK' : `异常 brace=${depth} min=${minDepth} paren=${parenDiff}`);
}
console.log(allOk ? '全部配平正常' : '存在结构问题!');
process.exit(allOk ? 0 : 1);
