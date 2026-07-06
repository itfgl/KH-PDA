package com.kaihang.scanner;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
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
                                callbacks.appendLog("开始下载更新: " + apkUrl);
                                downloadApkWithSystemManager(activity, apkUrl, callbacks);
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

    private static void downloadApkWithSystemManager(Activity activity, String url, Callbacks callbacks) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("凯航扫码 更新");
            request.setDescription("正在下载新版本...");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "kaihang_update.apk");
            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
            DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                throw new IllegalStateException("DownloadManager unavailable");
            }
            manager.enqueue(request);
            callbacks.toast("已开始下载更新，请查看系统通知");
        } catch (Exception e) {
            callbacks.appendLog("启动下载失败: " + e.getMessage());
            callbacks.toast("启动下载失败: " + e.getMessage());
        }
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
