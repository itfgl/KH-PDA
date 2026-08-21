package com.kaihang.scanner.plugins;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import com.uc.pdasdk.utils.AbsoluteLayoutBitmap;
import com.uc.pdasdk.utils.BarcodeCreater;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * 标签排版引擎（从 PrintPlugin 拆出）：二维码位图生成与白边裁剪、字段单/双列排版、
 * 二维码右侧区域、居中行落位，输出 BuiltLabel（位图 + 诊断信息）。
 * 纯静态工具，不持有打印机连接与打印执行状态——打印调度仍在 PrintPlugin。
 */
final class LabelLayoutBuilder {

    private LabelLayoutBuilder() {}

    /** 排版产物：标签位图 + 诊断信息（供运行日志输出） */
    static final class BuiltLabel {
        final Bitmap label;
        final String diagnostic;

        BuiltLabel(Bitmap label, String diagnostic) {
            this.label = label;
            this.diagnostic = diagnostic;
        }
    }

    // ── 位图工具 ───────────────────────────────────────────────────────────────

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

    private static int charUnits(char ch) {
        return ch <= 0x7f ? 1 : 2;
    }

    /** 标签画布与二维码尺寸常量 */
    private static final class GenericLabelLayout {
        static final int LABEL_WIDTH = 384;
        // 默认二维码为约 40% 画布宽（154 点）
        static final double QR_WIDTH_RATIO = 0.40d;
        static final int QR_MIN_SIZE = 60;
        static final int QR_MAX_SIZE = LABEL_WIDTH;
    }

    /** 通用标签固定布局（标准档）：字号 24 / 行高 32 / 最小高 280 */
    private static final class LegacyGenericLayout {
        final int qrWidth;
        final int qrHeight;
        final int textSize;
        final int lineHeight;
        final int minHeight;
        final int textLeft;
        final int wrapUnits;

