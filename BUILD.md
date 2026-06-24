# 鍑埅鎵爜 APK 鏋勫缓璇存槑

## 鏋舵瀯璇存槑

```
APK锛圕apacitor 澹筹級
  鈹斺攢 鍚姩鏃跺姞杞?鈫?http://115.29.178.34:2974  锛堟湇鍔＄鎵樼鐨?H5 椤甸潰锛?
鏈嶅姟绔?  鈹溾攢 /static/        H5 鍓嶇鏂囦欢
  鈹斺攢 /api/...        涓氬姟鎺ュ彛
```

**鏍稿績鍘熷垯**锛欰PK 鍙槸涓€涓師鐢熷３锛孒5 鐣岄潰閮ㄧ讲鍦ㄦ湇鍔＄銆? 
- **鏇存柊鐣岄潰/涓氬姟閫昏緫** 鈫?鍙渶閲嶆柊閮ㄧ讲鏈嶅姟绔?H5锛屾棤闇€閲嶆柊鎵撳寘 APK  
- **鏇存柊鍘熺敓鍔熻兘**锛堟彃浠躲€佹潈闄愩€丯FC銆佹墦鍗帮級鈫?闇€瑕侀噸鏂版墦鍖?APK

---

## 涓ょ鍔犺浇妯″紡

### 妯″紡 A锛氳繙绋嬫湇鍔＄妯″紡锛堢敓浜ч粯璁わ級

`capacitor.config.json` 涓寘鍚?`server.url`锛?```json
{
  "appId": "com.kaihang.scanner",
  "appName": "鍑埅鎵爜",
  "webDir": "www",
  "server": {
    "url": "http://115.29.178.34:2974",
    "cleartext": true
  }
}
```
APK 鍚姩鍚庣洿鎺ュ姞杞芥湇鍔＄椤甸潰锛宍www/` 鐩綍鍐呭琚拷鐣ャ€? 
**浼樼偣**锛欻5 鏇存柊涓嶉渶瑕侀噸鏂版墦鍖?APK銆?
### 妯″紡 B锛氭湰鍦扮绾挎ā寮忥紙璋冭瘯鐢級

鍒犻櫎 `server` 瀛楁鎴栨敞閲婃帀锛?```json
{
  "appId": "com.kaihang.scanner",
  "appName": "鍑埅鎵爜",
  "webDir": "www"
}
```
APK 浠庢墦鍖呰繘鍘荤殑 `www/` 鐩綍鍔犺浇锛屾棤缃戠粶涔熻兘杩愯銆? 
**浣跨敤鍦烘櫙**锛氭棤鏈嶅姟鍣ㄧ幆澧冭皟璇曘€丏emo 婕旂ず銆?
> 鈿狅笍 鍒囨崲妯″紡鍚庡繀椤婚噸鏂版瀯寤?APK銆?
---

## 鑷姩鏋勫缓锛圙itHub Actions锛?
浠撳簱鍦板潃锛歨ttps://github.com/nyushimentor-crypto/scanner

### 瑙﹀彂鏉′欢
- 鎺ㄩ€佷唬鐮佸埌 `master` 鍒嗘敮 鈫?鑷姩瑙﹀彂
- GitHub 浠撳簱 Actions 椤甸潰鎵嬪姩鐐瑰嚮 `Run workflow`

### 鏋勫缓娴佺▼锛坄.github/workflows/build.yml`锛?
```
1. 鎷夊彇浠ｇ爜
2. 閰嶇疆 Node.js 22
3. 閰嶇疆 Java 21
4. npm install                    瀹夎渚濊禆
   npm run build                  Vite 鎵撳寘 JS锛堢敓鎴?www/plugins.js锛?   npx cap sync android           鍚屾 Capacitor 鎻掍欢鍒?Android 椤圭洰
5. ./gradlew assembleDebug        缂栬瘧 Debug APK锛堟棤闇€绛惧悕閰嶇疆锛?6. 涓婁紶 Artifact                  浜х墿鍚嶏細kaihang-scanner-debug
```

