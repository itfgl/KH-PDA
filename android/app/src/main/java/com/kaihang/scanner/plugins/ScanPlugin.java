package com.kaihang.scanner.plugins;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 扫码 Plugin
 *
 * 依赖设备上安装"扫码助手"App（硬件驱动），通过系统广播收发扫码事件。
 *
 * JS 调用：
 *   ScanPlugin.addListener('scanResult', (data) => { data.value })
 *   ScanPlugin.startScan()   // 触发扫码（等效按硬件扳机）
 *   ScanPlugin.stopScan()    // 停止扫码
 */
@CapacitorPlugin(name = "ScanPlugin")
public class ScanPlugin extends Plugin {

    private static final String ACTION_RESULT = "com.uc.scanner.result";
    private static final String ACTION_START  = "com.uc.scanner.trigger.START";
    private static final String ACTION_STOP   = "com.uc.scanner.trigger.STOP";
    private static final String EVENT_SCAN    = "scanResult";

    private BroadcastReceiver scanReceiver;
    private boolean receiverRegistered = false;

    @Override
    public void load() {
        scanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String value = intent.getStringExtra("string");
                if (value == null || value.isEmpty()) return;
                JSObject data = new JSObject();
                data.put("value", value);
                notifyListeners(EVENT_SCAN, data);
            }
        };
        // Android 13+（API 33）必须显式声明 RECEIVER_EXPORTED，否则跨 App 广播收不到
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(scanReceiver, new IntentFilter(ACTION_RESULT), Context.RECEIVER_EXPORTED);
        } else {
            getContext().registerReceiver(scanReceiver, new IntentFilter(ACTION_RESULT));
        }
        receiverRegistered = true;
    }

    @PluginMethod
    public void startScan(PluginCall call) {
        getContext().sendBroadcast(new Intent(ACTION_START));
        call.resolve();
    }

    @PluginMethod
    public void stopScan(PluginCall call) {
        getContext().sendBroadcast(new Intent(ACTION_STOP));
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        if (receiverRegistered && scanReceiver != null) {
            getContext().unregisterReceiver(scanReceiver);
            receiverRegistered = false;
        }
    }
}