        LegacyGenericLayout(
            int qrWidth,
            int qrHeight,
            int textSize,
            int lineHeight,
            int minHeight,
            int textLeft,
            int wrapUnits
        ) {
            this.qrWidth = qrWidth;
            this.qrHeight = qrHeight;
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

    private static LegacyGenericLayout getLegacyGenericLayout(Integer qrSize) {
        int resolvedQrSize = resolveRequestedQrSize(qrSize);
        return new LegacyGenericLayout(resolvedQrSize, resolvedQrSize, 24, 32, 280, 8, 30);
    }

    // ── 文本测量与换行 ─────────────────────────────────────────────────────────

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
        int besideBottom = 0;     // 右侧列内容底部 y（可延伸超过二维码底部）

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
     * - besideEnabled（二维码靠左时）：右侧窄列收普通字段（含带字号），从上方整个媒体区顶部
     *   （besideRegionTop，有一维码时为一维码顶部）依次往下排，容量按整个区域高度算，装不下转入下方；
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
        int besideRegionTop,
        int besideRegionHeight,
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

        // 二维码右侧窄列：字段（含带字号）从上方整个媒体区顶部依次往下排，
        // 容量按整个区域高度（一维码段+间距+二维码段）计算，超出部分转入下方
        if (besideEnabled && !normalIndexes.isEmpty() && besideRowCapacity > 0 && besideWidth > 0) {
            int besideUnits = columnWrapUnits(baseWrapUnits, besideWidth);
            List<int[]> besideRows = new ArrayList<>(); // {rowStart, rowCount, rowUnits, styleSize}
            List<List<String>> besideWrapped = new ArrayList<>();
            int usedRows = 0;
            int position = 0;
            while (position < normalIndexes.size()) {
                int fieldIndex = normalIndexes.get(position);
                // 带字号行按实际行高折算等效行数，行距加高
                int styleSize = styles.get(fieldIndex).size;
                int rowUnits = Math.max(1, (int) Math.round((double) styledLineHeight(lineHeight, styles.get(fieldIndex)) / lineHeight));
                List<String> wrapped = wrapPlainText(fields.get(fieldIndex), besideUnits);
                if (wrapped.isEmpty()) {
                    wrapped = new ArrayList<>();
                    wrapped.add("");
                }
                if (usedRows + wrapped.size() * rowUnits > besideRowCapacity) break;
                besideRows.add(new int[]{usedRows, wrapped.size(), rowUnits, styleSize});
                besideWrapped.add(wrapped);
                usedRows += wrapped.size() * rowUnits;
                position++;
            }
            if (position > 0) {
                // 右侧列从上方媒体区顶部开始排，起始偏移由用户模板开头空行控制
                int startY = besideRegionTop;
                for (int i = 0; i < besideRows.size(); i++) {
                    int rowStart = besideRows.get(i)[0];
                    int rowUnits = besideRows.get(i)[2];
                    int styleSize = besideRows.get(i)[3];
                    for (int k = 0; k < besideWrapped.get(i).size(); k++) {
                        plan.rows.add(new TextRow(besideWrapped.get(i).get(k), besideX, startY + (rowStart + k * rowUnits) * lineHeight, styleSize));
                    }
                }
                plan.besideBottom = startY + usedRows * lineHeight;
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
            // centerY 取下方内容底与右侧列底较大者，避免与右列延伸部分重叠
            plan.centerY = Math.max(remaining.isEmpty() && plan.hasCenterRows() ? mediaBottom : y, plan.besideBottom);
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
            // centerY 取下方内容底与右侧列底较大者，避免与右列延伸部分重叠
            if (remaining.isEmpty()) {
                plan.centerY = Math.max(plan.hasCenterRows() ? mediaBottom : belowY, plan.besideBottom);
            } else {
                plan.centerY = Math.max(y, plan.besideBottom);
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

    // ── 标签构建入口 ───────────────────────────────────────────────────────────

    static BuiltLabel buildLegacyGenericLabel(
        Context context,
        String qrCodeValue,
        String textValue,
        Integer qrSize,
        String qrAlign,
        Integer textColumns,
        String textStylesJson,
        String diagnosticSource
    ) {
        if (context == null) throw new IllegalArgumentException("context is required");
        String safeQrCodeValue = qrCodeValue == null ? "" : qrCodeValue.trim();
        String safeTextValue = textValue == null ? "" : textValue;
        if (safeQrCodeValue.isEmpty() && safeTextValue.trim().isEmpty()) {
            throw new IllegalArgumentException("printLabel requires qrCodeValue or textValue");
        }

        int columns = normalizeTextColumns(textColumns);
        LegacyGenericLayout layout = getLegacyGenericLayout(qrSize);
        Bitmap qr = null;
        if (!safeQrCodeValue.isEmpty()) {
            qr = BarcodeCreater.createBarcode(context, safeQrCodeValue, layout.qrWidth, layout.qrHeight, false, 2);
            if (qr == null) throw new IllegalStateException("qr bitmap null");
            // 裁掉二维码位图四周白边（quiet zone），字段才能紧贴二维码黑块本身
            qr = cropBitmapToContent(qr);
        }

        // 二维码靠左时右侧放字段
        boolean qrLeftAligned = qr != null && "left".equals(normalizeQrAlign(qrAlign));
        boolean pureQrLabel = qr != null;
        // 二维码实际位图尺寸参与布局：SDK 生成的 QR 可能小于请求尺寸，按实际值预留
        int qrEffHeight = qr != null ? qr.getHeight() : layout.qrHeight;
        int qrEffWidth = qr != null ? qr.getWidth() : layout.qrWidth;
        // 居中按裁剪白边后的实际宽度计算，保证二维码真正水平居中；靠左贴左页边
        int qrLeft = qrLeftAligned ? TEXT_MARGIN : resolveCenteredMediaLeft(qrEffWidth);
        int besideX = 0;
        int besideWidth = 0;
        if (qrLeftAligned) {
            besideX = qrLeft + qrEffWidth + BESIDE_GAP;
            besideWidth = GenericLabelLayout.LABEL_WIDTH - TEXT_MARGIN - besideX;
        }
        // 二维码顶部页边距：基础上边距再加一行高度
        int qrTopPad = 8 + layout.lineHeight;
        int mediaBottom = qr != null ? qrTopPad + qrEffHeight : 0;
        // 二维码与下方字段零间距紧贴，空白由用户模板控制
        int belowStartY = mediaBottom > 0 ? mediaBottom : 16;
        // 右侧字段区域：二维码顶部到二维码底部
        int besideRegionTop = qrTopPad;
        int besideRegionHeight = Math.max(0, qrTopPad + qrEffHeight - besideRegionTop);
        int besideRowCapacity = qrLeftAligned ? Math.max(1, besideRegionHeight / layout.lineHeight) : 0;

        FieldTextPlan plan = planFieldText(
            safeTextValue,
            columns,
            qrLeftAligned,
            besideX,
            besideWidth,
            besideRowCapacity,
            besideRegionTop,
            besideRegionHeight,
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
        if (qr != null) {
            builder.addBmp(qr, qrLeft, qrTopPad);
        }
        for (TextRow row : plan.rows) {
            int textSize = row.size > 0 ? row.size : layout.textSize;
            builder.addText(row.text, textSize, row.x, textBaselineY(row.y, textSize));
        }

        Bitmap label = builder.getBitmap();
        if (label == null) throw new IllegalStateException("label bitmap null");
        String diagnostic =
            "legacyGeneric=true"
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

    static BuiltLabel buildPortablePreviewLabel(
        String qrCodeValue,
        String textValue,
        Integer qrSize,
        String qrAlign,
        Integer textColumns,
        String textStylesJson,
        String diagnosticSource
    ) throws Exception {
        String safeQrCodeValue = qrCodeValue == null ? "" : qrCodeValue.trim();
        String safeTextValue = textValue == null ? "" : textValue;
        if (safeQrCodeValue.isEmpty() && safeTextValue.trim().isEmpty()) {
            throw new IllegalArgumentException("preview requires qrCodeValue or textValue");
        }

        int columns = normalizeTextColumns(textColumns);
        LegacyGenericLayout layout = getLegacyGenericLayout(qrSize);
        Bitmap qr = safeQrCodeValue.isEmpty()
            ? null
            : createPortableCode(safeQrCodeValue, BarcodeFormat.QR_CODE, layout.qrWidth, layout.qrHeight);
        // 裁掉二维码位图四周白边（quiet zone），与实纸打印行为一致
        if (qr != null) qr = cropBitmapToContent(qr);

        // 二维码靠左时右侧放字段
        boolean qrLeftAligned = qr != null && "left".equals(normalizeQrAlign(qrAlign));
        boolean pureQrLabel = qr != null;
        // 实际位图尺寸参与布局，与实纸打印保持一致
        int qrEffHeight = qr != null ? qr.getHeight() : layout.qrHeight;
        int qrEffWidth = qr != null ? qr.getWidth() : layout.qrWidth;
        // 居中按裁剪白边后的实际宽度计算，与实纸打印保持一致
        int qrLeft = qrLeftAligned ? TEXT_MARGIN : resolveCenteredMediaLeft(qrEffWidth);
        int besideX = 0;
        int besideWidth = 0;
        if (qrLeftAligned) {
            besideX = qrLeft + qrEffWidth + BESIDE_GAP;
            besideWidth = GenericLabelLayout.LABEL_WIDTH - TEXT_MARGIN - besideX;
        }
        // 二维码顶部页边距：基础上边距再加一行高度，与实纸打印保持一致
        int qrTopPad = 8 + layout.lineHeight;
        int mediaBottom = qr != null ? qrTopPad + qrEffHeight : 0;
        // 二维码与下方字段零间距紧贴，与实纸打印保持一致
        int belowStartY = mediaBottom > 0 ? mediaBottom : 16;
        // 右侧字段区域：二维码顶部到二维码底部
        int besideRegionTop = qrTopPad;
        int besideRegionHeight = Math.max(0, qrTopPad + qrEffHeight - besideRegionTop);
        int besideRowCapacity = qrLeftAligned ? Math.max(1, besideRegionHeight / layout.lineHeight) : 0;

        FieldTextPlan plan = planFieldText(
            safeTextValue,
            columns,
            qrLeftAligned,
            besideX,
            besideWidth,
            besideRowCapacity,
            besideRegionTop,
            besideRegionHeight,
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
        if (qr != null) canvas.drawBitmap(qr, qrLeft, qrTopPad, null);

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
}
