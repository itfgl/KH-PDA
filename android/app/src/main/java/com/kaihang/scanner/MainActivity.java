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
    private static final String DEFAULT_SERVER_BASE = "http://192.168.2.60:8080";
    private static final String DEFAULT_UPDATE_BASE = "http://192.168.2.138:9000";
    private static final long SCAN_RELEASE_TIMEOUT_MS = 8000L;
    private static final int NATIVE_CONTROL_MARGIN_END_DP = 18;
    private static final int NATIVE_CONTROL_MARGIN_BOTTOM_DP = 24;
    private static final int NATIVE_STATUS_DOT_OFFSET_END_DP = 2;
    private static final int NATIVE_STATUS_DOT_OFFSET_BOTTOM_DP = 46;
    private static final int NATIVE_SCAN_BUTTON_OFFSET_BOTTOM_DP = 68;
    private static final int REQUEST_EXPORT_LOGS = 8421;
    private static final int REQUEST_CAMERA_SCAN = 8422;
    private static final int REQUEST_CAMERA_UPLOAD = 8423;
    private static final int REQUEST_FILE_CHOOSER = 8424;
    private static final long IMAGE_COMPRESSION_MIN_BYTES = 500L * 1024L;
    private static final int IMAGE_COMPRESSION_MAX_LONG_EDGE = 2560;
    private static final int IMAGE_COMPRESSION_JPEG_QUALITY = 88;
    private android.widget.ImageButton nativeControlButton;
    private android.widget.Button nativeScanButton;
    private android.view.View nativeStatusDot;
    private android.widget.FrameLayout nativeControlOverlay;
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
    private int nativePrintBridgeCallCount = 0;
    private int nativeControlMarginEndPx = -1;
    private int nativeControlMarginBottomPx = -1;
    private float nativeControlDragDownRawX = 0f;
    private float nativeControlDragDownRawY = 0f;
    private int nativeControlDragStartEndPx = 0;
    private int nativeControlDragStartBottomPx = 0;
    private boolean nativeControlDragging = false;
    private int nativeControlTouchSlopPx = 0;
    private String pendingLogExportText;
    private android.webkit.ValueCallback<Uri[]> pendingFileChooserCallback;
    private Uri pendingCameraUploadUri;

    private interface CombinedLogCallback {
        void onReady(String text);
    }

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
        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        android.content.ClipData clipData = data == null ? null : data.getClipData();
        int clipCount = clipData == null ? 0 : clipData.getItemCount();
        for (int index = 0; index < clipCount; index++) {
            Uri uri = clipData.getItemAt(index).getUri();
            if (uri != null && seen.add(uri.toString())) {
                uris.add(uri);
            }
        }
        Uri dataUri = data == null ? null : data.getData();
        if (dataUri != null && seen.add(dataUri.toString())) {
            uris.add(dataUri);
        }
        if (uris.isEmpty()) {
            Uri[] parsed = WebChromeClient.FileChooserParams.parseResult(RESULT_OK, data);
            if (parsed != null) {
                for (Uri uri : parsed) {
                    if (uri != null && seen.add(uri.toString())) {
                        uris.add(uri);
                    }
                }
            }
        }
        appendNativeLog(
            "文件选择结果: clipCount=" + clipCount
                + ", dataUri=" + (dataUri != null)
                + ", resolved=" + uris.size()
        );
        return uris.isEmpty() ? null : uris.toArray(new Uri[0]);
    }

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
                    prepared[index] = prepareImageForUpload(original);
                } catch (Exception error) {
                    prepared[index] = original;
                    appendNativeLog(sourceLabel + "图片优化失败，使用原文件: " + safe(error.getMessage()));
                }
            }
            mainHandler.post(() -> callback.onReceiveValue(prepared));
        }, "kh-image-upload-compression").start();
    }

    private Uri prepareImageForUpload(Uri sourceUri) throws java.io.IOException {
        if (sourceUri == null) {
            return null;
        }
        String mimeType = safe(getContentResolver().getType(sourceUri)).toLowerCase(java.util.Locale.ROOT);
        String pathHint = safe(sourceUri.getLastPathSegment()).toLowerCase(java.util.Locale.ROOT);
        boolean isJpeg = mimeType.equals("image/jpeg") || pathHint.endsWith(".jpg") || pathHint.endsWith(".jpeg");
        boolean isPng = mimeType.equals("image/png") || pathHint.endsWith(".png");
        boolean isCompressibleImage = isJpeg
            || isPng
            || mimeType.equals("image/webp")
            || mimeType.equals("image/heic")
            || mimeType.equals("image/heif")
            || pathHint.endsWith(".webp")
            || pathHint.endsWith(".heic")
            || pathHint.endsWith(".heif");
        if (!isCompressibleImage) {
            return sourceUri;
        }

        long originalBytes = resolveContentLength(sourceUri);
        if (originalBytes >= 0 && originalBytes < IMAGE_COMPRESSION_MIN_BYTES) {
            appendVerboseNativeLog("图片小于压缩阈值，直接上传: bytes=" + originalBytes);
            return sourceUri;
        }

        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream input = getContentResolver().openInputStream(sourceUri)) {
            if (input == null) throw new java.io.IOException("无法读取图片");
            android.graphics.BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return sourceUri;
        }
        int sourceLongEdge = Math.max(bounds.outWidth, bounds.outHeight);
        if (isPng && sourceLongEdge <= IMAGE_COMPRESSION_MAX_LONG_EDGE) {
            return sourceUri;
        }

        android.graphics.BitmapFactory.Options decodeOptions = new android.graphics.BitmapFactory.Options();
        decodeOptions.inSampleSize = 1;
        while (sourceLongEdge / (decodeOptions.inSampleSize * 2) > IMAGE_COMPRESSION_MAX_LONG_EDGE) {
            decodeOptions.inSampleSize *= 2;
        }
        android.graphics.Bitmap bitmap;
        try (java.io.InputStream input = getContentResolver().openInputStream(sourceUri)) {
            if (input == null) throw new java.io.IOException("无法读取图片像素");
            bitmap = android.graphics.BitmapFactory.decodeStream(input, null, decodeOptions);
        }
        if (bitmap == null) {
            return sourceUri;
        }

        android.graphics.Bitmap transformed = applyExifOrientation(bitmap, sourceUri);
        if (transformed != bitmap) bitmap.recycle();
        android.graphics.Bitmap resized = resizeBitmapToLongEdge(transformed, IMAGE_COMPRESSION_MAX_LONG_EDGE);
        if (resized != transformed) transformed.recycle();

        java.io.File outputDir = new java.io.File(getCacheDir(), "photo-uploads/compressed");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            resized.recycle();
            throw new java.io.IOException("无法创建图片压缩缓存目录");
        }
        boolean preservePng = isPng || resized.hasAlpha();
        java.io.File outputFile = java.io.File.createTempFile("upload_", preservePng ? ".png" : ".jpg", outputDir);
        boolean encoded;
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(outputFile)) {
            encoded = resized.compress(
                preservePng ? android.graphics.Bitmap.CompressFormat.PNG : android.graphics.Bitmap.CompressFormat.JPEG,
                preservePng ? 100 : IMAGE_COMPRESSION_JPEG_QUALITY,
                output
            );
            output.flush();
        } finally {
            resized.recycle();
        }
        if (!encoded) {
            outputFile.delete();
            return sourceUri;
        }
        long compressedBytes = outputFile.length();
        if (originalBytes >= 0 && compressedBytes >= originalBytes) {
            outputFile.delete();
            appendVerboseNativeLog("图片优化后未变小，继续使用原文件: before=" + originalBytes + ", after=" + compressedBytes);
            return sourceUri;
        }
        Uri outputUri = androidx.core.content.FileProvider.getUriForFile(
            this,
            getPackageName() + ".fileprovider",
            outputFile
        );
        appendNativeLog(
            "上传图片已优化: before=" + originalBytes
                + ", after=" + compressedBytes
                + ", bounds=" + bounds.outWidth + "x" + bounds.outHeight
                + ", format=" + (preservePng ? "PNG" : "JPEG")
        );
        return outputUri;
    }

    private long resolveContentLength(Uri uri) {
        try (android.content.res.AssetFileDescriptor descriptor = getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return descriptor == null ? -1L : descriptor.getLength();
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private android.graphics.Bitmap applyExifOrientation(android.graphics.Bitmap source, Uri uri) {
        int orientation = android.media.ExifInterface.ORIENTATION_NORMAL;
        try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
            if (input != null) {
                android.media.ExifInterface exif = new android.media.ExifInterface(input);
                orientation = exif.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                );
            }
        } catch (Exception ignored) {}
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        switch (orientation) {
            case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case android.media.ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case android.media.ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90f);
                break;
            default:
                return source;
        }
        try {
            return android.graphics.Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        } catch (Exception ignored) {
            return source;
        }
    }

    private android.graphics.Bitmap resizeBitmapToLongEdge(android.graphics.Bitmap source, int maxLongEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longEdge = Math.max(width, height);
        if (longEdge <= maxLongEdge) {
            return source;
        }
        float scale = (float) maxLongEdge / (float) longEdge;
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        return android.graphics.Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
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
        nativeControlOverlay = container;
        nativeControlTouchSlopPx = android.view.ViewConfiguration.get(this).getScaledTouchSlop();

        nativeControlButton = new android.widget.ImageButton(this);
        nativeControlButton.setImageResource(android.R.drawable.ic_menu_manage);
        nativeControlButton.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        nativeControlButton.setBackground(buildNativeFabBackground());
        nativeControlButton.setColorFilter(android.graphics.Color.WHITE);
        nativeControlButton.setContentDescription("客户端工具");
        int size = dp(56);
        android.widget.FrameLayout.LayoutParams fabParams = new android.widget.FrameLayout.LayoutParams(size, size);
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
        nativeControlButton.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    nativeControlDragDownRawX = event.getRawX();
                    nativeControlDragDownRawY = event.getRawY();
                    nativeControlDragStartEndPx = getNativeControlMarginEndPx();
                    nativeControlDragStartBottomPx = getNativeControlMarginBottomPx();
                    nativeControlDragging = false;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float deltaX = event.getRawX() - nativeControlDragDownRawX;
                    float deltaY = event.getRawY() - nativeControlDragDownRawY;
                    if (!nativeControlDragging) {
                        nativeControlDragging = Math.hypot(deltaX, deltaY) > nativeControlTouchSlopPx;
                    }
                    if (nativeControlDragging) {
                        int nextEnd = Math.round(nativeControlDragStartEndPx - deltaX);
                        int nextBottom = Math.round(nativeControlDragStartBottomPx - deltaY);
                        updateNativeControlAnchor(nextEnd, nextBottom);
                    }
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    nativeControlDragging = false;
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    boolean handledAsClick = !nativeControlDragging;
                    nativeControlDragging = false;
                    if (handledAsClick) {
                        v.performClick();
                    }
                    return true;
                default:
                    return false;
            }
        });

        nativeStatusDot = new android.view.View(this);
        android.widget.FrameLayout.LayoutParams dotParams = new android.widget.FrameLayout.LayoutParams(dp(12), dp(12));
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
        nativeScanButton.setLayoutParams(scanParams);
        nativeScanButton.setPadding(dp(18), 0, dp(18), 0);
        nativeScanButton.setOnClickListener(v -> {
            if (nativeScanActive && !isCameraScanEntryAvailable()) {
                appendNativeLog("点击原生扫码按钮: 停扫");
                stopNativeScan();
            } else {
                appendNativeLog("点击原生扫码按钮: 扫码");
                triggerPreferredScan();
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
        updateNativeControlPositions();
        updateNativeStatusDot();
    }

    private int getNativeControlMarginEndPx() {
        if (nativeControlMarginEndPx < 0) {
            nativeControlMarginEndPx = dp(NATIVE_CONTROL_MARGIN_END_DP);
        }
        return nativeControlMarginEndPx;
    }

    private int getNativeControlMarginBottomPx() {
        if (nativeControlMarginBottomPx < 0) {
            nativeControlMarginBottomPx = dp(NATIVE_CONTROL_MARGIN_BOTTOM_DP);
        }
        return nativeControlMarginBottomPx;
    }

    private void updateNativeControlAnchor(int marginEndPx, int marginBottomPx) {
        nativeControlMarginEndPx = clampNativeControlHorizontalMargin(marginEndPx);
        nativeControlMarginBottomPx = clampNativeControlVerticalMargin(marginBottomPx);
        updateNativeControlPositions();
    }

    private int clampNativeControlHorizontalMargin(int requestedPx) {
        int overlayWidth = nativeControlOverlay != null ? nativeControlOverlay.getWidth() : 0;
        int buttonWidth = nativeControlButton != null ? nativeControlButton.getWidth() : 0;
        if (overlayWidth <= 0) {
            overlayWidth = getResources().getDisplayMetrics().widthPixels;
        }
        if (buttonWidth <= 0) {
            buttonWidth = dp(56);
        }
        int maxMargin = Math.max(0, overlayWidth - buttonWidth);
        return Math.max(0, Math.min(requestedPx, maxMargin));
    }

    private int clampNativeControlVerticalMargin(int requestedPx) {
        int overlayHeight = nativeControlOverlay != null ? nativeControlOverlay.getHeight() : 0;
        int buttonHeight = nativeControlButton != null ? nativeControlButton.getHeight() : 0;
        if (overlayHeight <= 0) {
            overlayHeight = getResources().getDisplayMetrics().heightPixels;
        }
        if (buttonHeight <= 0) {
            buttonHeight = dp(56);
        }
        int maxMargin = Math.max(0, overlayHeight - buttonHeight);
        return Math.max(0, Math.min(requestedPx, maxMargin));
    }

    private void updateNativeControlPositions() {
        if (nativeControlButton == null || nativeScanButton == null || nativeStatusDot == null) {
            return;
        }
        int end = getNativeControlMarginEndPx();
        int bottom = getNativeControlMarginBottomPx();

        android.widget.FrameLayout.LayoutParams fabParams =
            (android.widget.FrameLayout.LayoutParams) nativeControlButton.getLayoutParams();
        fabParams.gravity = android.view.Gravity.END | android.view.Gravity.BOTTOM;
        fabParams.setMargins(0, 0, end, bottom);
        nativeControlButton.setLayoutParams(fabParams);

        android.widget.FrameLayout.LayoutParams scanParams =
            (android.widget.FrameLayout.LayoutParams) nativeScanButton.getLayoutParams();
        scanParams.gravity = android.view.Gravity.END | android.view.Gravity.BOTTOM;
        scanParams.setMargins(0, 0, end, bottom + dp(NATIVE_SCAN_BUTTON_OFFSET_BOTTOM_DP));
        nativeScanButton.setLayoutParams(scanParams);

        android.widget.FrameLayout.LayoutParams dotParams =
            (android.widget.FrameLayout.LayoutParams) nativeStatusDot.getLayoutParams();
        dotParams.gravity = android.view.Gravity.END | android.view.Gravity.BOTTOM;
        dotParams.setMargins(0, 0, end + dp(NATIVE_STATUS_DOT_OFFSET_END_DP), bottom + dp(NATIVE_STATUS_DOT_OFFSET_BOTTOM_DP));
        nativeStatusDot.setLayoutParams(dotParams);
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
        menu.getMenu().add(0, 2, 1, isCameraScanEntryAvailable() ? "相机扫码" : "扫码");
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
        if (nativeScanButton == null) return;
        boolean showCameraButton = isCameraScanEntryAvailable();
        nativeScanButton.setVisibility(showCameraButton ? android.view.View.VISIBLE : android.view.View.GONE);
        if (showCameraButton) {
            nativeScanButton.setText("相机扫码");
            nativeScanButton.setBackground(buildNativeCapsuleBackground(false));
            nativeScanButton.bringToFront();
        } else {
            setNativeScanActive(false);
        }
    }

    private void setNativeScanActive(boolean active) {
        nativeScanActive = active;
        if (nativeScanButton == null) {
            return;
        }
        nativeScanButton.setText(isCameraScanEntryAvailable() ? "相机扫码" : (active ? "停扫" : "扫码"));
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
        public String getClientConfig() {
            return ClientConfigPlugin.getSavedConfig(MainActivity.this).toString();
        }

        @JavascriptInterface
        public String saveClientConfig(String payloadJson) {
            try {
                org.json.JSONObject payload = new org.json.JSONObject(payloadJson == null ? "{}" : payloadJson);
                com.getcapacitor.JSObject current = ClientConfigPlugin.getSavedConfig(MainActivity.this);
                com.getcapacitor.JSObject saved = ClientConfigPlugin.saveConfig(
                    MainActivity.this,
                    payload.optString("serverBase", null),
                    payload.optString("updateBase", null),
                    payload.optString("paperType", null),
                    payload.optString("layoutPreset", null),
                    payload.optString("injectionMode", null),
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
                appendNativeLog("保存客户端配置失败: " + e.getMessage());
                return null;
            }
        }

        @JavascriptInterface
        public void restartApp() {
            runOnUiThread(() -> ClientConfigPlugin.restartApp(MainActivity.this));
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
        public boolean shouldPreviewPrint() {
            return deviceCapabilitiesResolved && !pdaPrinterAvailable;
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
                int qrSize = payload.optInt("qrSize", 0);
                String qrAlign = payload.optString("qrAlign", "center");
                int textColumns = payload.optInt("textColumns", 1);
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
                        + ", qrSize=" + qrSize
                        + ", qrAlign=" + safe(qrAlign)
                        + ", textColumns=" + textColumns
                );
                if (shouldPreviewPrint()) {
                    appendNativeLog("原生打印桥检测到无打印机，强制转为标签预览");
                    runOnUiThread(() -> {
                        toast("正在生成标签预览…");
                        PrintPlugin.previewLabelNative(
                            MainActivity.this,
                            MainActivity.this,
                            barcodeValue,
                            qrCodeValue,
                            textValue,
                            layoutPreset,
                            qrSize,
                            qrAlign,
                            textColumns
                        );
                    });
                    return true;
                }
                runOnUiThread(() -> PrintPlugin.printLabelNative(
                    MainActivity.this,
                    MainActivity.this,
                    barcodeValue,
                    qrCodeValue,
                    textValue,
                    paperType,
                    layoutPreset,
                    qrSize,
                    qrAlign,
                    textColumns
                ));
                return true;
            } catch (Exception e) {
                appendNativeLog("原生打印桥参数解析失败: " + e.getMessage());
                return false;
            }
        }

        @JavascriptInterface
        public boolean previewLabel(String payloadJson) {
            try {
                org.json.JSONObject payload = new org.json.JSONObject(payloadJson == null ? "{}" : payloadJson);
                String barcodeValue = payload.optString("barcodeValue", "");
                String qrCodeValue = payload.optString("qrCodeValue", "");
                String textValue = payload.optString("textValue", "");
                String layoutPreset = payload.optString("layoutPreset", "standard");
                int qrSize = payload.optInt("qrSize", 0);
                String qrAlign = payload.optString("qrAlign", "center");
                int textColumns = payload.optInt("textColumns", 1);
                appendNativeLog(
                    "无打印机，生成标签预览: barcode=" + safe(barcodeValue)
                        + ", qrcode=" + safe(qrCodeValue)
                        + ", layout=" + safe(layoutPreset)
                        + ", qrSize=" + qrSize
                        + ", qrAlign=" + safe(qrAlign)
                        + ", textColumns=" + textColumns
                );
                runOnUiThread(() -> {
                    toast("正在生成标签预览…");
                    PrintPlugin.previewLabelNative(
                        MainActivity.this,
                        MainActivity.this,
                        barcodeValue,
                        qrCodeValue,
                        textValue,
                        layoutPreset,
                        qrSize,
                        qrAlign,
                        textColumns
                    );
                });
                return true;
            } catch (Exception error) {
                appendNativeLog("标签预览参数解析失败: " + error.getMessage());
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
            .setNegativeButton("导出 TXT", null)
            .setNeutralButton("清空", null)
            .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> clearCombinedLogs(content));
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> exportCombinedLogs());
        });
        dialog.show();
        loadCombinedLogs(content);
    }

    private void loadCombinedLogs(android.widget.TextView targetView) {
        collectCombinedLogs(text -> runOnUiThread(() -> targetView.setText(text)));
    }

    private void collectCombinedLogs(CombinedLogCallback callback) {
        String nativeLogs = nativeLogLines.isEmpty() ? "(暂无原生日志)" : android.text.TextUtils.join("\n", nativeLogLines);
        if (bridge == null || bridge.getWebView() == null) {
            callback.onReady("== 原生日志 ==\n" + nativeLogs + "\n\n== 网页日志 ==\n(网页不可用)");
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
            callback.onReady(text.toString());
        });
    }

    private void clearCombinedLogs(android.widget.TextView targetView) {
        nativeLogLines.clear();
        if (bridge == null || bridge.getWebView() == null) {
            targetView.setText("原生日志已清空；网页当前不可用，网页日志未清理");
            return;
        }
        String script = "(function(){try{var kh=window.__khClientRuntime;if(kh&&kh.clearFloatingLogs){kh.clearFloatingLogs();}else if(window.localStorage){window.localStorage.removeItem('KH_FLOATING_LOGS');}window.__khLastLogRaw='[]';window.__khLastLogSnapshot=[];return true;}catch(e){return false;}})();";
        bridge.getWebView().evaluateJavascript(script, value -> runOnUiThread(() -> {
            boolean webCleared = "true".equalsIgnoreCase(safe(value).replace("\"", "").trim());
            targetView.setText(webCleared ? "原生和网页日志已清空" : "原生日志已清空，网页日志清理失败");
        }));
    }

    private void exportCombinedLogs() {
        appendNativeLog("准备导出运行日志");
        collectCombinedLogs(text -> runOnUiThread(() -> {
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
        }));
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
