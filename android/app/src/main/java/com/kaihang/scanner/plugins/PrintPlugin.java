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
import com.uc.pdasdk.utils.AbsoluteLayoutBitmap;
import com.uc.pdasdk.utils.BarcodeCreater;

import java.util.ArrayList;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
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
        String diagnosticSource
    ) {
        if (context == null) throw new IllegalArgumentException("context is required");
        String safeBarcodeValue = barcodeValue == null ? "" : barcodeValue.trim();
        String safeQrCodeValue = qrCodeValue == null ? "" : qrCodeValue.trim();
        String safeTextValue = textValue == null ? "" : textValue.trim();
        if (safeBarcodeValue.isEmpty() && safeQrCodeValue.isEmpty() && safeTextValue.isEmpty()) {
            throw new IllegalArgumentException("printLabel requires barcodeValue, qrCodeValue or textValue");
        }

        GenericLabelLayout layout = getGenericLabelLayout(layoutPreset);
        Bitmap barcode = null;
        Bitmap qr = null;
        if (!safeBarcodeValue.isEmpty()) {
            barcode = BarcodeCreater.createBarcode(context, safeBarcodeValue, layout.barcodeWidth, layout.barcodeHeight, false, 1);
            if (barcode == null) throw new IllegalStateException("barcode bitmap null");
        }
        if (!safeQrCodeValue.isEmpty()) {
            qr = BarcodeCreater.createBarcode(context, safeQrCodeValue, layout.qrWidth, layout.qrHeight, false, 2);
            if (qr == null) throw new IllegalStateException("qr bitmap null");
        }

        List<String> textLines = wrapPlainText(safeTextValue, layout.wrapUnits);
        int bodyTop = 16;
        if (barcode != null) {
            bodyTop = layout.barcodeTop + layout.barcodeHeight + layout.mediaGap;
        }
        if (qr != null) {
            bodyTop = Math.max(bodyTop, layout.qrTop + layout.qrHeight + layout.mediaGap);
        }
        int bodyHeight = Math.max(1, textLines.size()) * layout.lineHeight;
        int labelHeight = Math.max(bodyTop + bodyHeight + 24, layout.minHeight);
        int barcodeLeft = barcode != null ? resolveCenteredMediaLeft(layout.barcodeWidth) : -1;
        int qrLeft = qr != null ? resolveCenteredMediaLeft(layout.qrWidth) : -1;

        AbsoluteLayoutBitmap builder = new AbsoluteLayoutBitmap(GenericLabelLayout.LABEL_WIDTH, labelHeight);
        if (barcode != null) {
            builder.addBmp(barcode, barcodeLeft, layout.barcodeTop);
        }
        if (qr != null) {
            builder.addBmp(qr, qrLeft, layout.qrTop);
        }

        int y = bodyTop;
        for (String line : textLines) {
            builder.addText(line, layout.textSize, resolveLeftAlignedTextLeft(), y);
            y += layout.lineHeight;
        }

        Bitmap label = builder.getBitmap();
        if (label == null) throw new IllegalStateException("label bitmap null");
        String diagnostic =
            "layoutPreset=" + normalizeLayoutPreset(layoutPreset)
                + ", requestBarcode=" + layout.barcodeWidth + "x" + layout.barcodeHeight
                + ", actualBarcode=" + bitmapSize(barcode)
                + ", barcodeLeft=" + barcodeLeft
                + ", requestQr=" + layout.qrWidth + "x" + layout.qrHeight
                + ", actualQr=" + bitmapSize(qr)
                + ", qrLeft=" + qrLeft
                + ", label=" + bitmapSize(label)
                + ", bodyTop=" + bodyTop
                + ", lines=" + textLines.size()
                + ", source=" + diagnosticSource;
        return new BuiltLabel(label, diagnostic);
    }

    private static void printBuiltLabel(Bitmap label, String paperType, String jobName) {
        String normalizedPaperType = normalizePaperType(paperType);
        if (PAPER_BLACK_MARK.equals(normalizedPaperType)) {
            Printer.print(new BitmapData(label, 15, 0), 8, jobName, false);
        } else {
            Printer.print(new BitmapData(label, 15, false), 8, BATCH_EXTRA_FEED, jobName, false);
        }
    }

    public static void printLabelNative(Context context, Activity activity, String barcodeValue, String qrCodeValue, String textValue, String paperType, String layoutPreset) {
        if (context == null || activity == null) return;
        connectNative(activity);
        nativePrintExecutor.execute(() -> {
            try {
                if (!nativeConnected && !waitForNativeConnection(NATIVE_CONNECT_TIMEOUT_MS)) {
                    emitNativeStatus("PRINT_BRIDGE_ERROR", "printer connect timeout");
                    return;
                }
                String normalizedPaperType = normalizePaperType(paperType);
                BuiltLabel builtLabel = buildUnifiedLabel(
                    context,
                    barcodeValue,
                    qrCodeValue,
                    textValue,
                    layoutPreset,
                    "printLabelNative"
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

    private static int resolveCenteredMediaLeft(int mediaWidth) {
        return Math.max(0, (GenericLabelLayout.LABEL_WIDTH - mediaWidth) / 2);
    }

    private static int resolveNinetyPercentBarcodeWidth() {
        return (int) Math.round(GenericLabelLayout.LABEL_WIDTH * GenericLabelLayout.BARCODE_WIDTH_RATIO);
    }

    private static GenericLabelLayout getGenericLabelLayout(String preset) {
        switch (normalizeLayoutPreset(preset)) {
            case "compact":
                return new GenericLabelLayout(346, 96, 118, 118, 8, 138, 24, 26, 32, 228, 24);
            case "large":
                return new GenericLabelLayout(346, 122, 144, 144, 8, 162, 24, 30, 38, 276, 22);
            default:
                return new GenericLabelLayout(346, 108, 132, 132, 8, 148, 24, 28, 36, 252, 24);
        }
    }

    private static int estimateTextWidth(String text, int textSize) {
        if (text == null || text.isEmpty()) return 0;
        int units = 0;
        for (int i = 0; i < text.length(); i++) {
            units += charUnits(text.charAt(i));
        }
        return Math.max(textSize * 2, (units * textSize) / 2);
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
     * 布局 384×270：大二维码居中，下方机器编号 + 打印时间
     * QR 内容格式：machineId（仅机器编号）
     */
    @PluginMethod
    public void printMachineQR(PluginCall call) {
        String machineId = call.getString("machineId", "");

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
     */
    @PluginMethod
    public void printLabel(PluginCall call) {
        String barcodeValue = getCallString(call, "barcodeValue");
        String qrCodeValue = getCallString(call, "qrCodeValue");
        String textValue = getCallString(call, "textValue");
        String paperType = normalizePaperType(getCallString(call, "paperType"));
        String layoutPreset = getCallString(call, "layoutPreset");

        if (barcodeValue.isEmpty() && qrCodeValue.isEmpty() && textValue.trim().isEmpty()) {
            call.reject("printLabel requires barcodeValue, qrCodeValue or textValue");
            return;
        }

        printExecutor.execute(() -> {
            if (destroyed) { call.reject("printer destroyed"); return; }
            try {
                BuiltLabel builtLabel = buildUnifiedLabel(
                    getContext(),
                    barcodeValue,
                    qrCodeValue,
                    textValue,
                    layoutPreset,
                    "printLabelPlugin"
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
