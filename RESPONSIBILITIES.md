# android-entry — Android 信息录入端

## 硬件

X8 Android 定制一体机，集成：
- 条码扫描枪（需安装"扫码助手"App 作为硬件驱动）
- NFC 读写模块
- 热敏标签打印机（最大纸宽 384 点，8点=1mm）

## 当前阶段方案

**H5 壳子模式**：使用 X8 厂商提供的壳子 APK，H5 由服务端托管，WebView 加载远程地址，硬件能力通过 `PDAJsBridge` 调用。

**后续合并方案**：迁移至自研 Capacitor APK，将打印 AAR 和扫码广播封装为 Capacitor Plugin，NFC 使用 `@capgo/capacitor-nfc`，一个 APK 统一覆盖所有硬件能力（风险低，可行）。

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

**文档来源**：`sdk_extracted/scan_demo/`，`sdk_extracted/broadcast/index.vue`

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

#### Android 原生接入示例

```java
IntentFilter filter = new IntentFilter("com.uc.scanner.result");
registerReceiver(new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
        String result = intent.getStringExtra("string");
    }
}, filter);
```

#### H5 壳子（当前阶段）调用方式

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

**文档来源**：`sdk_extracted/print_demo/`

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
// 初始化（Activity 生命周期内调用一次）
Printer.connect(activity, connectionHandler, printCallback);
// connectionHandler msg.what: 101=连接成功, 102=连接失败, 103=关闭成功
// printCallback: (PrintResult result, byte[] feedbackBytes, String flag) -> {}

// 普通纸打印
Printer.print(data, int top, int forwardMorePaper, String flag, boolean runOnNewThread);
// top: 距顶部空白，单位点（8点=1mm），范围 8~304
// forwardMorePaper: 打印后额外走纸，范围 0~248

// 标签纸打印（不走 forwardMorePaper，走纸由 prepareToPrintLabel 控制）
Printer.print(data, int top, String flag, boolean runOnNewThread);

// 标签就绪（打印前调用，走纸到标签起始位置）
Printer.prepareToPrintLabel();

// 检测黑标（打印完成回调后调用，走到下一张标签起始位置）
// 注：在 PrintResult.PRINT_OK 回调后调用

// 关闭打印机
Printer.close(activity);
```

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
BitmapData data = new BitmapData(label, 12, 0);  // 参数2=浓度, 参数3=普通纸时isAlignCenter
Printer.print(data, 16, "batch_label", false);
```

#### PrintResult 枚举（回调状态）

| 值 | 说明 |
|----|------|
| `PRINT_OK` | 打印完成 |
| `NO_PAPER` | 缺纸 |
| `PRINTER_CLOSED` | 打印机未连接 |
| `SEND_DATA_FAILED` | 数据发送失败 |
| `PRINT_FAILED` | 未知失败 |
| `BLACK_FLAG_NOT_FOUND` | 未检测到黑标/缝标 |
| `PREPARE_LABEL_OK` | 标签就绪完成 |
| `PREPARE_LABEL_*` | 标签就绪阶段各类失败 |

#### H5 壳子（当前阶段）调用方式

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

// printType: 0=文字, 1=一维码, 2=二维码, 3=图片(base64)
```

---

### 三、NFC 方案

**实现库**：`@capgo/capacitor-nfc`（Capacitor 插件）

**运行要求**：需要自研 Capacitor 壳（`com.kaihang.scanner`），不能用 X8 厂商壳子。

**写卡格式**：双 Record 保险
- Record 1（TNF=1）：URI Record，内容 `kaihang://nfc/<业务数据>`
- Record 2（TNF=4）：AAR，内容 `com.kaihang.scanner`，确保贴卡直接唤醒本应用

**AndroidManifest.xml intent-filter**：
```xml
<intent-filter>
    <action android:name="android.nfc.action.NDEF_DISCOVERED" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="kaihang" android:host="nfc" />
</intent-filter>
```

**状态管理要点**：
- 用 `useRef` 持有监听器引用，切换读/写模式前必须先调用 `cancelNfcOperation()` 销毁旧监听
- 组件卸载时 `useEffect` 清理函数自动销毁，防止监听器泄漏导致重复触发

**实现文件**：`src/pages/NfcPage.tsx`（当前为框架占位，NFC Bridge 方法待与扫码/打印统一至 Capacitor 后填充）

---

## 后续 Capacitor 迁移计划

| 能力 | 迁移方式 | 预估工作量 |
|------|---------|-----------|
| 扫码 | 新建 Capacitor Plugin，封装广播注册/接收 | ~150 行 Java |
| 打印 | 新建 Capacitor Plugin，集成 `uc_pda_sdk_native_temp_v1.16_240515.aar` | ~200 行 Java |
| NFC | `@capgo/capacitor-nfc`（已规划） | 0 |

迁移前提：设备上保留"扫码助手"App（扫码硬件驱动，不可移除）。

## 文件索引

| 文件/目录 | 说明 |
|-----------|------|
| `src/bridge/PDABridge.ts` | H5 壳子阶段 PDAJsBridge 类型化封装 |
| `src/pages/NfcPage.tsx` | NFC 读写页面 |
| `android/app/src/main/AndroidManifest.xml` | Capacitor 壳 Manifest 模板 |
| `sdk_extracted/scan_demo/` | 扫码广播 SDK 源码 demo |
| `sdk_extracted/print_demo/PdaDemo/app/libs/` | 打印 AAR 文件 |
| `X8_安卓壳子_H5_对接资料包/` | X8 壳子 H5 对接 demo |
| `X8_安卓本地服务器_对接资料包/` | X8 本地服务器模式 demo（备用参考） |
