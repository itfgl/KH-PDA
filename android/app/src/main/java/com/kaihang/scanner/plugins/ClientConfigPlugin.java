package com.kaihang.scanner.plugins;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "ClientConfigPlugin")
public class ClientConfigPlugin extends Plugin {
    private static final String PREFS_NAME = "kaihang_client_config";
    private static final String KEY_SERVER_BASE = "server_base";
    private static final String KEY_UPDATE_BASE = "update_base";
    private static final String KEY_PAPER_TYPE = "paper_type";
    private static final String KEY_INJECTION_MODE = "injection_mode";
    private static final String KEY_ENABLE_FLOATING_LOGS = "enable_floating_logs";
    private static final String KEY_ENABLE_VERBOSE_LOGS = "enable_verbose_logs";
    private static final String KEY_ENABLE_NETWORK_HEADER_PATCH = "enable_network_header_patch";
    private static final String KEY_ENABLE_HISTORY_PATCH = "enable_history_patch";
    private static final String KEY_ENABLE_STORAGE_PATCH = "enable_storage_patch";
    private static final String KEY_ENABLE_UI_READY_OBSERVER = "enable_ui_ready_observer";
    private static final String KEY_ENABLE_ACTION_OBSERVER = "enable_action_observer";
    private static final String KEY_ENABLE_RUNTIME_REUSE = "enable_runtime_reuse";
    private static final String DEFAULT_SERVER_BASE = "http://192.168.2.60:8080";
    private static final String DEFAULT_UPDATE_BASE = "http://192.168.2.138:9000";

    public static String getSavedServerBase(Context context, String fallback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return normalizeBaseUrl(prefs.getString(KEY_SERVER_BASE, ""), fallback);
    }

    public static JSObject getSavedConfig(Context context) {
        return readConfig(context);
    }

