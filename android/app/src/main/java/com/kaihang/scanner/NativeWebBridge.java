package com.kaihang.scanner;

import android.app.Activity;
import android.webkit.JavascriptInterface;

import com.kaihang.scanner.plugins.ClientConfigPlugin;
import com.kaihang.scanner.plugins.PrintPlugin;

/**
 * 注入网页的 JS 桥（window.KaihangNativeBridge）：扫码控制、客户端配置读写、
 * 打印/预览调度、更新与日志入口。从 MainActivity 拆出——本类只做参数解析与
 * 原生插件调用，页面级状态与弹窗决策经 Host 回调交回 MainActivity。
 */
final class NativeWebBridge {

    /** 宿主回调：MainActivity 持有的页面状态与交互入口 */
    interface Host {
        void triggerScanStart();

        void triggerScanStop();

        void openClientSettings();

        void showUpdateDialog();

        void showLogDialog();

        void setScanActionVisible(boolean visible);

        void setPageReadyState(String state, String detail);

        /** 扫码结果已返回：取消超时释放计时并复位扫码激活态 */
        void releaseScanAfterResult();

        /** 设备能力已探测完成且无原生打印机 → 打印转预览 */
        boolean shouldPreviewPrint();

        void appendLog(String message);

        void toast(String message);
    }

    private final Activity activity;
    private final Host host;
    private int printBridgeCallCount = 0;

