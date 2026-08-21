package com.kaihang.scanner.plugins;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.NonNull;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.uc.pdasdk.print.BitmapData;
import com.uc.pdasdk.print.Printer;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONException;

@CapacitorPlugin(name = "PrintPlugin")
public class PrintPlugin extends Plugin {

    private static final String EVENT_STATUS = "printStatus";
    private static final int BATCH_EXTRA_FEED = 96;
    private static final String PAPER_THERMAL = "thermal";
    private static final String PAPER_BLACK_MARK = "black_mark";
    private static final long NATIVE_CONNECT_TIMEOUT_MS = 8000L;
    private static final Object nativeConnectionLock = new Object();
    private static boolean nativeConnected = false;
    private static boolean nativeConnecting = false;
    private static final ExecutorService nativePrintExecutor = Executors.newSingleThreadExecutor();
    private static PrintEventSink nativeEventSink;
    private static final ArrayDeque<PreviewRequest> nativePreviewQueue = new ArrayDeque<>();
    private static boolean nativePreviewShowing = false;
    private boolean isConnected           = false;
    private boolean isConnecting          = false;
    private volatile boolean destroyed    = false;
    /**
     * App 进后台时若处于已连接/连接中状态，置为 true。
     * handleOnResume() 据此决定是否自动重连，避免从未连接过时乱重连。
     */
    private boolean wasConnectedBeforePause = false;
    // 串行执行打印位图生成任务，destroy 时统一 shutdownNow 中断
    private final ExecutorService printExecutor = Executors.newSingleThreadExecutor();

    public interface PrintEventSink {
        void onConnection(String connection);
        void onStatus(String status, String flag);
    }

    public static synchronized void setNativeEventSink(PrintEventSink sink) {
        nativeEventSink = sink;
    }

    public static synchronized boolean isNativeConnected() {
        return nativeConnected;
    }

    private static void emitNativeConnection(String connection) {
        PrintEventSink sink = nativeEventSink;
        if (sink != null) sink.onConnection(connection);
    }

    private static void emitNativeStatus(String status, String flag) {
        PrintEventSink sink = nativeEventSink;
        if (sink != null) sink.onStatus(status, flag);
    }

    private static void emitPrintDiagnostic(String source, String detail) {
        String message = (source == null || source.trim().isEmpty() ? "unknown" : source)
            + " | "
            + (detail == null ? "" : detail);
        debugLog(message);
        emitNativeStatus("PRINT_LAYOUT", message);
    }

    /**
     * logcat 调试输出统一入口：受「详细运行日志」开关控制（默认关），
     * 与 appendNativeLog 同规则，生产环境不落 logcat。
     */
    private static void debugLog(String message) {
        if (!isVerboseLoggingEnabled()) return;
        android.util.Log.d("PrintPlugin", message);
    }

    // verbose 开关 5 秒缓存：打印回调/诊断可能高频触发，避免每次全量读 SharedPreferences
    private static volatile boolean cachedVerboseLogs = false;
    private static volatile long cachedVerboseLogsAt = 0L;
    /** 插件 Context 弱引用（load() 时设置），供静态 debugLog 读取日志开关配置 */
    private static volatile java.lang.ref.WeakReference<android.content.Context> pluginContext = null;

    private static boolean isVerboseLoggingEnabled() {
        long now = System.currentTimeMillis();
        if (now - cachedVerboseLogsAt > 5000L) {
            android.content.Context ctx = pluginContext != null ? pluginContext.get() : null;
            cachedVerboseLogs = ctx != null
                && ClientConfigPlugin.getSavedConfig(ctx).optBoolean("enableVerboseLogs", false);
            cachedVerboseLogsAt = now;
        }
        return cachedVerboseLogs;
    }

    private static final class PreviewRequest {
        final Activity activity;
        final Bitmap bitmap;
        final String diagnostic;

        PreviewRequest(Activity activity, Bitmap bitmap, String diagnostic) {
            this.activity = activity;
            this.bitmap = bitmap;
            this.diagnostic = diagnostic;
        }
    }

