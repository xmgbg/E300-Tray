package com.ezhan.amr.ui.fragments;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.ezhan.amr.R;
import com.ezhan.amr.ui.FloatingTipView;

public class MapSettingsFragment extends Fragment {
    private WebView webView;
    private FloatingTipView floatingTipView;

    private OnBackPressedListener onBackPressedListener;
    // 添加返回键处理接口
    public interface OnBackPressedListener {
        boolean onBackPressed();
    }

    // 圆点默认位置距右上角的边距
    private static final int TIP_DEFAULT_MARGIN_DP = 16;
    // 展开弹窗固定宽度（与 floating_tip_expanded.xml 一致），用于计算默认位置避免初始超出
    private static final int TIP_EXPANDED_WIDTH_DP = 320;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 设置窗口背景为白色，避免Splash背景闪烁
        if (getActivity() != null && getActivity().getWindow() != null) {
            getActivity().getWindow().setBackgroundDrawableResource(android.R.color.white);
        }
        webView = view.findViewById(R.id.webView);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // 所有链接都在WebView中加载，而不是默认浏览器
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                // 页面开始加载
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 页面加载完成
                super.onPageFinished(view, url);
            }
        });
        webView.getSettings().setJavaScriptEnabled(true); // 启用JavaScript


        webView.loadUrl(getString(R.string.map_url));

        // 初始化建图规则浮动提示
        initFloatingTipView(view);
    }

    /**
     * 初始化建图规则浮动提示
     * 等宿主布局完成后计算右上角默认位置，先用默认值同步设位置，
     * 再异步读取持久化位置修正，避免视图先在(0,0)显示再跳动
     */
    private void initFloatingTipView(@NonNull View rootView) {
        floatingTipView = rootView.findViewById(R.id.floatingTipView);
        if (floatingTipView == null) return;

        rootView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        int hostWidth = rootView.getWidth();
                        int hostHeight = rootView.getHeight();
                        if (hostWidth > 0 && hostHeight > 0) {
                            rootView.getViewTreeObserver()
                                    .removeOnGlobalLayoutListener(this);

                            float density = getResources().getDisplayMetrics().density;
                            float margin = TIP_DEFAULT_MARGIN_DP * density;

                            // 默认位置以展开弹窗宽度计算 X，保证首次展开时弹窗完全在屏幕内
                            float defaultX = hostWidth - TIP_EXPANDED_WIDTH_DP * density - margin;
                            float defaultY = margin;

                            // 先同步设默认位置+显示，避免异步回调前在(0,0)闪烁
                            floatingTipView.setX(defaultX);
                            floatingTipView.setY(defaultY);
                            floatingTipView.setVisibility(View.VISIBLE);
                            // 再异步读取持久化位置和已查看状态
                            floatingTipView.initPosition(defaultX, defaultY);
                        }
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_map_settings, container, false);
    }


    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // 设置返回键监听器
        if (context instanceof OnBackPressedListener) {
            onBackPressedListener = (OnBackPressedListener) context;
        }
    }


    @Override
    public void onDetach() {
        super.onDetach();
        onBackPressedListener = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (floatingTipView != null) {
            floatingTipView.resumeTimer();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (floatingTipView != null) {
            floatingTipView.pauseTimer();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (floatingTipView != null) {
            floatingTipView.destroy();
            floatingTipView = null;
        }
    }

    // 处理返回键的方法
    public boolean handleBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return false;
    }
}
