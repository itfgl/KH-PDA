# www/index.html 维护规则

修改此文件前请先阅读本文档，以下规则记录了历史踩坑和修复原因。

---

## 1. plugins.js 加载方式

```html
<!-- ✅ 正确 -->
<script src="plugins.js"></script>

<!-- ❌ 错误，会导致 window.ScanPlugin / KaihangNfc 等为 undefined -->
<script type="module" src="plugins.js"></script>
```

**原因**：`plugins.js` 由 Vite 以 **IIFE 格式**构建，所有导出挂到 `window.*`。
IIFE 本身不是 ES Module，用 `type="module"` 加载会导致全局变量失效。
如需 ESM 格式，需同步修改 `vite.config.js` 的 `formats: ['es']`，并处理代码分割问题（当前未实施）。

---

## 2. NFC 插件 API

```js
// ✅ 正确 —— KaihangNfc（自研插件，v2+）
const { KaihangNfc } = window;
KaihangNfc.addListener('nfcEvent', (event) => {
  // event.isNdef           → true 表示 NDEF 格式卡（NTAG213 等）
  // event.isMifareUltralight → true 表示 MifareUltralight 原始格式卡
  // event.ndefMessage      → NDEF 卡的记录数组，每条含 { tnf, type, id, payload }
  // event.mifareData       → 凯航自定义格式卡的业务数据字符串（已解析）
});

// ✅ 正确 —— 写卡（自动选择方式）
if (event.isNdef)           await KaihangNfc.writeNdef({ data: '...', allowFormat: true });
if (event.isMifareUltralight) await KaihangNfc.writeMifareRaw({ data: '...' });

// ❌ 错误 —— 旧 @capgo/capacitor-nfc API，已从项目中移除
NFC.addListener('nfcTag', (tag) => { ... });        // 事件名 nfcTag 不存在
tag.messages[0].records                              // 数据结构不同
NFC.write({ records: [ { tnf, type, id, payload } ] }) // 已替换为 writeNdef/writeMifareRaw
```

**原因**：`@capgo/capacitor-nfc` 已被替换为自研 `KaihangNfcPlugin.java`（支持 MifareUltralight 原始读写）。
原因和详细说明见 `NFC_RULES.md`。

---

## 3. 打印直接调用，无需走纸定位

```js
// ✅ 正确 —— 直接打印（黑标纸由 SDK 自身定位，普通纸连续走纸）
await PrintPlugin.printLabel({ qrCodeValue, textValue, paperType });

// ❌ prepareToPrintLabel() / checkBlack() 接口已删除（2026-08-21）
// 曾经每次打印前调用会多走一张空白标签；黑标定位已交给 SDK 黑标模式自身完成
```

**历史**：早期版本打印前调 `prepareToPrintLabel()` 做黑标定位，导致每张标签前多出一张空白。
现已从打印流程移除该调用，并连同 `prepareToPrintLabel` / `checkBlack` 原生接口一并删除。
更换标签纸后的重新定位由打印机自身上电校准完成，无需 App 干预。

---

## 4. 相机扫码（ZXing 降级）

```js
// ✅ 正确 —— 使用已打包的 window.ZXingReader
_camReader = new window.ZXingReader();

// ❌ 错误 —— ZXing 没有单独文件，不能这样加载
import { BrowserMultiFormatReader } from '@zxing/browser'; // 在 HTML 中无效
await import('@zxing/browser');                             // IIFE 格式不支持动态 import
```

**原因**：IIFE 格式不支持代码分割，`@zxing/browser` 已静态打包进 `plugins.js`（这是它体积大的原因）。
如要优化体积，需将 `vite.config.js` 改为 ESM 格式并处理相关兼容性，当前暂不实施。

---

## 5. 插件引用方式

```js
// ✅ 正确 —— 从 window 解构
const { KaihangNfc, ScanPlugin, PrintPlugin } = window;

// ❌ 错误 —— plugins.js 未加载完时 window 上没有这些变量
// 确保 <script src="plugins.js"> 在业务代码 <script> 之前
```

**plugins.js 当前提供的全局变量**（2026-08-21 精简后）：
- 插件：`ScanPlugin` / `PrintPlugin` / `KaihangNfc` / `UpdatePlugin` / `ClientConfigPlugin`
- 相机扫码：`ZXingReader`
- 元信息：`BUILD_TIME` / `BRIDGE_MODE`
- 服务端 API：`API`（登录/设置/角色路由；业务打印走动作表，不再经此模块）

已删除：`Printer` / `Scanner` / `NFC` / `Machine` / `Events`（src/modules 业务模块已随动作表方案退役）。

---

## 变更记录

| 日期 | 修复内容 |
|------|---------|
| 2026-06-04 | NFC API 从 @capgo 迁移到 KaihangNfc，事件名 nfcTag→nfcEvent |
| 2026-06-04 | 去掉打印前的 prepareToPrintLabel()，修复空白标签 |
| 2026-06-04 | 实现相机扫码（BarcodeDetector 优先，ZXing 降级） |
| 2026-06-04 | ZXing 回归 IIFE 静态打包，放弃 ESM 懒加载（兼容性优先） |
| 2026-06-13 | 批次标签改为普通纸（热敏）打印，不再黑标定位/checkBlack |
| 2026-08-20 | 打印全面二维码化，删除一维码批次标签 printBatchLabel 与机器二维码 printMachineQR |
| 2026-08-21 | 删除 prepareToPrintLabel/checkBlack 接口；删除 src/modules 业务模块（Printer/Scanner/NFC/Machine/Events）与 X8 壳适配器，plugins.js 只保留插件代理 + ZXing + API |
