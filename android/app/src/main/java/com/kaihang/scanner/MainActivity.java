package com.kaihang.scanner;

import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.os.Message;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;
import com.kaihang.scanner.plugins.ClientConfigPlugin;
import com.kaihang.scanner.plugins.KaihangNfcPlugin;
import com.kaihang.scanner.plugins.PrintPlugin;
import com.kaihang.scanner.plugins.ScanPlugin;
import com.kaihang.scanner.plugins.UpdatePlugin;

public class MainActivity extends BridgeActivity {
    private static final String NOCOBASE_STORAGE_PREFIX = "NOCOBASE_";
    private static final String DEFAULT_STORAGE_APP_NAME = "main";
    private static final String DEFAULT_PAGE_ACTIONS_API_PATH = "/api/scanner_page_binding_actions:list?pageSize=200";
    private static final String DEFAULT_SERVER_BASE = "http://115.29.178.34:2974";
    private static final String DEFAULT_UPDATE_BASE = "http://115.29.178.34:2973";
    private static final long SCAN_RELEASE_TIMEOUT_MS = 8000L;
    private android.widget.ImageButton nativeControlButton;
    private android.widget.Button nativeScanButton;
    private android.view.View nativeStatusDot;
    private final java.util.List<String> nativeLogLines = new java.util.ArrayList<>();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingScanRelease;
    private boolean nativeScanActive = false;
    private String nativePageReadyState = "loading";
    private String lastInjectedUrl = "";
    private long lastInjectAtMs = 0L;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(ScanPlugin.class);
        registerPlugin(PrintPlugin.class);
        registerPlugin(KaihangNfcPlugin.class);
        registerPlugin(UpdatePlugin.class);
        registerPlugin(ClientConfigPlugin.class);
        super.onCreate(savedInstanceState);
        appendNativeLog("应用启动: version=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + "), buildTime=" + BuildConfig.BUILD_TIME);
        PrintPlugin.setNativeEventSink(new PrintPlugin.PrintEventSink() {
            @Override
            public void onConnection(String connection) {
                appendNativeLog("打印连接状态: " + connection);
                emitPrintStatusToPage(connection, null, true);
            }

            @Override
            public void onStatus(String status, String flag) {
                appendNativeLog("打印状态: " + status + (flag == null || flag.isEmpty() ? "" : (" flag=" + flag)));
                emitPrintStatusToPage(status, flag, false);
            }
        });
        mainHandler.postDelayed(() -> {
            appendNativeLog("预热原生打印连接");
            PrintPlugin.connectNative(MainActivity.this);
        }, 300L);

