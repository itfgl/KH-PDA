package com.kaihang.scanner;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class CameraScanActivity extends AppCompatActivity {
    public static final String EXTRA_SCAN_VALUE = "scanValue";
    private static final int REQUEST_CAMERA_PERMISSION = 9042;

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean processingFrame = new AtomicBoolean(false);
    private final AtomicBoolean resultDelivered = new AtomicBoolean(false);
    private final BarcodeScanner barcodeScanner = BarcodeScanning.getClient();
    private PreviewView previewView;
    private Button flashButton;
    private Camera camera;
    private boolean torchEnabled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScannerUi();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.CAMERA},
                REQUEST_CAMERA_PERMISSION
            );
        }
    }

    private void buildScannerUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        root.addView(new ScannerOverlayView(this), new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        Button closeButton = createTopButton("关闭");
        closeButton.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams closeParams = topButtonParams(Gravity.START);
        root.addView(closeButton, closeParams);

        flashButton = createTopButton("闪光灯");
        flashButton.setEnabled(false);
        flashButton.setOnClickListener(v -> toggleTorch());
        FrameLayout.LayoutParams flashParams = topButtonParams(Gravity.END);
        root.addView(flashButton, flashParams);

        TextView hint = new TextView(this);
        hint.setText("将条码或二维码放入框内");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(56)
        );
        hintParams.gravity = Gravity.BOTTOM;
        hintParams.setMargins(dp(24), 0, dp(24), dp(48));
        root.addView(hint, hintParams);

        setContentView(root);
    }

    private Button createTopButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackgroundColor(Color.argb(150, 0, 0, 0));
        return button;
    }

    private FrameLayout.LayoutParams topButtonParams(int horizontalGravity) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(92), dp(48));
        params.gravity = Gravity.TOP | horizontalGravity;
        params.setMargins(dp(16), dp(24), dp(16), 0);
        return params;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
                analysis.setAnalyzer(analysisExecutor, this::analyzeFrame);

                CameraSelector selector = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                    ? CameraSelector.DEFAULT_BACK_CAMERA
                    : CameraSelector.DEFAULT_FRONT_CAMERA;
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, selector, preview, analysis);
                boolean hasFlash = camera.getCameraInfo().hasFlashUnit();
                flashButton.setEnabled(hasFlash);
                flashButton.setVisibility(hasFlash ? View.VISIBLE : View.GONE);
            } catch (Exception error) {
                Toast.makeText(this, "摄像头启动失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (resultDelivered.get() || !processingFrame.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }
        android.media.Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            processingFrame.set(false);
            imageProxy.close();
            return;
        }
        InputImage inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.getImageInfo().getRotationDegrees()
        );
        barcodeScanner.process(inputImage)
            .addOnSuccessListener(this::handleBarcodes)
            .addOnCompleteListener(task -> {
                processingFrame.set(false);
                imageProxy.close();
            });
    }

    private void handleBarcodes(List<Barcode> barcodes) {
        for (Barcode barcode : barcodes) {
            String value = barcode.getRawValue();
            if (value == null || value.trim().isEmpty()) continue;
            if (!resultDelivered.compareAndSet(false, true)) return;
            Intent result = new Intent();
            result.putExtra(EXTRA_SCAN_VALUE, value.trim());
            setResult(RESULT_OK, result);
            finish();
            return;
        }
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        torchEnabled = !torchEnabled;
        camera.getCameraControl().enableTorch(torchEnabled);
        flashButton.setText(torchEnabled ? "关闭灯" : "闪光灯");
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        @NonNull String[] permissions,
        @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA_PERMISSION) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "需要摄像头权限才能扫码", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        barcodeScanner.close();
        analysisExecutor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ScannerOverlayView extends View {
        private final Paint shadePaint = new Paint();
        private final Paint borderPaint = new Paint();

        ScannerOverlayView(android.content.Context context) {
            super(context);
            shadePaint.setColor(Color.argb(115, 0, 0, 0));
            borderPaint.setColor(Color.WHITE);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 3f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float boxWidth = width * 0.78f;
            float boxHeight = Math.min(boxWidth * 0.72f, height * 0.38f);
            float left = (width - boxWidth) / 2f;
            float top = (height - boxHeight) / 2f - height * 0.05f;
            RectF box = new RectF(left, top, left + boxWidth, top + boxHeight);
            canvas.drawRect(0, 0, width, box.top, shadePaint);
            canvas.drawRect(0, box.bottom, width, height, shadePaint);
            canvas.drawRect(0, box.top, box.left, box.bottom, shadePaint);
            canvas.drawRect(box.right, box.top, width, box.bottom, shadePaint);
            canvas.drawRoundRect(box, 18f, 18f, borderPaint);
        }
    }
}
