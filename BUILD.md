# 凯航扫码 Android / GitHub 构建说明

## 当前定位

`android-entry` 现在按独立仓库使用，GitHub 构建、说明文档、Android 工程都以这个目录为唯一入口。

以后判断是否会触发远程构建，只看：

- `android-entry/.github/workflows/build.yml`

不要再看外层目录的 `.github/workflows/`。

## 先看结论

这个项目现在有两层运行内容：

```text
Android APK（Capacitor 壳）
  ├── 内置本地 www/ 登录壳页
  ├── 原生插件：扫码、打印、NFC、更新
  └── 登录后跳转到服务端业务页

服务端 H5 / API
  ├── http://115.29.178.34:2974/static/
  └── http://115.29.178.34:2974/api/...
```

所以请把变更分成两类理解：

- 改原生能力、权限、插件、登录壳页：需要重新打 APK
- 改登录后的业务页面、路由、页面动作、接口逻辑：通常只需要发服务端 H5 / API

如果“构建了新 APK 但页面没变”，先怀疑是不是你改的是服务端业务页，而不是 APK 壳。

## 当前加载方式

当前 [capacitor.config.json](/C:/work/project/scanner/android-entry/capacitor.config.json:1) 没有 `server.url`，所以 App 启动时先加载包内 `www/`。

但登录成功后，壳页会把用户跳到服务端角色页：

- 目标地址拼接逻辑在 [src/modules/api.js](/C:/work/project/scanner/android-entry/src/modules/api.js:103)
- 登录后跳转在 [www/index.html](/C:/work/project/scanner/android-entry/www/index.html:331)

这意味着：

- `www/index.html` 和 `www/plugins.js` 的变化会跟 APK 一起发
- 登录后的业务页面主要来自 `http://115.29.178.34:2974`

## APK 自动构建

唯一有效的远程 APK workflow 是 [build.yml](/C:/work/project/scanner/android-entry/.github/workflows/build.yml:1)。

### 触发条件

- push 到 `main` 或 `master`
- 手动 `Run workflow`

### 构建环境

- Node.js 20
- Java 17
- Gradle Wrapper `8.14.3`
- Android Gradle Plugin `8.13.0`

远程构建环境不再负责前端编译，但仍会安装 Node 依赖，用来提供 Capacitor Android 模块。

### 流程

```text
1. checkout
2. 安装 Node 20
3. 执行 `npm ci`
4. 安装 Java 17
5. 直接在 `android/` 下执行 `./gradlew assembleDebug`
6. 上传 debug APK artifact
```

### 什么时候会让你误以为“没生效”

- 你推送到的不是 `android-entry` 对应的 GitHub 仓库
- 你改了前端源码，但没有先在本地执行 `npm run build` / `npx cap sync android` 并提交生成结果
- 你更新的是服务端业务页，结果却去等 APK 生效
- 你装上的还是旧 artifact，或者没卸载旧包

### 远程构建现在包含什么

- 只编译当前仓库里已经存在的 Android 工程
- 会安装 Node 依赖，仅用于提供 `@capacitor/android`
- 不构建前端
- 不同步 Capacitor 资源

如果你改了 `src/`、`www/` 或需要重新同步 Capacitor 资源，先在本地完成以下步骤，再提交到仓库：

```bash
cd android-entry
npm ci
npm run build
npx cap sync android
```

注意：

- `android/capacitor-cordova-android-plugins/`
- `android/app/src/main/assets/public/`
- `android/app/src/main/assets/capacitor.config.json`
- `android/app/src/main/assets/capacitor.plugins.json`
- `android/app/src/main/res/xml/config.xml`

这些 Capacitor 生成文件现在需要一并提交，因为远程 CI 已经不再执行 `cap sync`。

## 本地构建

建议本地环境：

- Node.js 20
- Java 17
- Android SDK

```bash
cd android-entry
npm ci
npm run sync
cd android
./gradlew assembleDebug
```

Debug APK 输出路径：

```text
android-entry/android/app/build/outputs/apk/debug/app-debug.apk
```

## 链接为什么会跳浏览器

现在的策略已经改成：

- 普通 `http/https` 链接默认尽量留在 App 内打开
- `window.open(url)` 默认也尽量在 App 内导航
- 明确标记外部打开的链接才放到系统层

可用于强制外部打开的约定：

- `<a rel="external" ...>`
- `<a data-kh-external="true" ...>`
- 带 `download` 属性的链接
- `window.open(url, "_system")`

## 常用文件索引

- [capacitor.config.json](/C:/work/project/scanner/android-entry/capacitor.config.json:1)：Capacitor 基础配置
- [src/modules/api.js](/C:/work/project/scanner/android-entry/src/modules/api.js:1)：服务端地址、角色页拼接、登录态
- [www/index.html](/C:/work/project/scanner/android-entry/www/index.html:1)：本地登录壳页
- [MainActivity.java](/C:/work/project/scanner/android-entry/android/app/src/main/java/com/kaihang/scanner/MainActivity.java:1)：WebView 注入、链接拦截、扫码桥
- [build.yml](/C:/work/project/scanner/android-entry/.github/workflows/build.yml:1)：唯一有效的 GitHub APK 构建
