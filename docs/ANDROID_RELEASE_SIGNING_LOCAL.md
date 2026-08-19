# Android 正式签名安全导出与本地构建

## 原则

GitHub Actions Secret 不能从 GitHub 界面或 API 读回。禁止修改工作流把 keystore、密码或 Secret 打印到日志，也禁止上传明文签名文件。

本仓库使用“本机公钥加密、云端输出密文、本机私钥解密”的方式恢复正式签名：

- 本机私钥：`%USERPROFILE%\.kaihang-signing-export\recipient-private-key.pem`
- 本机证书：`%USERPROFILE%\.kaihang-signing-export\recipient-cert.pem`
- 仓库只提交公钥证书：`.github/signing-export-recipient.pem`
- 手动工作流：`Export Android Release Signing (Encrypted)`
- 加密产物：临时草稿 Release 中的 `kaihang-release-signing.p7m`
- 临时草稿 Release 不会成为正式版或最新版；本地恢复后立即手动删除

证书 SHA-256 指纹：

```text
4D:79:82:D1:C8:D7:61:71:57:45:A4:BA:0C:C5:7F:A0:39:5F:0D:7A:32:28:F3:41:7E:0D:B6:78:27:F7:DD:F0
```

## 第一次导出

1. 将新增工作流和公钥证书推送到 GitHub。
2. 打开仓库的 Actions 页面。
3. 选择 `Export Android Release Signing (Encrypted)`。
4. 点击 `Run workflow`，选择 `master` 后运行。
5. 执行成功后，打开该次运行的 Summary，点击其中的临时草稿 Release 链接。
6. 下载 `kaihang-release-signing.p7m`。它已由本机公钥加密，不占用 GitHub Actions Artifact 配额。
7. 在 `android-entry` 目录执行：

```powershell
.\scripts\restore-release-signing.ps1 `
  -EncryptedBundle 'C:\下载目录\kaihang-release-signing.p7m'
```

恢复脚本会生成：

- `android/app/release.keystore`
- `keystore.properties`

两个文件均已被 `.gitignore` 排除，不会进入 Git。

8. 确认本地恢复成功后，回到 GitHub Releases 删除名为 `Temporary encrypted signing export ...` 的草稿 Release。

## 本地正式构建

```powershell
cd C:\work\project\scanner\android-entry\android
.\gradlew.bat assembleRelease
```

产物：

```text
android\app\build\outputs\apk\release\app-release.apk
```

构建后应验证包名、版本和签名：

```powershell
$BuildTools = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0"
& "$BuildTools\aapt.exe" dump badging .\app\build\outputs\apk\release\app-release.apk
& "$BuildTools\apksigner.bat" verify --verbose --print-certs .\app\build\outputs\apk\release\app-release.apk
```

正式证书应为：

```text
CN=Kaihang, O=Kaihang, C=CN
```

证书 SHA-256 应为：

```text
cfeeeac199ef37db7ecba811e3b403aed20ed57cd3cb3c0b20e028514fcf979d
```

## 备份与安全

- 不要把 `recipient-private-key.pem`、`release.keystore`、`keystore.properties` 或导出的 `.p7m` 提交到 Git；仓库已忽略后三者。
- 不要通过聊天、邮件或网盘发送未加密的签名文件和密码。
- 草稿 Release 中只有公钥加密后的密文，但仍应在下载并恢复成功后删除。
- 成功恢复后，至少制作两份离线加密备份，分别保存在不同介质。
- 本机私钥遗失后，已下载的 `.p7m` 无法解密，但可以用新的本机证书重新运行导出工作流。
- 正式 Android 签名 keystore 一旦永久丢失，将无法用同一签名升级已安装应用，因此不能只保留在 GitHub Secrets 中。
