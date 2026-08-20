package com.kaihang.scanner;

import android.app.Activity;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.getcapacitor.JSObject;

final class NativeSettingsDialog {
    interface Callbacks {
        String normalizeBaseUrl(String value, String fallback);

        void toast(String message);

        void onSave(SettingsValues values);
    }

    static final class SettingsValues {
        final String serverBase;
        final String updateBase;
        final String paperType;
        final String layout;
        final String injectionMode;
        final boolean enableFloatingLogs;
        final boolean enableVerboseLogs;
        final boolean enableNetworkHeaderPatch;
        final boolean enableHistoryPatch;
        final boolean enableStoragePatch;
        final boolean enableUiReadyObserver;
        final boolean enableActionObserver;
        final boolean enableRuntimeReuse;

        SettingsValues(
            String serverBase,
            String updateBase,
            String paperType,
            String layout,
            String injectionMode,
            boolean enableFloatingLogs,
            boolean enableVerboseLogs,
            boolean enableNetworkHeaderPatch,
            boolean enableHistoryPatch,
            boolean enableStoragePatch,
            boolean enableUiReadyObserver,
            boolean enableActionObserver,
            boolean enableRuntimeReuse
        ) {
            this.serverBase = serverBase;
            this.updateBase = updateBase;
            this.paperType = paperType;
            this.layout = layout;
            this.injectionMode = injectionMode;
            this.enableFloatingLogs = enableFloatingLogs;
            this.enableVerboseLogs = enableVerboseLogs;
            this.enableNetworkHeaderPatch = enableNetworkHeaderPatch;
            this.enableHistoryPatch = enableHistoryPatch;
            this.enableStoragePatch = enableStoragePatch;
            this.enableUiReadyObserver = enableUiReadyObserver;
            this.enableActionObserver = enableActionObserver;
            this.enableRuntimeReuse = enableRuntimeReuse;
        }

        String buildSaveSummary() {
            return "已保存原生设置: serverBase=" + serverBase
                + ", updateBase=" + updateBase
                + ", paperType=" + paperType
                + ", layout=" + layout
                + ", injectionMode=" + injectionMode
                + ", floatingLogs=" + enableFloatingLogs
                + ", verboseLogs=" + enableVerboseLogs
                + ", headerPatch=" + enableNetworkHeaderPatch
                + ", historyPatch=" + enableHistoryPatch
                + ", storagePatch=" + enableStoragePatch
                + ", uiReadyObserver=" + enableUiReadyObserver
                + ", actionObserver=" + enableActionObserver
                + ", runtimeReuse=" + enableRuntimeReuse;
        }
    }

    private NativeSettingsDialog() {}

