package com.kaihang.scanner.plugins;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;

@CapacitorPlugin(name = "UpdatePlugin")
public class UpdatePlugin extends Plugin {

    @PluginMethod
    public void getVersionInfo(PluginCall call) {
        try {
            long versionCode;
            String versionName;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0).getLongVersionCode();
            } else {
                versionCode = getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0).versionCode;
            }
            versionName = getContext().getPackageManager()
                .getPackageInfo(getContext().getPackageName(), 0).versionName;

            JSObject data = new JSObject();
            data.put("versionCode", versionCode);
            data.put("versionName", versionName);
            call.resolve(data);
        } catch (Exception e) {
            call.reject("获取版本信息失败: " + e.getMessage());
        }
    }

    /**
     * 用系统 DownloadManager 下载 APK，下载完自动触发系统安装器。
     * 绕开 FileProvider，系统进程直接操作文件，不依赖本 App 进程存活。
     */
    @PluginMethod
    public void downloadAndInstallApk(PluginCall call) {
        String urlString = call.getString("url");
        if (urlString == null || urlString.isEmpty()) {
            call.reject("URL 不能为空");
            return;
        }

        try {
            // 清理旧文件
            File dest = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "kaihang_update.apk"
            );
            if (dest.exists()) dest.delete();

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(urlString));
            request.setTitle("凯航扫码 更新");
            request.setDescription("正在下载新版本...");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, "kaihang_update.apk");
            // 允许 Wi-Fi 和移动网络
            request.setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);

            DownloadManager dm = (DownloadManager)
                getContext().getSystemService(Context.DOWNLOAD_SERVICE);
            long downloadId = dm.enqueue(request);

            // 在后台轮询进度上报给 JS
            new Thread(() -> {
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                int lastProgress = -1;
                while (true) {
                    android.database.Cursor c = dm.query(query);
                    if (c == null) break;
                    if (!c.moveToFirst()) { c.close(); break; }
                    int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_FAILED) {
                        c.close();
                        call.reject("DownloadManager 下载失败");
                        return;
                    }
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        c.close();
                        break;
                    }
                    long downloaded = c.getLong(
                        c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = c.getLong(
                        c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    c.close();
                    if (total > 0) {
                        int progress = (int) (downloaded * 100 / total);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            JSObject obj = new JSObject();
                            obj.put("progress", progress);
                            notifyListeners("downloadProgress", obj);
                        }
                    }
                    try { Thread.sleep(300); } catch (InterruptedException e) { break; }
                }
                // 下载完成，通知 JS（安装靠用户点通知栏，不再用 FileProvider）
                call.resolve();
            }).start();

        } catch (Exception e) {
            call.reject("启动下载失败: " + e.getMessage());
        }
    }

    @PluginMethod
    public void restartApp(PluginCall call) {
        Intent intent = getContext().getPackageManager()
            .getLaunchIntentForPackage(getContext().getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        }
        call.resolve();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void installApk(File file) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Uri apkUri = FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                file
            );
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
        }

        getContext().startActivity(intent);
    }
}
