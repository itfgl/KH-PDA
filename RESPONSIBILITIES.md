# android-entry — Android 信息录入端

## 硬件

X8 Android 定制一体机，集成：
- 条码扫描枪（需安装"扫码助手"App 作为硬件驱动）
- NFC 读写模块
- 热敏标签打印机（最大纸宽 384 点，8点=1mm）

## 当前阶段方案

**H5 壳子模式**：使用 X8 厂商提供的壳子 APK，H5 由服务端托管，WebView 加载远程地址，硬件能力通过 `PDAJsBridge` 调用。

**自研 Capacitor APK**（`com.kaihang.scanner`）：三路硬件能力已全部封装为 Capacitor Plugin，一个 APK 统一覆盖：
- 扫码：`ScanPlugin`（广播）
- 打印：`PrintPlugin`（集成 `uc_pda_sdk_native_temp_v1.16_240515.aar`）
- NFC：`KaihangNfcPlugin`（自研，支持 NDEF + MifareUltralight 双模）

## 技术栈

- H5 前端：React + TypeScript
- 当前壳子：X8 厂商壳（`apptest_printer_v2.2.6_20250725.apk`），注入 `PDAJsBridge`
- 后续壳子：自研 Capacitor Android 项目（`com.kaihang.scanner`）
- 服务端托管 H5 静态资源，WebView 加载 `http://公网IP:端口/static/`

## 职责

### 机器与批次确认
- 从服务端查询机器列表，操作员选择当前机器
- 显示当前机器的生产状态和产品类型

### 批次信息录入
- 扫描条码或读取 NFC 采集本批次物料数据
- 累积展示已采集列表，支持删除单条

### 批次码申请
- 提交物料信息到服务端，由服务端生成并返回 15 位批次码
- 客户端不自行拼码

### 标签打印
- 打印多张批次标签（数量由操作员指定）
- 标签内容：批次码一维码 + 批次码明文 + 机器编号 + 日期

---

## SDK 文档

### 一、扫码 SDK

**文档来源**：`sdk_extracted/scan_demo/`

**机制**：无独立 AAR，通过 Android 系统广播实现，需设备上安装"扫码助手"App。

#### 广播 Action

| 方向 | Action | 说明 |
|------|--------|------|
| 发送 | `com.uc.scanner.trigger.START` | 触发一次扫码 |
| 发送 | `com.uc.scanner.trigger.STOP` | 停止扫码 |
| 接收 | `com.uc.scanner.result` | 扫码结果回调 |

#### 结果 Extra

| Key | 类型 | 说明 |
|-----|------|------|
| `string` | String | 扫码结果字符串（常用） |
| `byteArray` | byte[] | 扫码结果字节数组（原始数据） |

#### Capacitor Plugin（`ScanPlugin`）JS 调用

```ts
// 监听扫码结果（插件加载时广播接收器已注册，无需手动 startScan 也能收到结果）
await ScanPlugin.addListener('scanResult', (data) => {
    console.log(data.value); // 扫码字符串
});

// 主动触发扫码（等效按硬件扳机键）
await ScanPlugin.startScan();

// 停止扫码
await ScanPlugin.stopScan();
```

#### 生命周期行为

| 时机 | 行为 |
|------|------|
| `load()` | 注册广播接收器（`RECEIVER_EXPORTED` on Android 13+） |
| `handleOnDestroy()` | **先发 `ACTION_STOP` 广播**（防止退出时扫码枪仍处于触发状态），再注销接收器 |

> **注意**：`ACTION_STOP` 广播在 `handleOnDestroy` 中无条件发送。即使 JS 侧从未调用过 `startScan()`，多发一次 STOP 对扫码助手 App 无副作用。

#### H5 壳子（厂商壳阶段）调用方式

```js
// 注册监听（注册后扫码结果通过 ReceiveControlCommand 回调）
PDAJsBridge.SendControlCommand(JSON.stringify({ name: 'setOnScan', data: 'onScan' }));

// 回调
function ReceiveControlCommand(action) {
    if (action.name === 'onScan') {
        const scanResult = action.data;
    }
}
```

