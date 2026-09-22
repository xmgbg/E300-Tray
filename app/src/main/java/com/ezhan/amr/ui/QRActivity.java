package com.ezhan.amr.ui;

import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.ezhan.amr.R;


import com.ezhan.amr.MyApplication;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

public class QRActivity extends BaseActivity {
    private ImageView qrImageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_qr);
        setupFullScreen();

        qrImageView = findViewById(R.id.qrImageView);

        generateQRCode();


        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());
    }

    private void generateQRCode() {
        String webUrl = ((MyApplication) getApplication()).getWebServerUrl();

        if (webUrl == null) {
            Toast.makeText(this, R.string.qr_web_service_not_started, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 使用ZXing生成二维码
            Bitmap bitmap = encodeAsBitmap(webUrl, 600, 600);
            qrImageView.setImageBitmap(bitmap);

            // 显示访问提示
            TextView urlText = findViewById(R.id.urlText);
            urlText.setText(getString(R.string.qr_scan, webUrl));

        } catch (Exception e) {
            Toast.makeText(this, R.string.qr_generation_failed, Toast.LENGTH_SHORT).show();
            Log.e("QRCode", "生成错误", e);
        }
    }

    // 二维码生成核心方法
    private Bitmap encodeAsBitmap(String str, int width, int height) throws WriterException {
        BitMatrix result;
        try {
            result = new MultiFormatWriter().encode(str, BarcodeFormat.QR_CODE, width, height, null);
        } catch (IllegalArgumentException iae) {
            return null;
        }

        int w = result.getWidth();
        int h = result.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int offset = y * w;
            for (int x = 0; x < w; x++) {
                pixels[offset + x] = result.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        return bitmap;
    }


    /**
     * 配置全屏显示参数
     */
    private void setupFullScreen() {
        // 确保内容延伸到系统栏区域
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // 获取窗口控制器
        WindowInsetsControllerCompat windowInsetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        // 配置沉浸式行为
        if (windowInsetsController != null) {
            // 同时隐藏状态栏和导航栏
            windowInsetsController.hide(
                    WindowInsetsCompat.Type.systemBars() |
                            WindowInsetsCompat.Type.navigationBars()
            );

            // 设置沉浸式粘性行为（自动隐藏）
            windowInsetsController.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
        }

        // 兼容旧版本实现（API 30以下）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }

        // 添加窗口焦点监听保持隐藏状态
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                setupFullScreen();
            }
        });
    }
}