    public static String getSavedInjectionMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return normalizeInjectionMode(prefs.getString(KEY_INJECTION_MODE, "aggressive"));
    }

    public static JSObject saveConfig(
        Context context,
        String serverBase,
        String updateBase,
        String paperType,
        String injectionMode,
        Boolean enableFloatingLogs,
        Boolean enableVerboseLogs,
        Boolean enableNetworkHeaderPatch,
        Boolean enableHistoryPatch,
        Boolean enableStoragePatch,
        Boolean enableUiReadyObserver,
        Boolean enableActionObserver,
        Boolean enableRuntimeReuse
    ) {
        String normalizedServerBase = normalizeBaseUrl(serverBase, DEFAULT_SERVER_BASE);
        String normalizedUpdateBase = normalizeBaseUrl(updateBase, DEFAULT_UPDATE_BASE);
        JSObject current = readConfig(context);
        // 字符串字段传 null（未携带）时保留现值，允许两个设置面板只保存各自负责的字段
        String normalizedPaperType = normalizePaperType(paperType != null ? paperType : current.optString("paperType", "thermal"));
        String normalizedInjectionMode = normalizeInjectionMode(injectionMode != null ? injectionMode : current.optString("injectionMode", "aggressive"));

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putString(KEY_SERVER_BASE, normalizedServerBase)
            .putString(KEY_UPDATE_BASE, normalizedUpdateBase)
            .putString(KEY_PAPER_TYPE, normalizedPaperType)
            .putString(KEY_INJECTION_MODE, normalizedInjectionMode)
            .putBoolean(KEY_ENABLE_FLOATING_LOGS, enableFloatingLogs != null ? enableFloatingLogs : current.optBoolean("enableFloatingLogs", true))
            .putBoolean(KEY_ENABLE_VERBOSE_LOGS, enableVerboseLogs != null ? enableVerboseLogs : current.optBoolean("enableVerboseLogs", true))
            .putBoolean(KEY_ENABLE_NETWORK_HEADER_PATCH, enableNetworkHeaderPatch != null ? enableNetworkHeaderPatch : current.optBoolean("enableNetworkHeaderPatch", true))
            .putBoolean(KEY_ENABLE_HISTORY_PATCH, enableHistoryPatch != null ? enableHistoryPatch : current.optBoolean("enableHistoryPatch", true))
            .putBoolean(KEY_ENABLE_STORAGE_PATCH, enableStoragePatch != null ? enableStoragePatch : current.optBoolean("enableStoragePatch", true))
            .putBoolean(KEY_ENABLE_UI_READY_OBSERVER, enableUiReadyObserver != null ? enableUiReadyObserver : current.optBoolean("enableUiReadyObserver", true))
            .putBoolean(KEY_ENABLE_ACTION_OBSERVER, enableActionObserver != null ? enableActionObserver : current.optBoolean("enableActionObserver", true))
            .putBoolean(KEY_ENABLE_RUNTIME_REUSE, enableRuntimeReuse != null ? enableRuntimeReuse : current.optBoolean("enableRuntimeReuse", true))
            .apply();

        return readConfig(context);
    }

    public static void restartApp(Context context) {
        Intent intent = context.getPackageManager()
            .getLaunchIntentForPackage(context.getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @PluginMethod
    public void getConfig(PluginCall call) {
        call.resolve(readConfig(getContext()));
    }

    @PluginMethod
    public void saveConfig(PluginCall call) {
        JSObject current = readConfig(getContext());
        call.resolve(saveConfig(
            getContext(),
            call.getString("serverBase"),
            call.getString("updateBase"),
            call.getString("paperType"),
            call.getString("injectionMode"),
            call.getBoolean("enableFloatingLogs", current.optBoolean("enableFloatingLogs", true)),
            call.getBoolean("enableVerboseLogs", current.optBoolean("enableVerboseLogs", true)),
            call.getBoolean("enableNetworkHeaderPatch", current.optBoolean("enableNetworkHeaderPatch", true)),
            call.getBoolean("enableHistoryPatch", current.optBoolean("enableHistoryPatch", true)),
            call.getBoolean("enableStoragePatch", current.optBoolean("enableStoragePatch", true)),
            call.getBoolean("enableUiReadyObserver", current.optBoolean("enableUiReadyObserver", true)),
            call.getBoolean("enableActionObserver", current.optBoolean("enableActionObserver", true)),
            call.getBoolean("enableRuntimeReuse", current.optBoolean("enableRuntimeReuse", true))
        ));
    }

    @PluginMethod
    public void restartApp(PluginCall call) {
        restartApp(getContext());
        call.resolve();
    }

    private static JSObject readConfig(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String serverBase = normalizeBaseUrl(prefs.getString(KEY_SERVER_BASE, ""), DEFAULT_SERVER_BASE);
        String updateBase = normalizeBaseUrl(prefs.getString(KEY_UPDATE_BASE, ""), DEFAULT_UPDATE_BASE);
        String paperType = normalizePaperType(prefs.getString(KEY_PAPER_TYPE, "thermal"));
        String injectionMode = normalizeInjectionMode(prefs.getString(KEY_INJECTION_MODE, "aggressive"));
        // 日志默认全关：生产环境零记录，调试人员在原生配置里手动打开
        boolean enableFloatingLogs = prefs.getBoolean(KEY_ENABLE_FLOATING_LOGS, false);
        boolean enableVerboseLogs = prefs.getBoolean(KEY_ENABLE_VERBOSE_LOGS, false);
        boolean enableNetworkHeaderPatch = prefs.getBoolean(KEY_ENABLE_NETWORK_HEADER_PATCH, true);
        boolean enableHistoryPatch = prefs.getBoolean(KEY_ENABLE_HISTORY_PATCH, true);
        boolean enableStoragePatch = prefs.getBoolean(KEY_ENABLE_STORAGE_PATCH, true);
        boolean enableUiReadyObserver = prefs.getBoolean(KEY_ENABLE_UI_READY_OBSERVER, true);
        boolean enableActionObserver = prefs.getBoolean(KEY_ENABLE_ACTION_OBSERVER, true);
        boolean enableRuntimeReuse = prefs.getBoolean(KEY_ENABLE_RUNTIME_REUSE, true);

        JSObject data = new JSObject();
        data.put("serverBase", serverBase);
        data.put("updateBase", updateBase);
        data.put("paperType", paperType);
        data.put("injectionMode", injectionMode);
        data.put("enableFloatingLogs", enableFloatingLogs);
        data.put("enableVerboseLogs", enableVerboseLogs);
        data.put("enableNetworkHeaderPatch", enableNetworkHeaderPatch);
        data.put("enableHistoryPatch", enableHistoryPatch);
        data.put("enableStoragePatch", enableStoragePatch);
        data.put("enableUiReadyObserver", enableUiReadyObserver);
        data.put("enableActionObserver", enableActionObserver);
        data.put("enableRuntimeReuse", enableRuntimeReuse);
        return data;
    }

    private static String normalizeBaseUrl(String value, String fallback) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) raw = fallback == null ? "" : fallback.trim();
        return raw.replaceAll("/+$", "");
    }

    private static String normalizePaperType(String value) {
        return "black_mark".equalsIgnoreCase(value == null ? "" : value.trim()) ? "black_mark" : "thermal";
    }

    // 注入时机固定激进模式：定制 ROM 只在该模式下可靠注入，不再提供其他选项
    private static String normalizeInjectionMode(String value) {
        return "aggressive";
    }
}
