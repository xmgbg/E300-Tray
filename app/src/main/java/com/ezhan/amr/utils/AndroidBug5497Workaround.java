package com.ezhan.amr.utils;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

public class AndroidBug5497Workaround {

    public static void assistActivity(Activity activity) {
        new AndroidBug5497Workaround(activity);
    }

    private View mChildOfContent;
    private int usableHeightPrevious;
    private FrameLayout.LayoutParams frameLayoutParams;

    private AndroidBug5497Workaround(Activity activity) {
        FrameLayout content = activity.findViewById(android.R.id.content);
        mChildOfContent = content.getChildAt(0);

        mChildOfContent.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    public void onGlobalLayout() {
                        possiblyResizeChildOfContent();
                    }
                });

        frameLayoutParams = (FrameLayout.LayoutParams)
                mChildOfContent.getLayoutParams();
    }

    private void possiblyResizeChildOfContent() {
        int usableHeightNow = computeUsableHeight();
        if (usableHeightNow != usableHeightPrevious) {
            int usableHeightSansKeyboard = mChildOfContent.getRootView().getHeight();
            int heightDifference = usableHeightSansKeyboard - usableHeightNow;

            if (heightDifference > (usableHeightSansKeyboard / 4)) {
                // 键盘可见时调整高度
                frameLayoutParams.height = usableHeightSansKeyboard - heightDifference;
            } else {
                // 键盘隐藏时恢复全屏
                frameLayoutParams.height = usableHeightSansKeyboard;
            }

            mChildOfContent.requestLayout();
            usableHeightPrevious = usableHeightNow;
        }
    }

    private int computeUsableHeight() {
        Rect rect = new Rect();
        mChildOfContent.getWindowVisibleDisplayFrame(rect);
        return rect.bottom - rect.top;
    }
}