package com.kaihang.scanner;

import android.app.Activity;
import android.webkit.WebView;

/**
 * 运行日志弹窗：合并展示原生日志与网页日志（localStorage 的 KH_FLOATING_LOGS），
 * 支持清空与导出 TXT。从 MainActivity 拆出，日志数据与导出流程经 Host 回调由 MainActivity 提供。
 */
final class NativeLogDialog {

    /** 宿主回调：日志数据源与导出/提示能力 */
    interface Host {
        /** 原生日志文本（已 join），空时返回 "(暂无原生日志)" */
        String getNativeLogsText();
        /** 清空原生日志缓冲 */
        void clearNativeLogs();
        /** 当前 WebView（网页日志读取/清理用），不可用时返回 null */
        WebView getWebView();
        void appendLog(String message);
        void toast(String message);
        void runOnUiThread(Runnable action);
        /** 发起导出：宿主持有 pendingLogExportText 并负责 ACTION_CREATE_DOCUMENT 流程 */
        void requestExportLogs(String text);
    }

    private NativeLogDialog() {}

    static void show(Activity activity, Host host) {
        android.widget.TextView content = new android.widget.TextView(activity);
        content.setTextSize(12);
        content.setTextColor(android.graphics.Color.parseColor("#101828"));
        content.setTypeface(android.graphics.Typeface.MONOSPACE);
        int padding = dp(activity, 12);
        content.setPadding(padding, padding, padding, padding);
        content.setText("正在加载日志...");

        android.widget.ScrollView scrollView = new android.widget.ScrollView(activity);
        scrollView.addView(content);

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("运行日志")
            .setView(scrollView)
            .setPositiveButton("关闭", null)
            .setNegativeButton("导出 TXT", null)
            .setNeutralButton("清空", null)
            .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> clearCombinedLogs(activity, host, content));
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> exportCombinedLogs(host));
        });
        dialog.show();
        loadCombinedLogs(host, content);
    }

    private static void loadCombinedLogs(Host host, android.widget.TextView targetView) {
        collectCombinedLogs(host, text -> host.runOnUiThread(() -> targetView.setText(text)));
    }

    private interface ReadyCallback {
        void onReady(String text);
    }

    private static void collectCombinedLogs(Host host, ReadyCallback callback) {
        String nativeLogs = host.getNativeLogsText();
        WebView webView = host.getWebView();
        if (webView == null) {
            callback.onReady("== 原生日志 ==\n" + nativeLogs + "\n\n== 网页日志 ==\n(网页不可用)");
            return;
        }
        String script = "(function(){try{var raw=(window.localStorage&&window.localStorage.getItem('KH_FLOATING_LOGS'))||'[]';window.__khLastLogRaw=raw;return raw;}catch(e){return JSON.stringify([{text:'读取网页日志失败: '+String(e&&e.message||e||'unknown'),type:'err'}]);}})();";
        webView.evaluateJavascript(script, value -> {
            StringBuilder text = new StringBuilder();
            text.append("== 原生日志 ==\n").append(nativeLogs).append("\n\n== 网页日志 ==\n");
            try {
                String json = decodeJsString(value);
                org.json.JSONArray array = new org.json.JSONArray(json);
                if (array.length() == 0) {
                    text.append("(暂无网页日志)");
                } else {
                    for (int i = 0; i < array.length(); i++) {
                        org.json.JSONObject item = array.optJSONObject(i);
                        if (item == null) continue;
                        text.append(item.optString("text", "")).append('\n');
                    }
                }
            } catch (Exception e) {
                text.append("读取网页日志失败: ").append(e.getMessage());
            }
            callback.onReady(text.toString());
        });
    }

    private static void clearCombinedLogs(Activity activity, Host host, android.widget.TextView targetView) {
        host.clearNativeLogs();
        WebView webView = host.getWebView();
        if (webView == null) {
            targetView.setText("原生日志已清空；网页当前不可用，网页日志未清理");
            return;
        }
        String script = "(function(){try{var kh=window.__khClientRuntime;if(kh&&kh.clearFloatingLogs){kh.clearFloatingLogs();}else if(window.localStorage){window.localStorage.removeItem('KH_FLOATING_LOGS');}window.__khLastLogRaw='[]';window.__khLastLogSnapshot=[];return true;}catch(e){return false;}})();";
        webView.evaluateJavascript(script, value -> host.runOnUiThread(() -> {
            boolean webCleared = "true".equalsIgnoreCase(safe(value).replace("\"", "").trim());
            targetView.setText(webCleared ? "原生和网页日志已清空" : "原生日志已清空，网页日志清理失败");
        }));
    }

    private static void exportCombinedLogs(Host host) {
        host.appendLog("准备导出运行日志");
        collectCombinedLogs(host, host::requestExportLogs);
    }

    /** evaluateJavascript 返回的 JSON 字符串解码（含引号包裹与转义序列） */
    static String decodeJsString(String value) {
        if (value == null || "null".equals(value)) {
            return "[]";
        }
        try {
            Object decoded = new org.json.JSONTokener(value).nextValue();
            if (decoded instanceof String) {
                return (String) decoded;
            }
        } catch (Exception ignored) {}
        String raw = value;
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            raw = raw.substring(1, raw.length() - 1);
        }
        raw = raw
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t");
        return raw;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