    NativeWebBridge(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    @JavascriptInterface
    public boolean startScan() {
        activity.runOnUiThread(host::triggerScanStart);
        return true;
    }

    @JavascriptInterface
    public boolean stopScan() {
        activity.runOnUiThread(host::triggerScanStop);
        return true;
    }

    @JavascriptInterface
    public void openSettings() {
        activity.runOnUiThread(host::openClientSettings);
    }

    @JavascriptInterface
    public void checkUpdate() {
        activity.runOnUiThread(host::showUpdateDialog);
    }

    @JavascriptInterface
    public void showLogs() {
        activity.runOnUiThread(host::showLogDialog);
    }

    @JavascriptInterface
    public String getClientConfig() {
        return ClientConfigPlugin.getSavedConfig(activity).toString();
    }

    @JavascriptInterface
    public String saveClientConfig(String payloadJson) {
        try {
            org.json.JSONObject payload = new org.json.JSONObject(payloadJson == null ? "{}" : payloadJson);
            com.getcapacitor.JSObject current = ClientConfigPlugin.getSavedConfig(activity);
            com.getcapacitor.JSObject saved = ClientConfigPlugin.saveConfig(
                activity,
                payload.optString("serverBase", null),
                payload.optString("updateBase", null),
                payload.optString("paperType", null),
                payload.has("enableFloatingLogs") ? payload.optBoolean("enableFloatingLogs") : current.optBoolean("enableFloatingLogs", true),
                payload.has("enableVerboseLogs") ? payload.optBoolean("enableVerboseLogs") : current.optBoolean("enableVerboseLogs", true),
                payload.has("enableNetworkHeaderPatch") ? payload.optBoolean("enableNetworkHeaderPatch") : current.optBoolean("enableNetworkHeaderPatch", true),
                payload.has("enableHistoryPatch") ? payload.optBoolean("enableHistoryPatch") : current.optBoolean("enableHistoryPatch", true),
                payload.has("enableStoragePatch") ? payload.optBoolean("enableStoragePatch") : current.optBoolean("enableStoragePatch", true),
                payload.has("enableUiReadyObserver") ? payload.optBoolean("enableUiReadyObserver") : current.optBoolean("enableUiReadyObserver", true),
                payload.has("enableActionObserver") ? payload.optBoolean("enableActionObserver") : current.optBoolean("enableActionObserver", true),
                payload.has("enableRuntimeReuse") ? payload.optBoolean("enableRuntimeReuse") : current.optBoolean("enableRuntimeReuse", true)
            );
            return saved.toString();
        } catch (Exception e) {
            host.appendLog("保存客户端配置失败: " + e.getMessage());
            return null;
        }
    }

    @JavascriptInterface
    public void restartApp() {
        activity.runOnUiThread(() -> ClientConfigPlugin.restartApp(activity));
    }

    @JavascriptInterface
    public void setScanActionEnabled(boolean enabled) {
        activity.runOnUiThread(() -> host.setScanActionVisible(enabled));
    }

    @JavascriptInterface
    public void reportPageReadyState(String state, String detail) {
        activity.runOnUiThread(() -> host.setPageReadyState(state, detail));
    }

    @JavascriptInterface
    public void onScanCompleted() {
        activity.runOnUiThread(host::releaseScanAfterResult);
    }

    @JavascriptInterface
    public boolean connectPrinter() {
        activity.runOnUiThread(() -> PrintPlugin.connectNative(activity));
        return true;
    }

    @JavascriptInterface
    public boolean shouldPreviewPrint() {
        return host.shouldPreviewPrint();
    }

    @JavascriptInterface
    public boolean printLabel(String payloadJson) {
        try {
            org.json.JSONObject payload = new org.json.JSONObject(payloadJson == null ? "{}" : payloadJson);
            String qrCodeValue = payload.optString("qrCodeValue", "");
            String textValue = payload.optString("textValue", "");
            String paperType = payload.optString("paperType", "thermal");
            int qrSize = payload.optInt("qrSize", 0);
            String qrAlign = payload.optString("qrAlign", "center");
            int textColumns = payload.optInt("textColumns", 1);
            String textStylesJson = payload.optString("textStyles", "");
            printBridgeCallCount += 1;
            String compactText = safe(textValue).replace("\r", " ").replace("\n", "\\n");
            if (compactText.length() > 120) compactText = compactText.substring(0, 120) + "…";
            host.appendLog(
                "原生打印桥调用#" + printBridgeCallCount
                    + ": qrcode=" + safe(qrCodeValue)
                    + ", text=" + compactText
                    + ", paperType=" + safe(paperType)
                    + ", qrSize=" + qrSize
                    + ", qrAlign=" + safe(qrAlign)
                    + ", textColumns=" + textColumns
                    + (textStylesJson.isEmpty() ? "" : ", textStyles=" + textStylesJson)
            );
            if (host.shouldPreviewPrint()) {
                host.appendLog("原生打印桥检测到无打印机，强制转为标签预览");
                activity.runOnUiThread(() -> {
                    host.toast("正在生成标签预览…");
                    PrintPlugin.previewLabelNative(
                        activity,
                        activity,
                        qrCodeValue,
                        textValue,
                        qrSize,
                        qrAlign,
                        textColumns,
                        textStylesJson
                    );
                });
                return true;
            }
            activity.runOnUiThread(() -> PrintPlugin.printLabelNative(
                activity,
                activity,
                qrCodeValue,
                textValue,
                paperType,
                qrSize,
                qrAlign,
                textColumns,
                textStylesJson
            ));
            return true;
        } catch (Exception e) {
            host.appendLog("原生打印桥参数解析失败: " + e.getMessage());
            return false;
        }
    }

    @JavascriptInterface
    public boolean previewLabel(String payloadJson) {
        try {
            org.json.JSONObject payload = new org.json.JSONObject(payloadJson == null ? "{}" : payloadJson);
            String qrCodeValue = payload.optString("qrCodeValue", "");
            String textValue = payload.optString("textValue", "");
            int qrSize = payload.optInt("qrSize", 0);
            String qrAlign = payload.optString("qrAlign", "center");
            int textColumns = payload.optInt("textColumns", 1);
            String textStylesJson = payload.optString("textStyles", "");
            host.appendLog(
                "无打印机，生成标签预览: qrcode=" + safe(qrCodeValue)
                    + ", qrSize=" + qrSize
                    + ", qrAlign=" + safe(qrAlign)
                    + ", textColumns=" + textColumns
                    + (textStylesJson.isEmpty() ? "" : ", textStyles=" + textStylesJson)
            );
            activity.runOnUiThread(() -> {
                host.toast("正在生成标签预览…");
                PrintPlugin.previewLabelNative(
                    activity,
                    activity,
                    qrCodeValue,
                    textValue,
                    qrSize,
                    qrAlign,
                    textColumns,
                    textStylesJson
                );
            });
            return true;
        } catch (Exception error) {
            host.appendLog("标签预览参数解析失败: " + error.getMessage());
            return false;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