---

### 二、打印 SDK

**文档来源**：`sdk_extracted/print_demo/`，`X8_安卓壳子_H5_对接资料包/web打印方法参数说明.txt`

**AAR 文件**：`PdaDemo/app/libs/uc_pda_sdk_native_temp_v1.16_240515.aar`

**包名**：`com.uc.pdasdk`

#### 核心类

| 类 | 说明 |
|----|------|
| `Printer` | 打印机控制主类 |
| `TextData` | 文字打印数据 |
| `BitmapData` | 图片打印数据 |
| `MixedData` | 混合打印数据（add 方式追加） |
| `AbsoluteLayoutBitmap` | 绝对坐标布局画布（标签纸用） |
| `BarcodeCreater` | 条码/二维码生成工具 |
| `BitmapUtils` | 图片旋转/翻转工具 |

#### Printer 主要 API

```java
// 初始化（Activity 生命周期内调用一次；快速重复调用由 isConnecting 标志守卫）
Printer.connect(activity, connectionHandler, printCallback);
// connectionHandler msg.what: 101=连接成功, 102=连接失败, 103=关闭成功
// printCallback: (PrintResult result, byte[] feedbackBytes, String flag) -> {}

// 普通纸打印
Printer.print(data, int top, int forwardMorePaper, String flag, boolean runOnNewThread);
// top: 距顶部空白，单位点（8点=1mm），范围 8~304
// forwardMorePaper: 打印后额外走纸，范围 0~248

// 标签纸打印（不走 forwardMorePaper，走纸由 prepareToPrintLabel/checkBlack 控制）
Printer.print(data, int top, String flag, boolean runOnNewThread);

// 标签就绪：走纸到第一张标签的起始位置（打印前调用一次）
Printer.prepareToPrintLabel();

// 检测黑标：每张标签打印完成（PRINT_OK 回调后）调用，走纸到下一张标签起始位置
// Java SDK 底层与 prepareToPrintLabel 相同；H5 壳子将其暴露为独立的 checkBlack 命令
Printer.prepareToPrintLabel(); // 即 checkBlack 的实现

// 关闭打印机
Printer.close(activity);
```

#### 标签纸多张打印完整流程

```
connect()
    └─ printStatus: connection=connected
         └─ prepareToPrintLabel()          // 走纸到第一张标签起始
              └─ printStatus: PREPARE_LABEL_OK
                   └─ printBatchLabel(...)  // 打印第一张
                        └─ printStatus: PRINT_OK
                             └─ checkBlack()  // 走纸到第二张标签起始  ← 必须调用
                                  └─ printStatus: PREPARE_LABEL_OK
                                       └─ printBatchLabel(...)  // 打印第二张
                                            └─ ...（循环直到所有张数打完）
```

> **注意**：每次 `PRINT_OK` 后必须调用 `checkBlack()`，否则打印机不走纸，下一张会从错误位置打印。
> `prepareToPrintLabel` 与 `checkBlack` 的区别仅是语义：前者用于"首次就绪"，后者用于"每张打完后继续"，底层调用相同。

#### BarcodeCreater

```java
// type: 1=一维码(Code128), 2=二维码(QR)
// displayCode: 是否在码下方显示内容字符串
Bitmap bmp = BarcodeCreater.createBarcode(context, content, width, height, displayCode, type);
```

#### AbsoluteLayoutBitmap（标签布局）

```java
Bitmap label = new AbsoluteLayoutBitmap(384, 280)  // 宽x高，单位点
    .addBmp(barcodeBmp, x, y)
    .addText("批次码：M05260604030012", textSize, x, y)  // y为文字基线位置
    .getBitmap();
BitmapData data = new BitmapData(label, 15, 0);  // 参数2=浓度(1~20), 参数3=普通纸时isAlignCenter
Printer.print(data, 16, "batch_label", false);
```

