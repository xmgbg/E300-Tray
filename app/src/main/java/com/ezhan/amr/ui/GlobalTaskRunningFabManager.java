package com.ezhan.amr.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.utils.TaskRunningStatusHolder;
import com.google.android.material.card.MaterialCardView;

/**
 * Adds a global floating button to every activity for toggling reported isTaskRunning.
 */
public final class GlobalTaskRunningFabManager implements Application.ActivityLifecycleCallbacks {

    private static final boolean ENABLED = false;
    private static final long LONG_PRESS_DURATION_MS = 2000L;
    private static final int DRAG_THRESHOLD_PX = 12;
    private static final int SCREEN_EDGE_MARGIN_DP = 8;
    private static final int FAB_SIZE_DP = 48;
    private static final int DEFAULT_END_MARGIN_DP = 16;
    private static final int DEFAULT_BOTTOM_MARGIN_DP = 88;

    private static GlobalTaskRunningFabManager instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Activity currentActivity;
    @Nullable
    private View overlayRoot;
    @Nullable
    private MaterialCardView fabCard;
    @Nullable
    private TextView fabLabel;
    @Nullable
    private Runnable pendingLongPress;

    private float touchDownX;
    private float touchDownY;
    private float initialFabX;
    private float initialFabY;
    private boolean isDragging;

    private float savedFabX = -1f;
    private float savedFabY = -1f;
    private boolean hasAppliedInitialPosition;

    public static void initialize(@NonNull Application application) {
        if (!ENABLED) {
            return;
        }
        if (instance == null) {
            instance = new GlobalTaskRunningFabManager();
            application.registerActivityLifecycleCallbacks(instance);
        }
    }

    public static GlobalTaskRunningFabManager getInstance() {
        return instance;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable android.os.Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        currentActivity = activity;
        attachOverlay(activity);
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        cancelPendingLongPress();
        if (currentActivity == activity) {
            detachOverlay(activity);
            currentActivity = null;
        }
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull android.os.Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        cancelPendingLongPress();
        if (currentActivity == activity) {
            detachOverlay(activity);
            currentActivity = null;
        }
    }

    private void attachOverlay(@NonNull Activity activity) {
        ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        if (overlayRoot != null && overlayRoot.getParent() == decorView) {
            applyFabPosition();
            refreshFabAppearance();
            bringOverlayToFront(decorView);
            return;
        }

        detachOverlay(activity);
        hasAppliedInitialPosition = false;

        overlayRoot = LayoutInflater.from(activity).inflate(
                R.layout.overlay_global_task_running_fab, decorView, false);
        fabCard = overlayRoot.findViewById(R.id.taskRunningFab);
        fabLabel = overlayRoot.findViewById(R.id.taskRunningFabLabel);

        if (fabCard != null) {
            fabCard.setOnTouchListener(this::handleFabTouch);
        }

        decorView.addView(overlayRoot);
        bringOverlayToFront(decorView);
        scheduleInitialFabPosition();
        refreshFabAppearance();
    }

    private void scheduleInitialFabPosition() {
        if (overlayRoot == null || fabCard == null) {
            return;
        }

        overlayRoot.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (overlayRoot == null || fabCard == null || hasAppliedInitialPosition) {
                            removeLayoutListenerSafely(this);
                            return;
                        }
                        if (overlayRoot.getWidth() <= 0 || overlayRoot.getHeight() <= 0) {
                            return;
                        }

                        applyFabPosition();
                        hasAppliedInitialPosition = true;
                        removeLayoutListenerSafely(this);
                    }

