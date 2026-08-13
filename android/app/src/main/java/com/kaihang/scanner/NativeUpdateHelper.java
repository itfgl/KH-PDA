package com.kaihang.scanner;

import android.app.Activity;
import android.os.Build;
import android.text.TextUtils;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Scanner;

final class NativeUpdateHelper {
    interface Callbacks {
        void appendLog(String message);

        void toast(String message);
    }

    private NativeUpdateHelper() {}

    static void showUpdateDialog(Activity activity, String updateBase, Callbacks callbacks) {
        AlertDialog progressDialog = new AlertDialog.Builder(activity)
            .setTitle("检查更新")
            .setMessage("正在检查更新...")
            .setCancelable(false)
            .create();
        progressDialog.show();
        new Thread(() -> {
            try {
                JSONObject serverInfo = fetchUpdateInfo(updateBase);
                long localVersionCode = getLocalVersionCode(activity);
                String localVersionName = getLocalVersionName(activity);
                long remoteVersionCode = serverInfo.optLong("versionCode", 0);
                String remoteVersionName = serverInfo.optString("versionName", "");
                String changelog = serverInfo.optString("changelog", "");
                String apkUrl = resolveAbsoluteUrl(updateBase, serverInfo.optString("apkUrl", ""));
                activity.runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (remoteVersionCode > localVersionCode && !apkUrl.isEmpty()) {
                        StringBuilder message = new StringBuilder();
                        message.append("发现新版本 ").append(remoteVersionName).append(" (").append(remoteVersionCode).append(")\n");
                        message.append("当前版本 ").append(localVersionName).append(" (").append(localVersionCode).append(")");
                        if (!changelog.isEmpty()) {
                            message.append("\n\n更新说明:\n").append(changelog);
                        }
                        new AlertDialog.Builder(activity)
                            .setTitle("发现新版本")
                            .setMessage(message.toString())
                            .setPositiveButton("下载更新", (dialog, which) -> {
                                if (!PackageUpdateInstaller.canInstallPackages(activity)) {
                                    callbacks.appendLog("未授予安装未知应用权限");
                                    callbacks.toast("请先允许本应用安装未知应用，然后再次检查更新");
                                    PackageUpdateInstaller.openUnknownAppSourcesSettings(activity);
                                    return;
                                }
                                callbacks.appendLog("开始下载更新: " + apkUrl);
                                PackageUpdateInstaller.downloadAndInstall(activity, apkUrl,
                                    new PackageUpdateInstaller.Listener() {
                                        private int lastLoggedProgress = -10;

                                        @Override
                                        public void onProgress(int progress) {
                                            if (progress >= lastLoggedProgress + 10 || progress == 100) {
                                                lastLoggedProgress = progress;
                                                callbacks.appendLog("更新下载进度: " + progress + "%");
                                            }
                                        }

                                        @Override
                                        public void onInstallSessionCommitted() {
                                            callbacks.appendLog("更新包已提交系统安装器");
                                            callbacks.toast("下载完成，正在打开系统安装界面");
                                        }

                                        @Override
                                        public void onError(String error) {
                                            callbacks.appendLog("更新安装失败: " + error);
                                            callbacks.toast("更新安装失败: " + error);
                                        }
                                    });
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    } else {
                        callbacks.appendLog("当前已是最新版本: " + localVersionName + " (" + localVersionCode + ")");
                        new AlertDialog.Builder(activity)
                            .setTitle("检查更新")
                            .setMessage("当前已是最新版本\n版本: " + localVersionName + " (" + localVersionCode + ")")
                            .setPositiveButton("知道了", null)
                            .show();
                    }
                });
            } catch (Exception e) {
                callbacks.appendLog("检查更新失败: " + e.getMessage());
                activity.runOnUiThread(() -> {
                    progressDialog.dismiss();
                    new AlertDialog.Builder(activity)
                        .setTitle("检查更新失败")
                        .setMessage(String.valueOf(e.getMessage()))
                        .setPositiveButton("知道了", null)
                        .show();
                });
            }
        }).start();
    }

    private static JSONObject fetchUpdateInfo(String updateBase) throws Exception {
        ArrayList<String> attemptedUrls = new ArrayList<>();

        String indexUrl = resolveAbsoluteUrl(updateBase, "/app-updates/versions.json");
        attemptedUrls.add(indexUrl);
        try {
            JSONObject indexInfo = fetchJsonObject(indexUrl);
            String currentVersionFile = indexInfo.optString("currentVersionFile", "").trim();
            if (currentVersionFile.isEmpty()) {
                long currentVersionCode = indexInfo.optLong("currentVersionCode", 0);
                if (currentVersionCode > 0) {
                    currentVersionFile = "version-" + currentVersionCode + ".json";
                }
            }
            if (!currentVersionFile.isEmpty()) {
                String detailUrl = resolveAbsoluteUrl(updateBase, "/app-updates/" + currentVersionFile);
                attemptedUrls.add(detailUrl);
                JSONObject detailInfo = fetchJsonObject(detailUrl);
                if (!detailInfo.has("apkUrl") && detailInfo.has("apkFileName")) {
                    detailInfo.put("apkUrl", "/app-updates/" + detailInfo.optString("apkFileName", ""));
                }
                return detailInfo;
            }
        } catch (Exception ignored) {}

        String staticVersionUrl = resolveAbsoluteUrl(updateBase, "/version.json");
        attemptedUrls.add(staticVersionUrl);
        try {
            return fetchJsonObject(staticVersionUrl);
        } catch (Exception ignored) {}

        String apiVersionUrl = resolveAbsoluteUrl(updateBase, "/api/app/version");
        attemptedUrls.add(apiVersionUrl);
        try {
            return fetchJsonObject(apiVersionUrl);
        } catch (Exception ignored) {}

        throw new IOException("无法获取最新版本信息，已尝试: " + TextUtils.join(", ", attemptedUrls));
    }

    private static JSONObject fetchJsonObject(String url) throws Exception {
        URL requestUrl = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) requestUrl.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("X-Client-Type", "capacitor");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " @ " + url);
        }
        String body;
        try (InputStream inputStream = connection.getInputStream();
             Scanner scanner = new Scanner(inputStream, "UTF-8").useDelimiter("\\A")) {
            body = scanner.hasNext() ? scanner.next() : "{}";
        } finally {
            connection.disconnect();
        }
        return new JSONObject(body);
    }

    private static String resolveAbsoluteUrl(String baseUrl, String path) {
        try {
            return new URL(new URL(baseUrl + "/"), path).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static long getLocalVersionCode(Activity activity) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).getLongVersionCode();
        }
        return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
    }

    private static String getLocalVersionName(Activity activity) throws Exception {
        return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
    }
}
