package com.ezhan.amr.ui;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ezhan.amr.R;
import com.ezhan.amr.MyApplication;
import com.ezhan.amr.data.datastore.DataStoreManager;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * 建图规则浮动提示组件
 * 支持展开/收缩两种形态，可自由拖拽，位置持久化
 */
public class FloatingTipView extends FrameLayout {

    private static final String TAG = "FloatingTipView";
    private static final long AUTO_COLLAPSE_DELAY_MS = 5000;
    private static final long ANIM_DURATION_EXPAND_MS = 250;
    private static final long ANIM_DURATION_COLLAPSE_MS = 200;

    // 子视图
    private View expandedPanel;
    private View collapsedDot;
    private ImageButton btnClose;
    private TextView tvTitle;
    private TextView tvContent;

    // 状态
    private boolean isExpanded = false;

    // 拖拽
    private float downX, downY;
    private float initialViewX, initialViewY;
    private boolean isDragging = false;
    private static final int DRAG_THRESHOLD = 8;
    // 初始位置加载标志：仅首次回调设位置，避免拖动保存后 Flowable 回调重置位置导致跳动
    private boolean hasXLoaded = false;
    private boolean hasYLoaded = false;
    // 边距：拖动与展开后距屏幕边缘留白
    private static final int SCREEN_EDGE_MARGIN_DP = 8;
    // 展开弹窗固定宽度（与 floating_tip_expanded.xml 一致）
    private static final int EXPANDED_WIDTH_DP = 320;
    // 收缩圆点固定宽度（与 floating_tip_collapsed.xml 一致）
    private static final int COLLAPSED_SIZE_DP = 48;

    // 定时器
    private android.os.Handler autoCollapseHandler;
    private Runnable autoCollapseRunnable;

    // 持久化
    private DataStoreManager dataStoreManager;
    private Disposable posXDisposable;
    private Disposable posYDisposable;

    // 位置
    private float savedX = -1f;
    private float savedY = -1f;
    private float defaultX = -1f;
    private float defaultY = -1f;

    // 提示类型（"map" = 建图规则 / "elevator" = 电梯操作规范）
    private String tipType = "map";

    public FloatingTipView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public FloatingTipView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public FloatingTipView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        dataStoreManager = MyApplication.getInstance().getDataStoreManager();

        // 加载展开面板布局
        expandedPanel = LayoutInflater.from(context).inflate(R.layout.floating_tip_expanded, this, false);
        btnClose = expandedPanel.findViewById(R.id.btn_close);
        tvTitle = expandedPanel.findViewById(R.id.tv_title);
        tvContent = expandedPanel.findViewById(R.id.tv_content);

        // 加载收缩圆点布局
        collapsedDot = LayoutInflater.from(context).inflate(R.layout.floating_tip_collapsed, this, false);

        // 初始化时只显示圆点
        expandedPanel.setVisibility(GONE);
        collapsedDot.setVisibility(VISIBLE);

        addView(expandedPanel);
        addView(collapsedDot);

        Log.d(TAG, "init: expandedPanel=" + expandedPanel + ", collapsedDot=" + collapsedDot);

        // 关闭按钮
        btnClose.setOnClickListener(v -> collapse());

        // 拖拽 - 展开面板
        expandedPanel.setOnTouchListener(dragTouchListener);
        // 拖拽 - 收缩圆点（点击逻辑在 touchListener 中处理）
        collapsedDot.setOnTouchListener(dragTouchListener);

