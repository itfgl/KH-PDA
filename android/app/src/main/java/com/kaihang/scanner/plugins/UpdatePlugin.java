package com.kaihang.scanner.plugins;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.kaihang.scanner.PackageUpdateInstaller;

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

    /** 流式写入系统安装会话，不在公共 Downloads 中创建 APK 文件。 */
    @PluginMethod
    public void downloadAndInstallApk(PluginCall call) {
        String urlString = call.getString("url");
        if (urlString == null || urlString.isEmpty()) {
            call.reject("URL 不能为空");
            return;
        }

        try {
            if (!canInstallPackages()) {
                notifyInstallPermissionRequired();
                openUnknownAppSourcesSettings();
                call.reject("当前设备未授予安装未知应用权限");
                return;
            }

            PackageUpdateInstaller.downloadAndInstall(getActivity(), urlString,
                new PackageUpdateInstaller.Listener() {
                    @Override
                    public void onProgress(int progress) {
                        JSObject obj = new JSObject();
                        obj.put("progress", progress);
                        notifyListeners("downloadProgress", obj);
                    }

                    @Override
                    public void onInstallSessionCommitted() {
                        call.resolve();
                    }

                    @Override
                    public void onInstallStatus(String status, String message) {
                        JSObject obj = new JSObject();
                        obj.put("status", status);
                        obj.put("message", message);
                        notifyListeners("installStatus", obj);
                    }

                    @Override
                    public void onError(String message) {
                        call.reject("更新安装失败: " + message);
                    }
                });

        } catch (Exception e) {
            call.reject("启动下载失败: " + e.getMessage());
        }
    }

    /** 弹出系统卸载对话框，用户确认后卸载本 App（覆盖安装签名不一致时使用） */
    @PluginMethod
    public void uninstallApp(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
        intent.setData(Uri.parse("package:" + getContext().getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void exitApp(PluginCall call) {
        call.resolve();
        android.os.Process.killProcess(android.os.Process.myPid());
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

    private boolean canInstallPackages() {
        return PackageUpdateInstaller.canInstallPackages(getContext());
    }

    private void notifyInstallPermissionRequired() {
        JSObject data = new JSObject();
        data.put("reason", "请先在系统设置中开启安装未知应用/未知来源，然后返回并再次点击检查更新");
        notifyListeners("installPermissionRequired", data);
    }

    private void openUnknownAppSourcesSettings() {
        PackageUpdateInstaller.openUnknownAppSourcesSettings(getContext());
    }
}
