package com.ezhan.amr.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import java.util.Calendar;

public class ClockView extends View {
    private Paint circlePaint;
    private Paint hourHandPaint;
    private Paint minuteHandPaint;
    private Paint secondHandPaint;
    private Paint centerPaint;
    private RectF rectF;
    private Handler handler;
    private Runnable runnable;

    public ClockView(Context context) {
        super(context);
        init();
    }

    public ClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ClockView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // 表盘
        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(3f);
        circlePaint.setColor(Color.WHITE);

        // 时针
        hourHandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hourHandPaint.setStrokeWidth(3f);
        hourHandPaint.setColor(Color.WHITE);

        // 分针
        minuteHandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        minuteHandPaint.setStrokeWidth(2f);
        minuteHandPaint.setColor(Color.WHITE);

        // 秒针
        secondHandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        secondHandPaint.setStrokeWidth(1f);
        secondHandPaint.setColor(Color.WHITE);

        // 中心点
        centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerPaint.setStyle(Paint.Style.FILL);
        centerPaint.setColor(Color.WHITE);

        rectF = new RectF();

        handler = new Handler();
        runnable = new Runnable() {
            @Override
            public void run() {
                invalidate();
                handler.postAtTime(this, SystemClock.uptimeMillis() + 1000);
            }
        };
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.post(runnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(runnable);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        int centerX = width / 2;
        int centerY = height / 2;
        int radius = Math.min(width, height) / 2 - 10;

        // 绘制表盘
        rectF.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        canvas.drawOval(rectF, circlePaint);

        // 绘制刻度
        drawHourMarks(canvas, centerX, centerY, radius);

        // 获取当前时间
        Calendar calendar = Calendar.getInstance();
        int hours = calendar.get(Calendar.HOUR);
        int minutes = calendar.get(Calendar.MINUTE);
        int seconds = calendar.get(Calendar.SECOND);

        // 计算指针角度
        float hourAngle = (hours * 30) + (minutes * 0.5f);
        float minuteAngle = minutes * 6;
        float secondAngle = seconds * 6;

        // 绘制时针
        float hourHandLength = radius * 0.5f;
        float hourX = (float) (centerX + hourHandLength * Math.sin(Math.toRadians(hourAngle)));
        float hourY = (float) (centerY - hourHandLength * Math.cos(Math.toRadians(hourAngle)));
        canvas.drawLine(centerX, centerY, hourX, hourY, hourHandPaint);

        // 绘制分针
        float minuteHandLength = radius * 0.7f;
        float minuteX = (float) (centerX + minuteHandLength * Math.sin(Math.toRadians(minuteAngle)));
        float minuteY = (float) (centerY - minuteHandLength * Math.cos(Math.toRadians(minuteAngle)));
        canvas.drawLine(centerX, centerY, minuteX, minuteY, minuteHandPaint);

        // 绘制秒针
        float secondHandLength = radius * 0.8f;
        float secondX = (float) (centerX + secondHandLength * Math.sin(Math.toRadians(secondAngle)));
        float secondY = (float) (centerY - secondHandLength * Math.cos(Math.toRadians(secondAngle)));
        canvas.drawLine(centerX, centerY, secondX, secondY, secondHandPaint);

        // 绘制中心点
        canvas.drawCircle(centerX, centerY, 8, centerPaint);
    }

    private void drawHourMarks(Canvas canvas, int centerX, int centerY, int radius) {
        Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        markPaint.setStrokeWidth(3f);
        markPaint.setColor(Color.WHITE);

        for (int i = 0; i < 12; i++) {
            double angle = Math.toRadians(i * 30);
            float startX = (float) (centerX + (radius - 15) * Math.sin(angle));
            float startY = (float) (centerY - (radius - 15) * Math.cos(angle));
            float endX = (float) (centerX + radius * Math.sin(angle));
            float endY = (float) (centerY - radius * Math.cos(angle));

            canvas.drawLine(startX, startY, endX, endY, markPaint);
        }
    }
}
