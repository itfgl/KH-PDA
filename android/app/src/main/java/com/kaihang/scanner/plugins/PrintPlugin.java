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

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import com.uc.pdasdk.print.BitmapData;
import com.uc.pdasdk.print.Printer;
import com.uc.pdasdk.utils.AbsoluteLayoutBitmap;
import com.uc.pdasdk.utils.BarcodeCreater;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
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
    private static final String LAYOUT_STANDARD = "standard";
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

    private static String bitmapSize(Bitmap bitmap) {
        if (bitmap == null) return "null";
        return bitmap.getWidth() + "x" + bitmap.getHeight();
    }

    private static boolean isDarkPixel(int color) {
        int alpha = (color >>> 24) & 0xff;
        if (alpha == 0) return false;
        int red = (color >>> 16) & 0xff;
        int green = (color >>> 8) & 0xff;
        int blue = color & 0xff;
        return ((red + green + blue) / 3) < 200;
    }

    private static Bitmap cropBitmapToContent(Bitmap bitmap) {
        if (bitmap == null) return null;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!isDarkPixel(bitmap.getPixel(x, y))) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }
        if (right < left || bottom < top) return bitmap;
        int croppedWidth = right - left + 1;
        int croppedHeight = bottom - top + 1;
        if (croppedWidth <= 0 || croppedHeight <= 0) return bitmap;
        if (croppedWidth == width && croppedHeight == height) return bitmap;
        return Bitmap.createBitmap(bitmap, left, top, croppedWidth, croppedHeight);
    }

    private static Bitmap normalizeBarcodeBitmap(Bitmap barcode, int targetWidth, int targetHeight) {
        if (barcode == null) return null;
        Bitmap cropped = cropBitmapToContent(barcode);
        if (cropped == null) return barcode;
        if (cropped.getWidth() == targetWidth && cropped.getHeight() == targetHeight) {
            return cropped;
        }
        return Bitmap.createScaledBitmap(
            cropped,
            Math.max(1, targetWidth),
            Math.max(1, targetHeight),
            false
        );
    }

    private static void emitPrintDiagnostic(String source, String detail) {
        String message = (source == null || source.trim().isEmpty() ? "unknown" : source)
            + " | "
            + (detail == null ? "" : detail);
        android.util.Log.d("PrintPlugin", message);
        emitNativeStatus("PRINT_LAYOUT", message);
    }

    private static final class BuiltLabel {
        final Bitmap label;
        final String diagnostic;

        BuiltLabel(Bitmap label, String diagnostic) {
            this.label = label;
            this.diagnostic = diagnostic;
        }
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

    public static void prepareToPrintLabelNative() {
        Printer.prepareToPrintLabel();
    }

    private static BuiltLabel buildUnifiedLabel(
        Context context,
        String barcodeValue,
        String qrCodeValue,
        String textValue,
        String layoutPreset,
        Integer qrSize,
        String qrAlign,
        Integer textColumns,
        String textStylesJson,
        String diagnosticSource
    ) {
        if (context == null) throw new IllegalArgumentException("context is required");
        String safeBarcodeValue = barcodeValue == null ? "" : barcodeValue.trim();
        String safeQrCodeValue = qrCodeValue == null ? "" : qrCodeValue.trim();
        String safeTextValue = textValue == null ? "" : textValue.trim();
        if (safeBarcodeValue.isEmpty() && safeQrCodeValue.isEmpty() && safeTextValue.isEmpty()) {
            throw new IllegalArgumentException("printLabel requires barcodeValue, qrCodeValue or textValue");
        }

        int columns = normalizeTextColumns(textColumns);
        GenericLabelLayout layout = getGenericLabelLayout(layoutPreset, qrSize);
        Bitmap barcode = null;
        Bitmap qr = null;
        String rawBarcodeSize = "null";
        if (!safeBarcodeValue.isEmpty()) {
            barcode = BarcodeCreater.createBarcode(context, safeBarcodeValue, layout.barcodeWidth, layout.barcodeHeight, false, 1);
            if (barcode == null) throw new IllegalStateException("barcode bitmap null");
            rawBarcodeSize = bitmapSize(barcode);
            barcode = normalizeBarcodeBitmap(barcode, layout.barcodeWidth, layout.barcodeHeight);
        }
        if (!safeQrCodeValue.isEmpty()) {
            qr = BarcodeCreater.createBarcode(context, safeQrCodeValue, layout.qrWidth, layout.qrHeight, false, 2);
            if (qr == null) throw new IllegalStateException("qr bitmap null");
        }

        // 二维码靠左仅在「纯二维码标签」（无一维码）时生效，右侧空白区放字段
        boolean qrLeftAligned = qr != null && barcode == null && "left".equals(normalizeQrAlign(qrAlign));
        boolean pureQrLabel = barcode == null && qr != null;
        int bodyTop = 16;
        int qrTop = -1;
        if (barcode != null) {
            bodyTop = layout.barcodeTop + layout.barcodeHeight + layout.mediaGap;
        }
        // 二维码实际位图高度：SDK 生成的 QR 可能小于请求尺寸（内容短/版本低），
        // 布局必须按实际高度预留，否则居中模式下二维码与下方字段间出现大片空白
        int qrEffHeight = qr != null ? qr.getHeight() : layout.qrHeight;
        int qrEffWidth = qr != null ? qr.getWidth() : layout.qrWidth;
        if (qr != null) {
            // 纯二维码标签：二维码紧贴顶部（8 点），减少上方空白
            // 同时存在一维码时：接在一维码之后
            qrTop = barcode == null
                ? 8
                : layout.barcodeTop + layout.barcodeHeight + layout.mediaGap;
            // 二维码与下方字段间距收紧到 4 点（居中/靠左一致），按实际高度计算
            bodyTop = Math.max(bodyTop, qrTop + qrEffHeight + 4);
        }
        int qrLeft = -1;
        int besideX = 0;
        int besideWidth = 0;
        if (qr != null) {
            qrLeft = qrLeftAligned
                ? TEXT_MARGIN
                : resolveCenteredMediaLeft(qrEffWidth);
            if (qrLeftAligned) {
                besideX = qrLeft + qrEffWidth + BESIDE_GAP;
                besideWidth = GenericLabelLayout.LABEL_WIDTH - TEXT_MARGIN - besideX;
            }
        }
        int barcodeLeft = barcode != null ? resolveCenteredMediaLeft(layout.barcodeWidth) : -1;
        // 计算媒体内容底部位置（二维码或一维码底部），用于 center 字段紧贴
        int mediaBottom;
        if (qr != null && barcode != null) {
            mediaBottom = Math.max(qrTop + qrEffHeight, layout.barcodeTop + layout.barcodeHeight);
        } else if (qr != null) {
            mediaBottom = qrTop + qrEffHeight;
        } else if (barcode != null) {
            mediaBottom = layout.barcodeTop + layout.barcodeHeight;
        } else {
            mediaBottom = bodyTop;
        }

        FieldTextPlan plan = planFieldText(
            safeTextValue,
            columns,
            qrLeftAligned,
            besideX,
            besideWidth,
            qrLeftAligned ? qrEffHeight / layout.lineHeight : 0,
            Math.max(qrTop, 0),
            qrEffHeight,
            bodyTop,
            layout.lineHeight,
            layout.wrapUnits,
            resolveLeftAlignedTextLeft(),
            textStylesJson,
            mediaBottom
        );
        // 纯二维码标签：不强制 minHeight，让标签高度根据内容自适应
        // 其他标签：保留 minHeight 约束
        int labelHeight;
        if (pureQrLabel) {
            labelHeight = plan.centerY + 8;
            if (plan.hasCenterRows()) {
                labelHeight = Math.max(labelHeight, plan.centerY + plan.centerBlockHeight + 8);
            }
        } else {
            labelHeight = Math.max(plan.centerY + 8, layout.minHeight);
            if (plan.hasCenterRows()) {
                labelHeight = Math.max(labelHeight, plan.centerY + plan.centerBlockHeight + 8);
            }
        }
        placeCenterRows(plan, labelHeight, layout.lineHeight, layout.textSize);

        AbsoluteLayoutBitmap builder = new AbsoluteLayoutBitmap(GenericLabelLayout.LABEL_WIDTH, labelHeight);
        if (barcode != null) {
            builder.addBmp(barcode, barcodeLeft, layout.barcodeTop);
        }
        if (qr != null) {
            builder.addBmp(qr, qrLeft, qrTop);
        }
        for (TextRow row : plan.rows) {
            int textSize = row.size > 0 ? row.size : layout.textSize;
            builder.addText(row.text, textSize, row.x, textBaselineY(row.y, textSize));
        }

        Bitmap label = builder.getBitmap();
        if (label == null) throw new IllegalStateException("label bitmap null");
        String diagnostic =
            "layoutPreset=" + normalizeLayoutPreset(layoutPreset)
                + ", requestBarcode=" + layout.barcodeWidth + "x" + layout.barcodeHeight
                + ", rawBarcode=" + rawBarcodeSize
                + ", actualBarcode=" + bitmapSize(barcode)
                + ", barcodeLeft=" + barcodeLeft
                + ", requestQr=" + layout.qrWidth + "x" + layout.qrHeight
                + ", actualQr=" + bitmapSize(qr)
                + ", qrLeft=" + qrLeft
                + ", qrTop=" + qrTop
                + ", label=" + bitmapSize(label)
                + ", bodyTop=" + bodyTop
                + ", lines=" + plan.rows.size()
                + ", qrAlign=" + normalizeQrAlign(qrAlign)
                + ", textColumns=" + columns
                + ", centerLines=" + plan.centerTexts.size()
                + ", source=" + diagnosticSource;
        return new BuiltLabel(label, diagnostic);
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

    public static void printLabelNative(Context context, Activity activity, String barcodeValue, String qrCodeValue, String textValue, String paperType, String layoutPreset, int qrSize, String qrAlign, int textColumns, String textStylesJson) {
        if (context == null || activity == null) return;
        connectNative(activity);
        nativePrintExecutor.execute(() -> {
            try {
                if (!nativeConnected && !waitForNativeConnection(NATIVE_CONNECT_TIMEOUT_MS)) {
                    emitNativeStatus("PRINT_BRIDGE_ERROR", "printer connect timeout");
                    return;
                }
                String normalizedPaperType = normalizePaperType(paperType);
                BuiltLabel builtLabel = buildLegacyGenericLabel(
                    context,
                    barcodeValue,
                    qrCodeValue,
                    textValue,
                    layoutPreset,
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
        String barcodeValue,
        String qrCodeValue,
        String textValue,
        String layoutPreset,
        int qrSize,
        String qrAlign,
        int textColumns,
        String textStylesJson
    ) {
        if (context == null || activity == null) return;
        nativePrintExecutor.execute(() -> {
            try {
                BuiltLabel builtLabel = buildPortablePreviewLabel(
                    barcodeValue,
                    qrCodeValue,
                    textValue,
                    layoutPreset,
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

    private static void enqueueNativePreview(Activity activity, BuiltLabel builtLabel) {
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
                android.util.Log.d("PrintPlugin", "printCallback: " + result.name() + " flag=" + flag);
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

    // ── 标签走纸 ──────────────────────────────────────────────────────────────

    @PluginMethod
    public void prepareToPrintLabel(PluginCall call) {
        Printer.prepareToPrintLabel();
        call.resolve();
    }

    /**
     * 检测黑标（标签间隙检测）
     * 每张标签打印完成（收到 printStatus.status == "PRINT_OK" 事件）后调用，
     * 走纸到下一张标签的起始位置，准备打印下一张。
     * 底层与 prepareToPrintLabel 调用相同 SDK 方法，回调同样走 printStatus 事件：
     *   PREPARE_LABEL_OK → 已就绪，可继续打印
     *   PREPARE_LABEL_BLACK_FLAG_NOT_FOUND → 未检测到标签间隙，需人工处理
     */
    @PluginMethod
    public void checkBlack(PluginCall call) {
        Printer.prepareToPrintLabel();
        call.resolve();
    }

    // ── 打印 ──────────────────────────────────────────────────────────────────

    private static List<String> wrapLabeledText(String label, String text, int maxUnitsPerLine) {
        List<String> lines = new ArrayList<>();
        String safeText = text == null ? "" : text.trim();
        String current = label;
        int units = textUnits(label);
        for (int i = 0; i < safeText.length(); i++) {
            char ch = safeText.charAt(i);
            int next = charUnits(ch);
            if (units + next > maxUnitsPerLine && !current.equals(label)) {
                lines.add(current);
                current = "      " + ch;
                units = textUnits("      ") + next;
            } else {
                current += ch;
                units += next;
            }
        }
        lines.add(current);
        return lines;
    }

    private static int textUnits(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) total += charUnits(text.charAt(i));
        return total;
    }

    private static int charUnits(char ch) {
        return ch <= 0x7f ? 1 : 2;
    }

    private static String getPrintedLaneLabel(String laneNo) {
        try {
            return String.valueOf(Integer.parseInt(laneNo.trim()) - 1);
        } catch (Exception ignore) {
            return laneNo == null ? "" : laneNo;
        }
    }

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

    private static String getPrintedBatchText(String batchNo, String laneNo) {
        String printedLane = getPrintedLaneLabel(laneNo);
        if (printedLane == null || printedLane.trim().isEmpty()) return batchNo;
        return batchNo + "-" + printedLane;
    }

    private static String normalizePaperType(String value) {
        return PAPER_BLACK_MARK.equalsIgnoreCase(String.valueOf(value).trim()) ? PAPER_BLACK_MARK : PAPER_THERMAL;
    }

    private static String normalizeLayoutPreset(String value) {
        String preset = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("compact".equals(preset) || "large".equals(preset)) return preset;
        return LAYOUT_STANDARD;
    }

    private static final class GenericLabelLayout {
        static final int LABEL_WIDTH = 384;
        static final double BARCODE_WIDTH_RATIO = 0.90d;
        // 默认二维码为原 80% 宽度的一半，即约 40% 画布宽（154 点）
        static final double QR_WIDTH_RATIO = 0.40d;
        static final int QR_MIN_SIZE = 60;
        static final int QR_MAX_SIZE = LABEL_WIDTH;
        final int barcodeWidth;
        final int barcodeHeight;
        final int qrWidth;
        final int qrHeight;
        final int barcodeTop;
        final int qrTop;
        final int mediaGap;
        final int textSize;
        final int lineHeight;
        final int minHeight;
        final int wrapUnits;

        GenericLabelLayout(int barcodeWidth, int barcodeHeight, int qrWidth, int qrHeight, int barcodeTop,
                           int qrTop, int mediaGap, int textSize, int lineHeight, int minHeight, int wrapUnits) {
            this.barcodeWidth = barcodeWidth;
            this.barcodeHeight = barcodeHeight;
            this.qrWidth = qrWidth;
            this.qrHeight = qrHeight;
            this.barcodeTop = barcodeTop;
            this.qrTop = qrTop;
            this.mediaGap = mediaGap;
            this.textSize = textSize;
            this.lineHeight = lineHeight;
            this.minHeight = minHeight;
            this.wrapUnits = wrapUnits;
        }
    }

    private static final class LegacyGenericLayout {
        final int barcodeWidth;
        final int barcodeHeight;
        final int qrWidth;
        final int qrHeight;
        final int qrLeft;
        final int bodyTop;
        final int textSize;
        final int lineHeight;
        final int minHeight;
        final int textLeft;
        final int wrapUnits;

        LegacyGenericLayout(
            int barcodeWidth,
            int barcodeHeight,
            int qrWidth,
            int qrHeight,
            int qrLeft,
            int bodyTop,
            int textSize,
            int lineHeight,
            int minHeight,
            int textLeft,
            int wrapUnits
        ) {
            this.barcodeWidth = barcodeWidth;
            this.barcodeHeight = barcodeHeight;
            this.qrWidth = qrWidth;
            this.qrHeight = qrHeight;
            this.qrLeft = qrLeft;
            this.bodyTop = bodyTop;
            this.textSize = textSize;
            this.lineHeight = lineHeight;
            this.minHeight = minHeight;
            this.textLeft = textLeft;
            this.wrapUnits = wrapUnits;
        }
    }

    private static int resolveCenteredMediaLeft(int mediaWidth) {
        return Math.max(0, (GenericLabelLayout.LABEL_WIDTH - mediaWidth) / 2);
    }

    private static int resolveNinetyPercentBarcodeWidth() {
        return (int) Math.round(GenericLabelLayout.LABEL_WIDTH * GenericLabelLayout.BARCODE_WIDTH_RATIO);
    }

    private static int resolveDefaultQrSize() {
        return (int) Math.round(GenericLabelLayout.LABEL_WIDTH * GenericLabelLayout.QR_WIDTH_RATIO);
    }

    /**
     * 解析调用方自定义的二维码尺寸（打印点数）。
     * null / 0 / 非正值 → 默认尺寸（画布宽的 40%，约 154 点）；
     * 有效值会夹在 [QR_MIN_SIZE, QR_MAX_SIZE] 区间内。
     */
    private static int resolveRequestedQrSize(Integer qrSize) {
        if (qrSize == null || qrSize <= 0) return resolveDefaultQrSize();
        if (qrSize < GenericLabelLayout.QR_MIN_SIZE) return GenericLabelLayout.QR_MIN_SIZE;
        if (qrSize > GenericLabelLayout.QR_MAX_SIZE) return GenericLabelLayout.QR_MAX_SIZE;
        return qrSize;
    }

    private static GenericLabelLayout getGenericLabelLayout(String preset, Integer qrSize) {
        int resolvedQrSize = resolveRequestedQrSize(qrSize);
        switch (normalizeLayoutPreset(preset)) {
            case "compact":
                return new GenericLabelLayout(346, 96, resolvedQrSize, resolvedQrSize, 20, 138, 24, 26, 32, 240, 32);
            case "large":
                return new GenericLabelLayout(346, 122, resolvedQrSize, resolvedQrSize, 20, 162, 24, 30, 38, 288, 28);
            default:
                return new GenericLabelLayout(346, 108, resolvedQrSize, resolvedQrSize, 20, 148, 24, 28, 36, 264, 30);
        }
    }

    private static LegacyGenericLayout getLegacyGenericLayout(String preset, Integer qrSize) {
        int resolvedQrSize = resolveRequestedQrSize(qrSize);
        int qrLeft = resolveCenteredMediaLeft(resolvedQrSize);
        switch (normalizeLayoutPreset(preset)) {
            case "compact":
                return new LegacyGenericLayout(346, 96, resolvedQrSize, resolvedQrSize, qrLeft, 216, 22, 28, 244, 8, 32);
            case "large":
                return new LegacyGenericLayout(346, 122, resolvedQrSize, resolvedQrSize, qrLeft, 264, 26, 34, 308, 8, 28);
            default:
                return new LegacyGenericLayout(346, 108, resolvedQrSize, resolvedQrSize, qrLeft, 240, 24, 32, 280, 8, 30);
        }
    }

    private static BuiltLabel buildLegacyGenericLabel(
        Context context,
        String barcodeValue,
        String qrCodeValue,
        String textValue,
        String layoutPreset,
        Integer qrSize,
        String qrAlign,
        Integer textColumns,
        String textStylesJson,
        String diagnosticSource
    ) {
        if (context == null) throw new IllegalArgumentException("context is required");
        String safeBarcodeValue = barcodeValue == null ? "" : barcodeValue.trim();
        String safeQrCodeValue = qrCodeValue == null ? "" : qrCodeValue.trim();
        String safeTextValue = textValue == null ? "" : textValue.trim();
        if (safeBarcodeValue.isEmpty() && safeQrCodeValue.isEmpty() && safeTextValue.isEmpty()) {
            throw new IllegalArgumentException("printLabel requires barcodeValue, qrCodeValue or textValue");
        }

        int columns = normalizeTextColumns(textColumns);
        LegacyGenericLayout layout = getLegacyGenericLayout(layoutPreset, qrSize);
        Bitmap barcode = null;
        Bitmap qr = null;
        String rawBarcodeSize = "null";
        if (!safeBarcodeValue.isEmpty()) {
            barcode = BarcodeCreater.createBarcode(context, safeBarcodeValue, layout.barcodeWidth, layout.barcodeHeight, false, 1);
            if (barcode == null) throw new IllegalStateException("barcode bitmap null");
            rawBarcodeSize = bitmapSize(barcode);
            barcode = normalizeBarcodeBitmap(barcode, layout.barcodeWidth, layout.barcodeHeight);
        }
        if (!safeQrCodeValue.isEmpty()) {
            qr = BarcodeCreater.createBarcode(context, safeQrCodeValue, layout.qrWidth, layout.qrHeight, false, 2);
            if (qr == null) throw new IllegalStateException("qr bitmap null");
        }

        // 二维码靠左仅在「纯二维码标签」（无一维码）时生效，右侧空白区放字段
        boolean qrLeftAligned = qr != null && barcode == null && "left".equals(normalizeQrAlign(qrAlign));
        boolean pureQrLabel = barcode == null && qr != null;
        int qrLeft = qrLeftAligned ? TEXT_MARGIN : layout.qrLeft;
        // 二维码实际位图尺寸参与布局：SDK 生成的 QR 可能小于请求尺寸，按实际值预留
        int qrEffHeight = qr != null ? qr.getHeight() : layout.qrHeight;
        int qrEffWidth = qr != null ? qr.getWidth() : layout.qrWidth;
        if (qrLeftAligned) qrLeft = TEXT_MARGIN;
        int besideX = 0;
        int besideWidth = 0;
        if (qrLeftAligned) {
            besideX = qrLeft + qrEffWidth + BESIDE_GAP;
            besideWidth = GenericLabelLayout.LABEL_WIDTH - TEXT_MARGIN - besideX;
        }
        int mediaBottom = 0;
        if (barcode != null) mediaBottom = Math.max(mediaBottom, 8 + layout.barcodeHeight);
        if (qr != null) mediaBottom = Math.max(mediaBottom, 8 + qrEffHeight);
        // 二维码与下方字段间距收紧到 4 点（居中/靠左一致）；一维码标签保持 24
        int belowGap = qr != null ? 4 : 24;
        int belowStartY = mediaBottom > 0 ? mediaBottom + belowGap : 16;

        FieldTextPlan plan = planFieldText(
            safeTextValue,
            columns,
            qrLeftAligned,
            besideX,
            besideWidth,
            qrLeftAligned ? qrEffHeight / layout.lineHeight : 0,
            8,
            qrEffHeight,
            belowStartY,
            layout.lineHeight,
            layout.wrapUnits,
            layout.textLeft,
            textStylesJson,
            mediaBottom
        );
        // 纯二维码标签：不强制 minHeight，让标签高度根据内容自适应
        int labelHeight;
        if (pureQrLabel) {
            labelHeight = plan.centerY + 8;
            if (plan.hasCenterRows()) {
                labelHeight = Math.max(labelHeight, plan.centerY + plan.centerBlockHeight + 8);
            }
        } else {
            labelHeight = Math.max(plan.centerY + 8, layout.minHeight);
            if (plan.hasCenterRows()) {
                labelHeight = Math.max(labelHeight, plan.centerY + plan.centerBlockHeight + 8);
            }
        }
        placeCenterRows(plan, labelHeight, layout.lineHeight, layout.textSize);

        AbsoluteLayoutBitmap builder = new AbsoluteLayoutBitmap(GenericLabelLayout.LABEL_WIDTH, labelHeight);
        if (barcode != null) {
            builder.addBmp(barcode, 8, 8);
        }
        if (qr != null) {
            builder.addBmp(qr, qrLeft, 8);
        }
        for (TextRow row : plan.rows) {
            int textSize = row.size > 0 ? row.size : layout.textSize;
            builder.addText(row.text, textSize, row.x, textBaselineY(row.y, textSize));
        }

        Bitmap label = builder.getBitmap();
        if (label == null) throw new IllegalStateException("label bitmap null");
        String diagnostic =
            "legacyGeneric=true"
                + ", layoutPreset=" + normalizeLayoutPreset(layoutPreset)
                + ", requestBarcode=" + layout.barcodeWidth + "x" + layout.barcodeHeight
                + ", rawBarcode=" + rawBarcodeSize
                + ", actualBarcode=" + bitmapSize(barcode)
                + ", requestQr=" + layout.qrWidth + "x" + layout.qrHeight
                + ", actualQr=" + bitmapSize(qr)
                + ", label=" + bitmapSize(label)
                + ", bodyTop=" + plan.centerY
                + ", lines=" + plan.rows.size()
                + ", qrAlign=" + normalizeQrAlign(qrAlign)
                + ", textColumns=" + columns
                + ", centerLines=" + plan.centerTexts.size()
                + ", source=" + diagnosticSource;
        return new BuiltLabel(label, diagnostic);
    }

    private static BuiltLabel buildPortablePreviewLabel(
        String barcodeValue,
        String qrCodeValue,
        String textValue,
        String layoutPreset,
        Integer qrSize,
        String qrAlign,
        Integer textColumns,
        String textStylesJson,
        String diagnosticSource
    ) throws Exception {
        String safeBarcodeValue = barcodeValue == null ? "" : barcodeValue.trim();
        String safeQrCodeValue = qrCodeValue == null ? "" : qrCodeValue.trim();
        String safeTextValue = textValue == null ? "" : textValue.trim();
        if (safeBarcodeValue.isEmpty() && safeQrCodeValue.isEmpty() && safeTextValue.isEmpty()) {
            throw new IllegalArgumentException("preview requires barcodeValue, qrCodeValue or textValue");
        }

        int columns = normalizeTextColumns(textColumns);
        LegacyGenericLayout layout = getLegacyGenericLayout(layoutPreset, qrSize);
        Bitmap barcode = safeBarcodeValue.isEmpty()
            ? null
            : createPortableCode(safeBarcodeValue, BarcodeFormat.CODE_128, layout.barcodeWidth, layout.barcodeHeight);
        Bitmap qr = safeQrCodeValue.isEmpty()
            ? null
            : createPortableCode(safeQrCodeValue, BarcodeFormat.QR_CODE, layout.qrWidth, layout.qrHeight);

        // 二维码靠左仅在「纯二维码标签」（无一维码）时生效，右侧空白区放字段
        boolean qrLeftAligned = qr != null && barcode == null && "left".equals(normalizeQrAlign(qrAlign));
        boolean pureQrLabel = barcode == null && qr != null;
        // 实际位图尺寸参与布局，与实纸打印保持一致
        int qrEffHeight = qr != null ? qr.getHeight() : layout.qrHeight;
        int qrEffWidth = qr != null ? qr.getWidth() : layout.qrWidth;
        int qrLeft = qrLeftAligned ? TEXT_MARGIN : layout.qrLeft;
        int besideX = 0;
        int besideWidth = 0;
        if (qrLeftAligned) {
            besideX = qrLeft + qrEffWidth + BESIDE_GAP;
            besideWidth = GenericLabelLayout.LABEL_WIDTH - TEXT_MARGIN - besideX;
        }
        int mediaBottom = 0;
        if (barcode != null) mediaBottom = Math.max(mediaBottom, 8 + layout.barcodeHeight);
        if (qr != null) mediaBottom = Math.max(mediaBottom, 8 + qrEffHeight);
        // 二维码与下方字段间距收紧到 4 点（居中/靠左一致），与实纸打印保持一致；一维码标签保持 24
        int belowGap = qr != null ? 4 : 24;
        int belowStartY = mediaBottom > 0 ? mediaBottom + belowGap : 16;

        FieldTextPlan plan = planFieldText(
            safeTextValue,
            columns,
            qrLeftAligned,
            besideX,
            besideWidth,
            qrLeftAligned ? qrEffHeight / layout.lineHeight : 0,
            8,
            qrEffHeight,
            belowStartY,
            layout.lineHeight,
            layout.wrapUnits,
            layout.textLeft,
            textStylesJson,
            mediaBottom
        );
        // 纯二维码标签：不强制 minHeight，让标签高度根据内容自适应
        int labelHeight;
        if (pureQrLabel) {
            labelHeight = plan.centerY + 8;
            if (plan.hasCenterRows()) {
                labelHeight = Math.max(labelHeight, plan.centerY + plan.centerBlockHeight + 8);
            }
        } else {
            labelHeight = Math.max(plan.centerY + 8, layout.minHeight);
            if (plan.hasCenterRows()) {
                labelHeight = Math.max(labelHeight, plan.centerY + plan.centerBlockHeight + 8);
            }
        }
        placeCenterRows(plan, labelHeight, layout.lineHeight, layout.textSize);

        Bitmap label = Bitmap.createBitmap(GenericLabelLayout.LABEL_WIDTH, labelHeight, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(label);
        canvas.drawColor(android.graphics.Color.WHITE);
        if (barcode != null) canvas.drawBitmap(barcode, 8, 8, null);
        if (qr != null) canvas.drawBitmap(qr, qrLeft, 8, null);

        android.graphics.Paint textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(android.graphics.Color.BLACK);
        textPaint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL));
        for (TextRow row : plan.rows) {
            textPaint.setTextSize(row.size > 0 ? row.size : layout.textSize);
            android.graphics.Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
            canvas.drawText(row.text, row.x, row.y - fontMetrics.top, textPaint);
        }

        String diagnostic =
            "portablePreview=true"
                + ", layoutPreset=" + normalizeLayoutPreset(layoutPreset)
                + ", barcode=" + bitmapSize(barcode)
                + ", qr=" + bitmapSize(qr)
                + ", label=" + bitmapSize(label)
                + ", lines=" + plan.rows.size()
                + ", qrAlign=" + normalizeQrAlign(qrAlign)
                + ", textColumns=" + columns
                + ", centerLines=" + plan.centerTexts.size()
                + ", source=" + diagnosticSource;
        return new BuiltLabel(label, diagnostic);
    }

    private static Bitmap createPortableCode(
        String value,
        BarcodeFormat format,
        int width,
        int height
    ) throws Exception {
        EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrix = new MultiFormatWriter().encode(value, format, width, height, hints);
        int matrixWidth = matrix.getWidth();
        int matrixHeight = matrix.getHeight();
        int[] pixels = new int[matrixWidth * matrixHeight];
        for (int y = 0; y < matrixHeight; y++) {
            int offset = y * matrixWidth;
            for (int x = 0; x < matrixWidth; x++) {
                pixels[offset + x] = matrix.get(x, y)
                    ? android.graphics.Color.BLACK
                    : android.graphics.Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(matrixWidth, matrixHeight, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, matrixWidth, 0, 0, matrixWidth, matrixHeight);
        return bitmap;
    }

    /** 文本宽度测量 Paint：与 SDK AbsoluteLayoutBitmap.addText 的默认渲染基准一致 */
    private static final android.graphics.Paint TEXT_MEASURE_PAINT = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    private static int estimateTextWidth(String text, int textSize) {
        if (text == null || text.isEmpty()) return 0;
        synchronized (TEXT_MEASURE_PAINT) {
            TEXT_MEASURE_PAINT.setTextSize(textSize);
            return (int) Math.ceil(TEXT_MEASURE_PAINT.measureText(text));
        }
    }

    /**
     * SDK AbsoluteLayoutBitmap.addText 的 y 参数是文字基线（直接传给 Canvas.drawText），
     * 而 TextRow.y 语义是行顶部。此方法把行顶部坐标换算成基线坐标，
     * 使实纸渲染与预览（drawText 用 y - fontMetrics.top）完全同基准。
     */
    private static int textBaselineY(int topY, int textSize) {
        synchronized (TEXT_MEASURE_PAINT) {
            TEXT_MEASURE_PAINT.setTextSize(textSize);
            return topY - (int) Math.ceil(TEXT_MEASURE_PAINT.getFontMetrics().top);
        }
    }

    private static int resolveCenteredTextLeft(String text, GenericLabelLayout layout) {
        int width = estimateTextWidth(text, layout.textSize);
        return Math.max(8, (GenericLabelLayout.LABEL_WIDTH - width) / 2);
    }

    private static int resolveLeftAlignedTextLeft() {
        return 12;
    }

    private static List<String> wrapPlainText(String text, int maxUnits) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return lines;
        String[] rawLines = text.replace("\r", "").split("\n");
        for (String rawLine : rawLines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder current = new StringBuilder();
            int currentUnits = 0;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                int units = charUnits(ch);
                if (currentUnits + units > maxUnits && current.length() > 0) {
                    lines.add(current.toString());
                    current = new StringBuilder();
                    currentUnits = 0;
                }
                current.append(ch);
                currentUnits += units;
            }
            if (current.length() > 0) {
                lines.add(current.toString());
            }
        }
        return lines;
    }

    // ── 字段排版：单列/双列 + 二维码右侧区域 ─────────────────────────────────

    private static final int TEXT_MARGIN = 8;
    private static final int COLUMN_GAP = 16;
    private static final int BESIDE_GAP = 12;
    private static final int MIN_BESIDE_WIDTH = 80;

    private static String normalizeQrAlign(String value) {
        return "left".equalsIgnoreCase(String.valueOf(value).trim()) ? "left" : "center";
    }

    private static int normalizeTextColumns(Integer value) {
        return value != null && value >= 2 ? 2 : 1;
    }

    private static final class TextRow {
        final String text;
        final int x;
        final int y;
        final int size; // 0 = 使用布局默认字号

        TextRow(String text, int x, int y) {
            this(text, x, y, 0);
        }

        TextRow(String text, int x, int y, int size) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.size = size;
        }
    }

    /** 单行样式：来自 H5 textStyles（占位符 |center / |字号 语法生成） */
    private static final class LineStyle {
        final int size;      // 0 = 默认字号
        final boolean center; // true = 二维码下方居中显示

        LineStyle(int size, boolean center) {
            this.size = size;
            this.center = center;
        }
    }

    private static LineStyle defaultLineStyle() {
        return new LineStyle(0, false);
    }

    /**
     * 解析 H5 传来的行样式 JSON：[{"align":"left","size":30,"center":false},...]
     * 与 textValue 的 \n 行一一对应，缺失或解析失败按默认样式补齐。
     */
    private static List<LineStyle> parseTextStyles(String json, int expectedLines) {
        List<LineStyle> styles = new ArrayList<>();
        if (json != null && !json.trim().isEmpty()) {
            try {
                org.json.JSONArray array = new org.json.JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    org.json.JSONObject item = array.optJSONObject(i);
                    if (item == null) {
                        styles.add(defaultLineStyle());
                        continue;
                    }
                    styles.add(new LineStyle(item.optInt("size", 0), item.optBoolean("center", false)));
                }
            } catch (Exception ignore) {
                styles.clear();
            }
        }
        while (styles.size() < expectedLines) styles.add(defaultLineStyle());
        return styles;
    }

    private static final class FieldTextPlan {
        final List<TextRow> rows = new ArrayList<>();
        /** 居中行（文本、字号），由 placeCenterRows 在确定标签高度后落位 */
        final List<LineStyle> centerStyles = new ArrayList<>();
        final List<String> centerTexts = new ArrayList<>();
        int centerY = 0;          // 非 center 内容底部 y
        int centerBlockHeight = 0; // center 行总高（含行距）

        boolean hasCenterRows() {
            return !centerTexts.isEmpty();
        }
    }

    private static int columnWrapUnits(int baseWrapUnits, int columnWidth) {
        int fullWidth = GenericLabelLayout.LABEL_WIDTH - 2 * TEXT_MARGIN;
        return Math.max(6, (int) Math.round(baseWrapUnits * (double) Math.max(0, columnWidth) / fullWidth));
    }

    /** 行高：带自定义字号的行适当加高，避免上下行重叠 */
    private static int styledLineHeight(int lineHeight, LineStyle style) {
        if (style == null || style.size <= 0) return lineHeight;
        return Math.max(lineHeight, style.size + 12);
    }

    /**
     * 统一规划字段文本落位：
     * - center 样式行（占位符 |center）收集为居中行，紧贴二维码下方；
     * - besideEnabled（二维码靠左时）：右侧窄列只放默认样式字段，整列相对二维码垂直居中，放不下的字段转入下方；
     * - 下方区域按 columns 列排版：1 列保持原有整段换行行为；2 列时字段行序配对；带字号的行按字号加高行距；
     * - 返回每行文本的绝对坐标（含字号）及内容底部 y，center 行由 placeCenterRows 落位。
     */
    private static FieldTextPlan planFieldText(
        String textValue,
        int columns,
        boolean besideEnabled,
        int besideX,
        int besideWidth,
        int besideRowCapacity,
        int qrTop,
        int qrHeight,
        int belowStartY,
        int lineHeight,
        int baseWrapUnits,
        int textLeft,
        String textStylesJson,
        int mediaBottom
    ) {
        FieldTextPlan plan = new FieldTextPlan();
        List<String> fields = new ArrayList<>();
        if (textValue != null && !textValue.trim().isEmpty()) {
            for (String raw : textValue.replace("\r", "").split("\n")) {
                fields.add(raw == null ? "" : raw.trim());
            }
        }
        List<LineStyle> styles = parseTextStyles(textStylesJson, fields.size());

        // 分离 center 行与普通行（普通行保留原顺序，样式随行）
        List<Integer> normalIndexes = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            LineStyle style = styles.get(i);
            if (style.center) {
                String trimmed = fields.get(i);
                if (!trimmed.isEmpty()) {
                    plan.centerTexts.add(trimmed);
                    plan.centerStyles.add(style);
                }
                continue;
            }
            normalIndexes.add(i);
        }
        int centerBlockHeight = 0;
        for (LineStyle style : plan.centerStyles) {
            centerBlockHeight += styledLineHeight(lineHeight, style);
        }
        plan.centerBlockHeight = centerBlockHeight;

        List<Integer> remaining = normalIndexes;
        int belowY = belowStartY;

        // 二维码右侧窄列：仅收默认样式字段；整列相对二维码垂直居中
        if (besideEnabled && !normalIndexes.isEmpty() && besideRowCapacity > 0 && besideWidth >= MIN_BESIDE_WIDTH) {
            int besideUnits = columnWrapUnits(baseWrapUnits, besideWidth);
            List<int[]> besideRows = new ArrayList<>(); // {rowStart, rowCount, fieldIndex}
            List<List<String>> besideWrapped = new ArrayList<>();
            int usedRows = 0;
            int position = 0;
            while (position < normalIndexes.size()) {
                int fieldIndex = normalIndexes.get(position);
                if (styles.get(fieldIndex).size > 0) break; // 带字号字段及后续全部转下方
                List<String> wrapped = wrapPlainText(fields.get(fieldIndex), besideUnits);
                if (wrapped.isEmpty()) {
                    wrapped = new ArrayList<>();
                    wrapped.add("");
                }
                if (usedRows + wrapped.size() > besideRowCapacity) break;
                besideRows.add(new int[]{usedRows, wrapped.size()});
                besideWrapped.add(wrapped);
                usedRows += wrapped.size();
                position++;
            }
            if (position > 0) {
                // 垂直居中：内容总高不超过二维码高度时居中起始，否则从二维码顶部开始
                int contentHeight = usedRows * lineHeight;
                int startY = contentHeight < qrHeight
                    ? qrTop + Math.max(0, (qrHeight - contentHeight) / 2)
                    : qrTop;
                for (int i = 0; i < besideRows.size(); i++) {
                    int rowStart = besideRows.get(i)[0];
                    for (int k = 0; k < besideWrapped.get(i).size(); k++) {
                        plan.rows.add(new TextRow(besideWrapped.get(i).get(k), besideX, startY + (rowStart + k) * lineHeight));
                    }
                }
                remaining = normalIndexes.subList(position, normalIndexes.size());
            }
        }

        if (columns >= 2) {
            // 双列：行序配对，左右字段各自在半宽内换行，行高取两者最大值
            int halfWidth = (GenericLabelLayout.LABEL_WIDTH - 2 * TEXT_MARGIN - COLUMN_GAP) / 2;
            int halfUnits = columnWrapUnits(baseWrapUnits, halfWidth);
            int col1X = TEXT_MARGIN;
            int col2X = TEXT_MARGIN + halfWidth + COLUMN_GAP;
            int y = belowY;
            int index = 0;
            while (index < remaining.size()) {
                int leftIndex = remaining.get(index);
                LineStyle leftStyle = styles.get(leftIndex);
                List<String> left = wrapPlainText(fields.get(leftIndex), halfUnits);
                if (left.isEmpty()) {
                    left = new ArrayList<>();
                    left.add("");
                }
                List<String> right = new ArrayList<>();
                LineStyle rightStyle = null;
                if (index + 1 < remaining.size()) {
                    int rightIndex = remaining.get(index + 1);
                    rightStyle = styles.get(rightIndex);
                    right = wrapPlainText(fields.get(rightIndex), halfUnits);
                    if (right.isEmpty()) {
                        right = new ArrayList<>();
                        right.add("");
                    }
                }
                int rowHeight = Math.max(styledLineHeight(lineHeight, leftStyle), styledLineHeight(lineHeight, rightStyle));
                for (int k = 0; k < left.size(); k++) {
                    plan.rows.add(new TextRow(left.get(k), col1X, y + k * rowHeight, leftStyle.size));
                }
                for (int k = 0; k < right.size(); k++) {
                    plan.rows.add(new TextRow(right.get(k), col2X, y + k * rowHeight, rightStyle == null ? 0 : rightStyle.size));
                }
                y += Math.max(Math.max(left.size(), right.size()), 1) * rowHeight;
                index += 2;
            }
            // 只有 center 字段：紧贴二维码/一维码底部
            plan.centerY = remaining.isEmpty() && plan.hasCenterRows() ? mediaBottom : y;
        } else {
            // 单列：逐行按样式输出（保持原有整段换行行为，字号行行距加高）
            int y = belowY;
            for (int index : remaining) {
                LineStyle style = styles.get(index);
                List<String> lines = wrapPlainText(fields.get(index), baseWrapUnits);
                if (lines.isEmpty()) {
                    lines = new ArrayList<>();
                    lines.add("");
                }
                int rowHeight = styledLineHeight(lineHeight, style);
                for (int k = 0; k < lines.size(); k++) {
                    plan.rows.add(new TextRow(lines.get(k), textLeft, y + k * rowHeight, style.size));
                }
                y += lines.size() * rowHeight;
            }
            if (remaining.isEmpty()) {
                // 只有 center 字段：紧贴二维码/一维码底部，无额外间距
                plan.centerY = plan.hasCenterRows() ? mediaBottom : belowY;
            } else {
                plan.centerY = y;
            }
        }
        return plan;
    }

    /**
     * center 行落位：水平居中、紧贴二维码下方，
     * 用户通过换行符自行控制间距。
     */
    private static void placeCenterRows(FieldTextPlan plan, int labelHeight, int lineHeight, int defaultTextSize) {
        if (!plan.hasCenterRows()) return;
        int y = plan.centerY;
        for (int i = 0; i < plan.centerTexts.size(); i++) {
            LineStyle style = plan.centerStyles.get(i);
            String line = plan.centerTexts.get(i);
            int size = style.size > 0 ? style.size : defaultTextSize;
            int x = Math.max(0, (GenericLabelLayout.LABEL_WIDTH - estimateTextWidth(line, size)) / 2);
            plan.rows.add(new TextRow(line, x, y, style.size));
            y += styledLineHeight(lineHeight, style);
        }
    }

    /**
     * 批次标签（一维码）
     * 布局 384×644：条码放大，文字略缩小；机器/日期分行；品类支持换行；
     * 穴号/周期/栏号依次紧随其后；批次间额外走纸明显加大。
     */
    @PluginMethod
    public void printBatchLabel(PluginCall call) {
        String batchNo     = getCallString(call, "batchNo");
        String machineId   = getCallString(call, "machineId");
        String productType = getCallString(call, "productType");
        String cavityNo    = getCallString(call, "cavityNo");
        String date        = getCallString(call, "date");
        String periodLabel = getCallString(call, "periodLabel");
        String laneNo      = getCallString(call, "laneNo");
        Integer textColumns = getCallOptionalInt(call, "textColumns");

        if (batchNo.isEmpty()) { call.reject("batchNo is required"); return; }

        printExecutor.execute(() -> {
            if (destroyed) { call.reject("printer destroyed"); return; }
            try {
                List<String> productLines = wrapLabeledText("品类：", productType, 18);
                String printedBatchText = getPrintedBatchText(batchNo, laneNo);
                String printedLaneLabel = getPrintedLaneLabel(laneNo);
                List<String> lines = new ArrayList<>();
                lines.add("批次：" + printedBatchText);
                if (!machineId.trim().isEmpty()) lines.add("机器：" + machineId.trim());
                if (!date.trim().isEmpty()) lines.add("日期：" + date.trim());
                lines.addAll(productLines);
                if (!cavityNo.trim().isEmpty()) lines.add("穴号：" + cavityNo.trim());
                if (!periodLabel.trim().isEmpty()) lines.add("周期：" + periodLabel.trim());
                if (!printedLaneLabel.trim().isEmpty()) lines.add("栏号：" + printedLaneLabel.trim());
                BuiltLabel builtLabel = buildUnifiedLabel(
                    getContext(),
                    batchNo,
                    "",
                    String.join("\n", lines),
                    LAYOUT_STANDARD,
                    null,
                    null,
                    textColumns,
                    null,
                    "printBatchLabel"
                );
                emitPrintDiagnostic(
                    "printBatchLabel",
                    builtLabel.diagnostic + ", batchNo=" + batchNo + ", productLines=" + productLines.size()
                );

                if (destroyed) { call.reject("printer destroyed"); return; }
                android.util.Log.d("PrintPlugin", "printBatchLabel → Printer.print()");
                printBuiltLabel(builtLabel.label, PAPER_THERMAL, "batch_" + batchNo);
                call.resolve();
            } catch (Exception e) {
                android.util.Log.e("PrintPlugin", "printBatchLabel crash", e);
                call.reject("printBatchLabel error: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 机器二维码标签
     * 二维码约占 384 点纸宽的 60%，居中显示，下方打印机器编号和时间。
     * QR 内容格式：machineId（仅机器编号）
     */
    @PluginMethod
    public void printMachineQR(PluginCall call) {
        String machineId = call.getString("machineId", "");
        Integer qrSize = getCallOptionalInt(call, "qrSize");
        String qrAlign = getCallString(call, "qrAlign");
        Integer textColumns = getCallOptionalInt(call, "textColumns");
        String textStylesJson = getCallString(call, "textStyles");

        if (machineId.isEmpty()) { call.reject("machineId is required"); return; }

        printExecutor.execute(() -> {
            if (destroyed) { call.reject("printer destroyed"); return; }
            try {
                String printTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date());
                BuiltLabel builtLabel = buildUnifiedLabel(
                    getContext(),
                    "",
                    machineId,
                    "机 器：" + machineId + "\n打印：" + printTime,
                    "large",
                    qrSize,
                    qrAlign,
                    textColumns,
                    textStylesJson,
                    "printMachineQR"
                );
                emitPrintDiagnostic("printMachineQR", builtLabel.diagnostic + ", machineId=" + machineId);

                if (destroyed) { call.reject("printer destroyed"); return; }
                printBuiltLabel(builtLabel.label, PAPER_BLACK_MARK, "machine_qr_" + machineId);
                call.resolve();
            } catch (Exception e) {
                call.reject("printMachineQR error: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 通用标签：
     * - barcodeValue: 一维码内容
     * - qrCodeValue: 二维码内容
     * - textValue: 多行正文，支持换行
     * - qrSize: 二维码边长（打印点数），可选，缺省约 154 点（画布宽 40%）
     * - qrAlign: 二维码水平对齐，center（默认）/ left；靠左时右侧空白区放字段
     * - textColumns: 字段列数，1（默认）/ 2；双列时字段行序配对并排
     */
    @PluginMethod
    public void printLabel(PluginCall call) {
        String barcodeValue = getCallString(call, "barcodeValue");
        String qrCodeValue = getCallString(call, "qrCodeValue");
        String textValue = getCallString(call, "textValue");
        String paperType = normalizePaperType(getCallString(call, "paperType"));
        String layoutPreset = getCallString(call, "layoutPreset");
        Integer qrSize = getCallOptionalInt(call, "qrSize");
        String qrAlign = getCallString(call, "qrAlign");
        Integer textColumns = getCallOptionalInt(call, "textColumns");
        String textStylesJson = getCallString(call, "textStyles");

        if (barcodeValue.isEmpty() && qrCodeValue.isEmpty() && textValue.trim().isEmpty()) {
            call.reject("printLabel requires barcodeValue, qrCodeValue or textValue");
            return;
        }

        printExecutor.execute(() -> {
            if (destroyed) { call.reject("printer destroyed"); return; }
            try {
                BuiltLabel builtLabel = buildLegacyGenericLabel(
                    getContext(),
                    barcodeValue,
                    qrCodeValue,
                    textValue,
                    layoutPreset,
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
    protected void handleOnPause() {
        super.handleOnPause();
        // App 进后台时关闭打印机，熄灭绿色连接指示灯
        if (isConnected || isConnecting) {
            wasConnectedBeforePause = true;
            isConnected  = false;
            isConnecting = false;
            android.util.Log.d("PrintPlugin", "onPause: closing printer");
            Printer.close(getActivity());
        }
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        // App 回到前台时自动重连（仅限之前已连接过的情况）
        if (wasConnectedBeforePause && !destroyed) {
            wasConnectedBeforePause = false;
            android.util.Log.d("PrintPlugin", "onResume: reconnecting printer");
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
        // 关闭打印机连接
        Printer.close(getActivity());
    }
}
