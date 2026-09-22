package com.ezhan.amr.ui.fragments;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.ezhan.amr.MyApplication;
import com.ezhan.amr.R;
import com.ezhan.amr.ui.QRActivity;
import com.ezhan.amr.utils.LocaleHelper;

public class DeviceSettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_device_settings, container, false);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 初始化视图引用
        TextView title = view.findViewById(R.id.title);
        TextView serialLabel = view.findViewById(R.id.serial_label);
        TextView machineLabel = view.findViewById(R.id.machine_label);
        TextView versionLabel = view.findViewById(R.id.version_label);
        Button showQRBtn = view.findViewById(R.id.showQRBtn);
        Button btnExitApp = view.findViewById(R.id.btnExitApp);

        // 设置文本（实际应用中可省略，因为XML中已设置）
        title.setText(R.string.title_device_settings);
        serialLabel.setText(R.string.device_serial_number);
        machineLabel.setText(R.string.device_machine_number);
        versionLabel.setText(R.string.app_version_number);

        showQRBtn.setOnClickListener(v -> {
            String webUrl = ((MyApplication) requireActivity().getApplication()).getWebServerUrl();
            if (webUrl != null) {
                Intent intent = new Intent(requireActivity(), QRActivity.class);
                startActivity(intent);
            } else {
                Toast.makeText(requireContext(), R.string.device_start_web_service_first, Toast.LENGTH_SHORT).show();
            }
        });


        // 设置退出按钮点击监听
        btnExitApp.setOnClickListener(v -> exitApplication());
    }

    private void exitApplication() {
        if (getActivity() != null) {
            getActivity().finishAffinity();
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }


    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // 确保语言设置正确应用
        LocaleHelper.setLocale(context);

    }

}