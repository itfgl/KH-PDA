package com.kaihang.scanner;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Downloads an APK into app-private cache and opens the device installer.
 *
 * The APK never appears in public Downloads and users never manage the file.
 * App-private cache is used because this customized PDA firmware rejects
 * PackageInstaller sessions even after reporting pending user confirmation.
 */
public final class PackageUpdateInstaller {
    public interface Listener {
        void onProgress(int progress);

        void onInstallSessionCommitted();

        void onInstallStatus(String status, String message);

        void onError(String message);
    }

    private static final int BUFFER_SIZE = 64 * 1024;
    private static volatile Listener activeListener;
    private static final ConcurrentHashMap<Integer, PackageInstaller.Session> ACTIVE_SESSIONS =
        new ConcurrentHashMap<>();

    private PackageUpdateInstaller() {}

    public static void downloadAndInstall(Activity activity, String url, Listener listener) {
        activeListener = listener;
        new Thread(() -> downloadToPrivateCacheAndInstall(activity, url, listener),
            "apk-update-installer").start();
    }

    private static void downloadToPrivateCacheAndInstall(
        Activity activity,
        String url,
        Listener listener
    ) {
        HttpURLConnection connection = null;
        File apkFile = null;
        try {
            File updateDirectory = new File(activity.getCacheDir(), "updates");
            if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
                throw new IOException("无法创建应用更新缓存目录");
            }
            apkFile = new File(updateDirectory, "kaihang_update.apk");

            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("X-Client-Type", "capacitor");
            connection.connect();
            int responseStatus = connection.getResponseCode();
            if (responseStatus < 200 || responseStatus >= 300) {
                throw new IOException("APK 下载失败: HTTP " + responseStatus);
            }

            long totalBytes = connection.getContentLengthLong();
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(apkFile, false)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long downloadedBytes = 0;
                int lastProgress = -1;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    downloadedBytes += read;
                    if (totalBytes > 0) {
                        int progress = (int) Math.min(99, downloadedBytes * 100 / totalBytes);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            dispatchProgress(activity, listener, progress);
                        }
                    }
                }
                output.flush();
                output.getFD().sync();
            }

            dispatchProgress(activity, listener, 100);
            Uri installUri = FileProvider.getUriForFile(
                activity,
                activity.getPackageName() + ".fileprovider",
                apkFile
            );
            Intent installIntent = new Intent(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? Intent.ACTION_INSTALL_PACKAGE
                : Intent.ACTION_VIEW);
            installIntent.setDataAndType(
                installUri,
                "application/vnd.android.package-archive"
            );
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            installIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            activity.runOnUiThread(() -> {
                try {
                    activity.startActivity(installIntent);
                    listener.onInstallStatus(
                        "pending_user_action",
                        "已打开定制系统 APK 安装界面"
                    );
                    listener.onInstallSessionCommitted();
                } catch (Exception error) {
                    listener.onError("打开系统安装界面失败: " + error.getMessage());
                }
            });
        } catch (Exception error) {
            if (apkFile != null && apkFile.exists()) {
                // A partial cache file is never exposed to the installer.
                apkFile.delete();
            }
            String message = error.getMessage();
            String finalMessage = message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
            activity.runOnUiThread(() -> listener.onError(finalMessage));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static void reportInstallStatus(
        Context context,
        int sessionId,
        String status,
        String message
    ) {
        context.getSharedPreferences("kh_update_installer", Context.MODE_PRIVATE)
            .edit()
            .putString("last_status", status)
            .putString("last_message", message == null ? "" : message)
            .apply();
        Listener listener = activeListener;
        if (listener != null) {
            listener.onInstallStatus(status, message == null ? "" : message);
        }
        if (!"pending_user_action".equals(status)) {
            activeListener = null;
            PackageInstaller.Session session = ACTIVE_SESSIONS.remove(sessionId);
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {}
            }
        }
    }

    private static void streamIntoInstallSession(
        Activity activity,
        String url,
        Listener listener
    ) {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        int sessionId = -1;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("X-Client-Type", "capacitor");
            connection.connect();

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("APK 下载失败: HTTP " + status);
            }

            long totalBytes = connection.getContentLengthLong();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(activity.getPackageName());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            }

            sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            try (InputStream input = connection.getInputStream();
                 OutputStream output = session.openWrite("base.apk", 0, totalBytes)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long downloadedBytes = 0;
                int lastProgress = -1;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    downloadedBytes += read;
                    if (totalBytes > 0) {
                        int progress = (int) Math.min(99, downloadedBytes * 100 / totalBytes);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            dispatchProgress(activity, listener, progress);
                        }
                    }
                }
                session.fsync(output);
            }

            dispatchProgress(activity, listener, 100);
            Intent resultIntent = new Intent(activity, PackageInstallStatusReceiver.class);
            resultIntent.setAction(PackageInstallStatusReceiver.ACTION_INSTALL_STATUS);
            resultIntent.putExtra(PackageInstallStatusReceiver.EXTRA_SESSION_ID, sessionId);
            int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pendingIntentFlags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                activity,
                sessionId,
                resultIntent,
                pendingIntentFlags
            );
            // Some customized PDA firmware destroys a committed session when the
            // installer-side handle is closed before user confirmation finishes.
            // Retain it until the final success/failure callback arrives.
            ACTIVE_SESSIONS.put(sessionId, session);
            session.commit(pendingIntent.getIntentSender());
            session = null;
            activity.runOnUiThread(listener::onInstallSessionCommitted);
        } catch (Exception e) {
            if (session != null) {
                ACTIVE_SESSIONS.remove(sessionId);
                try {
                    session.abandon();
                } catch (Exception ignored) {}
                session.close();
            } else if (sessionId >= 0) {
                try {
                    installer.abandonSession(sessionId);
                } catch (Exception ignored) {}
            }
            String message = e.getMessage();
            String finalMessage = message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : message;
            activeListener = null;
            activity.runOnUiThread(() -> listener.onError(finalMessage));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void dispatchProgress(Activity activity, Listener listener, int progress) {
        activity.runOnUiThread(() -> listener.onProgress(progress));
    }
}
