package com.kaihang.scanner;

import android.app.Activity;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;

/**
 * 原生悬浮控件层：工具球（可拖拽、闲置半藏）、页面状态点、扫码胶囊按钮。
 * 只负责视图呈现与手势，业务决策（点击行为、扫码入口、日志）通过 Host 回调交给 MainActivity。
 */
final class NativeControlOverlay {
    // 悬浮球闲置半藏：1.5 秒无交互滑向最近的屏幕边缘，保留 45% 可见；任意触碰即滑回
    private static final long DOCK_DELAY_MS = 1500L;
    private static final int FAB_SIZE_DP = 56;
    private static final int MARGIN_END_DP = 18;
    private static final int MARGIN_BOTTOM_DP = 24;
    private static final int STATUS_DOT_OFFSET_END_DP = 2;
    private static final int STATUS_DOT_OFFSET_BOTTOM_DP = 46;
    private static final int SCAN_BUTTON_OFFSET_BOTTOM_DP = 68;

    interface Host {
        boolean isCameraScanEntryAvailable();

        void onFabClick(View anchor);

        void onScanClick();

        void appendLog(String message);

        void appendVerboseLog(String message);
    }

    private final Activity activity;
    private final Host host;

    private ImageButton fabButton;
    private Button scanButton;
    private View statusDot;
    private FrameLayout overlayContainer;

    private int marginEndPx = -1;
    private int marginBottomPx = -1;
    private float dragDownRawX = 0f;
    private float dragDownRawY = 0f;
    private int dragStartEndPx = 0;
    private int dragStartBottomPx = 0;
    private boolean dragging = false;
    private int touchSlopPx = 0;
    private boolean docked = false;
    private Runnable dockRunnable = null;
    private String pageReadyState = "loading";

    private NativeControlOverlay(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    static NativeControlOverlay attach(Activity activity, Host host) {
        NativeControlOverlay overlay = new NativeControlOverlay(activity, host);
        overlay.ensureViews();
        return overlay;
    }

    void setPageReadyState(String state) {
        pageReadyState = state == null ? "loading" : state;
        updateStatusDot();
    }

    void updateScanButtonVisibility() {
        if (scanButton == null) {
            return;
        }
        boolean showCameraButton = host.isCameraScanEntryAvailable();
        scanButton.setVisibility(showCameraButton ? View.VISIBLE : View.GONE);
        if (showCameraButton) {
            scanButton.setText("相机扫码");
            scanButton.setBackground(buildCapsuleBackground(false));
            scanButton.bringToFront();
        } else {
            setScanActive(false);
        }
    }

    void setScanActive(boolean active) {
        if (scanButton == null) {
            return;
        }
        scanButton.setText(host.isCameraScanEntryAvailable() ? "相机扫码" : (active ? "停扫" : "扫码"));
        scanButton.setBackground(buildCapsuleBackground(active));
    }

    void scheduleDock() {
        cancelDockTimer();
        if (fabButton == null) {
            return;
        }
        dockRunnable = this::dock;
        fabButton.postDelayed(dockRunnable, DOCK_DELAY_MS);
    }

    void postOnFab(Runnable action, long delayMs) {
        if (fabButton != null) {
            fabButton.postDelayed(action, delayMs);
        }
    }

    private void ensureViews() {
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        FrameLayout container = new FrameLayout(activity);
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        container.setLayoutParams(containerParams);
        container.setClickable(false);
        container.setFocusable(false);
        overlayContainer = container;
        touchSlopPx = ViewConfiguration.get(activity).getScaledTouchSlop();

        fabButton = new ImageButton(activity);
        fabButton.setImageResource(android.R.drawable.ic_menu_manage);
        fabButton.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        fabButton.setBackground(buildFabBackground());
        fabButton.setColorFilter(android.graphics.Color.WHITE);
        fabButton.setContentDescription("客户端工具");
        int size = dp(FAB_SIZE_DP);
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(size, size);
        fabButton.setLayoutParams(fabParams);
        fabButton.setElevation(dp(10));
        fabButton.setOnClickListener(v -> host.onFabClick(v));
        fabButton.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    undock();
                    dragDownRawX = event.getRawX();
                    dragDownRawY = event.getRawY();
                    dragStartEndPx = getMarginEndPx();
                    dragStartBottomPx = getMarginBottomPx();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float deltaX = event.getRawX() - dragDownRawX;
                    float deltaY = event.getRawY() - dragDownRawY;
                    if (!dragging) {
                        dragging = Math.hypot(deltaX, deltaY) > touchSlopPx;
                    }
                    if (dragging) {
                        int nextEnd = Math.round(dragStartEndPx - deltaX);
                        int nextBottom = Math.round(dragStartBottomPx - deltaY);
                        updateAnchor(nextEnd, nextBottom);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean handledAsClick = !dragging;
                    dragging = false;
                    scheduleDock();
                    if (handledAsClick) {
                        v.performClick();
                    }
                    return true;
                default:
                    return false;
            }
        });

