package com.kaihang.scanner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

public class PackageInstallStatusReceiver extends BroadcastReceiver {
    static final String ACTION_INSTALL_STATUS =
        "com.kaihang.scanner.action.PACKAGE_INSTALL_STATUS";
    static final String EXTRA_SESSION_ID = "session_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        );
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmationIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmationIntent == null) {
                showToast(context, "无法打开系统安装确认界面");
                return;
            }
            confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(confirmationIntent);
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            showToast(context, "应用更新安装完成");
            return;
        }

        String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        showToast(context, "应用更新安装失败" + (detail == null ? "" : ": " + detail));
    }

    private static void showToast(Context context, String message) {
        Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }
}
