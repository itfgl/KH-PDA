package com.kaihang.scanner;

import android.content.Intent;
import android.net.Uri;
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
    private static final String DEFAULT_SERVER_BASE = "http://192.168.2.60:8080";
    private static final String DEFAULT_UPDATE_BASE = "http://192.168.2.138:9000";
    private static final long SCAN_RELEASE_TIMEOUT_MS = 8000L;
    private static final int REQUEST_EXPORT_LOGS = 8421;
    private static final int REQUEST_CAMERA_SCAN = 8422;
    private static final int REQUEST_CAMERA_UPLOAD = 8423;
    private static final int REQUEST_FILE_CHOOSER = 8424;
    private NativeControlOverlay nativeControlOverlay;
    private final java.util.List<String> nativeLogLines = new java.util.ArrayList<>();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingScanRelease;
    private boolean nativeScanActive = false;
    private boolean pageHasScanAction = false;
    private boolean pdaScannerAvailable = false;
    private boolean pdaPrinterAvailable = false;
    private boolean deviceCapabilitiesResolved = false;
    private boolean cameraAvailable = false;
    private String nativePageReadyState = "loading";
    private String lastInjectedUrl = "";
    private long lastInjectAtMs = 0L;
    private String pendingLogExportText;
    private android.webkit.ValueCallback<Uri[]> pendingFileChooserCallback;
    private Uri pendingCameraUploadUri;

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
        pdaScannerAvailable = ScanPlugin.isHardwareScannerAvailable(this);
        pdaPrinterAvailable = PrintPlugin.isNativeConnected();
        deviceCapabilitiesResolved = pdaScannerAvailable || pdaPrinterAvailable;
        cameraAvailable = getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY);
        appendNativeLog("应用启动: version=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + "), buildTime=" + BuildConfig.BUILD_TIME);
        appendNativeLog(
            "设备能力初检: pdaScan=" + pdaScannerAvailable
                + ", pdaPrint=" + pdaPrinterAvailable
                + ", camera=" + cameraAvailable
        );
        PrintPlugin.setNativeEventSink(new PrintPlugin.PrintEventSink() {
            @Override
            public void onConnection(String connection) {
                if ("connected".equals(connection)) {
                    pdaPrinterAvailable = true;
                    deviceCapabilitiesResolved = true;
                } else if ("failed".equals(connection) || "closed".equals(connection)) {
                    deviceCapabilitiesResolved = true;
                }
                runOnUiThread(() -> updateNativeScanActionVisibility());
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
        mainHandler.postDelayed(() -> {
            if (deviceCapabilitiesResolved) return;
            pdaPrinterAvailable = PrintPlugin.isNativeConnected();
            deviceCapabilitiesResolved = true;
            appendNativeLog(
                "设备能力探测超时，采用当前结果: pdaScan=" + pdaScannerAvailable
                    + ", pdaPrint=" + pdaPrinterAvailable
            );
            updateNativeScanActionVisibility();
        }, 8500L);

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
        nativeControlOverlay = NativeControlOverlay.attach(this, nativeControlHost);

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
            public boolean onShowFileChooser(
                WebView webView,
                android.webkit.ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams
            ) {
                if (pendingFileChooserCallback != null) {
                    pendingFileChooserCallback.onReceiveValue(null);
                }
                pendingFileChooserCallback = filePathCallback;
                boolean capturePhoto = fileChooserParams != null && fileChooserParams.isCaptureEnabled();
                if (capturePhoto && cameraAvailable) {
                    return launchCameraUpload();
                }
                if (cameraAvailable) {
                    showUploadSourceChooser(fileChooserParams);
                    return true;
                }
                return launchFileUploadChooser(fileChooserParams);
            }

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
                if (consoleMessage != null && !isFrameworkConsoleNoise(consoleMessage)) {
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
            refreshRuntimeAfterResume();
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

    /**
     * 框架噪音过滤：NocoBase 与 React 框架包（vendors-* 和 umi.js）自身的高频告警不计入运行日志。
     * 这类告警每页数百条（React prop 告警、FlowModel 派发日志、antd 警告、[object Object] 调试输出），
     * 与本 App 无关且会淹没真正有用的日志；业务代码来源的消息不过滤。
     */
    private boolean isFrameworkConsoleNoise(ConsoleMessage consoleMessage) {
        String source = safe(consoleMessage.sourceId());
        boolean fromFrameworkBundle = source.contains("vendors-node_modules") || source.contains("/umi.js");
        if (!fromFrameworkBundle) {
            return false;
        }
        String message = safe(consoleMessage.message()).trim();
        if ("[object Object]".equals(message) || "undefined".equals(message)) {
            return true;
        }
        return message.startsWith("Warning:")
            || message.contains("FlowModel]")
            || message.contains("FlowEngine")
            || message.contains("[antd:")
            || message.contains("Warning: React")
            || message.contains("validateDOMNesting")
            || message.contains("defaultProps");
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAMERA_UPLOAD) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && pendingCameraUploadUri != null) {
                result = new Uri[]{pendingCameraUploadUri};
                appendNativeLog("拍照完成，准备优化后交给网页附件控件: " + pendingCameraUploadUri);
            } else {
                appendNativeLog("已取消附件拍照");
            }
            pendingCameraUploadUri = null;
            compressAndFinishFileChooser(result, "拍照");
            return;
        }
        if (requestCode == REQUEST_FILE_CHOOSER) {
            Uri[] result = resultCode == RESULT_OK ? extractFileChooserUris(data) : null;
            compressAndFinishFileChooser(result, "选择文件");
            return;
        }
        if (requestCode == REQUEST_CAMERA_SCAN) {
            if (resultCode != RESULT_OK || data == null) {
                return;
            }
            String value = safe(data.getStringExtra(CameraScanActivity.EXTRA_SCAN_VALUE)).trim();
            if (value.isEmpty()) return;
            appendNativeLog("收到相机扫码: " + value);
            emitCameraScanToPage(value);
            return;
        }
        if (requestCode != REQUEST_EXPORT_LOGS) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingLogExportText = null;
            appendNativeLog("已取消导出运行日志");
            return;
        }
        Uri targetUri = data.getData();
        String exportText = pendingLogExportText == null ? "" : pendingLogExportText;
        pendingLogExportText = null;
        try (java.io.OutputStream output = getContentResolver().openOutputStream(targetUri, "w")) {
            if (output == null) {
                throw new java.io.IOException("无法打开目标文件");
            }
            output.write(exportText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.flush();
            appendNativeLog("运行日志已导出到本地 TXT");
            toast("运行日志已导出");
        } catch (Exception e) {
            appendNativeLog("导出运行日志失败: " + e.getMessage());
            toast("导出失败: " + safe(e.getMessage()));
        }
    }

    private boolean launchCameraUpload() {
        try {
            java.io.File captureDir = new java.io.File(getCacheDir(), "photo-uploads");
            if (!captureDir.exists() && !captureDir.mkdirs()) {
                throw new java.io.IOException("无法创建拍照缓存目录");
            }
            java.io.File photoFile = java.io.File.createTempFile("photo_", ".jpg", captureDir);
            Uri photoUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                photoFile
            );
            Intent cameraIntent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            cameraIntent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri);
            cameraIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            cameraIntent.setClipData(android.content.ClipData.newRawUri("photo", photoUri));
            pendingCameraUploadUri = photoUri;
            appendNativeLog("打开相机拍摄附件");
            startActivityForResult(cameraIntent, REQUEST_CAMERA_UPLOAD);
            return true;
        } catch (Exception error) {
            appendNativeLog("打开附件拍照失败: " + error.getMessage());
            toast("打开相机失败: " + safe(error.getMessage()));
            pendingCameraUploadUri = null;
            finishFileChooser(null);
            return true;
        }
    }

    private void showUploadSourceChooser(WebChromeClient.FileChooserParams params) {
        appendNativeLog("网页上传请求，等待选择拍照或文件");
        new android.app.AlertDialog.Builder(this)
            .setTitle("上传附件")
            .setItems(new String[]{"拍照", "选择文件"}, (dialog, which) -> {
                if (which == 0) {
                    launchCameraUpload();
                } else {
                    launchFileUploadChooser(params);
                }
            })
            .setNegativeButton("取消", (dialog, which) -> finishFileChooser(null))
            .setOnCancelListener(dialog -> finishFileChooser(null))
            .show();
    }

    private boolean launchFileUploadChooser(WebChromeClient.FileChooserParams params) {
        try {
            boolean allowMultiple = params != null
                && params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
            Intent chooserIntent = params == null
                ? new Intent(Intent.ACTION_GET_CONTENT).setType("*/*")
                : params.createIntent();
            // Some OEM document pickers do not honor WebView's chooser mode unless the
            // standard Android multiple-selection extra is present on the final intent.
            chooserIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
            appendNativeLog(
                "打开文件选择器: mode=" + (params == null ? "null" : params.getMode())
                    + ", multiple=" + allowMultiple
                    + ", accept=" + java.util.Arrays.toString(
                        params == null ? new String[0] : params.getAcceptTypes()
                    )
            );
            startActivityForResult(chooserIntent, REQUEST_FILE_CHOOSER);
            return true;
        } catch (Exception error) {
            appendNativeLog("打开文件选择器失败: " + error.getMessage());
            finishFileChooser(null);
            return true;
        }
    }

    private void finishFileChooser(Uri[] result) {
        android.webkit.ValueCallback<Uri[]> callback = pendingFileChooserCallback;
        pendingFileChooserCallback = null;
        if (callback != null) {
            callback.onReceiveValue(result);
        }
    }

    private Uri[] extractFileChooserUris(Intent data) {
        return ImageUploadHelper.extractFileChooserUris(this, data, imageUploadLogger);
    }

    private final ImageUploadHelper.Logger imageUploadLogger = new ImageUploadHelper.Logger() {
        @Override
        public void appendLog(String message) {
            appendNativeLog(message);
        }

        @Override
        public void appendVerboseLog(String message) {
            appendVerboseNativeLog(message);
        }
    };

    private final NativeControlOverlay.Host nativeControlHost = new NativeControlOverlay.Host() {
        @Override
        public boolean isCameraScanEntryAvailable() {
            return MainActivity.this.isCameraScanEntryAvailable();
        }

        @Override
        public void onFabClick(android.view.View anchor) {
            if (!"ready".equals(nativePageReadyState)) {
                appendNativeLog("点击悬浮球: 页面未就绪，先尝试重新初始化，同时保持原生菜单可用");
                triggerRuntimeInitialization(true);
                android.widget.Toast.makeText(MainActivity.this, "网页未就绪，可直接打开设置或检查更新", android.widget.Toast.LENGTH_SHORT).show();
            }
            showNativeControlMenu(anchor);
        }

        @Override
        public void onScanClick() {
            if (nativeScanActive && !isCameraScanEntryAvailable()) {
                appendNativeLog("点击原生扫码按钮: 停扫");
                stopNativeScan();
            } else {
                appendNativeLog("点击原生扫码按钮: 扫码");
                triggerPreferredScan();
            }
        }

        @Override
        public void appendLog(String message) {
            appendNativeLog(message);
        }

        @Override
        public void appendVerboseLog(String message) {
            appendVerboseNativeLog(message);
        }
    };

    private void compressAndFinishFileChooser(Uri[] result, String sourceLabel) {
        android.webkit.ValueCallback<Uri[]> callback = pendingFileChooserCallback;
        pendingFileChooserCallback = null;
        if (callback == null) {
            return;
        }
        if (result == null || result.length == 0) {
            callback.onReceiveValue(result);
            return;
        }
        new Thread(() -> {
            Uri[] prepared = new Uri[result.length];
            for (int index = 0; index < result.length; index++) {
                Uri original = result[index];
                try {
                    prepared[index] = ImageUploadHelper.prepareImageForUpload(this, original, imageUploadLogger);
                } catch (Exception error) {
                    prepared[index] = original;
                    appendNativeLog(sourceLabel + "图片优化失败，使用原文件: " + safe(error.getMessage()));
                }
            }
            mainHandler.post(() -> callback.onReceiveValue(prepared));
        }, "kh-image-upload-compression").start();
    }

    private void handleRuntimeInjection(WebView view, String targetUrl, InjectionTrigger trigger) {
        String url = safe(targetUrl);
        if (url.isEmpty() && view != null) {
            url = safe(view.getUrl());
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
            appendVerboseNativeLog("复用已注入 runtime: trigger=" + trigger + ", url=" + resolvedUrl);
            setNativePageReadyState("loading", resolvedUrl);
            view.post(() -> view.evaluateJavascript(
                "window.__khClientRuntime&&window.__khClientRuntime.bootOnce&&window.__khClientRuntime.bootOnce()" +
                    ".then(function(){return window.__khClientRuntime.refreshCurrentPage&&window.__khClientRuntime.refreshCurrentPage(false);})" +
                    ".catch(function(err){window.log&&window.log('复用 runtime 刷新失败: '+String(err&&err.message||err||'unknown'),'warn');});",
                null
            ));
        });
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
        // 350ms 兜底：探测 runtime 存活则只跑 bootOnce+refresh（省一次 158KB 全量注入）；
        // 探测失败时传 PAGE_LOADED（force=true）强制重注——必须绕过 injectClientTypeHeader 的
        // 1200ms 同 URL 去重闸门，否则定制 ROM 上首次注入静默失败后兜底会被闸门吞掉（冷启动 runtime 缺失）
        view.postDelayed(() -> {
            String latestUrl = safe(view.getUrl());
            if (latestUrl.equals(url)) {
                probeReusableRuntime(view, latestUrl, InjectionTrigger.PAGE_LOADED);
            }
        }, 350);
    }

    private void setNativePageReadyState(String state, String detail) {
        String normalized = safe(state).trim().toLowerCase(java.util.Locale.ROOT);
        if (!"ready".equals(normalized) && !"error".equals(normalized)) {
            normalized = "loading";
        }
        boolean changed = !normalized.equals(nativePageReadyState);
        nativePageReadyState = normalized;
        if (nativeControlOverlay != null) {
            nativeControlOverlay.setPageReadyState(normalized);
        }
        if (changed) {
            appendNativeLog("页面状态: " + normalized + (safe(detail).isEmpty() ? "" : (" - " + detail)));
        }
    }

    private void showNativeControlMenu(android.view.View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "重新初始化");
        menu.getMenu().add(0, 2, 1, isCameraScanEntryAvailable() ? "相机扫码" : "扫码");
        menu.getMenu().add(0, 3, 2, "客户端设置");
        menu.getMenu().add(0, 4, 3, "原生配置");
        menu.getMenu().add(0, 5, 4, "检查更新");
        menu.getMenu().add(0, 6, 5, "日志");
        menu.getMenu().add(0, 7, 6, "重新加载页面");
        menu.setOnDismissListener(menu1 -> {
            if (nativeControlOverlay != null) {
                nativeControlOverlay.scheduleDock();
            }
        });
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
                triggerPreferredScan();
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
            if (id == 7) {
                appendNativeLog("触发原生菜单: 重新加载页面");
                if (bridge != null && bridge.getWebView() != null) {
                    WebView webView = bridge.getWebView();
                    String current = safe(webView.getUrl());
                    // 断网加载失败后 reload() 在该 ROM 上会静默无效（错误页未提交进导航栈），
                    // 且 WebView 可能丢失业务地址（空/about:blank/本地占位页），
                    // 因此统一用 loadUrl 强制发起全新导航
                    boolean validPageUrl = current.startsWith("http")
                        && !current.startsWith("http://localhost")
                        && !current.startsWith("about:");
                    String target = validPageUrl
                        ? current
                        : buildLaunchUrl(ClientConfigPlugin.getSavedServerBase(this, DEFAULT_SERVER_BASE));
                    if (!validPageUrl) {
                        appendNativeLog("当前无有效页面地址，重新加载启动页: " + target);
                    }
                    appendNativeLog("重新加载目标: " + target);
                    webView.loadUrl(target);
                }
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

    private void refreshRuntimeAfterResume() {
        if (bridge == null || bridge.getWebView() == null) {
            return;
        }
        WebView webView = bridge.getWebView();
        String currentUrl = safe(webView.getUrl());
        setNativePageReadyState("loading", currentUrl.isEmpty() ? "activity resume" : currentUrl);
        String script =
            "(function(){"
                + "var kh=window.__khClientRuntime;"
                + "if(!kh){return 'missing-runtime';}"
                + "if(document&&document.visibilityState&&document.visibilityState!=='visible'){return 'hidden';}"
                + "if(kh.pageApplyState==='ready'&&kh.reportPageReadyState){"
                    + "kh.reportPageReadyState('ready','activity resume');"
                    + "if(kh.schedulePageActionRefresh){kh.schedulePageActionRefresh(false);}"
                    + "return 'reported-ready';"
                + "}"
                + "if(window.KaihangAppReady&&window.KaihangAppReady.refresh){"
                    + "window.KaihangAppReady.refresh(false);"
                    + "return 'refresh-requested';"
                + "}"
                + "if(kh.refreshCurrentPage){"
                    + "kh.refreshCurrentPage(false);"
                    + "return 'refresh-current-page';"
                + "}"
                + "return 'noop';"
            + "})();";
        webView.post(() -> webView.evaluateJavascript(script, value ->
            appendVerboseNativeLog("前台恢复 runtime 检查结果: " + safe(value))
        ));
    }

    private void attachNativeWebBridge(WebView webView) {
        webView.addJavascriptInterface(new NativeWebBridge(this, nativeWebBridgeHost), "KaihangNativeBridge");
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

    private void triggerPreferredScan() {
        if (isCameraScanEntryAvailable()) {
            startCameraScan();
            return;
        }
        triggerNativeScan();
    }

    private void startCameraScan() {
        if (!cameraAvailable) {
            appendNativeLog("相机扫码不可用: 设备未检测到摄像头");
            toast("当前设备没有可用摄像头");
            return;
        }
        try {
            appendNativeLog("打开后置摄像头扫码");
            startActivityForResult(new Intent(this, CameraScanActivity.class), REQUEST_CAMERA_SCAN);
        } catch (Exception error) {
            appendNativeLog("打开相机扫码失败: " + error.getMessage());
            toast("打开相机失败: " + error.getMessage());
        }
    }

    private void emitCameraScanToPage(String value) {
        if (bridge == null || bridge.getWebView() == null) return;
        String script =
            "(function(){"
                + "var val=" + js(value) + ";"
                + "if(!val)return;"
                + "window.dispatchEvent(new CustomEvent('kh:scan',{detail:{value:val,source:'native-camera'}}));"
            + "})();";
        bridge.getWebView().post(() -> bridge.getWebView().evaluateJavascript(script, null));
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
        pageHasScanAction = visible;
        updateNativeScanActionVisibility();
    }

    private boolean isCameraScanEntryAvailable() {
        return DeviceCapabilityPolicy.shouldShowCameraScanButton(
            pageHasScanAction,
            deviceCapabilitiesResolved,
            pdaScannerAvailable,
            pdaPrinterAvailable,
            cameraAvailable
        );
    }

    private void updateNativeScanActionVisibility() {
        if (nativeControlOverlay == null) return;
        if (!isCameraScanEntryAvailable()) {
            nativeScanActive = false;
        }
        nativeControlOverlay.updateScanButtonVisibility();
    }

    private void setNativeScanActive(boolean active) {
        nativeScanActive = active;
        if (nativeControlOverlay == null) {
            return;
        }
        nativeControlOverlay.setScanActive(active);
    }

    private final NativeWebBridge.Host nativeWebBridgeHost = new NativeWebBridge.Host() {
        @Override
        public void triggerScanStart() {
            triggerNativeScan();
        }

        @Override
        public void triggerScanStop() {
            stopNativeScan();
        }

        @Override
        public void openClientSettings() {
            openClientRuntimeSettings();
        }

        @Override
        public void showUpdateDialog() {
            showNativeUpdateDialog();
        }

        @Override
        public void showLogDialog() {
            showNativeLogDialog();
        }

        @Override
        public void setScanActionVisible(boolean visible) {
            setNativeScanActionVisible(visible);
        }

        @Override
        public void setPageReadyState(String state, String detail) {
            setNativePageReadyState(state, detail);
        }

        @Override
        public void releaseScanAfterResult() {
            if (pendingScanRelease != null) {
                mainHandler.removeCallbacks(pendingScanRelease);
                pendingScanRelease = null;
            }
            setNativeScanActive(false);
            appendNativeLog("扫码结果已返回，自动释放扫码状态");
        }

        @Override
        public boolean shouldPreviewPrint() {
            return deviceCapabilitiesResolved && !pdaPrinterAvailable;
        }

        @Override
        public void appendLog(String message) {
            appendNativeLog(message);
        }

        @Override
        public void toast(String message) {
            MainActivity.this.toast(message);
        }
    };

    // 日志时间格式线程级缓存：避免每条日志都 new SimpleDateFormat（console 高频转发时开销可观）
    private static final ThreadLocal<java.text.SimpleDateFormat> NATIVE_LOG_TIME_FORMAT = new ThreadLocal<java.text.SimpleDateFormat>() {
        @Override
        protected java.text.SimpleDateFormat initialValue() {
            return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
        }
    };

    private void appendNativeLog(String message) {
        // 原生日志默认不记录：详细运行日志开关关闭时静默丢弃（真零日志）；
        // 打开开关后才写入环形缓冲（200 条上限），供运行日志窗口查看与导出
        if (!isVerboseRuntimeLoggingEnabled()) {
            return;
        }
        String line = "[" + NATIVE_LOG_TIME_FORMAT.get().format(new java.util.Date()) + "] " + message;
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

    // verbose 开关 5 秒缓存：每条 console 消息都会查一次，避免每次都全量读取 SharedPreferences 配置
    private volatile boolean cachedVerboseLogs = false;
    private volatile long cachedVerboseLogsAt = 0L;

    private boolean isVerboseRuntimeLoggingEnabled() {
        long now = System.currentTimeMillis();
        if (now - cachedVerboseLogsAt > 5000L) {
            cachedVerboseLogs = ClientConfigPlugin.getSavedConfig(this).optBoolean("enableVerboseLogs", false);
            cachedVerboseLogsAt = now;
        }
        return cachedVerboseLogs;
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
                if (nativeControlOverlay != null) {
                    nativeControlOverlay.postOnFab(() -> ClientConfigPlugin.restartApp(MainActivity.this), 300);
                } else {
                    mainHandler.postDelayed(() -> ClientConfigPlugin.restartApp(MainActivity.this), 300);
                }
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

    private final NativeLogDialog.Host nativeLogHost = new NativeLogDialog.Host() {
        @Override
        public String getNativeLogsText() {
            return nativeLogLines.isEmpty() ? "(暂无原生日志)" : android.text.TextUtils.join("\n", nativeLogLines);
        }

        @Override
        public void clearNativeLogs() {
            nativeLogLines.clear();
        }

        @Override
        public WebView getWebView() {
            return bridge == null ? null : bridge.getWebView();
        }

        @Override
        public void appendLog(String message) {
            appendNativeLog(message);
        }

        @Override
        public void toast(String message) {
            MainActivity.this.toast(message);
        }

        @Override
        public void runOnUiThread(Runnable action) {
            MainActivity.this.runOnUiThread(action);
        }

        @Override
        public void requestExportLogs(String text) {
            pendingLogExportText = text;
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "kaihang-runtime-logs-" + timestamp + ".txt");
            try {
                startActivityForResult(intent, REQUEST_EXPORT_LOGS);
            } catch (Exception e) {
                pendingLogExportText = null;
                appendNativeLog("打开日志保存界面失败: " + e.getMessage());
                toast("无法打开文件保存界面");
            }
        }
    };

    private void showNativeLogDialog() {
        NativeLogDialog.show(this, nativeLogHost);
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
