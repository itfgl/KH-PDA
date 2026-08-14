package com.kaihang.scanner;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.os.Bundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Streams an APK from the network into a PackageInstaller session.
 *
 * The APK never appears in public Downloads and is not held as one large byte
 * array in application memory. Android still stages the package in its own
 * private installer storage before asking the user to approve installation.
 */
public final class PackageUpdateInstaller {
    public interface PermissionCallback {
        void onResult(boolean granted);
    }

    public interface Listener {
        void onProgress(int progress);

        void onInstallSessionCommitted();

        void onInstallStatus(String status, String message);

        void onError(String message);
    }

    private static final int BUFFER_SIZE = 64 * 1024;
    private static volatile Listener activeListener;

    private PackageUpdateInstaller() {}

    public static boolean canInstallPackages(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return context.getPackageManager().canRequestPackageInstalls();
        }
        try {
            return Settings.Global.getInt(
                context.getContentResolver(),
                Settings.Global.INSTALL_NON_MARKET_APPS,
                0
            ) == 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean openUnknownAppSourcesSettings(Context context) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
        } else {
            intent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(intent);
            return true;
        }
        return false;
    }

    public static void requestInstallPermission(
        Activity activity,
        PermissionCallback callback
    ) {
        final boolean[] leftActivity = {false};
        final boolean[] completed = {false};
        android.app.Application.ActivityLifecycleCallbacks lifecycleCallbacks =
            new android.app.Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityPaused(Activity resumedActivity) {
                    if (resumedActivity == activity) {
                        leftActivity[0] = true;
                    }
                }

                @Override
                public void onActivityResumed(Activity resumedActivity) {
                    if (resumedActivity != activity || !leftActivity[0] || completed[0]) {
                        return;
                    }
                    completed[0] = true;
                    activity.getApplication().unregisterActivityLifecycleCallbacks(this);
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        () -> callback.onResult(canInstallPackages(activity)),
                        500
                    );
                }

                @Override public void onActivityCreated(Activity a, Bundle b) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityStopped(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                @Override
                public void onActivityDestroyed(Activity destroyedActivity) {
                    if (destroyedActivity == activity && !completed[0]) {
                        completed[0] = true;
                        activity.getApplication().unregisterActivityLifecycleCallbacks(this);
                        callback.onResult(false);
                    }
                }
            };
        activity.getApplication().registerActivityLifecycleCallbacks(lifecycleCallbacks);
        if (!openUnknownAppSourcesSettings(activity)) {
            completed[0] = true;
            activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
            callback.onResult(false);
        }
    }

    public static boolean isPermissionRejection(String message) {
        String normalized = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("rejected permissions")
            || normalized.contains("permission")
            || normalized.contains("not allowed to install unknown");
    }

    public static void downloadAndInstall(Activity activity, String url, Listener listener) {
        activeListener = listener;
        new Thread(() -> streamIntoInstallSession(activity, url, listener),
            "apk-update-installer").start();
    }

    static void reportInstallStatus(Context context, String status, String message) {
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
            if (!canInstallPackages(activity)) {
                activity.runOnUiThread(() -> openUnknownAppSourcesSettings(activity));
                throw new SecurityException("安装未知应用权限未开启，请允许后重新点击更新");
            }
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
            session.commit(pendingIntent.getIntentSender());
            session.close();
            session = null;
            activity.runOnUiThread(listener::onInstallSessionCommitted);
        } catch (Exception e) {
            if (session != null) {
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