        statusDot = new View(activity);
        FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(dp(12), dp(12));
        statusDot.setLayoutParams(dotParams);
        statusDot.setElevation(dp(12));
        statusDot.setBackground(buildStatusDotBackground("#98A2B3"));

        scanButton = new Button(activity);
        scanButton.setText("扫码");
        scanButton.setTextSize(14);
        scanButton.setTextColor(android.graphics.Color.WHITE);
        scanButton.setAllCaps(false);
        scanButton.setBackground(buildCapsuleBackground(false));
        scanButton.setVisibility(View.GONE);
        scanButton.setElevation(dp(8));
        FrameLayout.LayoutParams scanParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            dp(44)
        );
        scanButton.setLayoutParams(scanParams);
        scanButton.setPadding(dp(18), 0, dp(18), 0);
        scanButton.setOnClickListener(v -> host.onScanClick());

        container.addView(statusDot);
        container.addView(scanButton);
        container.addView(fabButton);
        root.addView(container);
        container.bringToFront();
        statusDot.bringToFront();
        scanButton.bringToFront();
        fabButton.bringToFront();
        updatePositions();
        updateStatusDot();
        // 布局完成后启动首次闲置计时
        fabButton.post(() -> scheduleDock());
    }

    private int getMarginEndPx() {
        if (marginEndPx < 0) {
            marginEndPx = dp(MARGIN_END_DP);
        }
        return marginEndPx;
    }

    private int getMarginBottomPx() {
        if (marginBottomPx < 0) {
            marginBottomPx = dp(MARGIN_BOTTOM_DP);
        }
        return marginBottomPx;
    }

    private void updateAnchor(int endPx, int bottomPx) {
        marginEndPx = clampHorizontalMargin(endPx);
        marginBottomPx = clampVerticalMargin(bottomPx);
        updatePositions();
    }

    private int clampHorizontalMargin(int requestedPx) {
        int overlayWidth = overlayContainer != null ? overlayContainer.getWidth() : 0;
        int buttonWidth = fabButton != null ? fabButton.getWidth() : 0;
        if (overlayWidth <= 0) {
            overlayWidth = activity.getResources().getDisplayMetrics().widthPixels;
        }
        if (buttonWidth <= 0) {
            buttonWidth = dp(FAB_SIZE_DP);
        }
        int maxMargin = Math.max(0, overlayWidth - buttonWidth);
        return Math.max(0, Math.min(requestedPx, maxMargin));
    }

    private int clampVerticalMargin(int requestedPx) {
        int overlayHeight = overlayContainer != null ? overlayContainer.getHeight() : 0;
        int buttonHeight = fabButton != null ? fabButton.getHeight() : 0;
        if (overlayHeight <= 0) {
            overlayHeight = activity.getResources().getDisplayMetrics().heightPixels;
        }
        if (buttonHeight <= 0) {
            buttonHeight = dp(FAB_SIZE_DP);
        }
        int maxMargin = Math.max(0, overlayHeight - buttonHeight);
        return Math.max(0, Math.min(requestedPx, maxMargin));
    }

    private void updatePositions() {
        if (fabButton == null || scanButton == null || statusDot == null) {
            return;
        }
        int end = getMarginEndPx();
        int bottom = getMarginBottomPx();

        FrameLayout.LayoutParams fabParams = (FrameLayout.LayoutParams) fabButton.getLayoutParams();
        fabParams.gravity = Gravity.END | Gravity.BOTTOM;
        fabParams.setMargins(0, 0, end, bottom);
        fabButton.setLayoutParams(fabParams);

        FrameLayout.LayoutParams scanParams = (FrameLayout.LayoutParams) scanButton.getLayoutParams();
        scanParams.gravity = Gravity.END | Gravity.BOTTOM;
        scanParams.setMargins(0, 0, end, bottom + dp(SCAN_BUTTON_OFFSET_BOTTOM_DP));
        scanButton.setLayoutParams(scanParams);

        FrameLayout.LayoutParams dotParams = (FrameLayout.LayoutParams) statusDot.getLayoutParams();
        dotParams.gravity = Gravity.END | Gravity.BOTTOM;
        dotParams.setMargins(0, 0, end + dp(STATUS_DOT_OFFSET_END_DP), bottom + dp(STATUS_DOT_OFFSET_BOTTOM_DP));
        statusDot.setLayoutParams(dotParams);
    }

    /** 闲置自动半藏：滑向最近的屏幕左右边缘，露出 45%，状态点跟随平移 */
    private void dock() {
        if (fabButton == null || docked || overlayContainer == null) {
            return;
        }
        int overlayWidth = overlayContainer.getWidth();
        int btnWidth = fabButton.getWidth() > 0 ? fabButton.getWidth() : dp(FAB_SIZE_DP);
        if (overlayWidth <= 0 || btnWidth <= 0) {
            return;
        }
        float visible = btnWidth * 0.45f;
        int left = fabButton.getLeft();
        boolean nearRight = (left + btnWidth / 2f) >= overlayWidth / 2f;
        float targetLeft = nearRight ? overlayWidth - visible : visible - btnWidth;
        float translation = targetLeft - left;
        docked = true;
        fabButton.animate().translationX(translation).setDuration(220).start();
        if (statusDot != null) {
            statusDot.animate().translationX(translation).setDuration(220).start();
        }
        host.appendVerboseLog("悬浮球已半藏至" + (nearRight ? "右侧" : "左侧") + "边缘，点击可唤回");
    }

    /** 唤回：滑回原位并重置闲置计时 */
    private void undock() {
        cancelDockTimer();
        if (!docked) {
            return;
        }
        docked = false;
        if (fabButton != null) {
            fabButton.animate().translationX(0f).setDuration(180).start();
        }
        if (statusDot != null) {
            statusDot.animate().translationX(0f).setDuration(180).start();
        }
    }

    private void cancelDockTimer() {
        if (dockRunnable != null && fabButton != null) {
            fabButton.removeCallbacks(dockRunnable);
        }
        dockRunnable = null;
    }

    private android.graphics.drawable.Drawable buildFabBackground() {
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

    private android.graphics.drawable.Drawable buildCapsuleBackground(boolean active) {
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        background.setCornerRadius(dp(22));
        background.setColor(android.graphics.Color.parseColor(active ? "#B42318" : "#1570EF"));
        return background;
    }

    private void updateStatusDot() {
        if (statusDot == null) {
            return;
        }
        String color = "#98A2B3";
        String description = "页面初始化中";
        if ("ready".equals(pageReadyState)) {
            color = "#12B76A";
            description = "页面已就绪";
        } else if ("error".equals(pageReadyState)) {
            color = "#F04438";
            description = "页面初始化异常";
        }
        statusDot.setBackground(buildStatusDotBackground(color));
        statusDot.setContentDescription(description);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
