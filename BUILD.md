# 凯航扫码 APK 构建说明

## 架构说明

```
APK（Capacitor 壳）
  └─ 启动时加载 → http://115.29.178.34:2973  （服务端托管的 H5 页面）

服务端
  ├─ /static/        H5 前端文件
  └─ /api/...        业务接口
```

**核心原则**：APK 只是一个原生壳，H5 界面部署在服务端。  
- **更新界面/业务逻辑** → 只需重新部署服务端 H5，无需重新打包 APK  
- **更新原生功能**（插件、权限、NFC、打印）→ 需要重新打包 APK

---

## 两种加载模式

### 模式 A：远程服务端模式（生产默认）

`capacitor.config.json` 中包含 `server.url`：
```json
{
  "appId": "com.kaihang.scanner",
  "appName": "凯航扫码",
  "webDir": "www",
  "server": {
    "url": "http://115.29.178.34:2973",
    "cleartext": true
  }
}
```
APK 启动后直接加载服务端页面，`www/` 目录内容被忽略。  
**优点**：H5 更新不需要重新打包 APK。

### 模式 B：本地离线模式（调试用）

删除 `server` 字段或注释掉：
```json
{
  "appId": "com.kaihang.scanner",
  "appName": "凯航扫码",
  "webDir": "www"
}
```
APK 从打包进去的 `www/` 目录加载，无网络也能运行。  
**使用场景**：无服务器环境调试、Demo 演示。

> ⚠️ 切换模式后必须重新构建 APK。

---

## 自动构建（GitHub Actions）

仓库地址：https://github.com/nyushimentor-crypto/scanner

### 触发条件
- 推送代码到 `master` 分支 → 自动触发
- GitHub 仓库 Actions 页面手动点击 `Run workflow`

### 构建流程（`.github/workflows/build.yml`）

```
1. 拉取代码
2. 配置 Node.js 22
3. 配置 Java 21
4. npm install                    安装依赖
   npm run build                  Vite 打包 JS（生成 www/plugins.js）
   npx cap sync android           同步 Capacitor 插件到 Android 项目
5. ./gradlew assembleDebug        编译 Debug APK（无需签名配置）
6. 上传 Artifact                  产物名：kaihang-scanner-debug
```

> 当前使用 `assembleDebug`，APK 使用 Android 调试签名，可直接安装测试。

### 下载 APK

1. 打开 [Actions](https://github.com/nyushimentor-crypto/scanner/actions)
2. 点击最新成功的构建
3. 页面底部 **Artifacts** → 下载 `kaihang-scanner-debug`
4. 解压得到 `.apk` 文件，传到设备安装

---

## 本地构建（可选）

需要本地安装：Node.js 22、JDK 21、Android SDK

```bash
cd android-entry

# 安装依赖
npm install

# 打包 JS（生产模式）
npm run build

# 同步到 Android 项目
npx cap sync android

# 编译 Debug APK
cd android
./gradlew assembleDebug

# APK 路径
android/app/build/outputs/apk/debug/app-debug.apk
```

---

## 正式签名打包（待配置）

切换到 `assembleRelease` 并在 GitHub 仓库配置以下 Secrets：

| Secret 名 | 说明 |
|-----------|------|
| `SIGNING_KEY` | `apk-release-key.jks` 转 Base64：`[Convert]::ToBase64String([IO.File]::ReadAllBytes("apk-release-key.jks"))` |
| `ALIAS` | keystore alias |
| `KEY_STORE_PASSWORD` | keystore 密码 |
| `KEY_PASSWORD` | key 密码 |

配置完成后修改 `build.yml` 第 5 步：
```yaml
./gradlew assembleRelease
```
并取消注释签名步骤。

---

## H5 前端单独更新（不重新打包 APK）

**仅在远程服务端模式下有效。**

将 `www/` 目录下的文件部署到服务端 `/static/` 路径即可：
```bash
# 构建 H5
npm run build
# 将 www/ 目录上传到服务器 115.29.178.34:2973 的静态文件目录
```

---

## 原生功能文件索引

| 文件 | 说明 |
|------|------|
| `capacitor.config.json` | 切换远程/本地模式、修改服务器地址 |
| `android/app/src/main/java/com/kaihang/scanner/plugins/ScanPlugin.java` | 扫码枪广播接收 |
| `android/app/src/main/java/com/kaihang/scanner/plugins/PrintPlugin.java` | 打印标签/二维码 |
| `android/app/src/main/java/com/kaihang/scanner/plugins/KaihangNfcPlugin.java` | NFC 读写（NDEF + MifareUltralight） |
| `android/app/src/main/AndroidManifest.xml` | 权限、NFC intent-filter |
| `android/app/libs/uc_pda_sdk_native_temp_v1.16_240515.aar` | 打印机原生 SDK |
| `src/main.js` | JS 插件入口，Vite 打包目标 |
| `www/index.html` | 调试用离线页面（远程模式下不使用） |
