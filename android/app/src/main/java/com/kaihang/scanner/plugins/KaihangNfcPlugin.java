package com.kaihang.scanner.plugins;

import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一 NFC 插件
 *
 * 支持两类卡：
 *  - NDEF 卡（NTAG213/215/216 等）：标准读写
 *  - MifareUltralight 原始卡：页读写（格式 KH + 2字节长度 + UTF-8数据）
 *
 * JS 事件：nfcEvent { techTypes[], ndefMessage?, isNdef, isMifareUltralight }
 * JS 方法：startScanning / stopScanning / writeNdef / writeMifareRaw / readMifareRaw
 */
@CapacitorPlugin(name = "KaihangNfc")
public class KaihangNfcPlugin extends Plugin {

    // 自定义格式魔数，用于识别凯航写入的数据
    private static final byte[] MAGIC = {'K', 'H'};
    // 用户数据从第 4 页开始
    private static final int DATA_START_PAGE = 4;

    private NfcAdapter adapter;
    private final AtomicReference<Tag> lastTag = new AtomicReference<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean scanningRequested = false;

    private final NfcAdapter.ReaderCallback readerCallback = tag -> {
        lastTag.set(tag);
        String[] techs = tag.getTechList();

        JSObject event = new JSObject();
        event.put("techTypes", new JSArray(Arrays.asList(techs)));

        boolean hasNdef = false;
        boolean hasMifare = false;

        for (String t : techs) {
            if (t.contains("Ndef")) hasNdef = true;
            if (t.contains("MifareUltralight")) hasMifare = true;
        }

        event.put("isNdef", hasNdef);
        event.put("isMifareUltralight", hasMifare);

        // 尝试读取 NDEF 数据
        if (hasNdef) {
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                NdefMessage msg = ndef.getCachedNdefMessage();
                if (msg != null) {
                    JSArray records = new JSArray();
                    for (NdefRecord r : msg.getRecords()) {
                        JSObject rec = new JSObject();
                        rec.put("tnf", r.getTnf());
                        rec.put("type", bytesToJSArray(r.getType()));
                        rec.put("id", bytesToJSArray(r.getId()));
                        rec.put("payload", bytesToJSArray(r.getPayload()));
                        records.put(rec);
                    }
                    event.put("ndefMessage", records);
                }
            }
        }

        // 尝试读取 MifareUltralight 原始数据
        if (hasMifare && !hasNdef) {
            MifareUltralight ul = MifareUltralight.get(tag);
            if (ul != null) {
                try {
                    ul.connect();
                    byte[] first = ul.readPages(DATA_START_PAGE);
                    if (first[0] == MAGIC[0] && first[1] == MAGIC[1]) {
                        int len = ((first[2] & 0xFF) << 8) | (first[3] & 0xFF);
                        byte[] buf = first;
                        if (len > 12 && len <= 28) {
                            byte[] second = ul.readPages(DATA_START_PAGE + 4);
                            buf = new byte[32];
                            System.arraycopy(first, 0, buf, 0, 16);
                            System.arraycopy(second, 0, buf, 16, 16);
                        } else if (len > 28) {
                            byte[] second = ul.readPages(DATA_START_PAGE + 4);
                            byte[] third  = ul.readPages(DATA_START_PAGE + 8);
                            buf = new byte[48];
                            System.arraycopy(first,  0, buf,  0, 16);
                            System.arraycopy(second, 0, buf, 16, 16);
                            System.arraycopy(third,  0, buf, 32, 16);
                        }
                        ul.close();
                        len = Math.min(len, buf.length - 4);
                        if (len > 0) {
                            String data = new String(buf, 4, len, StandardCharsets.UTF_8);
                            event.put("mifareData", data);
                        }
                    } else {
                        ul.close();
                    }
                } catch (IOException e) {
                    // 读取失败不影响事件派发
                }
            }
        }

