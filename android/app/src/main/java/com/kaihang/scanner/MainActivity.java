package com.kaihang.scanner;

import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.ConsoleMessage;
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
    private int nativePrintBridgeCallCount = 0;

    private enum InjectionTrigger {
        PAGE_STARTED,
        PAGE_COMMIT_VISIBLE,
        PAGE_LOADED,
        MANUAL
    }

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
                handleRuntimeInjection(view, url, InjectionTrigger.PAGE_COMMIT_VISIBLE);
            }
            @Override
            public void onPageLoaded(WebView view) {
                super.onPageLoaded(view);
                handleRuntimeInjection(view, view != null ? view.getUrl() : null, InjectionTrigger.PAGE_LOADED);
            }
        });
    }

    private void configureInAppNavigation(WebView webView) {
        webView.getSettings().setSupportMultipleWindows(false);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                handleRuntimeInjection(view, url, InjectionTrigger.PAGE_STARTED);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    String failingUrl = request.getUrl() != null ? request.getUrl().toString() : safe(view != null ? view.getUrl() : "");
                    String description = error != null && error.getDescription() != null
                        ? error.getDescription().toString()
                        : "unknown";
                    appendNativeLog("页面加载失败: " + description + " @ " + failingUrl);
                    setNativePageReadyState("error", description);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request != null && request.isForMainFrame()) {
                    int statusCode = errorResponse != null ? errorResponse.getStatusCode() : 0;
                    String reason = errorResponse != null ? safe(errorResponse.getReasonPhrase()) : "";
                    String failingUrl = request.getUrl() != null ? request.getUrl().toString() : safe(view != null ? view.getUrl() : "");
                    String detail = "HTTP " + statusCode + (reason.isEmpty() ? "" : (" " + reason));
                    appendNativeLog("页面 HTTP 异常: " + detail + " @ " + failingUrl);
                    setNativePageReadyState("error", detail);
                }
            }

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

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage != null) {
                    appendVerboseNativeLog(
                        "控制台[" + consoleMessage.messageLevel() + "]: "
                            + safe(consoleMessage.message())
                            + " @ "
                            + safe(consoleMessage.sourceId())
                            + ":"
                            + consoleMessage.lineNumber()
                    );
                }
                return super.onConsoleMessage(consoleMessage);
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
            if (isSameWebOrigin(view, uri)) {
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {}
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            return true;
        } catch (Exception ignored) {
            return true;
        }
    }

    private boolean isSameWebOrigin(WebView view, Uri targetUri) {
        if (targetUri == null) {
            return false;
        }
        Uri currentUri = null;
        try {
            if (view != null && view.getUrl() != null && !view.getUrl().trim().isEmpty()) {
                currentUri = Uri.parse(view.getUrl());
            }
        } catch (Exception ignored) {}
        if (currentUri == null) {
            try {
                currentUri = Uri.parse(buildLaunchUrl(ClientConfigPlugin.getSavedServerBase(this, DEFAULT_SERVER_BASE)));
            } catch (Exception ignored) {
                currentUri = null;
            }
        }
        if (currentUri == null) {
            return false;
        }
        String targetScheme = safe(targetUri.getScheme());
        String currentScheme = safe(currentUri.getScheme());
        String targetHost = safe(targetUri.getHost());
        String currentHost = safe(currentUri.getHost());
        int targetPort = targetUri.getPort();
        int currentPort = currentUri.getPort();
        return targetScheme.equalsIgnoreCase(currentScheme)
            && targetHost.equalsIgnoreCase(currentHost)
            && normalizePort(targetScheme, targetPort) == normalizePort(currentScheme, currentPort);
    }

    private int normalizePort(String scheme, int port) {
        if (port >= 0) {
            return port;
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        return -1;
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

    private void handleRuntimeInjection(WebView view, String targetUrl, InjectionTrigger trigger) {
        String url = safe(targetUrl);
        if (url.isEmpty() && view != null) {
            url = safe(view.getUrl());
        }
        if (!shouldInjectForTrigger(trigger)) {
            appendVerboseNativeLog("跳过自动注入: mode=" + getCurrentInjectionMode() + ", trigger=" + trigger + ", url=" + url);
            if (trigger != InjectionTrigger.MANUAL) {
                setNativePageReadyState("loading", url);
            }
            return;
        }
        if (view != null && isRuntimeReuseEnabled() && trigger != InjectionTrigger.MANUAL && trigger != InjectionTrigger.PAGE_STARTED) {
            probeReusableRuntime(view, url, trigger);
            return;
        }
        continueRuntimeInjection(view, url, trigger);
    }

    private void continueRuntimeInjection(WebView view, String url, InjectionTrigger trigger) {
        boolean force = trigger == InjectionTrigger.PAGE_LOADED || trigger == InjectionTrigger.MANUAL;
        if (!force) {
            setNativePageReadyState("loading", url);
            if (view != null) {
                if (trigger == InjectionTrigger.PAGE_COMMIT_VISIBLE) {
                    view.post(() -> injectClientTypeHeader(view, false));
                } else {
                    view.postDelayed(() -> injectClientTypeHeader(view, false), 40);
                }
                if ("commit_loaded".equals(getCurrentInjectionMode()) && trigger == InjectionTrigger.PAGE_COMMIT_VISIBLE) {
                    scheduleInjectionRecoveryCheck(view, url, 1200L);
                }
            }
            return;
        }
        injectClientTypeHeader(view, true);
    }

    private void probeReusableRuntime(WebView view, String expectedUrl, InjectionTrigger trigger) {
        if (view == null) {
            continueRuntimeInjection(null, expectedUrl, trigger);
            return;
        }
        String probeScript =
            "(function(){var kh=window.__khClientRuntime;" +
            "return !!(kh&&kh.bootOnce&&window.KaihangAppReady" +
            "&&Number(window.APP_VERSION_CODE||0)===" + BuildConfig.VERSION_CODE +
            "&&String(window.BUILD_TIME||'')===" + js(BuildConfig.BUILD_TIME) +
            ");})();";
        view.evaluateJavascript(probeScript, value -> {
            String currentUrl = safe(view.getUrl());
            String resolvedUrl = currentUrl.isEmpty() ? safe(expectedUrl) : currentUrl;
            boolean runtimeReusable = "true".equalsIgnoreCase(safe(value).replace("\"", "").trim());
            if (!runtimeReusable) {
                continueRuntimeInjection(view, resolvedUrl, trigger);
                return;
            }
            appendVerboseNativeLog("复用已注入 runtime: mode=" + getCurrentInjectionMode() + ", trigger=" + trigger + ", url=" + resolvedUrl);
            setNativePageReadyState("loading", resolvedUrl);
            view.post(() -> view.evaluateJavascript(
                "window.__khClientRuntime&&window.__khClientRuntime.bootOnce&&window.__khClientRuntime.bootOnce()" +
                    ".then(function(){return window.__khClientRuntime.refreshCurrentPage&&window.__khClientRuntime.refreshCurrentPage(false);})" +
                    ".catch(function(err){window.log&&window.log('复用 runtime 刷新失败: '+String(err&&err.message||err||'unknown'),'warn');});",
                null
            ));
            if ("commit_loaded".equals(getCurrentInjectionMode()) && trigger == InjectionTrigger.PAGE_COMMIT_VISIBLE) {
                scheduleInjectionRecoveryCheck(view, resolvedUrl, 1200L);
            }
        });
    }

    private void scheduleInjectionRecoveryCheck(WebView view, String expectedUrl, long delayMs) {
        if (view == null) {
            return;
        }
        view.postDelayed(() -> {
            if (bridge == null || bridge.getWebView() != view) {
                return;
            }
            String latestUrl = safe(view.getUrl());
            if (!safe(expectedUrl).equals(latestUrl)) {
                return;
            }
            if ("ready".equals(nativePageReadyState)) {
                return;
            }
            appendNativeLog("稳妥模式兜底: commit 后页面仍未就绪，自动补一次强制初始化 @" + latestUrl);
            injectClientTypeHeader(view, true);
        }, Math.max(200L, delayMs));
    }

    private boolean shouldInjectForTrigger(InjectionTrigger trigger) {
        String mode = getCurrentInjectionMode();
        if (trigger == InjectionTrigger.MANUAL) {
            return true;
        }
        if ("manual".equals(mode)) {
            return false;
        }
        if ("loaded_only".equals(mode)) {
            return trigger == InjectionTrigger.PAGE_LOADED;
        }
        if ("commit_loaded".equals(mode)) {
            return trigger == InjectionTrigger.PAGE_COMMIT_VISIBLE || trigger == InjectionTrigger.PAGE_LOADED;
        }
        return true;
    }

    private String getCurrentInjectionMode() {
        return ClientConfigPlugin.getSavedInjectionMode(this);
    }

    private boolean isRuntimeReuseEnabled() {
        return ClientConfigPlugin.getSavedConfig(this).optBoolean("enableRuntimeReuse", true);
    }

    private void injectClientTypeHeader(WebView view, boolean force) {
        if (view == null) {
            return;
        }
        String url = safe(view.getUrl());
        long now = System.currentTimeMillis();
        boolean sameUrlRecently = url.equals(lastInjectedUrl) && (now - lastInjectAtMs) < 1200L;
        if (sameUrlRecently && !force) {
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
        nativeControlButton.setOnClickListener(v -> {
            if (!"ready".equals(nativePageReadyState)) {
                appendNativeLog("点击悬浮球: 页面未就绪，先尝试重新初始化，同时保持原生菜单可用");
                triggerRuntimeInitialization(true);
                android.widget.Toast.makeText(this, "网页未就绪，可直接打开设置或检查更新", android.widget.Toast.LENGTH_SHORT).show();
            }
            showNativeControlMenu(v);
        });

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
        menu.getMenu().add(0, 1, 0, "重新初始化");
        menu.getMenu().add(0, 2, 1, "扫码");
        menu.getMenu().add(0, 3, 2, "客户端设置");
        menu.getMenu().add(0, 4, 3, "原生配置");
        menu.getMenu().add(0, 5, 4, "检查更新");
        menu.getMenu().add(0, 6, 5, "日志");
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                appendNativeLog("触发原生菜单: 重新初始化");
                triggerRuntimeInitialization(true);
                android.widget.Toast.makeText(this, "正在重新初始化页面动作…", android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
            if (id == 2) {
                appendNativeLog("触发原生菜单: 扫码");
                triggerNativeScan();
                return true;
            }
            if (id == 3) {
                appendNativeLog("打开客户端设置面板");
                openClientRuntimeSettings();
                return true;
            }
            if (id == 4) {
                appendNativeLog("打开原生配置");
                showNativeSettingsDialog();
                return true;
            }
            if (id == 5) {
                appendNativeLog("触发原生更新检查");
                showNativeUpdateDialog();
                return true;
            }
            if (id == 6) {
                appendNativeLog("打开运行日志");
                showNativeLogDialog();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void triggerRuntimeInitialization(boolean forceRefresh) {
        if (bridge == null || bridge.getWebView() == null) {
            appendNativeLog("页面初始化失败: WebView 不可用");
            return;
        }
        WebView webView = bridge.getWebView();
        setNativePageReadyState("loading", "manual init");
        injectClientTypeHeader(webView, true);
        String command = forceRefresh
            ? "window.__khClientRuntime&&window.__khClientRuntime.bootOnce&&window.__khClientRuntime.bootOnce().then(function(){return window.__khClientRuntime.refreshCurrentPage&&window.__khClientRuntime.refreshCurrentPage(true);}).catch(function(err){window.log&&window.log('手动初始化失败: '+String(err&&err.message||err||'unknown'),'err');});"
            : "window.__khClientRuntime&&window.__khClientRuntime.bootOnce&&window.__khClientRuntime.bootOnce();";
        webView.postDelayed(() -> webView.evaluateJavascript(command, null), 220);
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
                injectClientTypeHeader(webView, true);
                webView.postDelayed(() -> webView.evaluateJavascript(command, null), 180);
            }
        ));
    }

    private void openClientRuntimeSettings() {
        if (bridge == null || bridge.getWebView() == null) {
            appendNativeLog("打开客户端设置失败: WebView 不可用，回退到原生配置");
            showNativeSettingsDialog();
            return;
        }
        setNativePageReadyState("loading", "open settings");
        runClientRuntimeCommand(
            "(function(){"
                + "if(!window.__khClientRuntime||!window.__khClientRuntime.openSettingsPanel){"
                + "window.log&&window.log('客户端设置面板未就绪，回退原生配置','warn');"
                + "return false;"
                + "}"
                + "window.__khClientRuntime.openSettingsPanel();"
                + "return true;"
            + "})();"
        );
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
            runOnUiThread(() -> openClientRuntimeSettings());
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
                nativePrintBridgeCallCount += 1;
                String compactText = safe(textValue).replace("\r", " ").replace("\n", "\\n");
                if (compactText.length() > 120) compactText = compactText.substring(0, 120) + "…";
                appendNativeLog(
                    "原生打印桥调用#" + nativePrintBridgeCallCount
                        + ": barcode=" + safe(barcodeValue)
                        + ", qrcode=" + safe(qrCodeValue)
                        + ", text=" + compactText
                        + ", paperType=" + safe(paperType)
                        + ", layout=" + safe(layoutPreset)
                );
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

    private void appendVerboseNativeLog(String message) {
        if (!isVerboseRuntimeLoggingEnabled()) {
            return;
        }
        appendNativeLog(message);
    }

    private boolean isVerboseRuntimeLoggingEnabled() {
        return ClientConfigPlugin.getSavedConfig(this).optBoolean("enableVerboseLogs", true);
    }

    private void showNativeSettingsDialog() {
        NativeSettingsDialog.show(this, ClientConfigPlugin.getSavedConfig(this), DEFAULT_SERVER_BASE, DEFAULT_UPDATE_BASE, new NativeSettingsDialog.Callbacks() {
            @Override
            public String normalizeBaseUrl(String value, String fallback) {
                return MainActivity.this.normalizeBaseUrl(value, fallback);
            }

            @Override
            public void toast(String message) {
                MainActivity.this.toast(message);
            }

            @Override
            public void onSave(NativeSettingsDialog.SettingsValues values) {
                ClientConfigPlugin.saveConfig(
                    MainActivity.this,
                    values.serverBase,
                    values.updateBase,
                    values.paperType,
                    values.layout,
                    values.injectionMode,
                    values.enableFloatingLogs,
                    values.enableVerboseLogs,
                    values.enableNetworkHeaderPatch,
                    values.enableHistoryPatch,
                    values.enableStoragePatch,
                    values.enableUiReadyObserver,
                    values.enableActionObserver,
                    values.enableRuntimeReuse
                );
                appendNativeLog(values.buildSaveSummary());
                toast("配置已保存，应用即将重启");
                nativeControlButton.postDelayed(() -> ClientConfigPlugin.restartApp(MainActivity.this), 300);
            }
        });
    }

    private void showNativeUpdateDialog() {
        com.getcapacitor.JSObject config = ClientConfigPlugin.getSavedConfig(this);
        String updateBase = normalizeBaseUrl(config.optString("updateBase", DEFAULT_UPDATE_BASE), DEFAULT_UPDATE_BASE);
        NativeUpdateHelper.showUpdateDialog(this, updateBase, new NativeUpdateHelper.Callbacks() {
            @Override
            public void appendLog(String message) {
                MainActivity.this.appendNativeLog(message);
            }

            @Override
            public void toast(String message) {
                MainActivity.this.toast(message);
            }
        });
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

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private String buildClientRuntimeScript(String currentUrl) {
        return ClientRuntimeScriptBuilder.build(
            this,
            currentUrl,
            BuildConfig.BUILD_TIME,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            DEFAULT_PAGE_ACTIONS_API_PATH,
            DEFAULT_SERVER_BASE,
            DEFAULT_UPDATE_BASE,
            NOCOBASE_STORAGE_PREFIX,
            DEFAULT_STORAGE_APP_NAME
        );
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
