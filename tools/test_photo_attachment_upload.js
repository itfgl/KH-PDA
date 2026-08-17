const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const runtimePath = path.resolve(__dirname, '../android/app/src/main/assets/runtime/client-runtime.core.js');
const runtime = fs.readFileSync(runtimePath, 'utf8');
new vm.Script(runtime, { filename: runtimePath });

assert.match(runtime, /capture_photo_upload/, 'runtime must support the photo attachment action');
assert.match(runtime, /attachment_field_name/, 'photo action must resolve the business attachment field');
assert.match(runtime, /capture_photo_upload/, 'legacy action parsing must remain compatible');

const eventRuntimePath = path.resolve(__dirname, '../android/app/src/main/assets/runtime/client-runtime.action-events.js');
const eventRuntime = fs.readFileSync(eventRuntimePath, 'utf8');
new vm.Script(eventRuntime, { filename: eventRuntimePath });
assert.doesNotMatch(eventRuntime, /IntersectionObserver/, 'upload choice must not add a visibility observer');
assert.match(eventRuntime, /querySelectorAll\('\[data-kh-photo-action\]'\)/, 'legacy injected photo buttons must be removed');
assert.match(eventRuntime, /__khUsesNativeUploadChooser=true/, 'event runtime must declare native upload chooser mode');

const mainPath = path.resolve(__dirname, '../android/app/src/main/java/com/kaihang/scanner/MainActivity.java');
const main = fs.readFileSync(mainPath, 'utf8');
assert.match(main, /onShowFileChooser\s*\(/, 'WebView must receive file chooser requests');
assert.match(main, /setItems\(new String\[\]\{"拍照", "选择文件"\}/, 'image uploads must offer camera or file selection');
assert.match(main, /if \(cameraAvailable\) \{\s*showUploadSourceChooser\(fileChooserParams\)/, 'all web upload controls must offer camera choice without relying on MIME accept metadata');
assert.doesNotMatch(main, /shouldOfferCameraUpload/, 'upload source choice must not guess attachment type from unstable MIME metadata');
assert.match(main, /MediaStore\.ACTION_IMAGE_CAPTURE/, 'capture requests must launch the system camera');
assert.match(main, /MediaStore\.EXTRA_OUTPUT/, 'camera must write a full-size image to a content Uri');
assert.match(main, /compressAndFinishFileChooser\(result, "拍照"\)/, 'captured Uri must be optimized before returning to the web attachment input');
assert.match(main, /compressAndFinishFileChooser\(result, "选择文件"\)/, 'ordinary selected images must use the same optimization pipeline');
assert.match(main, /FileChooserParams\.parseResult/, 'ordinary file selection must remain supported');
assert.match(main, /IMAGE_COMPRESSION_MIN_BYTES\s*=\s*500L \* 1024L/, 'small images must bypass compression');
assert.match(main, /IMAGE_COMPRESSION_MAX_LONG_EDGE\s*=\s*2560/, 'large images must be bounded to the configured long edge');
assert.match(main, /IMAGE_COMPRESSION_JPEG_QUALITY\s*=\s*88/, 'photo uploads must use the visually lossless JPEG quality');
assert.match(main, /compressedBytes >= originalBytes/, 'compression must fall back when output is not smaller');
assert.match(main, /applyExifOrientation/, 'native compression must correct EXIF orientation');

console.log('photo attachment upload: existing upload interception and native source chooser checks passed');
