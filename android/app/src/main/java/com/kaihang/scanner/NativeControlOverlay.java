package com.kaihang.scanner;

import android.app.Activity;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;

/**
 * 原生悬浮控件层：工具球（可拖拽、闲置半藏）、页面状态点、相机扫码图标按钮（独立拖拽）。
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
    // 更新提醒红点：贴在工具球右上角（状态点上方），仅检测到标记版本的新更新时显示
    private static final int UPDATE_BADGE_SIZE_DP = 12;
    private static final int UPDATE_BADGE_OFFSET_END_DP = STATUS_DOT_OFFSET_END_DP;
    private static final int UPDATE_BADGE_OFFSET_BOTTOM_DP = 62;
    // 扫码按钮独立锚点：默认停在工具球正上方（与旧版跟随位置一致），支持单独拖动
    private static final int SCAN_BUTTON_SIZE_DP = 48;
    private static final int SCAN_MARGIN_END_DP = MARGIN_END_DP;
    private static final int SCAN_MARGIN_BOTTOM_DP = MARGIN_BOTTOM_DP + 68;

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
    private ImageButton scanButton;
    private View statusDot;
    private View updateBadge;
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
    // 扫码按钮独立位置与拖拽状态（不随工具球移动）
    private int scanMarginEndPx = -1;
    private int scanMarginBottomPx = -1;
    private float scanDragDownRawX = 0f;
    private float scanDragDownRawY = 0f;
    private int scanDragStartEndPx = 0;
    private int scanDragStartBottomPx = 0;
    private boolean scanDragging = false;

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
            scanButton.setBackground(buildCircleBackground(false));
            scanButton.bringToFront();
        } else {
            setScanActive(false);
        }
    }

    void setScanActive(boolean active) {
        if (scanButton == null) {
            return;
        }
        scanButton.setBackground(buildCircleBackground(active));
    }

    /** 更新提醒红点显隐：检测到标记了提示/强制更新的新版本时显示，更新后自动隐藏 */
    void setUpdateBadgeVisible(boolean visible) {
        if (updateBadge == null) {
            return;
        }
        updateBadge.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            updateBadge.bringToFront();
        }
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

        updateBadge = new View(activity);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(UPDATE_BADGE_SIZE_DP), dp(UPDATE_BADGE_SIZE_DP));
        updateBadge.setLayoutParams(badgeParams);
        updateBadge.setElevation(dp(13));
        updateBadge.setBackground(buildUpdateBadgeBackground());
        updateBadge.setVisibility(View.GONE);
        updateBadge.setContentDescription("有新版本可更新");

        scanButton = new ImageButton(activity);
        scanButton.setImageResource(android.R.drawable.ic_menu_camera);
        scanButton.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        scanButton.setColorFilter(android.graphics.Color.WHITE);
        scanButton.setContentDescription("相机扫码");
        scanButton.setBackground(buildCircleBackground(false));
        scanButton.setVisibility(View.GONE);
        scanButton.setElevation(dp(8));
        int scanSize = dp(SCAN_BUTTON_SIZE_DP);
        FrameLayout.LayoutParams scanParams = new FrameLayout.LayoutParams(scanSize, scanSize);
        scanButton.setLayoutParams(scanParams);
        scanButton.setOnClickListener(v -> host.onScanClick());
        // 独立拖拽：位置不随工具球移动，拖动只更新自己的锚点
        scanButton.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scanDragDownRawX = event.getRawX();
                    scanDragDownRawY = event.getRawY();
                    scanDragStartEndPx = getScanMarginEndPx();
                    scanDragStartBottomPx = getScanMarginBottomPx();
                    scanDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float deltaX = event.getRawX() - scanDragDownRawX;
                    float deltaY = event.getRawY() - scanDragDownRawY;
                    if (!scanDragging) {
                        scanDragging = Math.hypot(deltaX, deltaY) > touchSlopPx;
                    }
                    if (scanDragging) {
                        int nextEnd = Math.round(scanDragStartEndPx - deltaX);
                        int nextBottom = Math.round(scanDragStartBottomPx - deltaY);
                        updateScanAnchor(nextEnd, nextBottom);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    scanDragging = false;
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean handledAsClick = !scanDragging;
                    scanDragging = false;
                    if (handledAsClick) {
                        v.performClick();
                    }
                    return true;
                default:
                    return false;
            }
        });

        container.addView(statusDot);
        container.addView(scanButton);
        container.addView(fabButton);
        container.addView(updateBadge);
        root.addView(container);
        container.bringToFront();
        statusDot.bringToFront();
        scanButton.bringToFront();
        fabButton.bringToFront();
        updateBadge.bringToFront();
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

    private int getScanMarginEndPx() {
        if (scanMarginEndPx < 0) {
            scanMarginEndPx = dp(SCAN_MARGIN_END_DP);
        }
        return scanMarginEndPx;
    }

    private int getScanMarginBottomPx() {
        if (scanMarginBottomPx < 0) {
            scanMarginBottomPx = dp(SCAN_MARGIN_BOTTOM_DP);
        }
        return scanMarginBottomPx;
    }

    private void updateAnchor(int endPx, int bottomPx) {
        marginEndPx = clampMargin(endPx, true, fabButton, FAB_SIZE_DP);
        marginBottomPx = clampMargin(bottomPx, false, fabButton, FAB_SIZE_DP);
        updateFabPositions();
    }

    private void updateScanAnchor(int endPx, int bottomPx) {
        scanMarginEndPx = clampMargin(endPx, true, scanButton, SCAN_BUTTON_SIZE_DP);
        scanMarginBottomPx = clampMargin(bottomPx, false, scanButton, SCAN_BUTTON_SIZE_DP);
        updateScanPosition();
    }

    /** 按钮边距钳制：限制在 overlay 可视范围内（布局未完成时用屏幕尺寸兜底） */
    private int clampMargin(int requestedPx, boolean horizontal, View button, int fallbackSizeDp) {
        int overlaySize = 0;
        if (overlayContainer != null) {
            overlaySize = horizontal ? overlayContainer.getWidth() : overlayContainer.getHeight();
        }
        if (overlaySize <= 0) {
            overlaySize = horizontal
                ? activity.getResources().getDisplayMetrics().widthPixels
                : activity.getResources().getDisplayMetrics().heightPixels;
        }
        int buttonSize = 0;
        if (button != null) {
            buttonSize = horizontal ? button.getWidth() : button.getHeight();
        }
        if (buttonSize <= 0) {
            buttonSize = dp(fallbackSizeDp);
        }
        int maxMargin = Math.max(0, overlaySize - buttonSize);
        return Math.max(0, Math.min(requestedPx, maxMargin));
    }

    /** 工具球与状态点、更新红点共用锚点（状态点/红点贴工具球）；扫码按钮独立锚点，互不影响 */
    private void updateFabPositions() {
        if (fabButton == null) {
            return;
        }
        int end = getMarginEndPx();
        int bottom = getMarginBottomPx();

        FrameLayout.LayoutParams fabParams = (FrameLayout.LayoutParams) fabButton.getLayoutParams();
        fabParams.gravity = Gravity.END | Gravity.BOTTOM;
        fabParams.setMargins(0, 0, end, bottom);
        fabButton.setLayoutParams(fabParams);

        if (statusDot != null) {
            FrameLayout.LayoutParams dotParams = (FrameLayout.LayoutParams) statusDot.getLayoutParams();
            dotParams.gravity = Gravity.END | Gravity.BOTTOM;
            dotParams.setMargins(0, 0, end + dp(STATUS_DOT_OFFSET_END_DP), bottom + dp(STATUS_DOT_OFFSET_BOTTOM_DP));
            statusDot.setLayoutParams(dotParams);
        }

        if (updateBadge != null) {
            FrameLayout.LayoutParams badgeParams = (FrameLayout.LayoutParams) updateBadge.getLayoutParams();
            badgeParams.gravity = Gravity.END | Gravity.BOTTOM;
            badgeParams.setMargins(0, 0, end + dp(UPDATE_BADGE_OFFSET_END_DP), bottom + dp(UPDATE_BADGE_OFFSET_BOTTOM_DP));
            updateBadge.setLayoutParams(badgeParams);
        }
    }

    private void updateScanPosition() {
        if (scanButton == null) {
            return;
        }
        FrameLayout.LayoutParams scanParams = (FrameLayout.LayoutParams) scanButton.getLayoutParams();
        scanParams.gravity = Gravity.END | Gravity.BOTTOM;
        scanParams.setMargins(0, 0, getScanMarginEndPx(), getScanMarginBottomPx());
        scanButton.setLayoutParams(scanParams);
    }

    private void updatePositions() {
        updateFabPositions();
        updateScanPosition();
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
        if (updateBadge != null) {
            updateBadge.animate().translationX(translation).setDuration(220).start();
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
        if (updateBadge != null) {
            updateBadge.animate().translationX(0f).setDuration(180).start();
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

    /** 更新提醒红点背景：红色圆点 + 白描边（与状态点同风格） */
    private android.graphics.drawable.Drawable buildUpdateBadgeBackground() {
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        background.setColor(android.graphics.Color.parseColor("#F04438"));
        background.setStroke(dp(2), android.graphics.Color.WHITE);
        return background;
    }

    private android.graphics.drawable.Drawable buildCircleBackground(boolean active) {
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setShape(android.graphics.drawable.GradientDrawable.OVAL);
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
