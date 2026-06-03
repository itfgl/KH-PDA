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

/**
 * 打印 Plugin
 *
 * 封装 uc_pda_sdk AAR（com.uc.pdasdk），向 H5 提供批次标签打印能力。
 *
 * JS 调用：
 *   PrintPlugin.connect()
 *   PrintPlugin.prepareToPrintLabel()
 *   PrintPlugin.printBatchLabel({ batchNo, machineId, productType, date })
 *   PrintPlugin.addListener('printStatus', (data) => { data.status / data.connection })
 */
@CapacitorPlugin(name = "PrintPlugin")
public class PrintPlugin extends Plugin {

    private static final String EVENT_STATUS  = "printStatus";
    private static final int    LABEL_WIDTH   = 384;
    private static final int    LABEL_HEIGHT  = 200;

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
                JSObject data = new JSObject();
                data.put("status", result.name());
                if (flag != null) data.put("flag", flag);
                notifyListeners(EVENT_STATUS, data);
            }
        );
        call.resolve();
    }

    /** 走纸到标签起始位置，printBatchLabel 之前调用 */
    @PluginMethod
    public void prepareToPrintLabel(PluginCall call) {
        Printer.prepareToPrintLabel();
        call.resolve();
    }

    /**
     * 打印批次标签
     *
     * 参数：
     *   batchNo     (String, required) 15位批次码，如 M05260604030012
     *   machineId   (String, optional) 机器编号
     *   productType (String, optional) 产品类型
     *   date        (String, optional) 日期 YYMMDD
     *
     * 标签布局 384×200 点：
     *   0~55   一维码（Code128）
     *   75     批次码明文
     *   105    机器编号 + 日期
     *   135    产品类型
     */
    @PluginMethod
    public void printBatchLabel(PluginCall call) {
        String batchNo     = call.getString("batchNo", "");
        String machineId   = call.getString("machineId", "");
        String productType = call.getString("productType", "");
        String date        = call.getString("date", "");

        if (batchNo.isEmpty()) {
            call.reject("batchNo is required");
            return;
        }

        Bitmap barcode = BarcodeCreater.createBarcode(
            getContext(), batchNo, LABEL_WIDTH - 20, 55, false, 1
        );

        Bitmap label = new AbsoluteLayoutBitmap(LABEL_WIDTH, LABEL_HEIGHT)
            .addBmp(barcode, 10, 0)
            .addText(batchNo, 22, 10, 75)
            .addText("机器：" + machineId + "  日期：" + date, 20, 10, 105)
            .addText("品类：" + productType, 20, 10, 135)
            .getBitmap();

        Printer.print(new BitmapData(label, 15, 0), 16, "batch_" + batchNo, false);
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        Printer.close(getActivity());
    }
}
