package com.kaihang.scanner.plugins;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@CapacitorPlugin(name = "UpdatePlugin")
public class UpdatePlugin extends Plugin {

    @PluginMethod
    public void getVersionInfo(PluginCall call) {
        try {
            long versionCode;
            String versionName;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).getLongVersionCode();
            } else {
                versionCode = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionCode;
            }
            versionName = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName;

            JSObject data = new JSObject();
            data.put("versionCode", versionCode);
            data.put("versionName", versionName);
            call.resolve(data);
        } catch (Exception e) {
            call.reject("获取版本信息失败: " + e.getMessage());
        }
    }

    @PluginMethod
    public void downloadAndInstallApk(PluginCall call) {
        String urlString = call.getString("url");
        if (urlString == null || urlString.isEmpty()) {
            call.reject("URL 不能为空");
            return;
        }

        // Run download in background thread
        new Thread(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.connect();

                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    call.reject("下载失败，服务器返回码: " + connection.getResponseCode());
                    return;
                }

                int fileLength = connection.getContentLength();
                InputStream input = connection.getInputStream();

                // Save to app's cache directory (internal cache, safe, doesn't need external storage permission)
                File cacheDir = getContext().getCacheDir();
                File apkFile = new File(cacheDir, "update.apk");
                if (apkFile.exists()) {
                    apkFile.delete();
                }

                FileOutputStream output = new FileOutputStream(apkFile);

                byte[] data = new byte[4096];
                long total = 0;
                int count;
                int lastProgress = -1;

                while ((count = input.read(data)) != -1) {
                    total += count;
                    output.write(data, 0, count);

                    if (fileLength > 0) {
                        int progress = (int) (total * 100 / fileLength);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            JSObject progressObj = new JSObject();
                            progressObj.put("progress", progress);
                            notifyListeners("downloadProgress", progressObj);
                        }
                    }
                }

                output.flush();
                output.close();
                input.close();

                // Start installation
                installApk(apkFile);
                call.resolve();

            } catch (Exception e) {
                call.reject("下载或安装 APK 失败: " + e.getMessage());
            }
        }).start();
    }

    private void installApk(File file) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Share via FileProvider
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
