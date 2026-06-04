# NFC 实现规则

## 核心原则

**NFC 前台拦截必须在原生层保证，不依赖 H5/JS 的加载状态。**

---

## 为什么这样设计

### 问题根源
`capacitor.config.json` 使用 `server.url` 从远程服务器加载 H5。当：
- 服务器未启动
- 网络不可达
- H5 是旧版本
- JS 执行报错

→ JS 中的 `KaihangNfc.startScanning()` 不会被调用  
→ Android `enableReaderMode` 从未激活  
→ 贴卡走系统 NFC 分发，弹出应用选择框

### 解决方案
`KaihangNfcPlugin.load()` 时自动调用 `enableReaderMode()`，与 JS 完全解耦。

```
App 启动
  └─ KaihangNfcPlugin.load()        ← 原生层，不依赖 H5
       └─ enableReaderMode()         ← NFC 前台拦截立即生效
            └─ 贴卡 → readerCallback → notifyListeners("nfcEvent")
                                              ↓
                                       JS 收到事件（如果 H5 已加载）
```

---

## 生命周期规则

| 时机 | 行为 | 原因 |
|------|------|------|
| `load()` | `enableReaderMode()` | App 启动即生效 |
| `handleOnResume()` | `enableReaderMode()` | 从后台/锁屏回来后恢复 |
| `handleOnPause()` | `disableReaderMode()` | App 进后台，释放 NFC 资源 |
| `handleOnDestroy()` | executor 关闭 | 清理线程池 |

---

## JS 层规则

### `startScanning()` / `stopScanning()`
- 这两个方法现在是**可选调用**，主要用于测试或手动控制
- 正常使用不需要 JS 调用，原生层自动管理

### `addListener('nfcEvent', handler)`
- **必须在 JS 里调用**，否则收不到贴卡事件
- 在 `nfcInit()` 里注册，页面加载时执行

### 贴卡事件处理
```js
// nfcEvent 数据结构
{
  techTypes: string[],        // ["android.nfc.tech.NfcA", "android.nfc.tech.MifareUltralight"]
  isNdef: boolean,            // true = NDEF 格式卡（NTAG213 等）
  isMifareUltralight: boolean,// true = MifareUltralight 原始卡
  ndefMessage?: NdefRecord[], // NDEF 卡才有，每条记录含 tnf/type/id/payload
  mifareData?: string,        // MifareUltralight 卡含凯航数据时自动解析（KH 魔数格式）
}
```

---

## 卡片格式规则

### NDEF 格式（推荐，NTAG213/215/216）
- URI Record：`kaihang://nfc/<业务数据>`
- AAR Record：`com.kaihang.scanner`（确保贴卡唤起本 App）
- App 未启动时贴卡可直接唤起，**无需选择框**

### MifareUltralight 原始格式（兼容现有贴纸）
- 写入位置：页 4 开始
- 格式：`KH`（2字节魔数）+ 长度（2字节 big-endian）+ UTF-8 数据
- 最大容量：44 字节（页4-15 共48字节 - 4字节头）
- 读取时自动识别魔数，解析后放入 `mifareData` 字段
- **限制**：App 未启动时贴卡会弹系统选择框（无 NDEF 数据无法精确匹配）

---

## AndroidManifest.xml NFC 配置

```xml
<!-- 权限 -->
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="false" />

<!-- App 运行时：由 enableReaderMode 拦截，不走 intent-filter -->

<!-- App 未运行时冷启动（NDEF 卡精确匹配，无选择框）-->
<intent-filter>
    <action android:name="android.nfc.action.NDEF_DISCOVERED" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="kaihang" android:host="nfc" />
</intent-filter>

<!-- App 未运行时冷启动（MifareUltralight 等，可能弹选择框）-->
<intent-filter>
    <action android:name="android.nfc.action.TECH_DISCOVERED" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
<meta-data android:name="android.nfc.action.TECH_DISCOVERED"
           android:resource="@xml/nfc_tech_filter" />

<!-- 兜底 -->
<intent-filter>
    <action android:name="android.nfc.action.TAG_DISCOVERED" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
```

---

## 常见问题

### Q：App 运行时贴卡还是弹选择框？
A：检查 `KaihangNfcPlugin.load()` 日志 `NFC reader mode enabled on load` 是否出现。
   若未出现，说明 NFC 未开启或设备不支持。

### Q：App 冷启动（未运行时贴卡）弹选择框？
A：正常现象（MifareUltralight 原始格式）。换 NTAG213 并写入 NDEF 格式可解决。

### Q：切换 H5 远程/本地模式后 NFC 失效？
A：NFC 由原生层控制，与 H5 加载无关，不会因为模式切换失效。