> 褰撳墠浣跨敤 `assembleDebug`锛孉PK 浣跨敤 Android 璋冭瘯绛惧悕锛屽彲鐩存帴瀹夎娴嬭瘯銆?
### 涓嬭浇 APK

1. 鎵撳紑 [Actions](https://github.com/nyushimentor-crypto/scanner/actions)
2. 鐐瑰嚮鏈€鏂版垚鍔熺殑鏋勫缓
3. 椤甸潰搴曢儴 **Artifacts** 鈫?涓嬭浇 `kaihang-scanner-debug`
4. 瑙ｅ帇寰楀埌 `.apk` 鏂囦欢锛屼紶鍒拌澶囧畨瑁?
---

## 鏈湴鏋勫缓锛堝彲閫夛級

闇€瑕佹湰鍦板畨瑁咃細Node.js 22銆丣DK 21銆丄ndroid SDK

```bash
cd android-entry

# 瀹夎渚濊禆
npm install

# 鎵撳寘 JS锛堢敓浜фā寮忥級
npm run build

# 鍚屾鍒?Android 椤圭洰
npx cap sync android

# 缂栬瘧 Debug APK
cd android
./gradlew assembleDebug

# APK 璺緞
android/app/build/outputs/apk/debug/app-debug.apk
```

---

## 姝ｅ紡绛惧悕鎵撳寘锛堝緟閰嶇疆锛?
鍒囨崲鍒?`assembleRelease` 骞跺湪 GitHub 浠撳簱閰嶇疆浠ヤ笅 Secrets锛?
| Secret 鍚?| 璇存槑 |
|-----------|------|
| `SIGNING_KEY` | `apk-release-key.jks` 杞?Base64锛歚[Convert]::ToBase64String([IO.File]::ReadAllBytes("apk-release-key.jks"))` |
| `ALIAS` | keystore alias |
| `KEY_STORE_PASSWORD` | keystore 瀵嗙爜 |
| `KEY_PASSWORD` | key 瀵嗙爜 |

閰嶇疆瀹屾垚鍚庝慨鏀?`build.yml` 绗?5 姝ワ細
```yaml
./gradlew assembleRelease
```
骞跺彇娑堟敞閲婄鍚嶆楠ゃ€?
---

## H5 鍓嶇鍗曠嫭鏇存柊锛堜笉閲嶆柊鎵撳寘 APK锛?
**浠呭湪杩滅▼鏈嶅姟绔ā寮忎笅鏈夋晥銆?*

灏?`www/` 鐩綍涓嬬殑鏂囦欢閮ㄧ讲鍒版湇鍔＄ `/static/` 璺緞鍗冲彲锛?```bash
# 鏋勫缓 H5
npm run build
# 灏?www/ 鐩綍涓婁紶鍒版湇鍔″櫒 115.29.178.34:2974 鐨勯潤鎬佹枃浠剁洰褰?```

---

## 鍘熺敓鍔熻兘鏂囦欢绱㈠紩

| 鏂囦欢 | 璇存槑 |
|------|------|
| `capacitor.config.json` | 鍒囨崲杩滅▼/鏈湴妯″紡銆佷慨鏀规湇鍔″櫒鍦板潃 |
| `android/app/src/main/java/com/kaihang/scanner/plugins/ScanPlugin.java` | 鎵爜鏋箍鎾帴鏀?|
| `android/app/src/main/java/com/kaihang/scanner/plugins/PrintPlugin.java` | 鎵撳嵃鏍囩/浜岀淮鐮?|
| `android/app/src/main/java/com/kaihang/scanner/plugins/KaihangNfcPlugin.java` | NFC 璇诲啓锛圢DEF + MifareUltralight锛?|
| `android/app/src/main/AndroidManifest.xml` | 鏉冮檺銆丯FC intent-filter |
| `android/app/libs/uc_pda_sdk_native_temp_v1.16_240515.aar` | 鎵撳嵃鏈哄師鐢?SDK |
| `src/main.js` | JS 鎻掍欢鍏ュ彛锛孷ite 鎵撳寘鐩爣 |
| `www/index.html` | 璋冭瘯鐢ㄧ绾块〉闈紙杩滅▼妯″紡涓嬩笉浣跨敤锛?|