#### PrintResult 枚举（回调状态）

| 值 | 说明 |
|----|------|
| `PRINT_OK` | 打印完成 → 应继续调用 `checkBlack()` |
| `NO_PAPER` | 缺纸 |
| `PRINTER_CLOSED` | 打印机未连接 |
| `SEND_DATA_FAILED` | 数据发送失败 |
| `PRINT_FAILED` | 未知失败 |
| `BLACK_FLAG_NOT_FOUND` | 未检测到黑标/缝标（打印时） |
| `PREPARE_LABEL_OK` | 标签就绪完成 → 可调用 `print()` |
| `PREPARE_LABEL_BLACK_FLAG_NOT_FOUND` | 标签就绪时未检测到黑标 |
| `PREPARE_LABEL_PRINTER_CLOSED` | 标签就绪时打印机未连接 |
| `PREPARE_LABEL_SEND_DATA_FAILED` | 标签就绪时数据发送失败 |
| `PREPARE_LABEL_NO_PAPER` | 标签就绪时缺纸 |
| `PREPARE_LABEL_FAILED` | 标签就绪时未知失败 |

#### Capacitor Plugin（`PrintPlugin`）JS 调用

```ts
// 监听所有打印机状态事件
await PrintPlugin.addListener('printStatus', (e) => {
    if (e.connection) {
        // 'connected' | 'failed' | 'closed'
    }
    if (e.status) {
        // PrintResult 枚举值字符串，如 'PRINT_OK', 'PREPARE_LABEL_OK' 等
    }
});

await PrintPlugin.connect();
// → 等待 printStatus: connection=connected

await PrintPlugin.prepareToPrintLabel();
// → 等待 printStatus: status=PREPARE_LABEL_OK

for (let i = 0; i < copies; i++) {
    await PrintPlugin.printBatchLabel({ batchNo, machineId, productType, date });
    // → 等待 printStatus: status=PRINT_OK

    if (i < copies - 1) {
        await PrintPlugin.checkBlack();  // 走纸到下一张起始位
        // → 等待 printStatus: status=PREPARE_LABEL_OK
    }
}
```

#### 生命周期与状态管理规则

**状态标志**

| 标志 | 类型 | 说明 |
|------|------|------|
| `isConnected` | `boolean` | 打印机是否已连接（由 101/102/103 回调维护） |
| `isConnecting` | `boolean` | 连接请求进行中，防止 `connect()` 重复向 SDK 注册回调 |
| `destroyed` | `volatile boolean` | 插件已销毁，阻止异步回调继续执行 |

**`connect()` 防重入**：`isConnected=true` 时直接返回已连接；`isConnecting=true` 时静默返回，不再发起新连接。

**异步回调保护**：连接 Handler（103 回调）和 printCallback 均在入口处检查 `destroyed`，销毁后不再调用 `notifyListeners`。

**打印任务线程**：`printBatchLabel` / `printMachineQR` 提交到 `printExecutor`（`newSingleThreadExecutor`）执行，不使用裸 `Thread`。任务在位图生成前和调用 `Printer.print()` 前各检查一次 `destroyed`，提前退出。

**`handleOnPause()` / `handleOnResume()` 保活控制**：
- `handleOnPause()`：若当前已连接或连接中，关闭打印机（熄灭绿色指示灯），并记录 `wasConnectedBeforePause = true`
- `handleOnResume()`：若 `wasConnectedBeforePause = true` 且未销毁，自动调用 `doConnect()` 重连，重连结果仍通过 `printStatus` 事件通知 JS

**连接逻辑复用（`doConnect()`）**：
`connect()` 和 `handleOnResume()` 都调用同一个私有 `doConnect()` 方法，回调逻辑只维护一份。

**`handleOnDestroy()` 关闭顺序**：
```
destroyed = true           // 1. 先封锁所有异步回调和待执行任务
printExecutor.shutdownNow() // 2. 中断队列中尚未开始和正在执行的位图任务
Printer.close(activity)    // 3. 最后断开打印机连接（会触发异步 103，已被 destroyed 拦截）
```