        // 全局崩溃拦截：将异常信息转发到 JS 日志
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            android.util.Log.e("KaihangCrash", "Uncaught exception on " + thread.getName(), throwable);
            try {
                // 取前 400 字符避免 JS 字符串过长
                String msg = throwable.toString().replace("'", "\\'").replace("\n", " ");
                if (msg.length() > 400) msg = msg.substring(0, 400) + "…";
                final String script = "window.log && window.log('CRASH: " + msg + "', 'err')";
                runOnUiThread(() -> {
                    if (bridge != null) bridge.getWebView().evaluateJavascript(script, null);
                });
            } catch (Exception ignored) {}
        });
    }

    @Override
    protected void load() {
        super.load();

        WebView webView = bridge.getWebView();
        configureInAppNavigation(webView);
        attachNativeWebBridge(webView);
        ensureNativeControlButton();

        String launchUrl = buildLaunchUrl(ClientConfigPlugin.getSavedServerBase(this, DEFAULT_SERVER_BASE));
        webView.post(() -> {
            String current = webView.getUrl();
            if (current == null || current.startsWith("http://localhost")) {
                webView.loadUrl(launchUrl);
            }
        });

        // 所有 fetch/XHR 请求自动附加 X-Client-Type: capacitor 头，便于服务端区分客户端类型
        bridge.addWebViewListener(new WebViewListener() {
            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                injectClientTypeHeader(view);
            }
            @Override
            public void onPageLoaded(WebView view) {
                super.onPageLoaded(view);
                injectClientTypeHeader(view);
            }
        });
    }

    private void configureInAppNavigation(WebView webView) {
        webView.getSettings().setSupportMultipleWindows(false);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(view, request != null && request.getUrl() != null ? request.getUrl().toString() : null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(view, url);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                String url = null;
                WebView.HitTestResult result = view.getHitTestResult();
                if (result != null) {
                    url = result.getExtra();
                }
                if (handleNavigation(view, url)) {
                    return false;
                }
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(view);
                resultMsg.sendToTarget();
                return true;
            }
        });
    }

    @Override
    public void onPause() {
        try {
            PrintPlugin.closeNative(this);
            appendNativeLog("页面暂停，关闭原生打印连接");
        } catch (Exception e) {
            appendNativeLog("页面暂停关闭打印连接失败: " + e.getMessage());
        }
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        mainHandler.postDelayed(() -> {
            appendNativeLog("页面恢复，检查打印连接");
            PrintPlugin.connectNative(MainActivity.this);
        }, 200L);
    }

    @Override
    public void onDestroy() {
        try {
            stopNativeScan();
        } catch (Exception ignored) {}
        try {
            PrintPlugin.closeNative(this);
        } catch (Exception e) {
            appendNativeLog("关闭原生打印机失败: " + e.getMessage());
        }
        PrintPlugin.setNativeEventSink(null);
        super.onDestroy();
    }

    private boolean handleNavigation(WebView view, String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception ignored) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            view.loadUrl(url);
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            return true;
        } catch (Exception ignored) {
            return true;
        }
    }

    private String buildLaunchUrl(String serverBase) {
        String base = safe(serverBase).trim();
        if (base.isEmpty()) {
            base = DEFAULT_SERVER_BASE;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    // App 已在前台时收到 NFC Intent，转发给 Capacitor Bridge（@capgo/capacitor-nfc 依赖此回调）
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bridge.onNewIntent(intent);
    }

    private void injectClientTypeHeader(WebView view) {
        String url = safe(view.getUrl());
        long now = System.currentTimeMillis();
        boolean sameUrlRecently = url.equals(lastInjectedUrl) && (now - lastInjectAtMs) < 1200L;
        if (sameUrlRecently) {
            return;
        }
        setNativePageReadyState("loading", url);
        lastInjectedUrl = url;
        lastInjectAtMs = now;
        String script = buildClientRuntimeScript(url);
        view.evaluateJavascript(script, null);
        view.postDelayed(() -> {
            String latestUrl = safe(view.getUrl());
            if (latestUrl.equals(url)) {
                view.evaluateJavascript(buildClientRuntimeScript(latestUrl), null);
            }
        }, 350);
    }

    private void ensureNativeControlButton() {
        if (nativeControlButton != null) {
            return;
        }
        android.view.ViewGroup root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams containerParams = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        );
        container.setLayoutParams(containerParams);
        container.setClickable(false);
        container.setFocusable(false);

        nativeControlButton = new android.widget.ImageButton(this);
        nativeControlButton.setImageResource(android.R.drawable.ic_menu_manage);
        nativeControlButton.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        nativeControlButton.setBackground(buildNativeFabBackground());
        nativeControlButton.setColorFilter(android.graphics.Color.WHITE);
        nativeControlButton.setContentDescription("客户端工具");
        int size = dp(56);
        android.widget.FrameLayout.LayoutParams fabParams = new android.widget.FrameLayout.LayoutParams(size, size);
        fabParams.gravity = android.view.Gravity.END | android.view.Gravity.BOTTOM;
        fabParams.setMargins(dp(16), dp(16), dp(18), dp(24));
        nativeControlButton.setLayoutParams(fabParams);
        nativeControlButton.setElevation(dp(10));
        nativeControlButton.setOnClickListener(v -> showNativeControlMenu(v));

        nativeStatusDot = new android.view.View(this);
        android.widget.FrameLayout.LayoutParams dotParams = new android.widget.FrameLayout.LayoutParams(dp(12), dp(12));
        dotParams.gravity = android.view.Gravity.END | android.view.Gravity.BOTTOM;
        dotParams.setMargins(dp(16), dp(16), dp(20), dp(70));
        nativeStatusDot.setLayoutParams(dotParams);
        nativeStatusDot.setElevation(dp(12));
        nativeStatusDot.setBackground(buildStatusDotBackground("#98A2B3"));

        nativeScanButton = new android.widget.Button(this);
        nativeScanButton.setText("扫码");
        nativeScanButton.setTextSize(14);
        nativeScanButton.setTextColor(android.graphics.Color.WHITE);
        nativeScanButton.setAllCaps(false);
        nativeScanButton.setBackground(buildNativeCapsuleBackground(false));
        nativeScanButton.setVisibility(android.view.View.GONE);
        nativeScanButton.setElevation(dp(8));
        android.widget.FrameLayout.LayoutParams scanParams = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            dp(44)
        );
        scanParams.gravity = android.view.Gravity.END | android.view.Gravity.BOTTOM;
        scanParams.setMargins(dp(16), dp(16), dp(18), dp(92));
        nativeScanButton.setLayoutParams(scanParams);
        nativeScanButton.setPadding(dp(18), 0, dp(18), 0);
        nativeScanButton.setOnClickListener(v -> {
            if (nativeScanActive) {
                appendNativeLog("点击原生扫码按钮: 停扫");
                stopNativeScan();
            } else {
                appendNativeLog("点击原生扫码按钮: 扫码");
                triggerNativeScan();
            }
        });

        container.addView(nativeStatusDot);
        container.addView(nativeScanButton);
        container.addView(nativeControlButton);
        root.addView(container);
        container.bringToFront();
        nativeStatusDot.bringToFront();
        nativeScanButton.bringToFront();
        nativeControlButton.bringToFront();
        updateNativeStatusDot();
    }

    private android.graphics.drawable.Drawable buildNativeFabBackground() {
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        background.setColor(android.graphics.Color.parseColor("#111827"));
        return background;
    }

    private android.graphics.drawable.Drawable buildStatusDotBackground(String color) {
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        background.setColor(android.graphics.Color.parseColor(color));
        background.setStroke(dp(2), android.graphics.Color.WHITE);
        return background;
    }

    private android.graphics.drawable.Drawable buildNativeCapsuleBackground(boolean active) {
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        background.setCornerRadius(dp(22));
        background.setColor(android.graphics.Color.parseColor(active ? "#B42318" : "#1570EF"));
        return background;
    }

    private void updateNativeStatusDot() {
        if (nativeStatusDot == null) {
            return;
        }
        String color = "#98A2B3";
        String description = "页面初始化中";
        if ("ready".equals(nativePageReadyState)) {
            color = "#12B76A";
            description = "页面已就绪";
        } else if ("error".equals(nativePageReadyState)) {
            color = "#F04438";
            description = "页面初始化异常";
        }
        nativeStatusDot.setBackground(buildStatusDotBackground(color));
        nativeStatusDot.setContentDescription(description);
    }

    private void setNativePageReadyState(String state, String detail) {
        String normalized = safe(state).trim().toLowerCase(java.util.Locale.ROOT);
        if (!"ready".equals(normalized) && !"error".equals(normalized)) {
            normalized = "loading";
        }
        boolean changed = !normalized.equals(nativePageReadyState);
        nativePageReadyState = normalized;
        updateNativeStatusDot();
        if (changed) {
            appendNativeLog("页面状态: " + normalized + (safe(detail).isEmpty() ? "" : (" - " + detail)));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showNativeControlMenu(android.view.View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "扫码");
        menu.getMenu().add(0, 2, 1, "设置");
        menu.getMenu().add(0, 3, 2, "检查更新");
        menu.getMenu().add(0, 4, 3, "日志");
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                appendNativeLog("触发原生菜单: 扫码");
                triggerNativeScan();
                return true;
            }
            if (id == 2) {
                appendNativeLog("打开原生设置");
                showNativeSettingsDialog();
                return true;
            }
            if (id == 3) {
                appendNativeLog("触发原生更新检查");
                showNativeUpdateDialog();
                return true;
            }
            if (id == 4) {
                appendNativeLog("打开网页日志");
                runClientRuntimeCommand("window.__khClientRuntime&&window.__khClientRuntime.toggleFloatingLog&&window.__khClientRuntime.toggleFloatingLog(true);");
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void runClientRuntimeCommand(String command) {
        if (bridge == null || bridge.getWebView() == null) {
            return;
        }
        WebView webView = bridge.getWebView();
        webView.post(() -> webView.evaluateJavascript(
            "(function(){return !!(window.__khClientRuntime&&window.__khClientRuntime.toggleFloatingLog);})();",
            value -> {
                boolean runtimeReady = "true".equalsIgnoreCase(safe(value).replace("\"", "").trim());
                if (runtimeReady) {
                    webView.evaluateJavascript(command, null);
                    return;
                }
                injectClientTypeHeader(webView);
                webView.postDelayed(() -> webView.evaluateJavascript(command, null), 180);
            }
        ));
    }

    private void emitPrintStatusToPage(String value, String flag, boolean isConnection) {
        if (bridge == null || bridge.getWebView() == null) {
            return;
        }
        String script;
        if (isConnection) {
            script = "(function(){window.dispatchEvent(new CustomEvent('kh:printStatus',{detail:{connection:" + js(value) + "}}));})();";
        } else {
            script = "(function(){window.dispatchEvent(new CustomEvent('kh:printStatus',{detail:{status:" + js(value) + ",flag:" + js(flag == null ? "" : flag) + "}}));})();";
        }
        bridge.getWebView().post(() -> bridge.getWebView().evaluateJavascript(script, null));
    }

    private void attachNativeWebBridge(WebView webView) {
        webView.addJavascriptInterface(new NativeWebBridge(), "KaihangNativeBridge");
    }

    private void triggerNativeScan() {
        try {
            if (pendingScanRelease != null) {
                mainHandler.removeCallbacks(pendingScanRelease);
            }
            ScanPlugin.triggerStartScan(this);
            setNativeScanActive(true);
            appendNativeLog("已发送原生扫码广播");
            pendingScanRelease = () -> {
                try {
                    ScanPlugin.triggerStopScan(this);
                    setNativeScanActive(false);
                    appendNativeLog("扫码超时自动释放");
                } catch (Exception e) {
                    appendNativeLog("扫码超时释放失败: " + e.getMessage());
                }
            };
            mainHandler.postDelayed(pendingScanRelease, SCAN_RELEASE_TIMEOUT_MS);
            toast("已触发扫码");
        } catch (Exception e) {
            appendNativeLog("触发扫码失败: " + e.getMessage());
            toast("触发扫码失败: " + e.getMessage());
        }
    }

    private void stopNativeScan() {
        try {
            if (pendingScanRelease != null) {
                mainHandler.removeCallbacks(pendingScanRelease);
                pendingScanRelease = null;
            }
            ScanPlugin.triggerStopScan(this);
            setNativeScanActive(false);
            appendNativeLog("已发送停止扫码广播");
        } catch (Exception e) {
            appendNativeLog("停止扫码失败: " + e.getMessage());
        }
    }

    private void setNativeScanActionVisible(boolean visible) {
        if (nativeScanButton == null) {
            return;
        }
        nativeScanButton.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!visible) {
            setNativeScanActive(false);
        }
    }

    private void setNativeScanActive(boolean active) {
        nativeScanActive = active;
        if (nativeScanButton == null) {
            return;
        }
        nativeScanButton.setText(active ? "停扫" : "扫码");
        nativeScanButton.setBackground(buildNativeCapsuleBackground(active));
    }

    private final class NativeWebBridge {
        @JavascriptInterface
        public boolean startScan() {
            runOnUiThread(() -> triggerNativeScan());
            return true;
        }

        @JavascriptInterface
        public boolean stopScan() {
            runOnUiThread(() -> stopNativeScan());
            return true;
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(() -> showNativeSettingsDialog());
        }

        @JavascriptInterface
        public void checkUpdate() {
            runOnUiThread(() -> showNativeUpdateDialog());
        }

        @JavascriptInterface
        public void showLogs() {
            runOnUiThread(() -> showNativeLogDialog());
        }

        @JavascriptInterface
        public void setScanActionEnabled(boolean enabled) {
            runOnUiThread(() -> setNativeScanActionVisible(enabled));
        }

        @JavascriptInterface
        public void reportPageReadyState(String state, String detail) {
            runOnUiThread(() -> setNativePageReadyState(state, detail));
        }

        @JavascriptInterface
        public void onScanCompleted() {
            runOnUiThread(() -> {
                if (pendingScanRelease != null) {
                    mainHandler.removeCallbacks(pendingScanRelease);
                    pendingScanRelease = null;
                }
                setNativeScanActive(false);
                appendNativeLog("扫码结果已返回，自动释放扫码状态");
            });
        }

        @JavascriptInterface
        public boolean connectPrinter() {
            runOnUiThread(() -> PrintPlugin.connectNative(MainActivity.this));
            return true;
        }

        @JavascriptInterface
        public boolean prepareToPrintLabel() {
            runOnUiThread(PrintPlugin::prepareToPrintLabelNative);
            return true;
        }

        @JavascriptInterface
        public boolean printLabel(String payloadJson) {
            try {
                org.json.JSONObject payload = new org.json.JSONObject(payloadJson == null ? "{}" : payloadJson);
                String barcodeValue = payload.optString("barcodeValue", "");
                String qrCodeValue = payload.optString("qrCodeValue", "");
                String textValue = payload.optString("textValue", "");
                String paperType = payload.optString("paperType", "thermal");
                String layoutPreset = payload.optString("layoutPreset", "standard");
                runOnUiThread(() -> PrintPlugin.printLabelNative(
                    MainActivity.this,
                    MainActivity.this,
                    barcodeValue,
                    qrCodeValue,
                    textValue,
                    paperType,
                    layoutPreset
                ));
                return true;
            } catch (Exception e) {
                appendNativeLog("原生打印桥参数解析失败: " + e.getMessage());
                return false;
            }
        }
    }

    private void appendNativeLog(String message) {
        String line = "[" + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()) + "] " + message;
        nativeLogLines.add(line);
        if (nativeLogLines.size() > 200) {
            nativeLogLines.remove(0);
        }
    }

    private void showNativeSettingsDialog() {
        com.getcapacitor.JSObject config = ClientConfigPlugin.getSavedConfig(this);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(4));

        android.widget.EditText serverInput = createUrlInput(config.optString("serverBase", DEFAULT_SERVER_BASE));
        android.widget.EditText updateInput = createUrlInput(config.optString("updateBase", DEFAULT_UPDATE_BASE));
        android.widget.Spinner paperSpinner = createSpinner(new String[]{"普通热敏纸", "黑标标签纸"});
        android.widget.Spinner layoutSpinner = createSpinner(new String[]{"标准排版", "紧凑排版", "大字排版"});
        paperSpinner.setSelection("black_mark".equals(config.optString("paperType", "thermal")) ? 1 : 0);
        String layoutPreset = config.optString("layoutPreset", "standard");
        layoutSpinner.setSelection("compact".equals(layoutPreset) ? 1 : ("large".equals(layoutPreset) ? 2 : 0));

        root.addView(createSectionLabel("服务地址"));
        root.addView(serverInput);
        root.addView(createSectionLabel("更新地址"));
        root.addView(updateInput);
        root.addView(createSectionLabel("纸张类型"));
        root.addView(paperSpinner);
        root.addView(createSectionLabel("排版预设"));
        root.addView(layoutSpinner);

        android.widget.TextView note = new android.widget.TextView(this);
        note.setText("保存后会写入 Android 本地配置，并重启后直接加载新的远程地址。");
        note.setTextSize(13);
        note.setTextColor(android.graphics.Color.parseColor("#667085"));
        note.setPadding(0, dp(14), 0, 0);
        root.addView(note);

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("客户端设置")
            .setView(root)
            .setPositiveButton("保存并重启", null)
            .setNegativeButton("关闭", null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String serverBase = normalizeBaseUrl(serverInput.getText().toString(), DEFAULT_SERVER_BASE);
            String updateBase = normalizeBaseUrl(updateInput.getText().toString(), DEFAULT_UPDATE_BASE);
            if (serverBase.isEmpty()) {
                toast("请输入服务地址");
                return;
            }
            if (updateBase.isEmpty()) {
                toast("请输入更新地址");
                return;
            }
            String paperType = paperSpinner.getSelectedItemPosition() == 1 ? "black_mark" : "thermal";
            String layout = layoutSpinner.getSelectedItemPosition() == 1 ? "compact" : (layoutSpinner.getSelectedItemPosition() == 2 ? "large" : "standard");
            ClientConfigPlugin.saveConfig(this, serverBase, updateBase, paperType, layout);
            appendNativeLog("已保存原生设置: serverBase=" + serverBase + ", updateBase=" + updateBase + ", paperType=" + paperType + ", layout=" + layout);
            toast("配置已保存，应用即将重启");
            dialog.dismiss();
            nativeControlButton.postDelayed(() -> ClientConfigPlugin.restartApp(this), 300);
        }));
        dialog.show();
    }

    private android.widget.TextView createSectionLabel(String text) {
        android.widget.TextView label = new android.widget.TextView(this);
        label.setText(text);
        label.setTextSize(14);
        label.setTextColor(android.graphics.Color.parseColor("#344054"));
        label.setPadding(0, dp(12), 0, dp(6));
        return label;
    }

    private android.widget.EditText createUrlInput(String value) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(value);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        return input;
    }

    private android.widget.Spinner createSpinner(String[] items) {
        android.widget.Spinner spinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private void showNativeUpdateDialog() {
        androidx.appcompat.app.AlertDialog progressDialog = new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("检查更新")
            .setMessage("正在检查更新...")
            .setCancelable(false)
            .create();
        progressDialog.show();
        new Thread(() -> {
            try {
                com.getcapacitor.JSObject config = ClientConfigPlugin.getSavedConfig(this);
                String updateBase = normalizeBaseUrl(config.optString("updateBase", DEFAULT_UPDATE_BASE), DEFAULT_UPDATE_BASE);
                java.net.URL requestUrl = new java.net.URL(updateBase + "/api/app/version");
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) requestUrl.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("X-Client-Type", "capacitor");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new java.io.IOException("HTTP " + status);
                }
                String body;
                try (java.io.InputStream inputStream = connection.getInputStream();
                     java.util.Scanner scanner = new java.util.Scanner(inputStream, "UTF-8").useDelimiter("\\A")) {
                    body = scanner.hasNext() ? scanner.next() : "{}";
                }
                org.json.JSONObject serverInfo = new org.json.JSONObject(body);
                long localVersionCode = getLocalVersionCode();
                String localVersionName = getLocalVersionName();
                long remoteVersionCode = serverInfo.optLong("versionCode", 0);
                String remoteVersionName = serverInfo.optString("versionName", "");
                String changelog = serverInfo.optString("changelog", "");
                String apkUrl = resolveAbsoluteUrl(updateBase, serverInfo.optString("apkUrl", ""));
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (remoteVersionCode > localVersionCode && !apkUrl.isEmpty()) {
                        StringBuilder message = new StringBuilder();
                        message.append("发现新版本 ").append(remoteVersionName).append(" (").append(remoteVersionCode).append(")\n");
                        message.append("当前版本 ").append(localVersionName).append(" (").append(localVersionCode).append(")");
                        if (!changelog.isEmpty()) {
                            message.append("\n\n更新说明:\n").append(changelog);
                        }
                        new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("发现新版本")
                            .setMessage(message.toString())
                            .setPositiveButton("下载更新", (dialog, which) -> {
                                appendNativeLog("开始下载更新: " + apkUrl);
                                downloadApkWithSystemManager(apkUrl);
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    } else {
                        appendNativeLog("当前已是最新版本: " + localVersionName + " (" + localVersionCode + ")");
                        new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("检查更新")
                            .setMessage("当前已是最新版本\n版本: " + localVersionName + " (" + localVersionCode + ")")
                            .setPositiveButton("知道了", null)
                            .show();
                    }
                });
            } catch (Exception e) {
                appendNativeLog("检查更新失败: " + e.getMessage());
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("检查更新失败")
                        .setMessage(String.valueOf(e.getMessage()))
                        .setPositiveButton("知道了", null)
                        .show();
                });
            }
        }).start();
    }

    private void downloadApkWithSystemManager(String url) {
        try {
            android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(android.net.Uri.parse(url));
            request.setTitle("凯航扫码 更新");
            request.setDescription("正在下载新版本...");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "kaihang_update.apk");
            request.setAllowedNetworkTypes(android.app.DownloadManager.Request.NETWORK_WIFI | android.app.DownloadManager.Request.NETWORK_MOBILE);
            android.app.DownloadManager manager = (android.app.DownloadManager) getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                throw new IllegalStateException("DownloadManager unavailable");
            }
            manager.enqueue(request);
            toast("已开始下载更新，请查看系统通知");
        } catch (Exception e) {
            appendNativeLog("启动下载失败: " + e.getMessage());
            toast("启动下载失败: " + e.getMessage());
        }
    }

    private void showNativeLogDialog() {
        android.widget.TextView content = new android.widget.TextView(this);
        content.setTextSize(12);
        content.setTextColor(android.graphics.Color.parseColor("#101828"));
        content.setTypeface(android.graphics.Typeface.MONOSPACE);
        content.setPadding(dp(12), dp(12), dp(12), dp(12));
        content.setText("正在加载日志...");

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(content);

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("运行日志")
            .setView(scrollView)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空", null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            nativeLogLines.clear();
            content.setText("本地日志已清空");
        }));
        dialog.show();
        loadCombinedLogs(content);
    }

    private void loadCombinedLogs(android.widget.TextView targetView) {
        String nativeLogs = nativeLogLines.isEmpty() ? "(暂无原生日志)" : android.text.TextUtils.join("\n", nativeLogLines);
        if (bridge == null || bridge.getWebView() == null) {
            targetView.setText(nativeLogs);
            return;
        }
        String script = "(function(){try{var raw=(window.localStorage&&window.localStorage.getItem('KH_FLOATING_LOGS'))||'[]';window.__khLastLogRaw=raw;return raw;}catch(e){return JSON.stringify([{text:'读取网页日志失败: '+String(e&&e.message||e||'unknown'),type:'err'}]);}})();";
        bridge.getWebView().evaluateJavascript(script, value -> {
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
            runOnUiThread(() -> targetView.setText(text.toString()));
        });
    }

    private String decodeJsString(String value) {
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

    private String normalizeBaseUrl(String value, String fallback) {
        String raw = safe(value).trim();
        if (raw.isEmpty()) {
            raw = safe(fallback).trim();
        }
        return raw.replaceAll("/+$", "");
    }

    private String resolveAbsoluteUrl(String baseUrl, String path) {
        try {
            return new java.net.URL(new java.net.URL(baseUrl + "/"), path).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private long getLocalVersionCode() throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        }
        return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
    }

    private String getLocalVersionName() throws Exception {
        return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private String buildClientRuntimeScript(String currentUrl) {
        Uri uri = null;
        try {
            uri = Uri.parse(currentUrl == null ? "" : currentUrl);
        } catch (Exception ignored) {}

        String khToken = uri != null ? safe(uri.getQueryParameter("kh_token")) : "";
        String khAuth = uri != null ? safe(uri.getQueryParameter("kh_auth")) : "";
        String khRole = uri != null ? safe(uri.getQueryParameter("kh_role")) : "";
        String khApp = uri != null ? safe(uri.getQueryParameter("kh_app")) : DEFAULT_STORAGE_APP_NAME;
        String khPaper = uri != null ? safe(uri.getQueryParameter("kh_paper")) : "";
        String khLayout = uri != null ? safe(uri.getQueryParameter("kh_layout")) : "";
        String redirect = uri != null ? safe(uri.getQueryParameter("redirect")) : "";
        boolean shouldBootstrap = uri != null
            && !khToken.isEmpty()
            && !redirect.isEmpty();

        StringBuilder script = new StringBuilder();
        script.append("(function(){");
        script.append("var h='X-Client-Type',v='capacitor';");
        script.append("var kh=window.__khClientRuntime||(window.__khClientRuntime={});");
        script.append("kh.bootState=kh.bootState||'idle';kh.bootInstalled=!!kh.bootInstalled;kh.pageApplyState=kh.pageApplyState||'idle';kh.pageApplySignature=kh.pageApplySignature||'';kh.actionCatalogVersion=kh.actionCatalogVersion||0;");
        script.append("window.BUILD_TIME=").append(js(BuildConfig.BUILD_TIME)).append(";");
        script.append("window.APP_VERSION_NAME=").append(js(BuildConfig.VERSION_NAME)).append(";");
        script.append("window.APP_VERSION_CODE=").append(BuildConfig.VERSION_CODE).append(";");
        script.append("kh.pageActionsApi=").append(js(DEFAULT_PAGE_ACTIONS_API_PATH)).append(";");
        script.append("kh.defaultServerBase=").append(js(DEFAULT_SERVER_BASE)).append(";");
        script.append("kh.defaultUpdateBase=").append(js(DEFAULT_UPDATE_BASE)).append(";");
        script.append("kh.paperTypeStorageKey='NOCOBASE_PAPER_TYPE';");
        script.append("kh.layoutPresetStorageKey='NOCOBASE_LAYOUT_PRESET';");
        script.append("kh.logStorageKey='KH_FLOATING_LOGS';");
        script.append("kh.getNativeBridge=function(){return window.KaihangNativeBridge||null;};");
        script.append("kh.reportPageReadyState=function(state,detail){var bridge=kh.getNativeBridge();if(bridge&&bridge.reportPageReadyState){try{bridge.reportPageReadyState(String(state||'loading'),String(detail||''));}catch(e){}}};");
        script.append("kh.setPageApplyState=function(state,detail){kh.pageApplyState=String(state||'idle');kh.reportPageReadyState(kh.pageApplyState,detail||'');};");
        script.append("kh.ensureDeviceClient=function(){if(window.DeviceClient)return window.DeviceClient;var bridge=kh.getNativeBridge();window.DeviceClient={scan:function(){return bridge&&bridge.startScan?Promise.resolve(bridge.startScan()):kh.startGlobalScan();},stopScan:function(){return bridge&&bridge.stopScan?Promise.resolve(bridge.stopScan()):Promise.resolve(false);},openSettings:function(){if(bridge&&bridge.openSettings){bridge.openSettings();return Promise.resolve(true);}return Promise.resolve(false);},checkUpdate:function(){if(bridge&&bridge.checkUpdate){bridge.checkUpdate();return Promise.resolve(true);}return Promise.resolve(false);},showLogs:function(){if(bridge&&bridge.showLogs){bridge.showLogs();return Promise.resolve(true);}return Promise.resolve(false);}};return window.DeviceClient;};");
        script.append("kh.getClientConfigPlugin=function(){return window.ClientConfigPlugin||(window.Capacitor&&window.Capacitor.Plugins&&window.Capacitor.Plugins.ClientConfigPlugin)||null;};");
        script.append("kh.getUpdatePlugin=function(){return window.UpdatePlugin||(window.Capacitor&&window.Capacitor.Plugins&&window.Capacitor.Plugins.UpdatePlugin)||null;};");
        script.append("kh.normalizeBaseUrl=function(value,fallback){var raw=String(value||'').trim();if(!raw)raw=String(fallback||'').trim();return raw?raw.replace(/\\/+$/,''):'';};");
        script.append("kh.getCurrentHttpOrigin=function(){try{var url=new URL(window.location.href);if(/^https?:$/i.test(url.protocol))return url.origin;}catch(e){}return '';};");
        script.append("kh.normalizePaperTypeValue=function(value){return String(value||'').trim().toLowerCase()==='black_mark'?'black_mark':'thermal';};");
        script.append("kh.normalizeLayoutPresetValue=function(value){var raw=String(value||'').trim().toLowerCase();return ['compact','large'].indexOf(raw)>=0?raw:'standard';};");
        script.append("kh.applyClientConfig=function(config){config=config||{};var serverBase=kh.normalizeBaseUrl(config.serverBase,kh.getCurrentHttpOrigin()||kh.defaultServerBase);var updateBase=kh.normalizeBaseUrl(config.updateBase,kh.defaultUpdateBase);var paperType=kh.normalizePaperTypeValue(config.paperType);var layoutPreset=kh.normalizeLayoutPresetValue(config.layoutPreset);kh.clientConfig={serverBase:serverBase,updateBase:updateBase,paperType:paperType,layoutPreset:layoutPreset};return kh.clientConfig;};");
        script.append("kh.clientConfig=kh.applyClientConfig({serverBase:kh.getCurrentHttpOrigin()||kh.defaultServerBase,updateBase:kh.defaultUpdateBase,paperType:'thermal',layoutPreset:'standard'});");
        script.append("kh.readClientConfig=function(){var plugin=kh.getClientConfigPlugin();if(!plugin||!plugin.getConfig)return Promise.resolve(kh.clientConfig);return Promise.resolve(plugin.getConfig()).then(function(config){return kh.applyClientConfig(config);}).catch(function(err){kh.pushLog('读取客户端配置失败: '+String(err&&err.message||err||'unknown'),'warn');return kh.clientConfig;});};");
        script.append("kh.getServerBase=function(){return (kh.clientConfig&&kh.clientConfig.serverBase)||kh.defaultServerBase;};");
        script.append("kh.getUpdateBase=function(){return (kh.clientConfig&&kh.clientConfig.updateBase)||kh.getServerBase();};");
        script.append("kh.getPrintPaperType=function(){return (kh.clientConfig&&kh.clientConfig.paperType)||'thermal';};");
        script.append("kh.getPrintLayoutPreset=function(){return (kh.clientConfig&&kh.clientConfig.layoutPreset)||'standard';};");
        script.append("kh.appendFloatingLog=function(text,type){if(!kh._logBody)return;var line=document.createElement('div');line.className='kh-log-line kh-'+(type||'plain');line.textContent=text;kh._logBody.appendChild(line);kh._logBody.scrollTop=kh._logBody.scrollHeight;};");
        script.append("kh.clearFloatingLogs=function(){try{window.localStorage&&window.localStorage.removeItem(kh.logStorageKey);}catch(e){}if(kh._logBody)kh._logBody.innerHTML='';};");
        script.append("kh.ensureFloatingLogger=function(){if(document.getElementById('kh-log-overlay'))return;var mount=function(){if(document.getElementById('kh-log-overlay')||!document.body)return;if(!document.getElementById('kh-log-style')&&document.head){var style=document.createElement('style');style.id='kh-log-style';style.textContent='.kh-log-overlay{position:fixed;inset:0;z-index:2147483001;background:rgba(28,28,30,.32);display:none;align-items:flex-end;justify-content:stretch;padding:16px}.kh-log-panel{width:100%;max-width:520px;margin:0 auto;background:#fff;border-radius:16px;padding:14px;box-shadow:0 12px 32px rgba(0,0,0,.2)}.kh-log-head{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:10px}.kh-log-title{font-size:12px;font-weight:700;color:#8e8e93;letter-spacing:.4px;text-transform:uppercase}.kh-log-actions{display:flex;gap:6px}.kh-log-btn{border:none;background:#e5e5ea;color:#1c1c1e;border-radius:7px;padding:6px 10px;font-size:12px;font-weight:700}.kh-log-body{background:#1c1c1e;border-radius:10px;padding:10px;max-height:min(55vh,420px);overflow-y:auto}.kh-log-line{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;line-height:1.6;white-space:pre-wrap;word-break:break-all;border-bottom:1px solid #2c2c2e}.kh-log-line.kh-info{color:#64d2ff}.kh-log-line.kh-ok{color:#30d158}.kh-log-line.kh-err{color:#ff453a}.kh-log-line.kh-warn{color:#ffd60a}.kh-log-line.kh-plain{color:#ebebf5}';document.head.appendChild(style);}var overlay=document.createElement('div');overlay.id='kh-log-overlay';overlay.className='kh-log-overlay';overlay.innerHTML='<div class=\"kh-log-panel\"><div class=\"kh-log-head\"><span class=\"kh-log-title\">运行日志</span><div class=\"kh-log-actions\"><button type=\"button\" class=\"kh-log-btn\" id=\"kh-log-clear\">清空</button><button type=\"button\" class=\"kh-log-btn\" id=\"kh-log-close\">关闭</button></div></div><div class=\"kh-log-body\" id=\"kh-log-body\"></div></div>';document.body.appendChild(overlay);kh._logOverlay=overlay;kh._logBody=overlay.querySelector('#kh-log-body');overlay.addEventListener('click',function(evt){if(evt.target===overlay)overlay.style.display='none';});overlay.querySelector('#kh-log-close').addEventListener('click',function(){overlay.style.display='none';});overlay.querySelector('#kh-log-clear').addEventListener('click',function(){kh.clearFloatingLogs();});var saved=[];try{saved=JSON.parse((window.localStorage&&window.localStorage.getItem(kh.logStorageKey))||'[]');}catch(e){saved=[];}if(Array.isArray(saved)){saved.forEach(function(item){if(item&&typeof item==='object')kh.appendFloatingLog(item.text||'',item.type||'plain');else if(item)kh.appendFloatingLog(String(item),'plain');});}};if(document.body)mount();else window.addEventListener('DOMContentLoaded',mount,{once:true});};");
        script.append("kh.toggleFloatingLog=function(show){kh.ensureFloatingLogger();if(kh._logOverlay)kh._logOverlay.style.display=show?'flex':'none';};");
        script.append("kh.pushLog=function(msg,type){var text='['+new Date().toTimeString().slice(0,8)+'] '+String(msg||'');try{var saved=JSON.parse((window.localStorage&&window.localStorage.getItem(kh.logStorageKey))||'[]');if(!Array.isArray(saved))saved=[];saved.push({text:text,type:type||'plain'});if(saved.length>200)saved=saved.slice(saved.length-200);window.localStorage&&window.localStorage.setItem(kh.logStorageKey,JSON.stringify(saved));window.__khLastLogSnapshot=saved;}catch(e){}kh.ensureFloatingLogger();kh.appendFloatingLog(text,type||'plain');};");
        script.append("kh.showToast=function(message,type){if(!message)return;var mount=function(){if(!document.body)return;var host=document.getElementById('kh-toast-host');if(!host){if(!document.getElementById('kh-toast-style')&&document.head){var style=document.createElement('style');style.id='kh-toast-style';style.textContent='.kh-toast-host{position:fixed;left:50%;bottom:96px;transform:translateX(-50%);z-index:2147483003;display:flex;flex-direction:column;gap:8px;align-items:center;pointer-events:none}.kh-toast{max-width:min(92vw,420px);padding:11px 14px;border-radius:12px;background:rgba(17,24,39,.92);color:#fff;font-size:14px;line-height:1.5;box-shadow:0 12px 28px rgba(0,0,0,.22);opacity:0;transform:translateY(8px);transition:opacity .18s ease,transform .18s ease}.kh-toast.show{opacity:1;transform:translateY(0)}.kh-toast.info{background:rgba(29,78,216,.94)}.kh-toast.ok{background:rgba(22,101,52,.94)}.kh-toast.warn{background:rgba(146,64,14,.94)}.kh-toast.err{background:rgba(180,35,24,.94)}';document.head.appendChild(style);}host=document.createElement('div');host.id='kh-toast-host';host.className='kh-toast-host';document.body.appendChild(host);}var toast=document.createElement('div');toast.className='kh-toast '+(type||'info');toast.textContent=String(message);host.appendChild(toast);requestAnimationFrame(function(){toast.classList.add('show');});setTimeout(function(){toast.classList.remove('show');setTimeout(function(){toast.remove();},220);},2200);};if(document.body)mount();else window.addEventListener('DOMContentLoaded',mount,{once:true});};");
        script.append("kh.signalActionTriggered=function(message,type){kh.pushLog(message,type||'info');kh.showToast(message,type||'info');};");
        script.append("kh.getPaperTypeLabel=function(value){return kh.normalizePaperTypeValue(value)==='black_mark'?'黑标标签纸':'普通热敏纸';};");
        script.append("kh.getLayoutPresetLabel=function(value){var raw=kh.normalizeLayoutPresetValue(value);return ({standard:'标准排版',compact:'紧凑排版',large:'大字排版'})[raw]||'标准排版';};");
        script.append("kh.triggerAppUpdate=function(setStatus){var plugin=kh.getUpdatePlugin();if(!plugin||!plugin.getVersionInfo){setStatus&&setStatus('当前环境不支持原生更新检测','warn');return Promise.resolve(false);}setStatus&&setStatus('正在检查更新…','info');return Promise.resolve(plugin.getVersionInfo()).then(function(localInfo){return window.fetch(kh.getUpdateBase()+'/api/app/version',{headers:{'X-Client-Type':'capacitor'}}).then(function(res){if(!res.ok)throw new Error('无法获取最新版本信息');return res.json();}).then(function(serverInfo){if(Number(serverInfo.versionCode||0)>Number(localInfo.versionCode||0)){var note='发现新版本 '+String(serverInfo.versionName||'')+'，是否立即下载安装？';if(serverInfo.changelog)note+='\\n\\n更新说明:\\n'+serverInfo.changelog;if(!window.confirm(note)){setStatus&&setStatus('已取消更新','warn');return false;}return Promise.resolve(plugin.downloadAndInstallApk({url:new URL(serverInfo.apkUrl,kh.getUpdateBase()).href})).then(function(){setStatus&&setStatus('已开始下载更新，请等待系统安装提示','info');setTimeout(function(){plugin.exitApp&&plugin.exitApp();},1500);return true;});}setStatus&&setStatus('当前已是最新版本 (v'+String(localInfo.versionName||'')+')','ok');return false;});}).catch(function(err){setStatus&&setStatus('检查更新失败: '+String(err&&err.message||err||'unknown'),'err');return false;});};");
        script.append("kh.ensureUpdateButton=function(){};");
        script.append("kh.ensureSettingsPanel=function(){if(document.getElementById('kh-settings-overlay'))return;var mount=function(){if(document.getElementById('kh-settings-overlay')||!document.body)return;if(!document.getElementById('kh-settings-style')&&document.head){var style=document.createElement('style');style.id='kh-settings-style';style.textContent='.kh-settings-overlay{position:fixed;inset:0;z-index:2147483002;background:rgba(15,23,42,.42);display:none;align-items:center;justify-content:center;padding:18px}.kh-settings-panel{width:min(100%,390px);background:#fff;border-radius:18px;padding:16px;box-shadow:0 16px 40px rgba(15,23,42,.24)}.kh-settings-title{font-size:13px;font-weight:800;color:#667085;letter-spacing:.08em;text-transform:uppercase;margin-bottom:12px}.kh-settings-field{margin-bottom:12px}.kh-settings-field label{display:block;font-size:12px;font-weight:600;color:#667085;margin-bottom:6px}.kh-settings-field input,.kh-settings-field select{width:100%;padding:12px 13px;border:1px solid #d0d5dd;border-radius:12px;background:#fff;font-size:15px;outline:none}.kh-settings-field input:focus,.kh-settings-field select:focus{border-color:#1570ef;box-shadow:0 0 0 3px rgba(21,112,239,.12)}.kh-settings-note{font-size:12px;line-height:1.6;color:#667085;margin:8px 0 12px}.kh-settings-meta{font-size:12px;line-height:1.6;color:#344054;background:#f8fafc;border-radius:12px;padding:10px 12px;margin-bottom:10px}.kh-settings-status{display:none;border-radius:12px;padding:10px 12px;font-size:13px;line-height:1.5;background:#f2f4f7;color:#344054;margin-bottom:10px}.kh-settings-status.ok{display:block;background:#dcfae6;color:#166534}.kh-settings-status.err{display:block;background:#fee4e2;color:#b42318}.kh-settings-status.info{display:block;background:#dbeafe;color:#1d4ed8}.kh-settings-status.warn{display:block;background:#fef3c7;color:#92400e}.kh-settings-row{display:flex;gap:8px;margin-top:8px}.kh-settings-btn{flex:1;border:none;border-radius:12px;padding:12px 13px;font-size:14px;font-weight:700;cursor:pointer}.kh-settings-btn.primary{background:#1570ef;color:#fff}.kh-settings-btn.secondary{background:#eaecf0;color:#101828}';document.head.appendChild(style);}var overlay=document.createElement('div');overlay.id='kh-settings-overlay';overlay.className='kh-settings-overlay';overlay.innerHTML='<div class=\"kh-settings-panel\"><div class=\"kh-settings-title\">客户端设置</div><div id=\"kh-settings-status\" class=\"kh-settings-status\"></div><div id=\"kh-settings-meta\" class=\"kh-settings-meta\">服务地址会写入 Android 本地，保存后自动重启并直接打开远程入口。</div><div class=\"kh-settings-field\"><label>服务地址</label><input id=\"kh-settings-server\" type=\"url\" inputmode=\"url\" autocomplete=\"url\" placeholder=\"http://127.0.0.1:13000\"></div><div class=\"kh-settings-field\"><label>更新地址</label><input id=\"kh-settings-update\" type=\"url\" inputmode=\"url\" autocomplete=\"url\" placeholder=\"http://127.0.0.1:13000\"></div><div class=\"kh-settings-field\"><label>纸张类型</label><select id=\"kh-settings-paper\"><option value=\"thermal\">普通热敏纸</option><option value=\"black_mark\">黑标标签纸</option></select></div><div class=\"kh-settings-field\"><label>排版预设</label><select id=\"kh-settings-layout\"><option value=\"standard\">标准排版</option><option value=\"compact\">紧凑排版</option><option value=\"large\">大字排版</option></select></div><div class=\"kh-settings-note\">应用启动时直接打开服务地址根路径，是否需要登录由服务端登录态自行判断。</div><div class=\"kh-settings-row\"><button type=\"button\" class=\"kh-settings-btn primary\" id=\"kh-settings-save\">保存并重启</button><button type=\"button\" class=\"kh-settings-btn secondary\" id=\"kh-settings-update-btn\">检查更新</button></div><div class=\"kh-settings-row\"><button type=\"button\" class=\"kh-settings-btn secondary\" id=\"kh-settings-close\">关闭</button></div></div>';document.body.appendChild(overlay);kh._settingsOverlay=overlay;kh._settingsStatus=overlay.querySelector('#kh-settings-status');kh._settingsMeta=overlay.querySelector('#kh-settings-meta');kh.setSettingsStatus=function(message,type){if(!kh._settingsStatus)return;if(!message){kh._settingsStatus.style.display='none';kh._settingsStatus.textContent='';kh._settingsStatus.className='kh-settings-status';return;}kh._settingsStatus.style.display='block';kh._settingsStatus.textContent=message;kh._settingsStatus.className='kh-settings-status '+(type||'info');};var fill=function(){overlay.querySelector('#kh-settings-server').value=kh.getServerBase();overlay.querySelector('#kh-settings-update').value=kh.getUpdateBase();overlay.querySelector('#kh-settings-paper').value=kh.getPrintPaperType();overlay.querySelector('#kh-settings-layout').value=kh.getPrintLayoutPreset();if(kh._settingsMeta){kh._settingsMeta.textContent='当前打印默认值：'+kh.getPaperTypeLabel(kh.getPrintPaperType())+' / '+kh.getLayoutPresetLabel(kh.getPrintLayoutPreset())+'；构建时间：'+(window.BUILD_TIME?new Date(window.BUILD_TIME).toLocaleString('zh-CN'):'未知');}kh.setSettingsStatus('','');};kh.openSettingsPanel=function(){kh.readClientConfig().then(function(){fill();overlay.style.display='flex';});};kh.closeSettingsPanel=function(){overlay.style.display='none';};overlay.addEventListener('click',function(evt){if(evt.target===overlay)kh.closeSettingsPanel();});overlay.querySelector('#kh-settings-close').addEventListener('click',function(){kh.closeSettingsPanel();});overlay.querySelector('#kh-settings-update-btn').addEventListener('click',function(){kh.triggerAppUpdate(kh.setSettingsStatus);});overlay.querySelector('#kh-settings-save').addEventListener('click',function(){var serverBase=kh.normalizeBaseUrl(overlay.querySelector('#kh-settings-server').value,kh.defaultServerBase);var updateBase=kh.normalizeBaseUrl(overlay.querySelector('#kh-settings-update').value,kh.defaultUpdateBase);var paperType=kh.normalizePaperTypeValue(overlay.querySelector('#kh-settings-paper').value);var layoutPreset=kh.normalizeLayoutPresetValue(overlay.querySelector('#kh-settings-layout').value);if(!serverBase){kh.setSettingsStatus('请输入服务地址','err');return;}if(!updateBase){kh.setSettingsStatus('请输入更新地址','err');return;}var settings={serverBase:serverBase,updateBase:updateBase,paperType:paperType,layoutPreset:layoutPreset};var plugin=kh.getClientConfigPlugin();if(!plugin||!plugin.saveConfig){kh.setSettingsStatus('原生配置插件不可用，无法保存本地地址','err');return;}Promise.resolve(plugin.saveConfig(settings)).then(function(saved){kh.applyClientConfig(saved||settings);kh.setSettingsStatus('配置已保存，应用即将重启…','info');setTimeout(function(){if(plugin.restartApp){Promise.resolve(plugin.restartApp()).catch(function(err){kh.setSettingsStatus('重启失败: '+String(err&&err.message||err||'unknown'),'err');});}else{kh.setSettingsStatus('当前环境不支持自动重启','warn');}},350);}).catch(function(err){kh.setSettingsStatus('保存失败: '+String(err&&err.message||err||'unknown'),'err');});});};if(document.body)mount();else window.addEventListener('DOMContentLoaded',mount,{once:true});};");
        script.append("kh.installGlobalLoggers=function(){kh.ensureFloatingLogger();if(!window.log){window.log=function(msg,type){kh.pushLog(msg,type||'plain');};}if(!window.__khRuntimeErrorHooked){window.__khRuntimeErrorHooked=true;window.addEventListener('error',function(e){kh.pushLog('JS ERROR: '+(e&&e.message||'unknown'),'err');});window.addEventListener('unhandledrejection',function(e){var reason=e&&e.reason;kh.pushLog('UNHANDLED: '+((reason&&reason.message)||reason||'unknown'),'err');});}};");
        script.append("kh.bootOnce=function(){if(kh.bootInstalled||kh.bootState==='booting')return Promise.resolve(kh.bootState);kh.bootState='booting';kh.setPageApplyState('loading','booting');kh.patchWindowOpen();kh.ensureDeviceClient();kh.installUiReadySignals();kh.patchHistory();kh.patchStorage();kh.attachButtonActions();kh.installActionObserver();kh.ensureSettingsPanel();kh.ensureUpdateButton();kh.installGlobalLoggers();kh.pushLog('网页日志桥已启动 version='+String(window.APP_VERSION_NAME||'')+' ('+String(window.APP_VERSION_CODE||'')+'), buildTime='+String(window.BUILD_TIME||''),'info');return kh.readClientConfig().catch(function(){return kh.clientConfig;}).then(function(){return kh.ensureScanBridge().catch(function(){return null;});}).then(function(){kh.bootInstalled=true;kh.bootState='ready';return 'ready';}).catch(function(err){kh.bootState='error';kh.setPageApplyState('error',String(err&&err.message||err||'boot failed'));throw err;});};");
        script.append("kh.navigateInApp=function(url){if(!url)return false;try{var resolved=new URL(url,window.location.href).toString();if(!/^https?:\\/\\//i.test(resolved))return false;kh.pushLog('应用内跳转: '+resolved,'info');window.location.assign(resolved);return true;}catch(e){kh.pushLog('应用内跳转失败: '+String(e&&e.message||e||'unknown'),'warn');return false;}};");
        script.append("kh.patchWindowOpen=function(){if(window.__khWindowOpenPatched)return;var originalOpen=window.open;window.open=function(url,target){var normalizedTarget=String(target||'').toLowerCase();if(url&&(!normalizedTarget||normalizedTarget==='_self'||normalizedTarget==='_blank')){if(kh.navigateInApp(url))return window;}return typeof originalOpen==='function'?originalOpen.apply(window,arguments):null;};document.addEventListener('click',function(event){var link=event.target&&event.target.closest?event.target.closest('a[href]'):null;if(!link)return;var href=link.getAttribute('href')||'';var target=String(link.getAttribute('target')||'').toLowerCase();var resolved=link.href||href;if(!/^https?:\\/\\//i.test(resolved))return;if(target&&target!=='_self'&&target!=='_blank')return;event.preventDefault();event.stopPropagation();kh.navigateInApp(resolved);},true);window.__khWindowOpenPatched=true;};");
        script.append("kh.getScanPlugin=function(){var nativeBridge=kh.getNativeBridge();if(nativeBridge&&nativeBridge.startScan){return {addListener:function(event,handler){if(event!=='scanResult')return Promise.resolve({remove:function(){}});var listener=function(e){handler&&handler({value:e&&e.detail&&e.detail.value?String(e.detail.value):''});};window.addEventListener('kh:scan',listener);return Promise.resolve({remove:function(){window.removeEventListener('kh:scan',listener);}});},startScan:function(){return Promise.resolve(nativeBridge.startScan());},stopScan:function(){return Promise.resolve(nativeBridge.stopScan&&nativeBridge.stopScan());}};}return window.ScanPlugin||(window.Capacitor&&window.Capacitor.Plugins&&window.Capacitor.Plugins.ScanPlugin)||null;};");
        script.append("kh.ensureScanBridge=function(){if(kh._scanBridgeReady)return kh._scanBridgeReady;var plugin=kh.getScanPlugin();if(!plugin||!plugin.addListener){kh._scanBridgeReady=Promise.reject(new Error('Scan bridge unavailable'));return kh._scanBridgeReady;}kh._scanBridgeReady=Promise.resolve(plugin.addListener('scanResult',function(evt){var value=evt&&evt.value?String(evt.value):'';if(!value)return;kh.pushLog('收到扫码: '+value,'ok');var bridge=kh.getNativeBridge();if(bridge&&bridge.onScanCompleted)bridge.onScanCompleted();var handled=kh.execTriggeredActions&&kh.execTriggeredActions('scan',value);if(!handled){kh.injectValue('',value,false);}})).then(function(){kh.pushLog('扫码桥已就绪','info');return true;}).catch(function(err){kh.pushLog('扫码桥初始化失败: '+String(err&&err.message||err||'unknown'),'err');throw err;});return kh._scanBridgeReady;};");
        script.append("kh.startGlobalScan=function(){var plugin=kh.getScanPlugin();if(!plugin||!plugin.startScan){kh.signalActionTriggered('扫码桥不可用','warn');return Promise.resolve({mock:true,reason:'Scan bridge unavailable'});}return kh.ensureScanBridge().catch(function(){return true;}).then(function(){kh.pushLog('手动触发扫码','info');return Promise.resolve(plugin.startScan()).then(function(){kh.showToast('已触发扫码','info');return true;}).catch(function(err){kh.pushLog('触发扫码失败: '+String(err&&err.message||err||'unknown'),'err');kh.showToast('扫码触发失败: '+String(err&&err.message||err||'unknown'),'err');throw err;});});};");
        script.append("kh.ensureGlobalScanButton=function(){};");
        script.append("kh.ensureControlMenu=function(){};");
        script.append("kh.markUiReady=function(detail){if(kh.pageApplyState!=='ready'){kh.setPageApplyState('ready',detail||'ui ready');}};");
        script.append("kh.installUiReadySignals=function(){if(kh._uiReadySignalsInstalled)return;kh._uiReadySignalsInstalled=true;var emit=function(detail){requestAnimationFrame(function(){requestAnimationFrame(function(){kh.markUiReady(detail);});});};if(document.readyState==='complete'||document.readyState==='interactive'){emit('document '+document.readyState);}else{window.addEventListener('DOMContentLoaded',function(){emit('DOMContentLoaded');},{once:true});}window.addEventListener('load',function(){emit('window load');},{once:true});window.addEventListener('pageshow',function(){emit('pageshow');});};");
        script.append("var patchFetch=function(){var of=window.fetch;if(!of||of.__khWrapped)return;var wf=function(r,i){i=i||{};var hs=new Headers(i.headers||(r&&r.headers)||{});if(!hs.has(h))hs.set(h,v);i.headers=hs;return of.call(this,r,i);};wf.__khWrapped=true;window.fetch=wf;};");
        script.append("var patchXhr=function(){if(XMLHttpRequest.prototype.__khWrapped)return;var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send,osr=XMLHttpRequest.prototype.setRequestHeader;");
        script.append("XMLHttpRequest.prototype.open=function(){this.__khSet=false;return oo.apply(this,arguments);};");
        script.append("XMLHttpRequest.prototype.setRequestHeader=function(n,val){if(String(n).toLowerCase()===h.toLowerCase())this.__khSet=true;return osr.apply(this,arguments);};");
        script.append("XMLHttpRequest.prototype.send=function(b){if(!this.__khSet){osr.call(this,h,v);this.__khSet=true;}return os.call(this,b);};");
        script.append("XMLHttpRequest.prototype.__khWrapped=true;};");
        script.append("kh.normalizeBool=function(value,def){if(value===undefined||value===null)return !!def;if(typeof value==='boolean')return value;if(typeof value==='number')return !!value;var text=String(value).trim().toLowerCase();if(['1','true','yes','y','on'].indexOf(text)>=0)return true;if(['0','false','no','n','off'].indexOf(text)>=0)return false;return !!def;};");
        script.append("kh.pickTarget=function(selector){var isEditable=function(el){if(!el)return false;if(el.isContentEditable)return true;var tag=(el.tagName||'').toLowerCase();if(tag==='textarea')return true;if(tag!=='input')return false;var type=(el.type||'text').toLowerCase();return ['button','submit','reset','checkbox','radio','file','image','hidden'].indexOf(type)<0;};var isVisible=function(el){if(!el)return false;var style=window.getComputedStyle(el);return style.display!=='none'&&style.visibility!=='hidden'&&!el.disabled;};if(selector){var nodes=Array.from(document.querySelectorAll(selector));for(var i=0;i<nodes.length;i++){if(isEditable(nodes[i])&&isVisible(nodes[i]))return nodes[i];}}var active=document.activeElement;if(isEditable(active)&&isVisible(active))return active;var all=Array.from(document.querySelectorAll('input,textarea,[contenteditable=\"true\"]'));for(var j=0;j<all.length;j++){if(isEditable(all[j])&&isVisible(all[j]))return all[j];}return null;};");
        script.append("kh.injectValue=function(selector,value,autoPressEnter){var target=kh.pickTarget(selector);if(!target)return false;target.focus&&target.focus();target.click&&target.click();if(target.isContentEditable){target.textContent=value;}else{var proto=(target.tagName||'').toLowerCase()==='textarea'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var desc=Object.getOwnPropertyDescriptor(proto,'value');if(desc&&desc.set){desc.set.call(target,value);}else{target.value=value;}}target.dispatchEvent(new Event('input',{bubbles:true}));target.dispatchEvent(new Event('change',{bubbles:true}));if(autoPressEnter){try{target.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));target.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));}catch(e){}}return true;};");
        script.append("kh.clickSelector=function(selector){if(!selector)return false;var target=document.querySelector(selector);if(!target)return false;target.focus&&target.focus();target.click&&target.click();return true;};");
        script.append("kh.normalizeAction=function(item,index){if(!item||typeof item!=='object')return null;var options=item.options;if(typeof options==='string'&&options.trim()){try{options=JSON.parse(options);}catch(e){options={};}}if(!options||typeof options!=='object')options={};var triggerType=String(item.trigger_type||item.triggerType||item.trigger||item.event||options.trigger_type||options.trigger||'').trim().toLowerCase();var actionType=String(item.action_type||item.actionType||item.action||item.type||options.action_type||options.action||'').trim().toLowerCase();if(!triggerType||!actionType)return null;var sortOrder=parseInt(item.sort||item.sortOrder||item.order||options.sort||options.order||index,10);if(Number.isNaN(sortOrder))sortOrder=index;var delayMs=parseInt(item.delay_ms||item.delayMs||options.delay_ms||0,10);if(Number.isNaN(delayMs)||delayMs<0)delayMs=0;return {id:String(item.id||item.key||('page-action-'+index)),enabled:kh.normalizeBool(item.enabled,true),roleName:String(item.role_name||item.roleName||item.role||options.role_name||options.role||'').trim(),pagePath:String(item.page_path||item.pagePath||item.page||item.path||item.page_url||item.pageUrl||options.page_path||options.page||'').trim(),triggerType:triggerType,triggerSelector:String(item.trigger_selector||item.triggerSelector||options.trigger_selector||'').trim(),actionType:actionType,targetSelector:String(item.target_selector||item.targetSelector||options.target_selector||'').trim(),value:String(item.value||options.value||'').trim(),autoPressEnter:kh.normalizeBool(item.auto_press_enter!==undefined?item.auto_press_enter:options.auto_press_enter,false),delayMs:delayMs,sortOrder:sortOrder,options:options,raw:item};};");
        script.append("kh.describeAction=function(action){if(!action)return '<null action>';var parts=[];parts.push('id='+(action.id||''));parts.push('trigger='+(action.triggerType||''));parts.push('type='+(action.actionType||''));if(action.triggerSelector)parts.push('triggerSelector='+(action.triggerSelector));if(action.targetSelector)parts.push('targetSelector='+(action.targetSelector));if(action.pagePath)parts.push('page='+(action.pagePath));if(action.roleName)parts.push('role='+(action.roleName));if(action.value)parts.push('value='+(action.value));if(action.delayMs)parts.push('delayMs='+(action.delayMs));return parts.join(', ');};");
        script.append("kh.getActionSignature=function(scan,button,role){var pack=function(list){return (list||[]).map(function(action){return [action.id,action.triggerType,action.actionType,action.triggerSelector,action.targetSelector,action.pagePath,action.roleName,action.value,action.delayMs].join('|');}).join('||');};return String(role||'')+'##'+pack(scan)+'###'+pack(button);};");
        script.append("kh.describeElement=function(element){if(!element)return '<null element>';var parts=[];var tag=String(element.tagName||'').toLowerCase();if(tag)parts.push('tag='+tag);if(element.id)parts.push('id='+element.id);var className='';try{className=typeof element.className==='string'?element.className:(element.className&&element.className.baseVal)||'';}catch(e){className='';}className=String(className||'').trim().replace(/\\s+/g,'.');if(className)parts.push('class=.'+className);var khAction='';try{khAction=element.getAttribute&&element.getAttribute('data-kh-action')||'';}catch(e){khAction='';}if(khAction)parts.push('data-kh-action='+khAction); var name='';try{name=element.getAttribute&&element.getAttribute('name')||'';}catch(e){name='';}if(name)parts.push('name='+name);var text=String(element.innerText||element.textContent||'').trim().replace(/\\s+/g,' ');if(text)parts.push('text='+(text.length>80?text.slice(0,80)+'...':text));return parts.join(', ');};");
        script.append("kh.roleMatch=function(actionRole,currentRole){var role=String(actionRole||'').trim().toLowerCase();if(!role)return true;var current=String(currentRole||'').trim().toLowerCase();var parts=role.split(/[;,|]/).map(function(v){return v.trim();}).filter(Boolean);return parts.indexOf(current)>=0;};");
        script.append("kh.pageMatch=function(pagePath,currentUrl){var path=String(pagePath||'').trim();if(!path)return true;if(/^https?:\\/\\//i.test(path))return String(currentUrl||'').indexOf(path)===0;var url;try{url=new URL(currentUrl||window.location.href);}catch(e){url=window.location;}var currentPath=url.pathname||'/';var expected=path.charAt(0)==='/'?path:('/'+path);return currentPath.indexOf(expected)===0;};");
        script.append("kh.readSelector=function(selector){if(!selector)return '';var el=document.querySelector(selector);if(!el)return '';var value=('value' in el&&el.value!==undefined&&el.value!==null)?String(el.value).trim():'';if(value)return value;return String(el.textContent||el.innerText||'').trim();};");
        script.append("kh.applyTemplate=function(value,scanValue){return String(value||'').replace(/\\{\\{\\s*scan\\s*\\}\\}/gi,String(scanValue||''));};");
        script.append("kh.getStoredValue=function(key){var storages=[window.localStorage,window.sessionStorage].filter(Boolean);for(var i=0;i<storages.length;i++){var value=storages[i].getItem(key);if(value!==null&&value!==undefined&&String(value).trim()!=='')return String(value).trim();}return '';};");
        script.append("kh.normalizePaperType=function(value){return String(value||'').trim().toLowerCase()==='black_mark'?'black_mark':'thermal';};");
        script.append("kh.normalizeLayoutPreset=function(value){var raw=String(value||'').trim().toLowerCase();return ['compact','large'].indexOf(raw)>=0?raw:'standard';};");
        script.append("kh.resolveActionField=function(action,name,scanValue){var options=action&&action.options||{};var raw=action&&action.raw||{};var direct=options[name];if((direct===undefined||direct===null||String(direct).trim()==='')&&raw[name]!==undefined)direct=raw[name];if(direct!==undefined&&direct!==null&&String(direct).trim()!=='')return kh.applyTemplate(String(direct),scanValue);var selector=options[name+'_selector']||options[name+'Selector']||raw[name+'_selector']||raw[name+'Selector']||'';if(selector){var selected=kh.readSelector(selector);if(selected)return kh.applyTemplate(selected,scanValue);}if(name==='barcode_value'&&scanValue)return String(scanValue).trim();if(name==='barcode_value'&&action&&action.value)return kh.applyTemplate(action.value,scanValue);return '';};");
        script.append("kh.resolvePrintConfig=function(action,row){var options=action&&action.options||{};var raw=action&&action.raw||{};var rowData=row||{};var paperType=rowData.paperType||options.paper_type||options.paperType||raw.paper_type||raw.paperType||kh.getStoredValue(kh.paperTypeStorageKey)||kh.getPrintPaperType()||'thermal';var layoutPreset=rowData.layoutPreset||options.layout_preset||options.layoutPreset||raw.layout_preset||raw.layoutPreset||kh.getStoredValue(kh.layoutPresetStorageKey)||kh.getPrintLayoutPreset()||'standard';return {paperType:kh.normalizePaperType(paperType),layoutPreset:kh.normalizeLayoutPreset(layoutPreset)};};");
        script.append("kh.resolvePrintSourceType=function(action){var options=action&&action.options||{};var raw=action&&action.raw||{};return String(options.print_source_type||options.printSourceType||raw.print_source_type||raw.printSourceType||'single').trim().toLowerCase()||'single';};");
        script.append("kh.resolveTableCells=function(row){if(!row||!row.children)return [];var cells=Array.from(row.children||[]).filter(function(node){return !!node;});if(cells.length)return cells;return Array.from(row.querySelectorAll(':scope > *'));};");
        script.append("kh.readNodeText=function(node){if(!node)return '';if('value' in node&&node.value!==undefined&&node.value!==null&&String(node.value).trim()!=='')return String(node.value).trim();return String(node.innerText||node.textContent||'').trim();};");
        script.append("kh.readIndexedCell=function(row,index){var parsed=parseInt(index,10);if(Number.isNaN(parsed)||parsed<0)return '';var cells=kh.resolveTableCells(row);if(parsed>=cells.length)return '';return kh.readNodeText(cells[parsed]);};");
        script.append("kh.resolveTablePrintItems=function(action){var options=action&&action.options||{};var raw=action&&action.raw||{};var selector=String(options.table_selector||options.tableSelector||raw.table_selector||raw.tableSelector||'').trim();if(!selector){kh.pushLog('table 打印缺少 table_selector: '+kh.describeAction(action),'err');return [];}var rows=Array.from(document.querySelectorAll(selector));kh.pushLog('table 打印读取行数: selector='+selector+', count='+rows.length,'info');var barcodeIndex=options.barcode_index!==undefined?options.barcode_index:(options.barcodeIndex!==undefined?options.barcodeIndex:raw.barcode_index);var qrcodeIndex=options.qrcode_index!==undefined?options.qrcode_index:(options.qrcodeIndex!==undefined?options.qrcodeIndex:raw.qrcode_index);var textIndex=options.text_index!==undefined?options.text_index:(options.textIndex!==undefined?options.textIndex:raw.text_index);var copiesIndex=options.copies_index!==undefined?options.copies_index:(options.copiesIndex!==undefined?options.copiesIndex:raw.copies_index);var paperTypeIndex=options.paper_type_index!==undefined?options.paper_type_index:(options.paperTypeIndex!==undefined?options.paperTypeIndex:raw.paper_type_index);var layoutPresetIndex=options.layout_preset_index!==undefined?options.layout_preset_index:(options.layoutPresetIndex!==undefined?options.layoutPresetIndex:raw.layout_preset_index);return rows.map(function(row,rowIndex){var item={barcodeValue:kh.readIndexedCell(row,barcodeIndex),qrCodeValue:kh.readIndexedCell(row,qrcodeIndex),textValue:kh.readIndexedCell(row,textIndex),copies:kh.readIndexedCell(row,copiesIndex),paperType:kh.readIndexedCell(row,paperTypeIndex),layoutPreset:kh.readIndexedCell(row,layoutPresetIndex)};kh.pushLog('table 行['+(rowIndex+1)+'/'+rows.length+']: barcode='+String(item.barcodeValue||'')+', qrcode='+String(item.qrCodeValue||'')+', text='+String(item.textValue||'')+', copies='+String(item.copies||''),'plain');return item;}).filter(function(item){return !!(String(item.barcodeValue||'').trim()||String(item.qrCodeValue||'').trim()||String(item.textValue||'').trim());});};");
        script.append("kh.getPrintPlugin=function(){var nativeBridge=kh.getNativeBridge();if(nativeBridge&&nativeBridge.printLabel){kh._printBridgeMode='native';return {addListener:function(event,handler){if(event!=='printStatus')return Promise.resolve({remove:function(){}});var listener=function(e){handler&&handler(e&&e.detail?e.detail:{});};window.addEventListener('kh:printStatus',listener);return Promise.resolve({remove:function(){window.removeEventListener('kh:printStatus',listener);}});},connect:function(){return Promise.resolve(nativeBridge.connectPrinter&&nativeBridge.connectPrinter());},prepareToPrintLabel:function(){return Promise.resolve(nativeBridge.prepareToPrintLabel&&nativeBridge.prepareToPrintLabel());},printLabel:function(payload){return Promise.resolve(nativeBridge.printLabel&&nativeBridge.printLabel(JSON.stringify(payload||{})));}};}var plugin=window.PrintPlugin||(window.Capacitor&&window.Capacitor.Plugins&&window.Capacitor.Plugins.PrintPlugin)||null;kh._printBridgeMode=plugin?'legacy-plugin':'missing';return plugin;};");
        script.append("kh.waitPrintStatus=function(plugin,wanted,timeoutMs){var ERRORS=['NO_PAPER','PRINTER_CLOSED','SEND_DATA_FAILED','PRINT_FAILED','BLACK_FLAG_NOT_FOUND','PREPARE_LABEL_NO_PAPER','PREPARE_LABEL_BLACK_FLAG_NOT_FOUND','PREPARE_LABEL_FAILED','PREPARE_LABEL_PRINTER_CLOSED','PREPARE_LABEL_SEND_DATA_FAILED'];return new Promise(function(resolve,reject){var done=false;var sub=null;var timer=setTimeout(function(){finish(new Error('print status timeout: '+wanted));},timeoutMs||15000);var finish=function(err){if(done)return;done=true;clearTimeout(timer);try{sub&&sub.remove&&sub.remove();}catch(e){}if(err)reject(err);else resolve();};Promise.resolve(plugin.addListener('printStatus',function(evt){var status=evt&&evt.status;if(!status)return;if(status===wanted){finish();}else if(ERRORS.indexOf(status)>=0){finish(new Error(status));}})).then(function(handle){sub=handle;}).catch(finish);});};");
        script.append("kh.ensurePrintConnected=function(plugin){kh._printConnectPromise=kh._printConnectPromise||Promise.resolve(plugin.connect&&plugin.connect()).catch(function(){return null;});return kh._printConnectPromise;};");
        script.append("kh.runSinglePrintPayload=function(plugin,action,payload){var printConfig=kh.resolvePrintConfig(action,payload);var finalPayload={barcodeValue:String(payload.barcodeValue||'').trim(),qrCodeValue:String(payload.qrCodeValue||'').trim(),textValue:String(payload.textValue||'').trim(),paperType:printConfig.paperType,layoutPreset:printConfig.layoutPreset};if(!finalPayload.barcodeValue&&!finalPayload.qrCodeValue&&!finalPayload.textValue)return Promise.reject(new Error('print action missing barcode/qrcode/text'));kh.pushLog('开始打印动作: '+(action.id||action.actionType)+' ['+finalPayload.paperType+'/'+finalPayload.layoutPreset+'] barcode='+finalPayload.barcodeValue+', qrcode='+finalPayload.qrCodeValue+', text='+finalPayload.textValue,'info');return kh.ensurePrintConnected(plugin).then(function(){if(finalPayload.paperType==='black_mark'&&plugin.prepareToPrintLabel){return Promise.resolve(plugin.prepareToPrintLabel()).catch(function(){return null;});}return null;}).then(function(){var waitDone=kh.waitPrintStatus(plugin,'PRINT_OK',15000);return Promise.resolve(plugin.printLabel(finalPayload)).then(function(){return waitDone;});});};");
        script.append("kh.runPrintAction=function(action,scanValue){var plugin=kh.getPrintPlugin();var actionType=String(action.actionType||'').toLowerCase();if(actionType==='print_label'||actionType==='print_batch_label'){var sourceType=kh.resolvePrintSourceType(action);var payloads=[];if(sourceType==='table'){payloads=kh.resolveTablePrintItems(action);}else{payloads=[{barcodeValue:kh.resolveActionField(action,'barcode_value',scanValue),qrCodeValue:kh.resolveActionField(action,'qrcode_value',scanValue),textValue:kh.resolveActionField(action,'text_value',scanValue)}];}if(!payloads.length)return Promise.reject(new Error('print action missing rows'));kh.pushLog('打印桥模式: '+String(kh._printBridgeMode||'unknown'),'info');if(!plugin){kh.signalActionTriggered('已触发打印动作，但当前设备无打印能力','warn');return Promise.resolve({mock:true,reason:'PrintPlugin unavailable',payloads:payloads,bridgeMode:kh._printBridgeMode||'missing'});}return payloads.reduce(function(chain,rowPayload,rowIndex){return chain.then(function(){var copies=parseInt(rowPayload&&rowPayload.copies,10);if(Number.isNaN(copies)||copies<1)copies=1;kh.pushLog('打印队列项['+(rowIndex+1)+'/'+payloads.length+'] copies='+copies,'info');var copyChain=Promise.resolve();for(var i=0;i<copies;i++){(function(copyIndex){copyChain=copyChain.then(function(){kh.pushLog('执行打印副本['+(copyIndex+1)+'/'+copies+']，队列项='+(rowIndex+1),'info');return kh.runSinglePrintPayload(plugin,action,rowPayload);});})(i);}return copyChain;});},Promise.resolve());}return Promise.reject(new Error('unsupported print action: '+actionType));};");
        script.append("kh.execAction=function(action,scanValue){if(!action||!action.enabled)return false;kh.pushLog('准备执行动作: '+kh.describeAction(action)+(scanValue?(', scanValue='+scanValue):''),'info');var runner=function(){if(action.actionType==='fill_input'||action.actionType==='fill'||action.actionType==='scan_fill'||action.actionType==='input'){return kh.injectValue(action.targetSelector,scanValue||action.value||'',!!action.autoPressEnter);}if(action.actionType==='click'||action.actionType==='tap'){return kh.clickSelector(action.targetSelector);}if(action.actionType==='scan'||action.actionType==='start_scan'||action.actionType==='device_scan'){kh.startGlobalScan().catch(function(err){kh.pushLog('动作触发扫码失败: '+String(err&&err.message||err||'unknown'),'err');});return true;}if(action.actionType==='stop_scan'){var bridge=kh.getNativeBridge();if(bridge&&bridge.stopScan){bridge.stopScan();kh.pushLog('已执行停止扫码动作: '+(action.id||action.actionType),'info');return true;}kh.pushLog('停止扫码桥不可用: '+(action.id||action.actionType),'warn');return false;}if(action.actionType==='print_label'||action.actionType==='print_batch_label'){kh.runPrintAction(action,scanValue||'').then(function(result){if(result&&result.mock){kh.signalActionTriggered('打印动作已命中，当前使用无设备确认模式','warn');return;}kh.pushLog('打印动作成功: '+(action.id||action.actionType),'ok');}).catch(function(err){kh.pushLog('打印动作失败: '+String(err&&err.message||err||'print failed'),'err');window.__khLastActionError=String(err&&err.message||err||'print failed');});return true;}if(action.actionType==='debug_notice'||action.actionType==='toast'||action.actionType==='alert'){kh.signalActionTriggered(action.value||('动作已触发: '+(action.id||action.actionType)),'info');return true;}if(action.actionType==='noop'||action.actionType==='none'){kh.pushLog('动作为 noop，跳过执行: '+(action.id||action.actionType),'warn');return true;}kh.pushLog('未支持的动作类型: '+(action.actionType||'<empty>')+'，'+kh.describeAction(action),'warn');return false;};if(action.delayMs>0){kh.pushLog('动作延迟执行 '+action.delayMs+'ms: '+(action.id||action.actionType),'info');setTimeout(runner,action.delayMs);return true;}return runner();};");
        script.append("kh.execTriggeredActions=function(triggerType,scanValue){var grouped=window.__khPageActions||{};var actions=Array.isArray(grouped[triggerType])?grouped[triggerType]:[];if(!actions.length)return false;actions.slice().sort(function(a,b){return (a.sortOrder||0)-(b.sortOrder||0);}).forEach(function(action,index){kh.pushLog('命中动作['+(index+1)+'/'+actions.length+']: '+kh.describeAction(action),'info');kh.execAction(action,scanValue||'');});return true;};");
        script.append("kh.attachButtonActions=function(){if(window.__khButtonActionsBound)return;document.addEventListener('click',function(event){var actions=(window.__khPageActions&&window.__khPageActions.button)||[];for(var i=0;i<actions.length;i++){var action=actions[i];if(!action.triggerSelector)continue;var target=event.target&&event.target.closest?event.target.closest(action.triggerSelector):null;if(!target)continue;kh.pushLog('按钮动作命中 selector: '+action.triggerSelector+'，matched={'+kh.describeElement(target)+'}，'+kh.describeAction(action),'ok');event.preventDefault();event.stopPropagation();kh.execAction(action,'');return;}},true);window.__khButtonActionsBound=true;};");
        script.append("kh.getActionAuth=function(){var storages=[window.localStorage,window.sessionStorage].filter(Boolean);var getStored=function(key){for(var i=0;i<storages.length;i++){var value=storages[i].getItem(key);if(value)return value;}return '';};return {role:getStored('NOCOBASE_ROLE')||getStored('NOCOBASE_MAIN_ROLE')||'',token:getStored('NOCOBASE_TOKEN')||getStored('NOCOBASE_MAIN_TOKEN')||'',auth:getStored('NOCOBASE_AUTH')||getStored('NOCOBASE_MAIN_AUTH')||'basic'};};");
        script.append("kh.getActionCacheKey=function(authInfo){authInfo=authInfo||kh.getActionAuth();return [authInfo.token||'',authInfo.auth||'',window.location.origin||''].join('|');};");
        script.append("kh.getActionCatalogStore=function(){var store=window.__khActionCatalogStore||(window.__khActionCatalogStore={cacheKey:'',items:[],fetchedAt:0,loading:null,lastAppliedKey:'',version:0});return store;};");
        script.append("kh.fetchActionCatalog=function(force){var authInfo=kh.getActionAuth();var token=authInfo.token;var auth=authInfo.auth;var store=kh.getActionCatalogStore();var cacheKey=kh.getActionCacheKey(authInfo);if(!token||!window.fetch){store.items=[];store.cacheKey=cacheKey;store.fetchedAt=0;kh.actionCatalogVersion=store.version||0;return Promise.resolve([]);}var freshEnough=!force&&store.cacheKey===cacheKey&&Array.isArray(store.items)&&store.items.length&&Date.now()-store.fetchedAt<300000;if(freshEnough)return Promise.resolve(store.items);if(store.loading&&store.cacheKey===cacheKey&&!force)return store.loading;var requestUrl=new URL(kh.pageActionsApi,window.location.origin).toString();store.cacheKey=cacheKey;store.loading=window.fetch(requestUrl,{headers:{'Authorization':'Bearer '+token,'X-Authenticator':auth,'X-Requested-With':'XMLHttpRequest'}}).then(function(res){if(!res.ok)throw new Error('page actions '+res.status);return res.json();}).then(function(payload){var data=(payload&&payload.data!==undefined)?payload.data:payload;var items=Array.isArray(data)?data:(Array.isArray(data&&data.items)?data.items:(Array.isArray(data&&data.rows)?data.rows:[]));store.items=items;store.fetchedAt=Date.now();store.version=(store.version||0)+1;kh.actionCatalogVersion=store.version;kh.pushLog('动作总表已缓存: count='+items.length+', version='+store.version,'info');return items;}).catch(function(err){kh.pushLog('动作总表加载失败: '+String(err&&err.message||err||'unknown'),'err');if(Array.isArray(store.items)&&store.items.length)return store.items;throw err;}).finally(function(){store.loading=null;});return store.loading;};");
        script.append("kh.applyPageActionsFromCatalog=function(items,role){var scan=[];var button=[];for(var i=0;i<items.length;i++){var action=kh.normalizeAction(items[i],i+1);if(!action||!action.enabled)continue;if(!kh.roleMatch(action.roleName,role))continue;if(!kh.pageMatch(action.pagePath,window.location.href))continue;if(action.triggerType==='button')button.push(action);else if(action.triggerType==='scan')scan.push(action);}scan.sort(function(a,b){return (a.sortOrder||0)-(b.sortOrder||0);});button.sort(function(a,b){return (a.sortOrder||0)-(b.sortOrder||0);});var bridge=kh.getNativeBridge();var tokenPresent=kh.getActionAuth().token?'1':'0';var urlKey=window.location.pathname+window.location.search+window.location.hash;var applySignature=[urlKey,String(role||''),tokenPresent,String(kh.actionCatalogVersion||0),String(scan.length),String(button.length),kh.getActionSignature(scan,button,role)].join('@@');if(kh.pageApplyState==='ready'&&kh.pageApplySignature===applySignature&&window.__khPageActions){if(bridge&&bridge.setScanActionEnabled)bridge.setScanActionEnabled(scan.length>0);return window.__khPageActions;}window.__khPageActions={scan:scan,button:button};window.__khExecTriggeredActions=kh.execTriggeredActions;kh.attachButtonActions();if(bridge&&bridge.setScanActionEnabled)bridge.setScanActionEnabled(scan.length>0);kh.pageApplySignature=applySignature;kh.getActionCatalogStore().lastAppliedKey=applySignature;kh.pushLog('页面动作已应用: scan='+scan.length+', button='+button.length+', role='+(role||'<empty>'),'info');kh.setPageApplyState('ready','scan='+scan.length+',button='+button.length+',role='+(role||''));return window.__khPageActions;};");
        script.append("kh.loadPageActions=function(force){var authInfo=kh.getActionAuth();var role=authInfo.role||'';var store=kh.getActionCatalogStore();var hasCached=Array.isArray(store.items)&&store.items.length>0;if(force||kh.pageApplyState!=='ready'||!hasCached){kh.setPageApplyState('loading','force='+(!!force)+',path='+window.location.pathname);}if(!force&&hasCached){kh.applyPageActionsFromCatalog(store.items,role);}return kh.fetchActionCatalog(!!force).then(function(items){return kh.applyPageActionsFromCatalog(items||[],role);}).catch(function(err){window.__khPageActions={scan:[],button:[]};window.__khExecTriggeredActions=kh.execTriggeredActions;kh.attachButtonActions();var bridge=kh.getNativeBridge();if(bridge&&bridge.setScanActionEnabled)bridge.setScanActionEnabled(false);kh.pageApplySignature='';kh.setPageApplyState('error',String(err&&err.message||err||'load actions failed'));return window.__khPageActions;});};");
        script.append("kh.refreshCurrentPage=function(force){if(kh._pageRefreshPromise&&!force)return kh._pageRefreshPromise;kh._pageRefreshPromise=kh.loadPageActions(!!force).finally(function(){kh._pageRefreshPromise=null;});return kh._pageRefreshPromise;};");
        script.append("kh.schedulePageActionRefresh=function(force){if(kh._pageActionRefreshScheduled)return;kh._pageActionRefreshScheduled=true;setTimeout(function(){kh._pageActionRefreshScheduled=false;kh.refreshCurrentPage(!!force).catch(function(){return null;});},50);};");
        script.append("kh.patchHistory=function(){if(kh._historyPatched)return;kh._historyPatched=true;['pushState','replaceState'].forEach(function(name){var original=history[name];if(typeof original!=='function')return;history[name]=function(){var result=original.apply(this,arguments);window.dispatchEvent(new CustomEvent('kh:routeChanged',{detail:{type:name}}));kh.schedulePageActionRefresh(false);return result;};});};");
        script.append("kh.patchStorage=function(){if(kh._storagePatched)return;kh._storagePatched=true;var proto=window.Storage&&window.Storage.prototype;if(!proto||proto.__khPatched)return;var originalSet=proto.setItem;var originalRemove=proto.removeItem;proto.setItem=function(key,value){var storageType='unknown';try{storageType=this===window.localStorage?'localStorage':(this===window.sessionStorage?'sessionStorage':'unknown');}catch(e){}var prev=null;try{prev=this.getItem(key);}catch(e){}var result=originalSet.apply(this,arguments);var next=String(value||'');if(prev!==next){window.dispatchEvent(new CustomEvent('kh:storageChanged',{detail:{storage:storageType,key:String(key||''),value:next}}));}return result;};proto.removeItem=function(key){var storageType='unknown';try{storageType=this===window.localStorage?'localStorage':(this===window.sessionStorage?'sessionStorage':'unknown');}catch(e){}var prev=null;try{prev=this.getItem(key);}catch(e){}var result=originalRemove.apply(this,arguments);if(prev!==null){window.dispatchEvent(new CustomEvent('kh:storageChanged',{detail:{storage:storageType,key:String(key||''),value:null}}));}return result;};proto.__khPatched=true;};");
        script.append("kh.installActionObserver=function(){if(kh._actionObserverInstalled)return;kh._actionObserverInstalled=true;var observer=null;var resubscribe=function(){if(observer||!window.MutationObserver||!document.documentElement)return;observer=new MutationObserver(function(mutations){var hasMeaningfulChange=false;for(var i=0;i<mutations.length;i++){var mutation=mutations[i];if(mutation.type==='childList'&&((mutation.addedNodes&&mutation.addedNodes.length)||(mutation.removedNodes&&mutation.removedNodes.length))){hasMeaningfulChange=true;break;}if(mutation.type==='attributes'){hasMeaningfulChange=true;break;}}if(hasMeaningfulChange)kh.schedulePageActionRefresh(false);});observer.observe(document.documentElement,{childList:true,subtree:true,attributes:false});};if(document.documentElement)resubscribe();else window.addEventListener('DOMContentLoaded',resubscribe,{once:true});window.addEventListener('kh:routeChanged',function(){kh.schedulePageActionRefresh(false);});window.addEventListener('kh:storageChanged',function(evt){var key=String(evt&&evt.detail&&evt.detail.key||'');if(/NOCOBASE_(MAIN_)?(TOKEN|AUTH|ROLE)$/i.test(key)){kh.schedulePageActionRefresh(true);}});};");
        script.append("patchFetch();patchXhr();");
        if (shouldBootstrap) {
            script.append("try{");
            script.append("var storages=[window.localStorage,window.sessionStorage].filter(Boolean);");
            script.append("var setValue=function(storage,key,val){if(val===null||val===undefined||val===''){storage.removeItem(key);}else{storage.setItem(key,val);}};");
            script.append("var token=").append(js(khToken)).append(";");
            script.append("var auth=").append(js(khAuth.isEmpty() ? "basic" : khAuth)).append(";");
            script.append("var role=").append(js(khRole)).append(";");
            script.append("var app=").append(js(khApp)).append(";");
            script.append("var paper=").append(js(khPaper)).append(";");
            script.append("var layout=").append(js(khLayout)).append(";");
            script.append("var redirect=").append(js(redirect)).append(";");
            script.append("var prefixes=['").append(NOCOBASE_STORAGE_PREFIX).append("'];");
            script.append("if(app){prefixes.push('").append(NOCOBASE_STORAGE_PREFIX).append("' + app.toUpperCase() + '_');}");
            script.append("kh.pushLog('注入登录态并跳转到业务页: '+redirect,'info');");
            script.append("prefixes.forEach(function(prefix){storages.forEach(function(storage){setValue(storage,prefix+'TOKEN',token);setValue(storage,prefix+'AUTH',auth);setValue(storage,prefix+'ROLE',role);});});");
            script.append("storages.forEach(function(storage){setValue(storage,kh.paperTypeStorageKey,paper);setValue(storage,kh.layoutPresetStorageKey,layout);});");
            script.append("window.location.replace(redirect);");
            script.append("return;");
            script.append("}catch(e){console.error('kh bootstrap failed',e);}");
        }
        script.append("kh.bootOnce().then(function(){return kh.refreshCurrentPage(false);}).catch(function(){return null;});window.addEventListener('DOMContentLoaded',function(){kh.schedulePageActionRefresh(false);},{once:true});window.addEventListener('pageshow',function(){kh.schedulePageActionRefresh(false);});window.addEventListener('hashchange',function(){window.dispatchEvent(new CustomEvent('kh:routeChanged',{detail:{type:'hashchange'}}));kh.schedulePageActionRefresh(false);});window.addEventListener('popstate',function(){window.dispatchEvent(new CustomEvent('kh:routeChanged',{detail:{type:'popstate'}}));kh.schedulePageActionRefresh(false);});");
        script.append("})();");
        return script.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
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
