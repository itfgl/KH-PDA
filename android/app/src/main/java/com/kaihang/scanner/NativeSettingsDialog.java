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
        // 高级选项（仅排障用）：默认收起。注入时机已固定激进模式，不再提供切换
        TextView advancedToggle = createSectionLabel(activity, "高级选项（仅排障用）▸");
        LinearLayout advancedBox = new LinearLayout(activity);
        advancedBox.setOrientation(LinearLayout.VERTICAL);
        advancedBox.setVisibility(android.view.View.GONE);
        advancedToggle.setOnClickListener(v -> {
            boolean expanded = advancedBox.getVisibility() == android.view.View.VISIBLE;
            advancedBox.setVisibility(expanded ? android.view.View.GONE : android.view.View.VISIBLE);
            advancedToggle.setText(expanded ? "高级选项（仅排障用）▸" : "高级选项（仅排障用）▾");
        });
        SwitchCompat floatingLogsSwitch = createSwitchRow(activity, advancedBox, "网页浮动日志（调试）", "默认关。排障时打开，可查看网页侧运行日志并持久化");
        SwitchCompat verboseLogsSwitch = createSwitchRow(activity, advancedBox, "详细运行日志（调试）", "默认关。排障时打开，记录动作匹配、observer、控制台等过程日志");
        SwitchCompat networkPatchSwitch = createSwitchRow(activity, advancedBox, "自动补 X-Client-Type", "默认开。给 App 内网页请求补客户端标识头，服务端按此识别 PDA 客户端");
        SwitchCompat historyPatchSwitch = createSwitchRow(activity, advancedBox, "路由监听", "默认开。切页后自动刷新扫码/打印动作，请勿关闭");
        SwitchCompat storagePatchSwitch = createSwitchRow(activity, advancedBox, "存储监听", "默认开。登录态变化后自动刷新页面动作，请勿关闭");
        SwitchCompat uiReadyObserverSwitch = createSwitchRow(activity, advancedBox, "页面就绪 observer", "默认开。页面就绪判定的兜底检测，请勿关闭");
        SwitchCompat actionObserverSwitch = createSwitchRow(activity, advancedBox, "页面动作 observer", "默认开。条件显示按钮等场景依赖，请勿关闭");
        SwitchCompat runtimeReuseSwitch = createSwitchRow(activity, advancedBox, "同页复用 runtime", "默认开。避免重复注入大段脚本，性能优化，请勿关闭");
        paperSpinner.setSelection("black_mark".equals(config.optString("paperType", "thermal")) ? 1 : 0);
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
        root.addView(advancedToggle);
        root.addView(advancedBox);
        createDetailToggle(activity, advancedBox);

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
        dialog.setOnDismissListener(d -> detailModeSummaries.clear());
        dialog.setOnShowListener(ignored -> {
            // 恢复默认：只把输入区填回默认值，不关闭弹窗、不立即生效，误改地址后一键还原
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                serverInput.setText(defaultServerBase);
                updateInput.setText(defaultUpdateBase);
                paperSpinner.setSelection(0);
                // 日志等调试项默认关，功能项默认开
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
                "aggressive",
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
            detailModeSummaries.clear();
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

    /** 开关行：说明文字默认隐藏，由 detailMode 列表统一控制显隐（小眼睛切换） */
    private static final java.util.List<TextView> detailModeSummaries = new java.util.ArrayList<>();

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
        summaryView.setVisibility(android.view.View.GONE);
        detailModeSummaries.add(summaryView);

        textWrap.addView(titleView);
        textWrap.addView(summaryView);

        SwitchCompat toggle = new SwitchCompat(activity);
        toggle.setChecked(true);

        row.addView(textWrap);
        row.addView(toggle);
        root.addView(row);
        return toggle;
    }

    /** 小眼睛开关：整组切换所有开关行的详细说明显隐，默认隐藏 */
    private static android.widget.ImageButton createDetailToggle(Activity activity, LinearLayout advancedBox) {
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = createSectionLabel(activity, "调试与功能开关");
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(labelParams);

        android.widget.ImageButton eye = new android.widget.ImageButton(activity);
        eye.setImageResource(android.R.drawable.ic_menu_view);
        eye.setBackground(null);
        eye.setColorFilter(Color.parseColor("#667085"));
        eye.setContentDescription("显示/隐藏详细说明");
        LinearLayout.LayoutParams eyeParams = new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36));
        eye.setLayoutParams(eyeParams);

        head.addView(label);
        head.addView(eye);
        advancedBox.addView(head);

        eye.setOnClickListener(v -> {
            boolean show = detailModeSummaries.isEmpty() || detailModeSummaries.get(0).getVisibility() != android.view.View.VISIBLE;
            for (TextView summary : detailModeSummaries) {
                summary.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
            }
            eye.setColorFilter(show ? Color.parseColor("#1570EF") : Color.parseColor("#667085"));
        });
        return eye;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
