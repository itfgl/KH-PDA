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
    private static final String KEY_LAYOUT_PRESET = "layout_preset";
    private static final String DEFAULT_SERVER_BASE = "http://115.29.178.34:2974";

    public static String getSavedServerBase(Context context, String fallback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return normalizeBaseUrl(prefs.getString(KEY_SERVER_BASE, ""), fallback);
    }

    public static JSObject getSavedConfig(Context context) {
        return readConfig(context);
    }

    public static JSObject saveConfig(Context context, String serverBase, String updateBase, String paperType, String layoutPreset) {
        String normalizedServerBase = normalizeBaseUrl(serverBase, DEFAULT_SERVER_BASE);
        String normalizedUpdateBase = normalizeBaseUrl(updateBase, normalizedServerBase);
        String normalizedPaperType = normalizePaperType(paperType);
        String normalizedLayoutPreset = normalizeLayoutPreset(layoutPreset);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putString(KEY_SERVER_BASE, normalizedServerBase)
            .putString(KEY_UPDATE_BASE, normalizedUpdateBase)
            .putString(KEY_PAPER_TYPE, normalizedPaperType)
            .putString(KEY_LAYOUT_PRESET, normalizedLayoutPreset)
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
        call.resolve(saveConfig(
            getContext(),
            call.getString("serverBase"),
            call.getString("updateBase"),
            call.getString("paperType"),
            call.getString("layoutPreset")
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
        String updateBase = normalizeBaseUrl(prefs.getString(KEY_UPDATE_BASE, ""), serverBase);
        String paperType = normalizePaperType(prefs.getString(KEY_PAPER_TYPE, "thermal"));
        String layoutPreset = normalizeLayoutPreset(prefs.getString(KEY_LAYOUT_PRESET, "standard"));

        JSObject data = new JSObject();
        data.put("serverBase", serverBase);
        data.put("updateBase", updateBase);
        data.put("paperType", paperType);
        data.put("layoutPreset", layoutPreset);
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

    private static String normalizeLayoutPreset(String value) {
        String raw = value == null ? "" : value.trim().toLowerCase();
        if ("compact".equals(raw) || "large".equals(raw)) return raw;
        return "standard";
    }
}