                    private void removeLayoutListenerSafely(
                            @NonNull ViewTreeObserver.OnGlobalLayoutListener listener) {
                        if (overlayRoot != null) {
                            overlayRoot.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
                        }
                    }
                });
    }

    private void applyFabPosition() {
        if (overlayRoot == null || fabCard == null) {
            return;
        }

        float x;
        float y;
        if (savedFabX >= 0f && savedFabY >= 0f) {
            x = savedFabX;
            y = savedFabY;
        } else {
            x = overlayRoot.getWidth()
                    - getFabWidth(fabCard)
                    - dp(fabCard, DEFAULT_END_MARGIN_DP);
            y = overlayRoot.getHeight()
                    - getFabHeight(fabCard)
                    - dp(fabCard, DEFAULT_BOTTOM_MARGIN_DP);
        }

        fabCard.setX(clampX(x));
        fabCard.setY(clampY(y));
        savedFabX = fabCard.getX();
        savedFabY = fabCard.getY();
    }

    private void detachOverlay(@Nullable Activity activity) {
        cancelPendingLongPress();
        if (overlayRoot != null && overlayRoot.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlayRoot.getParent()).removeView(overlayRoot);
        }
        overlayRoot = null;
        fabCard = null;
        fabLabel = null;
        isDragging = false;
    }

    private void bringOverlayToFront(@NonNull ViewGroup decorView) {
        if (overlayRoot != null) {
            overlayRoot.bringToFront();
            decorView.requestLayout();
        }
    }

    private boolean handleFabTouch(@NonNull View view, @NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getRawX();
                touchDownY = event.getRawY();
                initialFabX = view.getX();
                initialFabY = view.getY();
                isDragging = false;
                beginLongPressFeedback(view);
                scheduleLongPressToggle();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - touchDownX;
                float dy = event.getRawY() - touchDownY;
                if (!isDragging && (Math.abs(dx) > DRAG_THRESHOLD_PX || Math.abs(dy) > DRAG_THRESHOLD_PX)) {
                    isDragging = true;
                    cancelPendingLongPress();
                    endLongPressFeedback(view);
                }
                if (isDragging) {
                    view.setX(clampX(initialFabX + dx));
                    view.setY(clampY(initialFabY + dy));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelPendingLongPress();
                endLongPressFeedback(view);
                if (isDragging) {
                    view.setX(clampX(view.getX()));
                    view.setY(clampY(view.getY()));
                    savedFabX = view.getX();
                    savedFabY = view.getY();
                }
                isDragging = false;
                return true;
            default:
                return false;
        }
    }

    private void scheduleLongPressToggle() {
        cancelPendingLongPress();
        pendingLongPress = () -> {
            pendingLongPress = null;
            if (fabCard == null || isDragging) {
                return;
            }
            endLongPressFeedback(fabCard);
            TaskRunningStatusHolder.getInstance().toggle(getRealIsTaskRunning());
            refreshFabAppearance();
            showToggleSuccessToast();
        };
        mainHandler.postDelayed(pendingLongPress, LONG_PRESS_DURATION_MS);
    }

    private void cancelPendingLongPress() {
        if (pendingLongPress != null) {
            mainHandler.removeCallbacks(pendingLongPress);
            pendingLongPress = null;
        }
    }

    private float clampX(float x) {
        if (overlayRoot == null || fabCard == null) {
            return x;
        }
        float margin = dp(fabCard, SCREEN_EDGE_MARGIN_DP);
        float fabWidth = getFabWidth(fabCard);
        float maxX = overlayRoot.getWidth() - fabWidth - margin;
        float minX = margin;
        if (maxX < minX) {
            maxX = minX;
        }
        return Math.max(minX, Math.min(x, maxX));
    }

    private float clampY(float y) {
        if (overlayRoot == null || fabCard == null) {
            return y;
        }
        float margin = dp(fabCard, SCREEN_EDGE_MARGIN_DP);
        float fabHeight = getFabHeight(fabCard);
        float maxY = overlayRoot.getHeight() - fabHeight - margin;
        float minY = margin;
        if (maxY < minY) {
            maxY = minY;
        }
        return Math.max(minY, Math.min(y, maxY));
    }

    private float getFabWidth(@NonNull View fab) {
        return fab.getWidth() > 0 ? fab.getWidth() : dp(fab, FAB_SIZE_DP);
    }

    private float getFabHeight(@NonNull View fab) {
        return fab.getHeight() > 0 ? fab.getHeight() : dp(fab, FAB_SIZE_DP);
    }

    private void beginLongPressFeedback(@NonNull View view) {
        view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(120L).start();
    }

    private void endLongPressFeedback(@NonNull View view) {
        view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start();
    }

    private void refreshFabAppearance() {
        if (fabCard == null || fabLabel == null) {
            return;
        }

        boolean reportedValue = TaskRunningStatusHolder.getInstance()
                .resolveIsTaskRunning(getRealIsTaskRunning());
        fabLabel.setText(reportedValue ? "T" : "F");
        fabCard.setCardBackgroundColor(reportedValue
                ? fabCard.getContext().getColor(R.color.delivery_green_normal)
                : fabCard.getContext().getColor(R.color.battery_low));

        float elevationDp = TaskRunningStatusHolder.getInstance().isManualOverrideActive() ? 16f : 12f;
        fabCard.setCardElevation(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                elevationDp,
                fabCard.getResources().getDisplayMetrics()));
    }

    private void showToggleSuccessToast() {
        Activity activity = currentActivity;
        if (activity == null) {
            return;
        }
        Toast toast = Toast.makeText(
                activity,
                R.string.task_running_status_toggle_success,
                Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dpToPx(activity, 72));
        toast.show();
    }

    private boolean getRealIsTaskRunning() {
        try {
            if (MyApplication.getInstance().getSharedViewModel().getNavigationHandler() == null) {
                return false;
            }
            return MyApplication.getInstance().getSharedViewModel().getNavigationHandler().isTaskRunning();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static float dp(@NonNull View view, int dpValue) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dpValue,
                view.getResources().getDisplayMetrics());
    }

    private static int dpToPx(@NonNull Activity activity, int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                activity.getResources().getDisplayMetrics()));
    }
}
