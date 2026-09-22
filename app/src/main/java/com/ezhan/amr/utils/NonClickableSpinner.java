package com.ezhan.amr.utils;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import androidx.appcompat.widget.AppCompatSpinner;

public class NonClickableSpinner extends AppCompatSpinner {  // 修改继承关系

    public NonClickableSpinner(Context context) {
        super(context);
    }

    public NonClickableSpinner(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NonClickableSpinner(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean performClick() {
        return false; // 禁用点击
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false; // 禁用触摸
    }
}