#### H5 壳子（厂商壳阶段）调用方式

```js
// 标签纸打印（绝对坐标，画布 384×280）
PDAJsBridge.SendControlCommand(JSON.stringify({
    name: 'printBmpLabel',
    width: 384, height: 280, top: 8, concentration: 18,
    data: [
        { printType: 1, text: 'M05260604030012', desiredWidth: 300, desiredHeight: 40, displayCode: false, left: 0, top: 0 },
        { printType: 0, text: '批次码：M05260604030012', textSize: 24, x: 0, y: 60 }
    ]
}));

// 每张打印完 PRINT_OK 后调用（走纸到下一张）
PDAJsBridge.SendControlCommand(JSON.stringify({ name: 'checkBlack' }));

// printType: 0=文字, 1=一维码, 2=二维码, 3=图片(base64)
```

---

### 三、NFC 方案

**实现**：自研 `KaihangNfcPlugin`（`android/app/src/main/java/com/kaihang/scanner/plugins/KaihangNfcPlugin.java`）

**运行要求**：需要自研 Capacitor 壳（`com.kaihang.scanner`），不能用 X8 厂商壳子。

**选择自研原因**：设备使用 MifareUltralight 原始卡（非 NDEF），`@capgo/capacitor-nfc` 不支持页级别的原始读写，故自行封装。

#### 支持卡类型

| 卡类型 | 读写方式 | 说明 |
|--------|---------|------|
| NDEF 卡（NTAG213/215/216 等） | `writeNdef` / `nfcEvent.ndefMessage` | 双 Record：URI + AAR |
| MifareUltralight 原始卡 | `writeMifareRaw` / `readMifareRaw` | 自定义 KH 格式 |

#### 写卡格式

**NDEF（`writeNdef`）**：双 Record 保险
- Record 1（TNF=1，RTD_URI）：`kaihang://nfc/<业务数据>`
- Record 2（TNF=4，AAR）：`com.kaihang.scanner`（贴卡直接唤醒本应用）

**MifareUltralight 原始（`writeMifareRaw`）**：自定义 KH 格式，从第 4 页起写入：
```
[K][H][len_hi][len_lo][UTF-8 data...][padding to 4-byte boundary]
```

#### AndroidManifest.xml intent-filter（三级兜底）

```xml
<!-- 已写入凯航数据的卡：精确匹配 kaihang://nfc/ URI -->
<intent-filter>
    <action android:name="android.nfc.action.NDEF_DISCOVERED" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="kaihang" android:host="nfc" />
</intent-filter>

<!-- 空白卡 / 非 NDEF 卡 -->
<intent-filter>
    <action android:name="android.nfc.action.TECH_DISCOVERED" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
<meta-data android:name="android.nfc.action.TECH_DISCOVERED"
    android:resource="@xml/nfc_tech_filter" />

<!-- 兜底：任意 NFC 卡唤起本 App -->
<intent-filter>
    <action android:name="android.nfc.action.TAG_DISCOVERED" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
```

> intent-filter 仅用于 App 在后台时被 NFC Intent 唤起。App 在前台时由 Reader Mode（`enableReaderMode`）独占处理，不走 Intent 路径，`onNewIntent` 对插件无实际作用。

#### 状态管理规则（重要）

1. **`lastTag` 单次消费**：`writeNdef` / `writeMifareRaw` / `readMifareRaw` / `clearTag` 任意一个操作完成（无论成功还是失败）后，`lastTag` 自动清为 `null`。下一次操作前**必须重新贴卡**触发 `nfcEvent`，否则方法直接 reject `"No tag available"`。

2. **`stopScanning()` 同时清 `lastTag`**：停止扫描后旧 tag 引用立即失效。

3. **MifareUltralight I/O 串行化**：`readerCallback` 里的 Mifare 读取与 `writeMifareRaw`/`readMifareRaw` 统一经过 `newSingleThreadExecutor` 排队，不会并发 `connect`。

