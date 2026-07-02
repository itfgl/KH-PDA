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
    private static final String DEFAULT_SCAN_SELECTOR =
        "input[placeholder*='流水号'],input[aria-label*='流水号'],input[name*='serial'],input[name*='batch'],input[id*='serial'],input[id*='batch'],input[placeholder*='编号'],input[aria-label*='编号']";

    private BroadcastReceiver scanReceiver;
    private boolean receiverRegistered = false;

    public static void triggerStartScan(Context context) {
        if (context == null) return;
        try {
            context.sendBroadcast(new Intent(ACTION_STOP));
        } catch (Exception ignored) {}
        context.sendBroadcast(new Intent(ACTION_START));
    }

    public static void triggerStopScan(Context context) {
        if (context == null) return;
        context.sendBroadcast(new Intent(ACTION_STOP));
    }

    @Override
    public void load() {
        scanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String value = intent.getStringExtra("string");
                if (value == null || value.isEmpty()) return;

                // 收到扫码结果后，立即发送 STOP 广播关闭扫描状态并复位扫码助手，否则可能导致下一次触发失效
                try {
                    context.sendBroadcast(new Intent(ACTION_STOP));
                } catch (Exception ignored) {}

                JSObject data = new JSObject();
                data.put("value", value);
                notifyListeners(EVENT_SCAN, data);
                injectScanToPage(value);
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
        triggerStartScan(getContext());
        call.resolve();
    }

    @PluginMethod
    public void stopScan(PluginCall call) {
        triggerStopScan(getContext());
        call.resolve();
    }



    @Override
    protected void handleOnDestroy() {
        // 先停止扫描，防止 App 退出后扫码枪仍处于触发状态（激光亮着）
        try { triggerStopScan(getContext()); } catch (Exception ignored) {}
        if (receiverRegistered && scanReceiver != null) {
            getContext().unregisterReceiver(scanReceiver);
            receiverRegistered = false;
        }
    }

    private void injectScanToPage(String value) {
        if (bridge == null || bridge.getWebView() == null) return;
        String script =
            "(function(){" +
            "var raw=" + js(value) + ";" +
            "var val=String(raw||'').trim();" +
            "if(!val)return;" +
            "var active=document.activeElement;" +
            "var target=null;" +
            "var isWritable=function(el){if(!el)return false;var tag=(el.tagName||'').toLowerCase();return tag==='input'||tag==='textarea'||el.isContentEditable;};" +
            "var readValue=function(el){if(!el)return '';if(el.isContentEditable)return String(el.textContent||'').trim();if(el.value!==undefined&&el.value!==null)return String(el.value).trim();return '';};" +
            "if(isWritable(active)){target=active;}" +
            "if(!target){target=document.querySelector(" + js(DEFAULT_SCAN_SELECTOR) + ");}" +
            "if(target){" +
            "var sameValue=readValue(target)===val;" +
            "if(!sameValue){" +
            "if(target.isContentEditable){target.textContent=val;}" +
            "else{target.focus();target.value=val;}" +
            "['input','change'].forEach(function(name){target.dispatchEvent(new Event(name,{bubbles:true}));});" +
            "try{target.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));}catch(e){}" +
            "try{target.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));}catch(e){}" +
            "}" +
            "}" +
            "window.dispatchEvent(new CustomEvent('kh:scan',{detail:{value:val,targetFound:!!target,duplicateInput:!!target&&readValue(target)===val}}));" +
            "})();";
        bridge.getWebView().post(() -> bridge.getWebView().evaluateJavascript(script, null));
    }

    private static String js(String value) {
        String v = value == null ? "" : value;
        return "'" + v
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\r", "\\r")
            .replace("\n", "\\n") + "'";
    }
}