    static void show(
        Activity activity,
        JSObject config,
        String defaultServerBase,
        String defaultUpdateBase,
        Callbacks callbacks
    ) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 4));

        EditText serverInput = createUrlInput(activity, config.optString("serverBase", defaultServerBase));
        EditText updateInput = createUrlInput(activity, config.optString("updateBase", defaultUpdateBase));
        Spinner paperSpinner = createSpinner(activity, new String[]{"普通热敏纸", "黑标标签纸"});
        Spinner layoutSpinner = createSpinner(activity, new String[]{"标准排版", "紧凑排版", "大字排版"});
        // 高级选项（仅排障用）：默认收起，避免现场误改注入时机/性能开关导致扫码打印失效
        TextView advancedToggle = createSectionLabel(activity, "高级选项（仅排障用）▸");
        LinearLayout advancedBox = new LinearLayout(activity);
        advancedBox.setOrientation(LinearLayout.VERTICAL);
        advancedBox.setVisibility(android.view.View.GONE);
        advancedToggle.setOnClickListener(v -> {
            boolean expanded = advancedBox.getVisibility() == android.view.View.VISIBLE;
            advancedBox.setVisibility(expanded ? android.view.View.GONE : android.view.View.VISIBLE);
            advancedToggle.setText(expanded ? "高级选项（仅排障用）▸" : "高级选项（仅排障用）▾");
        });
        Spinner injectionModeSpinner = createSpinner(activity, new String[]{"激进模式（started + commit + loaded）", "稳妥模式（commit + loaded）", "轻量模式（仅 loaded）", "手动模式（只手动初始化）"});
        SwitchCompat floatingLogsSwitch = createSwitchRow(activity, advancedBox, "网页浮动日志", "控制网页侧日志面板、日志持久化和全局错误日志");
        SwitchCompat verboseLogsSwitch = createSwitchRow(activity, advancedBox, "详细运行日志", "控制是否记录大量初始化、observer、动作匹配和控制台过程日志");
        SwitchCompat networkPatchSwitch = createSwitchRow(activity, advancedBox, "自动补 X-Client-Type", "控制是否 patch fetch / XHR 并自动补客户端请求头");
        SwitchCompat historyPatchSwitch = createSwitchRow(activity, advancedBox, "路由监听", "控制是否 patch history.pushState / replaceState");
        SwitchCompat storagePatchSwitch = createSwitchRow(activity, advancedBox, "存储监听", "控制是否 patch localStorage / sessionStorage 变更");
        SwitchCompat uiReadyObserverSwitch = createSwitchRow(activity, advancedBox, "页面就绪 observer", "控制网页 ready 的 DOM 兜底检测");
        SwitchCompat actionObserverSwitch = createSwitchRow(activity, advancedBox, "页面动作 observer", "控制 DOM 变化时是否自动刷新页面动作");
        SwitchCompat runtimeReuseSwitch = createSwitchRow(activity, advancedBox, "同页复用 runtime", "控制检测到已初始化 runtime 后是否只刷新页面动作，不再重复整段注入");
        paperSpinner.setSelection("black_mark".equals(config.optString("paperType", "thermal")) ? 1 : 0);
        String layoutPreset = config.optString("layoutPreset", "standard");
        layoutSpinner.setSelection("compact".equals(layoutPreset) ? 1 : ("large".equals(layoutPreset) ? 2 : 0));
        injectionModeSpinner.setSelection(getInjectionModeSelection(config.optString("injectionMode", "aggressive")));
        floatingLogsSwitch.setChecked(config.optBoolean("enableFloatingLogs", false));
        verboseLogsSwitch.setChecked(config.optBoolean("enableVerboseLogs", false));
        networkPatchSwitch.setChecked(config.optBoolean("enableNetworkHeaderPatch", true));
        historyPatchSwitch.setChecked(config.optBoolean("enableHistoryPatch", true));
        storagePatchSwitch.setChecked(config.optBoolean("enableStoragePatch", true));
        uiReadyObserverSwitch.setChecked(config.optBoolean("enableUiReadyObserver", true));
        actionObserverSwitch.setChecked(config.optBoolean("enableActionObserver", true));
        runtimeReuseSwitch.setChecked(config.optBoolean("enableRuntimeReuse", true));

        root.addView(createSectionLabel(activity, "服务地址"));
        root.addView(serverInput);
        root.addView(createSectionLabel(activity, "更新地址"));
        root.addView(updateInput);
        root.addView(createSectionLabel(activity, "纸张类型"));
        root.addView(paperSpinner);
        root.addView(createSectionLabel(activity, "排版预设"));
        root.addView(layoutSpinner);
        root.addView(advancedToggle);
        root.addView(advancedBox);
        // 注入时机与性能开关收进高级区，保存值与展开无关
        advancedBox.addView(createSectionLabel(activity, "注入时机"));
        advancedBox.addView(injectionModeSpinner);
        advancedBox.addView(createSectionLabel(activity, "性能开关"));

        TextView note = new TextView(activity);
        note.setText("保存后写入 Android 本地并重启加载。高级选项仅排障用，日常保持默认即可；改错会导致扫码打印失效。");
        note.setTextSize(13);
        note.setTextColor(Color.parseColor("#667085"));
        note.setPadding(0, dp(activity, 14), 0, 0);
        root.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("原生配置")
            .setView(wrapInDialogScrollView(activity, root))
            .setPositiveButton("保存并重启", null)
            .setNegativeButton("关闭", null)
            .setNeutralButton("恢复默认", null)
            .create();
        dialog.setOnShowListener(ignored -> {
            // 恢复默认：只把输入区填回默认值，不关闭弹窗、不立即生效，误改地址后一键还原
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                serverInput.setText(defaultServerBase);
                updateInput.setText(defaultUpdateBase);
                paperSpinner.setSelection(0);
                layoutSpinner.setSelection(0);
                injectionModeSpinner.setSelection(0);
                // 日志默认关：恢复默认 = 回到生产态零日志
                floatingLogsSwitch.setChecked(false);
                verboseLogsSwitch.setChecked(false);
                networkPatchSwitch.setChecked(true);
                historyPatchSwitch.setChecked(true);
                storagePatchSwitch.setChecked(true);
                uiReadyObserverSwitch.setChecked(true);
                actionObserverSwitch.setChecked(true);
                runtimeReuseSwitch.setChecked(true);
                callbacks.toast("已恢复默认值，点「保存并重启」生效");
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String serverBase = callbacks.normalizeBaseUrl(serverInput.getText().toString(), defaultServerBase);
            String updateBase = callbacks.normalizeBaseUrl(updateInput.getText().toString(), defaultUpdateBase);
            if (serverBase.isEmpty()) {
                callbacks.toast("请输入服务地址");
                return;
            }
            if (updateBase.isEmpty()) {
                callbacks.toast("请输入更新地址");
                return;
            }
            SettingsValues values = new SettingsValues(
                serverBase,
                updateBase,
                paperSpinner.getSelectedItemPosition() == 1 ? "black_mark" : "thermal",
                layoutSpinner.getSelectedItemPosition() == 1 ? "compact" : (layoutSpinner.getSelectedItemPosition() == 2 ? "large" : "standard"),
                getInjectionModeValue(injectionModeSpinner.getSelectedItemPosition()),
                floatingLogsSwitch.isChecked(),
                verboseLogsSwitch.isChecked(),
                networkPatchSwitch.isChecked(),
                historyPatchSwitch.isChecked(),
                storagePatchSwitch.isChecked(),
                uiReadyObserverSwitch.isChecked(),
                actionObserverSwitch.isChecked(),
                runtimeReuseSwitch.isChecked()
            );
            callbacks.onSave(values);
            dialog.dismiss();
        });
        });
        dialog.show();
    }

    private static TextView createSectionLabel(Activity activity, String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(14);
        label.setTextColor(Color.parseColor("#344054"));
        label.setPadding(0, dp(activity, 12), 0, dp(activity, 6));
        return label;
    }

    private static EditText createUrlInput(Activity activity, String value) {
        EditText input = new EditText(activity);
        input.setText(value);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
        return input;
    }

    private static Spinner createSpinner(Activity activity, String[] items) {
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private static ScrollView wrapInDialogScrollView(Activity activity, View content) {
        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(true);
        scrollView.setScrollbarFadingEnabled(false);
        scrollView.addView(content, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT
        ));
        return scrollView;
    }

    private static SwitchCompat createSwitchRow(Activity activity, LinearLayout root, String title, String summary) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(activity, 6), 0, dp(activity, 6));

        LinearLayout textWrap = new LinearLayout(activity);
        textWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textWrap.setLayoutParams(textParams);

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextSize(14);
        titleView.setTextColor(Color.parseColor("#101828"));

        TextView summaryView = new TextView(activity);
        summaryView.setText(summary);
        summaryView.setTextSize(12);
        summaryView.setTextColor(Color.parseColor("#667085"));
        summaryView.setPadding(0, dp(activity, 4), dp(activity, 12), 0);

        textWrap.addView(titleView);
        textWrap.addView(summaryView);

        SwitchCompat toggle = new SwitchCompat(activity);
        toggle.setChecked(true);

        row.addView(textWrap);
        row.addView(toggle);
        root.addView(row);
        return toggle;
    }

    private static int getInjectionModeSelection(String mode) {
        String normalized = safe(mode).trim().toLowerCase(java.util.Locale.ROOT);
        if ("commit_loaded".equals(normalized)) {
            return 1;
        }
        if ("loaded_only".equals(normalized)) {
            return 2;
        }
        if ("manual".equals(normalized)) {
            return 3;
        }
        return 0;
    }

    private static String getInjectionModeValue(int selection) {
        if (selection == 1) {
            return "commit_loaded";
        }
        if (selection == 2) {
            return "loaded_only";
        }
        if (selection == 3) {
            return "manual";
        }
        return "aggressive";
    }

    private static int dp(Activity activity, int value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