        // 自动收缩定时器
        autoCollapseHandler = new android.os.Handler();
        autoCollapseRunnable = this::collapse;
    }

    /**
     * 初始化位置（在布局完成后调用）
     * 使用 hasXLoaded/hasYLoaded 标志确保仅首次回调设置位置，
     * 避免拖动保存后 Flowable 持续回调重置位置导致跳动
     */
    public void initPosition(float defaultX, float defaultY) {
        this.defaultX = defaultX;
        this.defaultY = defaultY;
        Log.d(TAG, "initPosition: tipType=" + tipType
                + ", defaultX=" + defaultX + ", defaultY=" + defaultY
                + ", width=" + getWidth() + ", height=" + getHeight()
                + ", visibility=" + getVisibility());

        // 根据提示类型选择对应的 DataStore 方法
        Flowable<Float> xSource, ySource;
        if ("elevator".equals(tipType)) {
            xSource = dataStoreManager.getElevatorTipX();
            ySource = dataStoreManager.getElevatorTipY();
        } else {
            xSource = dataStoreManager.getMapRulesTipX();
            ySource = dataStoreManager.getMapRulesTipY();
        }

        posXDisposable = xSource
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(x -> {
                    Log.d(TAG, "onX: x=" + x + ", hasXLoaded=" + hasXLoaded);
                    if (!hasXLoaded) {
                        savedX = x;
                        applyPosition();
                        hasXLoaded = true;
                    }
                });

        posYDisposable = ySource
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(y -> {
                    Log.d(TAG, "onY: y=" + y + ", hasYLoaded=" + hasYLoaded);
                    if (!hasYLoaded) {
                        savedY = y;
                        applyPosition();
                        hasYLoaded = true;
                    }
                });

        // 每次进入页面都展开显示 5 秒
        expand();
        startAutoCollapseTimer();
    }

    private void applyPosition() {
        // 拖动中不重置位置，避免与实时 setX/setY 冲突导致跳动
        if (isDragging) return;
        float x = savedX >= 0 ? savedX : defaultX;
        float y = savedY >= 0 ? savedY : defaultY;
        // 限制在宿主边界内，防止持久化的越界位置导致视图不可见
        x = clampX(x);
        y = clampY(y);
        // 同步修正保存值，避免后续展开/收缩用到越界值
        savedX = x;
        savedY = y;
        setX(x);
        setY(y);
        Log.d(TAG, "applyPosition: setX=" + x + ", setY=" + y
                + ", savedX=" + savedX + ", savedY=" + savedY);
    }

    /**
     * 展开为完整弹窗
     */
    public void expand() {
        Log.d(TAG, "expand: isExpanded=" + isExpanded
                + ", width=" + getWidth() + ", height=" + getHeight()
                + ", expandedPanel.width=" + expandedPanel.getWidth()
                + ", visibility=" + getVisibility());
        if (isExpanded) return;
        isExpanded = true;

        cancelAutoCollapseTimer();

        // 先获取圆点中心作为缩放锚点（此时圆点仍 VISIBLE，宽度有效）
        float density = getResources().getDisplayMetrics().density;
        float dotCenter = COLLAPSED_SIZE_DP / 2f * density;
        expandedPanel.setPivotX(dotCenter);
        expandedPanel.setPivotY(dotCenter);

        collapsedDot.setVisibility(GONE);
        expandedPanel.setVisibility(VISIBLE);

        // 缩放动画
        expandedPanel.setScaleX(0f);
        expandedPanel.setScaleY(0f);
        expandedPanel.setAlpha(0f);

        // 先应用保存的位置，再约束到屏幕内，防止超出屏幕右侧/下侧
        applyPosition();
        clampPositionForExpand();
        Log.d(TAG, "expand: after clamp, X=" + getX() + ", Y=" + getY());

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(expandedPanel, "scaleX", 0f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(expandedPanel, "scaleY", 0f, 1f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(expandedPanel, "alpha", 0f, 1f);

        scaleX.setDuration(ANIM_DURATION_EXPAND_MS);
        scaleY.setDuration(ANIM_DURATION_EXPAND_MS);
        alpha.setDuration(ANIM_DURATION_EXPAND_MS);
        scaleX.setInterpolator(new DecelerateInterpolator());
        scaleY.setInterpolator(new DecelerateInterpolator());
        alpha.setInterpolator(new DecelerateInterpolator());

        // 动画结束后按实际尺寸做最终边界约束（高度 wrap_content 需等测量完成）
        scaleX.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                clampPositionToScreen();
            }
        });

        scaleX.start();
        scaleY.start();
        alpha.start();
    }

    /**
     * 收缩为小圆点
     */
    public void collapse() {
        if (!isExpanded) return;
        isExpanded = false;

        cancelAutoCollapseTimer();

        // 设置缩放锚点为圆点中心（固定值，避免 GONE 后 getWidth=0 导致锚点错位）
        float density = getResources().getDisplayMetrics().density;
        float dotCenter = COLLAPSED_SIZE_DP / 2f * density;
        expandedPanel.setPivotX(dotCenter);
        expandedPanel.setPivotY(dotCenter);

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(expandedPanel, "scaleX", 1f, 0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(expandedPanel, "scaleY", 1f, 0f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(expandedPanel, "alpha", 1f, 0f);

        scaleX.setDuration(ANIM_DURATION_COLLAPSE_MS);
        scaleY.setDuration(ANIM_DURATION_COLLAPSE_MS);
        alpha.setDuration(ANIM_DURATION_COLLAPSE_MS);
        scaleX.setInterpolator(new DecelerateInterpolator());
        scaleY.setInterpolator(new DecelerateInterpolator());
        alpha.setInterpolator(new DecelerateInterpolator());

        scaleX.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                expandedPanel.setVisibility(GONE);
                collapsedDot.setVisibility(VISIBLE);

                // 收缩后按圆点尺寸约束位置
                clampPositionForCollapse();

                ObjectAnimator dotScaleX = ObjectAnimator.ofFloat(collapsedDot, "scaleX", 0f, 1f);
                ObjectAnimator dotScaleY = ObjectAnimator.ofFloat(collapsedDot, "scaleY", 0f, 1f);
                ObjectAnimator dotAlpha = ObjectAnimator.ofFloat(collapsedDot, "alpha", 0f, 1f);
                dotScaleX.setDuration(ANIM_DURATION_COLLAPSE_MS);
                dotScaleY.setDuration(ANIM_DURATION_COLLAPSE_MS);
                dotAlpha.setDuration(ANIM_DURATION_COLLAPSE_MS);
                dotScaleX.setInterpolator(new DecelerateInterpolator());
                dotScaleY.setInterpolator(new DecelerateInterpolator());
                dotAlpha.setInterpolator(new DecelerateInterpolator());
                dotScaleX.start();
                dotScaleY.start();
                dotAlpha.start();
            }
        });

        scaleX.start();
        scaleY.start();
        alpha.start();
    }

    /**
     * 启动5秒自动收缩定时器
     */
    private void startAutoCollapseTimer() {
        cancelAutoCollapseTimer();
        autoCollapseHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_DELAY_MS);
    }

    private void cancelAutoCollapseTimer() {
        if (autoCollapseHandler != null && autoCollapseRunnable != null) {
            autoCollapseHandler.removeCallbacks(autoCollapseRunnable);
        }
    }

    /**
     * 拖拽监听器
     * 拖动时实时 clamp 到宿主边界内，防止拉出屏幕
     */
    private final OnTouchListener dragTouchListener = new OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    initialViewX = getX();
                    initialViewY = getY();
                    isDragging = false;
                    v.setAlpha(0.8f);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;

                    if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                        isDragging = true;
                    }

                    if (isDragging) {
                        // 先设位置，再 clamp 到边界内
                        float newX = initialViewX + dx;
                        float newY = initialViewY + dy;
                        setX(clampX(newX));
                        setY(clampY(newY));
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    v.setAlpha(1.0f);
                    if (isDragging) {
                        // 拖拽结束，最终 clamp 并保存位置
                        setX(clampX(getX()));
                        setY(clampY(getY()));
                        savedX = getX();
                        savedY = getY();
                        if ("elevator".equals(tipType)) {
                            dataStoreManager.saveElevatorTipPosition(savedX, savedY);
                        } else {
                            dataStoreManager.saveMapRulesTipPosition(savedX, savedY);
                        }
                    } else {
                        // 未拖拽，视为点击：圆点点击展开
                        if (v == collapsedDot) {
                            expand();
                        }
                    }
                    isDragging = false;
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1.0f);
                    isDragging = false;
                    return true;

                default:
                    return false;
            }
        }
    };

    /**
     * 获取宿主（父 View）可用宽高
     */
    private int[] getHostSize() {
        View parent = (View) getParent();
        if (parent == null) return new int[]{0, 0};
        return new int[]{parent.getWidth(), parent.getHeight()};
    }

    private float dp(int dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    /**
     * X 方向 clamp：基于当前可见子视图宽度，保证不超出宿主左右边界
     */
    private float clampX(float x) {
        int[] host = getHostSize();
        int parentWidth = host[0];
        if (parentWidth <= 0) return x;
        float margin = dp(SCREEN_EDGE_MARGIN_DP);
        float viewWidth = isExpanded ? getWidth() : dp(COLLAPSED_SIZE_DP);
        if (viewWidth <= 0) viewWidth = isExpanded ? dp(EXPANDED_WIDTH_DP) : dp(COLLAPSED_SIZE_DP);
        float maxX = parentWidth - viewWidth - margin;
        float minX = margin;
        if (maxX < minX) maxX = minX;
        return Math.max(minX, Math.min(x, maxX));
    }

    /**
     * Y 方向 clamp：基于当前可见子视图高度，保证不超出宿主上下边界
     */
    private float clampY(float y) {
        int[] host = getHostSize();
        int parentHeight = host[1];
        if (parentHeight <= 0) return y;
        float margin = dp(SCREEN_EDGE_MARGIN_DP);
        float viewHeight = getHeight();
        if (viewHeight <= 0) {
            // 高度 wrap_content 未测量时，展开态用展开面板高度，收缩态用圆点尺寸
            viewHeight = isExpanded ? (expandedPanel.getHeight() > 0 ? expandedPanel.getHeight() : dp(COLLAPSED_SIZE_DP))
                                    : dp(COLLAPSED_SIZE_DP);
        }
        float maxY = parentHeight - viewHeight - margin;
        float minY = margin;
        if (maxY < minY) maxY = minY;
        return Math.max(minY, Math.min(y, maxY));
    }

    /**
     * 展开时位置约束：用展开弹窗预期宽度，防止右侧超出屏幕
     */
    private void clampPositionForExpand() {
        int[] host = getHostSize();
        int parentWidth = host[0];
        int parentHeight = host[1];
        if (parentWidth <= 0) return;
        float margin = dp(SCREEN_EDGE_MARGIN_DP);
        float expandedWidth = dp(EXPANDED_WIDTH_DP);

        float x = getX();
        float maxX = parentWidth - expandedWidth - margin;
        float minX = margin;
        if (maxX < minX) maxX = minX;
        x = Math.max(minX, Math.min(x, maxX));
        setX(x);

        // Y 方向：若已测量则约束，否则交给动画结束后的 clampPositionToScreen
        if (parentHeight > 0 && expandedPanel.getHeight() > 0) {
            float y = getY();
            float maxY = parentHeight - expandedPanel.getHeight() - margin;
            float minY = margin;
            if (maxY < minY) maxY = minY;
            y = Math.max(minY, Math.min(y, maxY));
            setY(y);
        }
    }

    /**
     * 收缩时位置约束：圆点尺寸小，一般不超出，做兜底处理
     */
    private void clampPositionForCollapse() {
        int[] host = getHostSize();
        int parentWidth = host[0];
        int parentHeight = host[1];
        if (parentWidth <= 0 || parentHeight <= 0) return;
        float margin = dp(SCREEN_EDGE_MARGIN_DP);
        float dotSize = dp(COLLAPSED_SIZE_DP);

        float x = getX();
        float maxX = parentWidth - dotSize - margin;
        float minX = margin;
        if (maxX < minX) maxX = minX;
        x = Math.max(minX, Math.min(x, maxX));
        setX(x);

        float y = getY();
        float maxY = parentHeight - dotSize - margin;
        float minY = margin;
        if (maxY < minY) maxY = minY;
        y = Math.max(minY, Math.min(y, maxY));
        setY(y);

        // 同步更新保存的位置，避免下次展开用到越界值
        savedX = x;
        savedY = y;
    }

    /**
     * 按当前实际尺寸做完整边界约束（展开动画结束后调用，此时尺寸已确定）
     */
    private void clampPositionToScreen() {
        int[] host = getHostSize();
        int parentWidth = host[0];
        int parentHeight = host[1];
        if (parentWidth <= 0 || parentHeight <= 0) return;
        float margin = dp(SCREEN_EDGE_MARGIN_DP);
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        if (viewWidth <= 0) viewWidth = dp(EXPANDED_WIDTH_DP);
        if (viewHeight <= 0) return;

        float x = getX();
        float maxX = parentWidth - viewWidth - margin;
        float minX = margin;
        if (maxX < minX) maxX = minX;
        x = Math.max(minX, Math.min(x, maxX));

        float y = getY();
        float maxY = parentHeight - viewHeight - margin;
        float minY = margin;
        if (maxY < minY) maxY = minY;
        y = Math.max(minY, Math.min(y, maxY));

        setX(x);
        setY(y);
        savedX = x;
        savedY = y;
    }

    /**
     * 暂停定时器（页面不可见时调用）
     */
    public void pauseTimer() {
        cancelAutoCollapseTimer();
    }

    /**
     * 恢复定时器（页面可见时调用，仅首次未关闭时）
     */
    public void resumeTimer() {
        if (isExpanded) {
            startAutoCollapseTimer();
        }
    }

    /**
     * 清理资源
     */
    public void destroy() {
        cancelAutoCollapseTimer();
        if (posXDisposable != null && !posXDisposable.isDisposed()) {
            posXDisposable.dispose();
        }
        if (posYDisposable != null && !posYDisposable.isDisposed()) {
            posYDisposable.dispose();
        }
    }

    public boolean isExpanded() {
        return isExpanded;
    }

    /**
     * 设置提示类型（"map"=建图规则 / "elevator"=电梯操作规范）
     * 必须在 initPosition() 之前调用
     */
    public void setTipType(String type) {
        this.tipType = type;
    }

    /**
     * 设置自定义标题和内容（覆写布局 XML 中的默认字符串）
     * 在 init() 之后、initPosition() 之前调用
     */
    public void setTipContent(String title, String content) {
        if (tvTitle != null) {
            tvTitle.setText(title);
        }
        if (tvContent != null) {
            tvContent.setText(content);
        }
    }
}
