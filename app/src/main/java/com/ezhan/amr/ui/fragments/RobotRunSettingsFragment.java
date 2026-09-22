package com.ezhan.amr.ui.fragments;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.ezhan.amr.R;

public class RobotRunSettingsFragment extends Fragment {
    private WebView webView;

    private OnBackPressedListener onBackPressedListener;
    // 添加返回键处理接口
    public interface OnBackPressedListener {
        boolean onBackPressed();
    }


    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

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


        webView.loadUrl(getString(R.string.robot_run_url));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_robot_run_settings, container, false);
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

    // 处理返回键的方法
    public boolean handleBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return false;
    }
}