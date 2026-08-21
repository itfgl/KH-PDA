package com.kaihang.scanner;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

/**
 * 网页附件上传辅助：图片压缩（EXIF 转正 + 长边缩放 + JPEG/PNG 重编码）与文件选择结果解析。
 * 从 MainActivity 拆出，均为无状态静态工具；日志经 Logger 回调走 MainActivity 的运行日志。
 */
final class ImageUploadHelper {

    /** 日志回调：转发到 MainActivity 的运行日志（appendNativeLog / appendVerboseNativeLog） */
    interface Logger {
        void appendLog(String message);
        void appendVerboseLog(String message);
    }

    private static final long COMPRESSION_MIN_BYTES = 500L * 1024L;
    private static final int COMPRESSION_MAX_LONG_EDGE = 2560;
    private static final int COMPRESSION_JPEG_QUALITY = 88;

    private ImageUploadHelper() {}

    /** 解析文件选择器返回的多选/单选 Uri（含去重与 WebView 兜底解析） */
    static Uri[] extractFileChooserUris(Activity activity, Intent data, Logger logger) {
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
            Uri[] parsed = android.webkit.WebChromeClient.FileChooserParams.parseResult(Activity.RESULT_OK, data);
            if (parsed != null) {
                for (Uri uri : parsed) {
                    if (uri != null && seen.add(uri.toString())) {
                        uris.add(uri);
                    }
                }
            }
        }
        logger.appendLog(
            "文件选择结果: clipCount=" + clipCount
                + ", dataUri=" + (dataUri != null)
                + ", resolved=" + uris.size()
        );
        return uris.isEmpty() ? null : uris.toArray(new Uri[0]);
    }

    /**
     * 图片上传前优化：非压缩类型或小于阈值直接返回原文件；
     * 否则 EXIF 转正 → 长边缩到上限 → 重编码，仅当结果更小时才替换。
     */
    static Uri prepareImageForUpload(Activity activity, Uri sourceUri, Logger logger) throws java.io.IOException {
        if (sourceUri == null) {
            return null;
        }
        String mimeType = safe(activity.getContentResolver().getType(sourceUri)).toLowerCase(java.util.Locale.ROOT);
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

        long originalBytes = resolveContentLength(activity, sourceUri);
        if (originalBytes >= 0 && originalBytes < COMPRESSION_MIN_BYTES) {
            logger.appendVerboseLog("图片小于压缩阈值，直接上传: bytes=" + originalBytes);
            return sourceUri;
        }

        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (java.io.InputStream input = activity.getContentResolver().openInputStream(sourceUri)) {
            if (input == null) throw new java.io.IOException("无法读取图片");
            android.graphics.BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return sourceUri;
        }
        int sourceLongEdge = Math.max(bounds.outWidth, bounds.outHeight);
        if (isPng && sourceLongEdge <= COMPRESSION_MAX_LONG_EDGE) {
            return sourceUri;
        }

        android.graphics.BitmapFactory.Options decodeOptions = new android.graphics.BitmapFactory.Options();
        decodeOptions.inSampleSize = 1;
        while (sourceLongEdge / (decodeOptions.inSampleSize * 2) > COMPRESSION_MAX_LONG_EDGE) {
            decodeOptions.inSampleSize *= 2;
        }
        android.graphics.Bitmap bitmap;
        try (java.io.InputStream input = activity.getContentResolver().openInputStream(sourceUri)) {
            if (input == null) throw new java.io.IOException("无法读取图片像素");
            bitmap = android.graphics.BitmapFactory.decodeStream(input, null, decodeOptions);
        }
        if (bitmap == null) {
            return sourceUri;
        }

        android.graphics.Bitmap transformed = applyExifOrientation(activity, bitmap, sourceUri);
        if (transformed != bitmap) bitmap.recycle();
        android.graphics.Bitmap resized = resizeBitmapToLongEdge(transformed, COMPRESSION_MAX_LONG_EDGE);
        if (resized != transformed) transformed.recycle();

        java.io.File outputDir = new java.io.File(activity.getCacheDir(), "photo-uploads/compressed");
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
                preservePng ? 100 : COMPRESSION_JPEG_QUALITY,
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
            logger.appendVerboseLog("图片优化后未变小，继续使用原文件: before=" + originalBytes + ", after=" + compressedBytes);
            return sourceUri;
        }
        Uri outputUri = androidx.core.content.FileProvider.getUriForFile(
            activity,
            activity.getPackageName() + ".fileprovider",
            outputFile
        );
        logger.appendLog(
            "上传图片已优化: before=" + originalBytes
                + ", after=" + compressedBytes
                + ", bounds=" + bounds.outWidth + "x" + bounds.outHeight
                + ", format=" + (preservePng ? "PNG" : "JPEG")
        );
        return outputUri;
    }

    private static long resolveContentLength(Activity activity, Uri uri) {
        try (android.content.res.AssetFileDescriptor descriptor = activity.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return descriptor == null ? -1L : descriptor.getLength();
        } catch (Exception ignored) {
            return -1L;
        }
    }

    /** 按 EXIF 方向信息转正位图（拍照上传常见旋转 90°/270° 问题） */
    private static android.graphics.Bitmap applyExifOrientation(Activity activity, android.graphics.Bitmap source, Uri uri) {
        int orientation = android.media.ExifInterface.ORIENTATION_NORMAL;
        try (java.io.InputStream input = activity.getContentResolver().openInputStream(uri)) {
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

    private static android.graphics.Bitmap resizeBitmapToLongEdge(android.graphics.Bitmap source, int maxLongEdge) {
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