        notifyListeners("nfcEvent", event);
    };

    @Override
    public void load() {
        adapter = NfcAdapter.getDefaultAdapter(getContext());
        // NFC 前台拦截随插件加载即启动，不依赖 JS 调用 startScanning()
        // 确保无论 H5 是否已加载、是否调用 startScanning()，贴卡都不弹系统选择框
        if (adapter != null && adapter.isEnabled()) {
            scanningRequested = true;
            // load() 在主线程调用，可直接 enableReader
            enableReader();
            android.util.Log.d("KaihangNfc", "NFC reader mode enabled on load");
        } else {
            android.util.Log.w("KaihangNfc", "NFC adapter null or disabled on load");
        }
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        // 从后台回来或锁屏解锁后重新激活 reader mode
        if (adapter != null && adapter.isEnabled()) {
            enableReader();
            android.util.Log.d("KaihangNfc", "NFC reader mode re-enabled on resume");
        }
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        if (adapter != null) {
            try { adapter.disableReaderMode(getActivity()); } catch (Exception ignored) {}
        }
    }

    /**
     * 清除卡片数据
     * - NDEF 卡：写入空 NDEF 消息（erase）
     * - MifareUltralight 卡：将页 4-7 全部写零（清除凯航格式魔数和数据）
     */
    @PluginMethod
    public void clearTag(PluginCall call) {
        Tag tag = lastTag.get();
        if (tag == null) { call.reject("No tag available"); return; }

        executor.execute(() -> {
            try {
                boolean cleared = false;

                // 尝试 NDEF erase
                Ndef ndef = Ndef.get(tag);
                if (ndef != null && ndef.isWritable()) {
                    ndef.connect();
                    ndef.writeNdefMessage(new NdefMessage(
                        new NdefRecord(NdefRecord.TNF_EMPTY, new byte[0], new byte[0], new byte[0])
                    ));
                    ndef.close();
                    cleared = true;
                    android.util.Log.d("KaihangNfc", "clearTag: NDEF erased");
                }

                // MifareUltralight：写零清页 4-7
                MifareUltralight ul = MifareUltralight.get(tag);
                if (ul != null) {
                    ul.connect();
                    byte[] zeros = new byte[4];
                    ul.writePage(DATA_START_PAGE,     zeros);
                    ul.writePage(DATA_START_PAGE + 1, zeros);
                    ul.writePage(DATA_START_PAGE + 2, zeros);
                    ul.writePage(DATA_START_PAGE + 3, zeros);
                    ul.close();
                    cleared = true;
                    android.util.Log.d("KaihangNfc", "clearTag: Mifare pages zeroed");
                }

                if (cleared) { call.resolve(); }
                else { call.reject("Tag type not supported for clear"); }
            } catch (Exception e) {
                call.reject("clearTag failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        executor.shutdownNow();
    }

    private void enableReader() {
        int flags = NfcAdapter.FLAG_READER_NFC_A
                | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_NFC_F
                | NfcAdapter.FLAG_READER_NFC_V
                | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;
        adapter.enableReaderMode(getActivity(), readerCallback, flags, null);
    }

    @PluginMethod
    public void startScanning(PluginCall call) {
        if (adapter == null) { call.reject("NFC not available"); return; }
        scanningRequested = true;
        enableReader();
        call.resolve();
    }

    @PluginMethod
    public void stopScanning(PluginCall call) {
        scanningRequested = false;
        if (adapter != null) {
            try { adapter.disableReaderMode(getActivity()); } catch (Exception ignored) {}
        }
        call.resolve();
    }

    /** 写入 NDEF（适用于 NTAG213/215/216） */
    @PluginMethod
    public void writeNdef(PluginCall call) {
        Tag tag = lastTag.get();
        if (tag == null) { call.reject("No tag available"); return; }

        String data = call.getString("data", "");
        boolean allowFormat = Boolean.TRUE.equals(call.getBoolean("allowFormat", true));

        executor.execute(() -> {
            try {
                byte[] uriBytes = ("kaihang://nfc/" + data).getBytes(StandardCharsets.UTF_8);
                byte[] payload = new byte[1 + uriBytes.length];
                payload[0] = 0x00;
                System.arraycopy(uriBytes, 0, payload, 1, uriBytes.length);

                NdefRecord uriRecord = new NdefRecord(
                        NdefRecord.TNF_WELL_KNOWN,
                        NdefRecord.RTD_URI,
                        new byte[0],
                        payload);

                NdefRecord aarRecord = NdefRecord.createApplicationRecord("com.kaihang.scanner");
                NdefMessage message = new NdefMessage(uriRecord, aarRecord);

                Ndef ndef = Ndef.get(tag);
                if (ndef != null) {
                    ndef.connect();
                    ndef.writeNdefMessage(message);
                    ndef.close();
                    call.resolve();
                    return;
                }

                if (allowFormat) {
                    NdefFormatable formatable = NdefFormatable.get(tag);
                    if (formatable != null) {
                        formatable.connect();
                        formatable.format(message);
                        formatable.close();
                        call.resolve();
                        return;
                    }
                }

                call.reject("Tag does not support NDEF");
            } catch (Exception e) {
                call.reject("NDEF write failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 写入 MifareUltralight 原始数据
     * 格式：KH(2) + length(2, big-endian) + UTF-8 data
     * 写入从第 4 页开始，每页 4 字节
     */
    @PluginMethod
    public void writeMifareRaw(PluginCall call) {
        Tag tag = lastTag.get();
        if (tag == null) { call.reject("No tag available"); return; }

        String data = call.getString("data", "");
        if (data.isEmpty()) { call.reject("data is required"); return; }

        executor.execute(() -> {
            MifareUltralight ul = MifareUltralight.get(tag);
            if (ul == null) { call.reject("Not a MifareUltralight tag"); return; }

            try {
                byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
                // 组装：KH + 2字节长度 + 数据，补齐到 4 字节倍数
                int headerLen = 4;
                int totalLen = headerLen + dataBytes.length;
                int paddedLen = ((totalLen + 3) / 4) * 4;
                byte[] buf = new byte[paddedLen];
                buf[0] = MAGIC[0];
                buf[1] = MAGIC[1];
                buf[2] = (byte)((dataBytes.length >> 8) & 0xFF);
                buf[3] = (byte)(dataBytes.length & 0xFF);
                System.arraycopy(dataBytes, 0, buf, 4, dataBytes.length);

                ul.connect();
                for (int i = 0; i < buf.length; i += 4) {
                    ul.writePage(DATA_START_PAGE + (i / 4), Arrays.copyOfRange(buf, i, i + 4));
                }
                ul.close();
                call.resolve();
            } catch (IOException e) {
                call.reject("MifareUltralight write failed: " + e.getMessage(), e);
            }
        });
    }

    /** 读取 MifareUltralight 原始数据（解析凯航格式） */
    @PluginMethod
    public void readMifareRaw(PluginCall call) {
        Tag tag = lastTag.get();
        if (tag == null) { call.reject("No tag available"); return; }

        executor.execute(() -> {
            MifareUltralight ul = MifareUltralight.get(tag);
            if (ul == null) { call.reject("Not a MifareUltralight tag"); return; }

            try {
                ul.connect();
                // 先读第一批 16 字节拿到头部和长度
                byte[] first = ul.readPages(DATA_START_PAGE);
                JSObject result = new JSObject();
                if (first[0] != MAGIC[0] || first[1] != MAGIC[1]) {
                    ul.close();
                    result.put("data", "");
                    result.put("isKaihang", false);
                    result.put("raw", bytesToJSArray(first));
                    call.resolve(result);
                    return;
                }
                int len = ((first[2] & 0xFF) << 8) | (first[3] & 0xFF);
                // 需要的总字节数（头 4 字节 + 数据）
                int totalNeeded = 4 + len;
                byte[] buf;
                if (totalNeeded <= 16) {
                    buf = first;
                } else {
                    // 继续读下一批（页 4+4 = 页 8），拼接
                    byte[] second = ul.readPages(DATA_START_PAGE + 4); // pages 8-11
                    buf = new byte[first.length + second.length];
                    System.arraycopy(first, 0, buf, 0, first.length);
                    System.arraycopy(second, 0, buf, first.length, second.length);
                    // 如果还不够继续读（最多 44 字节，3 次 readPages 即可覆盖）
                    if (totalNeeded > 32) {
                        byte[] third = ul.readPages(DATA_START_PAGE + 8); // pages 12-15
                        byte[] tmp = new byte[buf.length + third.length];
                        System.arraycopy(buf, 0, tmp, 0, buf.length);
                        System.arraycopy(third, 0, tmp, buf.length, third.length);
                        buf = tmp;
                    }
                }
                ul.close();
                len = Math.min(len, buf.length - 4);
                String data = new String(buf, 4, len, StandardCharsets.UTF_8);
                result.put("data", data);
                result.put("isKaihang", true);
                call.resolve(result);
            } catch (IOException e) {
                call.reject("MifareUltralight read failed: " + e.getMessage(), e);
            }
        });
    }

    private JSArray bytesToJSArray(byte[] bytes) {
        JSArray arr = new JSArray();
        if (bytes != null) for (byte b : bytes) arr.put(b & 0xFF);
        return arr;
    }
}