    public static synchronized void connectNative(Activity activity) {
        if (activity == null) return;
        if (nativeConnected) {
            emitNativeConnection("connected");
            return;
        }
        if (nativeConnecting) {
            return;
        }
        nativeConnecting = true;
        Printer.connect(
            activity,
            new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(@NonNull Message msg) {
                    switch (msg.what) {
                        case 101:
                            nativeConnecting = false;
                            nativeConnected = true;
                            synchronized (nativeConnectionLock) {
                                nativeConnectionLock.notifyAll();
                            }
                            emitNativeConnection("connected");
                            break;
                        case 102:
                            nativeConnecting = false;
                            nativeConnected = false;
                            synchronized (nativeConnectionLock) {
                                nativeConnectionLock.notifyAll();
                            }
                            emitNativeConnection("failed");
                            break;
                        case 103:
                            nativeConnecting = false;
                            nativeConnected = false;
                            synchronized (nativeConnectionLock) {
                                nativeConnectionLock.notifyAll();
                            }
                            emitNativeConnection("closed");
                            break;
                        default:
                            break;
                    }
                }
            },
            (result, feedbackBytes, flag) -> emitNativeStatus(result.name(), flag)
        );
    }

    private static boolean waitForNativeConnection(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
        synchronized (nativeConnectionLock) {
            while (nativeConnecting && !nativeConnected && System.currentTimeMillis() < deadline) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    nativeConnectionLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return nativeConnected;
        }
    }

    public static synchronized void closeNative(Activity activity) {
        nativeConnecting = false;
        nativeConnected = false;
        synchronized (nativeConnectionLock) {
            nativeConnectionLock.notifyAll();
        }
        if (activity != null) {
            Printer.close(activity);
        }
        emitNativeConnection("closed");
    }

    /**
     * SDK 打印内容前强制 setTop(8)（最小顶部边距 8 点，传更小也会被钳到 8），
     * 裁掉位图顶部 8 点抵消该偏移，保证实纸内容位置与预览位图坐标一致。
     */
    private static final int PRINT_TOP_OFFSET = 8;

    private static Bitmap cropTopOffset(Bitmap source) {
        if (source == null || source.getHeight() <= PRINT_TOP_OFFSET) return source;
        Bitmap cropped = Bitmap.createBitmap(source, 0, PRINT_TOP_OFFSET, source.getWidth(), source.getHeight() - PRINT_TOP_OFFSET);
        if (cropped != source && !source.isRecycled()) source.recycle();
        return cropped;
    }

    private static void printBuiltLabel(Bitmap label, String paperType, String jobName) {
        String normalizedPaperType = normalizePaperType(paperType);
        if (PAPER_BLACK_MARK.equals(normalizedPaperType)) {
            // 黑标纸：同样裁顶 8 点抵消 SDK 强制 setTop(8)，二维码顶部留空从 ~20 点收紧到 ~12 点
            Bitmap printable = cropTopOffset(label);
            Printer.print(new BitmapData(printable, 15, 0), 8, jobName, false);
        } else {
            // 热敏纸：裁顶抵消 SDK 强制 setTop(8)，实纸与预览对齐
            Bitmap printable = cropTopOffset(label);
            Printer.print(new BitmapData(printable, 15, false), 8, BATCH_EXTRA_FEED, jobName, false);
        }
    }

    public static void printLabelNative(Context context, Activity activity, String qrCodeValue, String textValue, String paperType, int qrSize, String qrAlign, int textColumns, String textStylesJson) {
        if (context == null || activity == null) return;
        connectNative(activity);
        nativePrintExecutor.execute(() -> {
            try {
                if (!nativeConnected && !waitForNativeConnection(NATIVE_CONNECT_TIMEOUT_MS)) {
                    emitNativeStatus("PRINT_BRIDGE_ERROR", "printer connect timeout");
                    return;
                }
                String normalizedPaperType = normalizePaperType(paperType);
                LabelLayoutBuilder.BuiltLabel builtLabel = LabelLayoutBuilder.buildLegacyGenericLabel(
                    context,
                    qrCodeValue,
                    textValue,
                    qrSize,
                    qrAlign,
                    textColumns,
                    textStylesJson,
                    "printLabelNativeLegacy"
                );
                emitPrintDiagnostic(
                    "printLabelNative",
                    "paperType=" + normalizedPaperType + ", " + builtLabel.diagnostic
                );
                printBuiltLabel(builtLabel.label, normalizedPaperType, "native_generic_label");
            } catch (Exception e) {
                emitNativeStatus("PRINT_BRIDGE_ERROR", e.getMessage());
            }
        });
    }

    public static void previewLabelNative(
        Context context,
        Activity activity,
        String qrCodeValue,
        String textValue,
        int qrSize,
        String qrAlign,
        int textColumns,
        String textStylesJson
    ) {
        if (context == null || activity == null) return;
        nativePrintExecutor.execute(() -> {
            try {
                LabelLayoutBuilder.BuiltLabel builtLabel = LabelLayoutBuilder.buildPortablePreviewLabel(
                    qrCodeValue,
                    textValue,
                    qrSize,
                    qrAlign,
                    textColumns,
                    textStylesJson,
                    "previewLabelNativeLegacy"
                );
                emitPrintDiagnostic("previewLabelNative", builtLabel.diagnostic);
                activity.runOnUiThread(() -> enqueueNativePreview(activity, builtLabel));
            } catch (Exception error) {
                emitNativeStatus("PRINT_BRIDGE_ERROR", "preview failed: " + error.getMessage());
                activity.runOnUiThread(() -> new androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle("标签预览生成失败")
                    .setMessage(error.getMessage() == null ? "未知错误" : error.getMessage())
                    .setPositiveButton("关闭", null)
                    .show());
            }
        });
    }

    private static void enqueueNativePreview(Activity activity, LabelLayoutBuilder.BuiltLabel builtLabel) {
        nativePreviewQueue.addLast(new PreviewRequest(activity, builtLabel.label, builtLabel.diagnostic));
        showNextNativePreview();
    }

    private static void showNextNativePreview() {
        if (nativePreviewShowing) return;
        PreviewRequest request = nativePreviewQueue.pollFirst();
        if (request == null) return;
        Activity activity = request.activity;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            if (request.bitmap != null && !request.bitmap.isRecycled()) request.bitmap.recycle();
            showNextNativePreview();
            return;
        }
        nativePreviewShowing = true;

        android.widget.LinearLayout content = new android.widget.LinearLayout(activity);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);

        android.widget.TextView sizeText = new android.widget.TextView(activity);
        sizeText.setText(
            "标签图片 " + request.bitmap.getWidth() + " × " + request.bitmap.getHeight() + " px"
                + "\n当前设备未连接打印机，仅生成预览"
        );
        sizeText.setTextColor(android.graphics.Color.parseColor("#344054"));
        sizeText.setTextSize(13);
        sizeText.setPadding(0, 0, 0, padding);
        content.addView(sizeText, new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        android.widget.ImageView imageView = new android.widget.ImageView(activity);
        imageView.setImageBitmap(request.bitmap);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        imageView.setBackgroundColor(android.graphics.Color.WHITE);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(activity);
        scrollView.setFillViewport(true);
        scrollView.addView(imageView, new android.widget.ScrollView.LayoutParams(
            android.widget.ScrollView.LayoutParams.MATCH_PARENT,
            android.widget.ScrollView.LayoutParams.WRAP_CONTENT
        ));
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int availableWidth = Math.max(1, metrics.widthPixels - (padding * 4));
        float previewScale = availableWidth / (float) Math.max(1, request.bitmap.getWidth());
        int desiredPreviewHeight = Math.round(request.bitmap.getHeight() * previewScale);
        int minimumPreviewHeight = Math.round(180 * metrics.density);
        int maximumPreviewHeight = Math.round(metrics.heightPixels * 0.62f);
        int previewHeight = Math.max(
            minimumPreviewHeight,
            Math.min(desiredPreviewHeight, maximumPreviewHeight)
        );
        content.addView(scrollView, new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            previewHeight
        ));

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("标签预览")
            .setView(content)
            .setPositiveButton("关闭", null)
            .create();
        dialog.setOnShowListener(ignored -> emitNativeStatus("PRINT_PREVIEW_READY", request.diagnostic));
        dialog.setOnDismissListener(ignored -> {
            imageView.setImageDrawable(null);
            if (request.bitmap != null && !request.bitmap.isRecycled()) request.bitmap.recycle();
            nativePreviewShowing = false;
            showNextNativePreview();
        });
        dialog.show();
    }

    // ── 连接 ──────────────────────────────────────────────────────────────────

    /**
     * 核心连接逻辑，抽出供 connect() 和 handleOnResume() 共用。
     * 调用前必须确认 !isConnected && !isConnecting。
     */
    private void doConnect() {
        isConnecting = true;
        Printer.connect(
            getActivity(),
            new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(@NonNull Message msg) {
                    if (destroyed) return;
                    JSObject data = new JSObject();
                    switch (msg.what) {
                        case 101:
                            isConnecting = false;
                            isConnected = true;
                            data.put("connection", "connected");
                            break;
                        case 102:
                            isConnecting = false;
                            isConnected = false;
                            data.put("connection", "failed");
                            break;
                        case 103:
                            isConnecting = false;
                            isConnected = false;
                            data.put("connection", "closed");
                            break;
                        default: return;
                    }
                    notifyListeners(EVENT_STATUS, data);
                }
            },
            (result, feedbackBytes, flag) -> {
                if (destroyed) return;
                debugLog("printCallback: " + result.name() + " flag=" + flag);
                JSObject data = new JSObject();
                data.put("status", result.name());
                if (flag != null) data.put("flag", flag);
                notifyListeners(EVENT_STATUS, data);
            }
        );
    }

    @PluginMethod
    public void connect(PluginCall call) {
        if (isConnected) {
            JSObject data = new JSObject();
            data.put("connection", "connected");
            notifyListeners(EVENT_STATUS, data);
            call.resolve();
            return;
        }
        if (isConnecting) {
            call.resolve();
            return;
        }
        doConnect();
        call.resolve();
    }

    // ── 打印 ──────────────────────────────────────────────────────────────────

    private static String getCallString(PluginCall call, String key) {
        try {
            Object value = call.getData().get(key);
            return value == null ? "" : String.valueOf(value);
        } catch (JSONException ignore) {
            return "";
        }
    }

    /**
     * 读取可选的整型参数；缺失、空串或非法时返回 null（由布局层回退默认二维码尺寸）。
     */
    private static Integer getCallOptionalInt(PluginCall call, String key) {
        try {
            Object value = call.getData().get(key);
            if (value == null) return null;
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) return null;
            return Integer.parseInt(text);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String normalizePaperType(String value) {
        return PAPER_BLACK_MARK.equalsIgnoreCase(String.valueOf(value).trim()) ? PAPER_BLACK_MARK : PAPER_THERMAL;
    }

    /**
     * 通用标签：
     * - qrCodeValue: 二维码内容
     * - textValue: 多行正文，支持换行
     * - qrSize: 二维码边长（打印点数），可选，缺省约 154 点（画布宽 40%）
     * - qrAlign: 二维码水平对齐，center（默认）/ left；靠左时右侧空白区放字段
     * - textColumns: 字段列数，1（默认）/ 2；双列时字段行序配对并排
     */
    @PluginMethod
    public void printLabel(PluginCall call) {
        String qrCodeValue = getCallString(call, "qrCodeValue");
        String textValue = getCallString(call, "textValue");
        String paperType = normalizePaperType(getCallString(call, "paperType"));
        Integer qrSize = getCallOptionalInt(call, "qrSize");
        String qrAlign = getCallString(call, "qrAlign");
        Integer textColumns = getCallOptionalInt(call, "textColumns");
        String textStylesJson = getCallString(call, "textStyles");

        if (qrCodeValue.isEmpty() && textValue.trim().isEmpty()) {
            call.reject("printLabel requires qrCodeValue or textValue");
            return;
        }

        printExecutor.execute(() -> {
            if (destroyed) { call.reject("printer destroyed"); return; }
            try {
                LabelLayoutBuilder.BuiltLabel builtLabel = LabelLayoutBuilder.buildLegacyGenericLabel(
                    getContext(),
                    qrCodeValue,
                    textValue,
                    qrSize,
                    qrAlign,
                    textColumns,
                    textStylesJson,
                    "printLabelPluginLegacy"
                );
                emitPrintDiagnostic(
                    "printLabelPlugin",
                    "paperType=" + paperType + ", " + builtLabel.diagnostic
                );

                if (destroyed) { call.reject("printer destroyed"); return; }
                printBuiltLabel(builtLabel.label, paperType, "generic_label");
                call.resolve();
            } catch (Exception e) {
                call.reject("printLabel error: " + e.getMessage(), e);
            }
        });
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────────

    @Override
    public void load() {
        super.load();
        pluginContext = new java.lang.ref.WeakReference<>(getContext());
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        // App 进后台时关闭打印机，熄灭绿色连接指示灯
        if (isConnected || isConnecting) {
            wasConnectedBeforePause = true;
            isConnected  = false;
            isConnecting = false;
            debugLog("onPause: closing printer");
            Printer.close(getActivity());
        }
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        // App 回到前台时自动重连（仅限之前已连接过的情况）
        if (wasConnectedBeforePause && !destroyed) {
            wasConnectedBeforePause = false;
            debugLog("onResume: reconnecting printer");
            doConnect();
        }
    }

    @Override
    protected void handleOnDestroy() {
        // 先标记 destroyed，阻止异步回调（103 / printCallback）在销毁后调用 notifyListeners，
        // 同时让 printExecutor 里正在等待的任务检查 destroyed 后提前退出
        destroyed              = true;
        isConnected            = false;
        isConnecting           = false;
        wasConnectedBeforePause = false;
        // 中断位图生成 / 打印任务
        printExecutor.shutdownNow();
        // 注意：nativePrintExecutor 是静态池，生命周期跟随进程而非 Activity；
        // Activity 销毁重建（退出重进）后若被 shutdown，后续打印任务会全部
        // 抛 RejectedExecutionException 导致打印失效，因此不能在此关闭
        // 关闭打印机连接
        Printer.close(getActivity());
    }
}