4. **`handleOnResume` 仅在 `scanningRequested=true` 时重启 Reader Mode**：调用 `stopScanning()` 后，切到后台再回来不会自动重新开启 NFC 扫描。

#### 生命周期行为

| 时机 | 行为 |
|------|------|
| `load()` | 若 NFC 可用，立即 `enableReaderMode`，设 `scanningRequested=true` |
| `handleOnResume()` | 仅 `scanningRequested=true` 时重新 `enableReaderMode` |
| `handleOnPause()` | 无条件 `disableReaderMode`（释放前台 NFC 独占） |
| `handleOnDestroy()` | `disableReaderMode` → `lastTag.set(null)` → `executor.shutdownNow()` |

> `handleOnDestroy` 中显式调用 `disableReaderMode` 是防御性措施：正常生命周期下 `handleOnPause` 已先执行，但显式清理更安全。`executor.shutdownNow()` 会向正在执行的 NFC I/O 任务发送中断信号；由于先禁用了 Reader Mode，不会有新任务入队。

#### Capacitor Plugin（`KaihangNfc`）JS 调用

```ts
// 监听 NFC 贴卡事件
await KaihangNfc.addListener('nfcEvent', (e) => {
    // e.techTypes: string[]
    // e.isNdef: boolean
    // e.isMifareUltralight: boolean
    // e.ndefMessage?: { tnf, type, id, payload }[]  — NDEF 卡自动读取
    // e.mifareData?: string                          — MifareUltralight 原始卡自动读取（KH 格式）
});

await KaihangNfc.startScanning();   // 可选，插件 load() 时已自动启动
await KaihangNfc.stopScanning();    // 暂停扫描（同时清除 lastTag）

// 写 NDEF（需先贴卡触发 nfcEvent）
await KaihangNfc.writeNdef({ data: '业务数据字符串', allowFormat: true });
// → 写完后 lastTag 自动清空，需重新贴卡才能再次操作

// 写 MifareUltralight 原始
await KaihangNfc.writeMifareRaw({ data: '业务数据字符串' });
// → 写完后 lastTag 自动清空

// 读 MifareUltralight 原始
const result = await KaihangNfc.readMifareRaw();
// result: { data: string, isKaihang: boolean, raw?: number[] }
// → 读完后 lastTag 自动清空

// 清空卡片数据
await KaihangNfc.clearTag();
// → 清完后 lastTag 自动清空
```

---

## Capacitor 迁移状态

| 能力 | 状态 | 实现文件 |
|------|------|---------|
| 扫码 | ✅ 已完成 | `plugins/ScanPlugin.java` |
| 打印 | ✅ 已完成 | `plugins/PrintPlugin.java` |
| NFC | ✅ 已完成（自研，非 @capgo/capacitor-nfc） | `plugins/KaihangNfcPlugin.java` |

迁移前提：设备上保留"扫码助手"App（扫码硬件驱动，不可移除）。

## 文件索引

| 文件/目录 | 说明 |
|-----------|------|
| `android/app/src/main/java/com/kaihang/scanner/plugins/` | 三个 Capacitor Plugin 实现 |
| `android/app/src/main/AndroidManifest.xml` | 含 NFC intent-filter 三级兜底 |
| `src/bridge/PDABridge.ts` | H5 壳子阶段 PDAJsBridge 类型化封装 |
| `src/pages/NfcPage.tsx` | NFC 读写页面 |
| `sdk_extracted/scan_demo/` | 扫码广播 SDK 源码 demo |
| `sdk_extracted/print_demo/PdaDemo/app/libs/` | 打印 AAR 文件 |
| `X8_安卓壳子_H5_对接资料包/` | X8 壳子 H5 对接 demo（含 checkBlack 等原始说明） |
| `X8_安卓本地服务器_对接资料包/` | X8 本地服务器模式 demo（备用参考） |
