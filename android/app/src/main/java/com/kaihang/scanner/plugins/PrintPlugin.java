package com.kaihang.scanner.plugins;

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

@CapacitorPlugin(name = "PrintPlugin")
public class PrintPlugin extends Plugin {

    private static final String EVENT_STATUS = "printStatus";

    @PluginMethod
    public void connect(PluginCall call) {
        Printer.connect(
            getActivity(),
            new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(@NonNull Message msg) {
                    JSObject data = new JSObject();
                    switch (msg.what) {
                        case 101: data.put("connection", "connected"); break;
                        case 102: data.put("connection", "failed");    break;
                        case 103: data.put("connection", "closed");    break;
                        default:  return;
                    }
                    notifyListeners(EVENT_STATUS, data);
                }
            },
            (result, feedbackBytes, flag) -> {
                android.util.Log.d("PrintPlugin", "printCallback: " + result.name() + " flag=" + flag);
                JSObject data = new JSObject();
                data.put("status", result.name());
                if (flag != null) data.put("flag", flag);
                notifyListeners(EVENT_STATUS, data);
            }
        );
        call.resolve();
    }

    @PluginMethod
    public void prepareToPrintLabel(PluginCall call) {
        Printer.prepareToPrintLabel();
        call.resolve();
    }

    /**
     * 批次标签（一维码）
     * 布局 384×240：条码 80高，下方批次码 + 机器/日期/品类
     */
    @PluginMethod
    public void printBatchLabel(PluginCall call) {
        String batchNo     = call.getString("batchNo", "");
        String machineId   = call.getString("machineId", "");
        String productType = call.getString("productType", "");
        String date        = call.getString("date", "");

        if (batchNo.isEmpty()) { call.reject("batchNo is required"); return; }

        new Thread(() -> {
            try {
                Bitmap barcode = BarcodeCreater.createBarcode(
                    getContext(), batchNo, 364, 80, false, 1
                );
                if (barcode == null) { call.reject("barcode bitmap null"); return; }

                Bitmap label = new AbsoluteLayoutBitmap(384, 240)
                    .addBmp(barcode, 10, 0)
                    .addText(batchNo, 22, 10, 96)
                    .addText("机器：" + machineId + "  日期：" + date, 20, 10, 124)
                    .addText("品类：" + productType, 20, 10, 152)
                    .getBitmap();
                if (label == null) { call.reject("label bitmap null"); return; }

                android.util.Log.d("PrintPlugin", "printBatchLabel → Printer.print()");
                Printer.print(new BitmapData(label, 15, 0), 16, "batch_" + batchNo, false);
                call.resolve();
            } catch (Exception e) {
                android.util.Log.e("PrintPlugin", "printBatchLabel crash", e);
                call.reject("printBatchLabel error: " + e.getMessage(), e);
            }
        }).start();
    }

    /**
     * 机器二维码标签
     * 布局 384×300：大二维码居中，下方机器信息三行
     * QR 内容格式：machineId|productType|date
     */
    @PluginMethod
    public void printMachineQR(PluginCall call) {
        String machineId   = call.getString("machineId", "");
        String productType = call.getString("productType", "");
        String date        = call.getString("date", "");

        if (machineId.isEmpty()) { call.reject("machineId is required"); return; }

        // 后台线程执行，避免主线程 ANR
        new Thread(() -> {
            try {
                String qrContent = machineId + "|" + productType + "|" + date;

                Bitmap qr = BarcodeCreater.createBarcode(
                    getContext(), qrContent, 200, 200, false, 2
                );
                if (qr == null) {
                    call.reject("QR code generation failed for content: " + qrContent);
                    return;
                }

                // 布局高度收紧到 300，防止超出打印机最大进纸
                Bitmap label = new AbsoluteLayoutBitmap(384, 300)
                    .addBmp(qr, 92, 8)                          // 居中（(384-200)/2=92）
                    .addText("机 器：" + machineId, 24, 16, 224)
                    .addText("品 类：" + productType, 22, 16, 252)
                    .addText("日 期：" + date, 22, 16, 278)
                    .getBitmap();

                if (label == null) {
                    call.reject("Label bitmap creation failed");
                    return;
                }

                Printer.print(new BitmapData(label, 15, 0), 16, "machine_qr_" + machineId, false);
                call.resolve();
            } catch (Exception e) {
                call.reject("printMachineQR error: " + e.getMessage(), e);
            }
        }).start();
    }

    @Override
    protected void handleOnDestroy() {
        Printer.close(getActivity());
    }
}
